package org.purpurmc.purpur.mirage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MirageChunkOptimizer — 区块优化模块。
 *
 * <p>本模块通过优化区块 tick 顺序、跳过空区块、限制随机 tick 数量、
 * 批量卸载和异步压缩等手段，显著降低区块处理的开销。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>区块 tick 顺序优化 — 按缓存局部性重排区块 tick 顺序</li>
 *   <li>空区块 tick 跳过 — 跳过无玩家和无待处理更新的区块</li>
 *   <li>随机 tick 区块限制 — 限制每 tick 处理的随机 tick 区块数</li>
 *   <li>快速区块哈希 — 使用位运算生成高效的区块坐标哈希</li>
 *   <li>批量区块卸载 — 延迟区块卸载到 tick 末尾批量处理</li>
 *   <li>异步区块压缩 — 在独立线程中执行区块数据压缩</li>
 * </ul>
 *
 * <p>所有配置项从 {@link MirageConfig} 读取，所有 public 方法均为 static。</p>
 *
 * <p><b>线程安全：</b>区块 tick 主要在主线程执行，但异步压缩使用独立线程池。
 * 所有共享状态使用线程安全结构。</p>
 */
@SuppressWarnings("unused")
public final class MirageChunkOptimizer {

    private MirageChunkOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. 区块 tick 顺序优化（缓存局部性）
    // ========================================================================

    /**
     * 区块 tick 顺序优化器。将区块按坐标排序，使内存中相邻的区块连续处理，
     * 提升 CPU 缓存命中率。
     *
     * <p>对应配置项：{@link MirageConfig#optimizeChunkTickOrder}</p>
     */

    /** 上次排序使用的中心坐标 X */
    private static volatile int lastCenterX = 0;
    /** 上次排序使用的中心坐标 Z */
    private static volatile int lastCenterZ = 0;

    /**
     * 对区块列表按缓存局部性进行排序。
     *
     * <p>排序策略：以玩家聚集区域中心为基准，按曼哈顿距离从近到远排序。
     * 这样内存中相邻的区块会被连续处理，提升 L1/L2 缓存命中率。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#tick} 的区块 tick 循环前，
     * 对待 tick 区块列表调用此方法进行排序。</p>
     *
     * @param chunkKeys  区块坐标键列表（使用 {@link MirageOptimizer#chunkKey(int, int)} 编码）
     * @param centerChunkX 中心区块 X 坐标（通常为玩家平均位置）
     * @param centerChunkZ 中心区块 Z 坐标
     * @return 排序后的区块键列表（若优化关闭则返回原列表）
     */
    public static List<Long> optimizeChunkTickOrder(List<Long> chunkKeys, int centerChunkX, int centerChunkZ) {
        if (!MirageConfig.optimizeChunkTickOrder || chunkKeys == null || chunkKeys.size() <= 1) {
            return chunkKeys;
        }

        lastCenterX = centerChunkX;
        lastCenterZ = centerChunkZ;

        // 使用曼哈顿距离排序（比欧几里得距离计算更快）
        final int cx = centerChunkX;
        final int cz = centerChunkZ;

        List<Long> sorted = new ArrayList<>(chunkKeys);
        sorted.sort(Comparator.comparingLong(key -> {
            int x = MirageOptimizer.chunkKeyX(key);
            int z = MirageOptimizer.chunkKeyZ(key);
            return (long) (Math.abs(x - cx) + Math.abs(z - cz));
        }));

        return sorted;
    }

    /**
     * 对区块坐标数组进行就地排序（避免分配新列表）。
     *
     * <p><b>调用位置：</b>patch 中需要对区块数组排序的场景。</p>
     *
     * @param chunkXs    区块 X 坐标数组
     * @param chunkZs    区块 Z 坐标数组
     * @param centerChunkX 中心区块 X 坐标
     * @param centerChunkZ 中心区块 Z 坐标
     * @param count      数组有效长度
     */
    public static void sortChunkArrays(int[] chunkXs, int[] chunkZs,
                                       int centerChunkX, int centerChunkZ, int count) {
        if (!MirageConfig.optimizeChunkTickOrder || count <= 1) {
            return;
        }

        // 简单的插入排序（对于较小的区块数比快排更高效）
        for (int i = 1; i < count; i++) {
            int distI = Math.abs(chunkXs[i] - centerChunkX) + Math.abs(chunkZs[i] - centerChunkZ);
            int xTemp = chunkXs[i];
            int zTemp = chunkZs[i];
            int j = i - 1;
            while (j >= 0) {
                int distJ = Math.abs(chunkXs[j] - centerChunkX) + Math.abs(chunkZs[j] - centerChunkZ);
                if (distJ <= distI) break;
                chunkXs[j + 1] = chunkXs[j];
                chunkZs[j + 1] = chunkZs[j];
                j--;
            }
            chunkXs[j + 1] = xTemp;
            chunkZs[j + 1] = zTemp;
        }
    }

    // ========================================================================
    // 2. 空区块 tick 跳过
    // ========================================================================

    /** 区块活跃状态缓存：以区块键为键，记录区块是否有待处理更新 */
    private static final ConcurrentHashMap<Long, Boolean> CHUNK_ACTIVE_STATE = new ConcurrentHashMap<>(512);

    /** 空 tick 跳过次数 */
    private static final AtomicLong emptyTickSkipCount = new AtomicLong();

    /**
     * 判断区块是否应该跳过 tick。
     *
     * <p>如果区块内没有玩家、没有待处理方块更新、没有活跃实体，
     * 则跳过该区块的 tick 以节省 CPU。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#tickChunk} 方法入口处，
     * 在区块 tick 循环中逐区块判断。</p>
     *
     * @param chunkKey       区块坐标键
     * @param hasPlayers     区块内是否有玩家（或玩家视距内）
     * @param hasPendingUpdates 区块是否有待处理的方块更新
     * @param hasActiveEntities 区块是否有活跃实体
     * @return true 如果应该跳过该区块的 tick
     */
    public static boolean shouldSkipChunkTick(long chunkKey, boolean hasPlayers,
                                              boolean hasPendingUpdates, boolean hasActiveEntities) {
        if (!MirageConfig.skipEmptyChunkTicks) {
            return false;
        }

        // 有玩家或活跃内容时不能跳过
        if (hasPlayers || hasPendingUpdates || hasActiveEntities) {
            CHUNK_ACTIVE_STATE.put(chunkKey, Boolean.TRUE);
            return false;
        }

        // 检查缓存中的活跃状态
        Boolean active = CHUNK_ACTIVE_STATE.get(chunkKey);
        if (active == null || active) {
            // 首次或之前是活跃的，标记为非活跃但不跳过（给一个过渡 tick）
            CHUNK_ACTIVE_STATE.put(chunkKey, Boolean.FALSE);
            return false;
        }

        // 确认非活跃，跳过 tick
        emptyTickSkipCount.incrementAndGet();
        return true;
    }

    /**
     * 标记区块为活跃状态（有方块更新或实体活动时调用）。
     *
     * <p><b>调用位置：</b>patch 中区块方块更新或实体活动时调用。</p>
     *
     * @param chunkKey 区块坐标键
     */
    public static void markChunkActive(long chunkKey) {
        if (MirageConfig.skipEmptyChunkTicks) {
            CHUNK_ACTIVE_STATE.put(chunkKey, Boolean.TRUE);
        }
    }

    /**
     * 移除区块的活跃状态记录（区块卸载时调用）。
     *
     * @param chunkKey 区块坐标键
     */
    public static void removeChunkActiveState(long chunkKey) {
        CHUNK_ACTIVE_STATE.remove(chunkKey);
    }

    /**
     * 获取空 tick 跳过次数。
     *
     * @return 跳过次数
     */
    public static long getEmptyTickSkipCount() {
        return emptyTickSkipCount.get();
    }

    // ========================================================================
    // 3. 随机 tick 区块限制
    // ========================================================================

    /** 当前 tick 已处理的随机 tick 区块数 */
    private static final AtomicInteger randomTickChunksProcessed = new AtomicInteger(0);
    /** 随机 tick 跳过次数 */
    private static final AtomicLong randomTickSkipCount = new AtomicLong();

    /**
     * 重置随机 tick区块计数器。在每个 tick 开始时调用。
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#tick} 方法开始处，
     * 在区块 tick 循环之前。</p>
     */
    public static void resetRandomTickCounter() {
        randomTickChunksProcessed.set(0);
    }

    /**
     * 判断区块是否应该执行随机 tick。
     *
     * <p>通过 {@link MirageConfig#maxRandomTickChunksPerTick} 限制每 tick 处理的随机 tick 区块数，
     * 超过限制的区块跳过本 tick 的随机 tick（不影响计划 tick）。</p>
     *
     * <p><b>调用位置：</b>patch 中区块随机 tick 执行前调用。</p>
     *
     * @return true 如果当前区块应该执行随机 tick
     */
    public static boolean shouldRandomTickChunk() {
        if (MirageConfig.maxRandomTickChunksPerTick <= 0) {
            return true; // 不限制
        }
        int current = randomTickChunksProcessed.incrementAndGet();
        if (current <= MirageConfig.maxRandomTickChunksPerTick) {
            return true;
        }
        randomTickSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 获取当前 tick 已处理的随机 tick区块数。
     *
     * @return 已处理区块数
     */
    public static int getRandomTickChunksProcessed() {
        return randomTickChunksProcessed.get();
    }

    /**
     * 获取随机 tick 跳过次数。
     *
     * @return 跳过次数
     */
    public static long getRandomTickSkipCount() {
        return randomTickSkipCount.get();
    }

    // ========================================================================
    // 4. 快速区块哈希
    // ========================================================================

    /** 快速区块哈希计算次数 */
    private static final AtomicLong fastHashCount = new AtomicLong();

    /**
     * 计算区块坐标的快速哈希值。
     *
     * <p>使用位运算混合函数生成高效的哈希值，比 {@code Long.hashCode()} 具有更好的分布性。
     * 适用于将区块坐标作为 HashMap 键的场景。</p>
     *
     * <p><b>调用位置：</b>patch 中区块 Map 的键哈希计算处，替代默认的 hashCode。</p>
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 混合后的哈希值
     */
    public static int fastChunkHash(int chunkX, int chunkZ) {
        if (!MirageConfig.fastChunkHash) {
            return 31 * chunkX + chunkZ;
        }
        fastHashCount.incrementAndGet();
        long key = MirageOptimizer.chunkKey(chunkX, chunkZ);
        return (int) MirageOptimizer.hashLong(key);
    }

    /**
     * 计算区块坐标键的快速哈希值。
     *
     * @param chunkKey 区块坐标键
     * @return 混合后的哈希值
     */
    public static int fastChunkHash(long chunkKey) {
        if (!MirageConfig.fastChunkHash) {
            return Long.hashCode(chunkKey);
        }
        fastHashCount.incrementAndGet();
        return (int) MirageOptimizer.hashLong(chunkKey);
    }

    /**
     * 判断两个区块坐标是否相邻（8 邻域）。
     *
     * @param x1,z1 第一个区块坐标
     * @param x2,z2 第二个区块坐标
     * @return true 如果两区块相邻
     */
    public static boolean areChunksAdjacent(int x1, int z1, int x2, int z2) {
        int dx = Math.abs(x1 - x2);
        int dz = Math.abs(z1 - z2);
        return dx <= 1 && dz <= 1 && (dx + dz > 0);
    }

    /**
     * 获取快速哈希计算次数。
     *
     * @return 计算次数
     */
    public static long getFastHashCount() {
        return fastHashCount.get();
    }

    // ========================================================================
    // 5. 批量区块卸载
    // ========================================================================

    /** 待卸载区块队列（延迟到 tick 末尾批量处理） */
    private static final ConcurrentLinkedQueue<Long> PENDING_UNLOAD_QUEUE = new ConcurrentLinkedQueue<>();

    /** 批量卸载次数 */
    private static final AtomicLong batchUnloadCount = new AtomicLong();

    /**
     * 将区块加入待卸载队列。区块不会立即卸载，而是延迟到 tick 末尾批量处理。
     *
     * <p>批量卸载可以减少区块卸载操作对 tick 的性能影响，同时允许在卸载前
     * 取消卸载（如果玩家重新进入区块范围）。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ChunkMap} 的区块卸载判断处，
     * 替代立即卸载。</p>
     *
     * @param chunkKey 区块坐标键
     */
    public static void scheduleChunkUnload(long chunkKey) {
        if (!MirageConfig.batchChunkUnload) {
            return;
        }
        PENDING_UNLOAD_QUEUE.add(chunkKey);
    }

    /**
     * 取消区块的待卸载请求。在玩家重新进入区块范围时调用。
     *
     * <p><b>调用位置：</b>patch 中区块加载或玩家进入范围时调用。</p>
     *
     * @param chunkKey 区块坐标键
     */
    public static void cancelChunkUnload(long chunkKey) {
        // ConcurrentLinkedQueue 不支持直接删除指定元素，
        // 通过标记机制实现：在处理时检查是否仍需要卸载
        // 这里简单移除（O(n) 但卸载队列通常很小）
        PENDING_UNLOAD_QUEUE.remove(chunkKey);
    }

    /**
     * 处理所有待卸载区块。在 tick 末尾调用。
     *
     * <p><b>调用位置：</b>patch 中 {@code MinecraftServer#tickServer} 或
     * {@code ServerLevel#tick} 方法末尾。</p>
     *
     * @return 实际卸载的区块数
     */
    public static int processPendingUnloads() {
        if (!MirageConfig.batchChunkUnload) {
            return 0;
        }

        int count = 0;
        Long chunkKey;
        while ((chunkKey = PENDING_UNLOAD_QUEUE.poll()) != null) {
            // 实际卸载逻辑由 patch 中的代码处理
            // 这里仅负责从队列中取出
            count++;
        }

        if (count > 0) {
            batchUnloadCount.incrementAndGet();
        }
        return count;
    }

    /**
     * 获取待卸载队列中的区块数。
     *
     * @return 待卸载数量
     */
    public static int getPendingUnloadCount() {
        return PENDING_UNLOAD_QUEUE.size();
    }

    /**
     * 获取批量卸载执行次数。
     *
     * @return 批量卸载次数
     */
    public static long getBatchUnloadCount() {
        return batchUnloadCount.get();
    }

    // ========================================================================
    // 6. 异步区块压缩
    // ========================================================================

    /** 异步压缩线程池 */
    private static final ExecutorService COMPRESSION_EXECUTOR;
    /** 异步压缩任务数 */
    private static final AtomicInteger pendingCompressionTasks = new AtomicInteger(0);
    /** 异步压缩完成数 */
    private static final AtomicLong compressionCompletedCount = new AtomicLong();
    /** 异步压缩线程池初始化标志 */
    private static final AtomicBoolean executorInitialized = new AtomicBoolean(false);

    static {
        COMPRESSION_EXECUTOR = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Mirage-Chunk-Compression-" + counter.getAndIncrement());
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY + 1);
                        return t;
                    }
                }
        );
        executorInitialized.set(true);
    }

    /**
     * 异步区块压缩任务接口。由 patch 中的代码实现具体的压缩逻辑。
     */
    @FunctionalInterface
    public interface ChunkCompressionTask {
        /**
         * 执行区块数据压缩。
         *
         * @return 压缩后的字节数据
         * @throws Exception 压缩过程中的异常
         */
        byte[] compress() throws Exception;
    }

    /**
     * 异步区块压缩结果回调接口。
     */
    @FunctionalInterface
    public interface ChunkCompressionCallback {
        /**
         * 压缩完成后的回调。
         *
         * @param chunkKey     区块坐标键
         * @param compressedData 压缩后的数据（如果失败则为 null）
         * @param error        错误信息（成功则为 null）
         */
        void onComplete(long chunkKey, byte[] compressedData, Throwable error);
    }

    /**
     * 提交异步区块压缩任务。
     *
     * <p>区块数据压缩（如 Zlib/LZ4 压缩）是 CPU 密集型操作，放在独立线程池中执行
     * 可以避免阻塞主线程 tick。压缩完成后通过回调通知调用方。</p>
     *
     * <p><b>调用位置：</b>patch 中区块保存/序列化时调用，
     * 替代同步的区块压缩。</p>
     *
     * @param chunkKey 区块坐标键
     * @param task    压缩任务
     * @param callback 完成回调（可为 null）
     */
    public static void submitAsyncCompression(long chunkKey, ChunkCompressionTask task,
                                              ChunkCompressionCallback callback) {
        if (!MirageConfig.asyncChunkCompression) {
            // 同步执行
            try {
                byte[] data = task.compress();
                if (callback != null) {
                    callback.onComplete(chunkKey, data, null);
                }
            } catch (Throwable e) {
                if (callback != null) {
                    callback.onComplete(chunkKey, null, e);
                }
            }
            return;
        }

        pendingCompressionTasks.incrementAndGet();
        COMPRESSION_EXECUTOR.submit(() -> {
            try {
                byte[] data = task.compress();
                compressionCompletedCount.incrementAndGet();
                if (callback != null) {
                    callback.onComplete(chunkKey, data, null);
                }
            } catch (Throwable e) {
                if (callback != null) {
                    callback.onComplete(chunkKey, null, e);
                }
            } finally {
                pendingCompressionTasks.decrementAndGet();
            }
        });
    }

    /**
     * 获取待处理的异步压缩任务数。
     *
     * @return 待处理任务数
     */
    public static int getPendingCompressionTasks() {
        return pendingCompressionTasks.get();
    }

    /**
     * 获取异步压缩完成数。
     *
     * @return 完成数
     */
    public static long getCompressionCompletedCount() {
        return compressionCompletedCount.get();
    }

    /**
     * 关闭异步压缩线程池。在服务器关闭时调用。
     */
    public static void shutdownCompressionExecutor() {
        if (executorInitialized.get()) {
            COMPRESSION_EXECUTOR.shutdown();
        }
    }

    // ========================================================================
    // 7. 区块保存间隔优化
    // ========================================================================

    /** 区块上次保存 tick 记录：以区块键为键 */
    private static final ConcurrentHashMap<Long, Long> CHUNK_LAST_SAVE_TICK = new ConcurrentHashMap<>(256);

    /**
     * 判断区块是否应该在本 tick 保存。
     *
     * <p>通过 {@link MirageConfig#chunkSaveInterval} 控制区块保存频率，
     * 避免频繁保存造成的 I/O 开销。每个区块独立计时。</p>
     *
     * <p><b>调用位置：</b>patch 中区块自动保存逻辑处。</p>
     *
     * @param chunkKey    区块坐标键
     * @param currentTick 当前 tick
     * @return true 如果区块应该在本 tick 保存
     */
    public static boolean shouldSaveChunk(long chunkKey, long currentTick) {
        Long lastSave = CHUNK_LAST_SAVE_TICK.get(chunkKey);
        if (lastSave == null) {
            CHUNK_LAST_SAVE_TICK.put(chunkKey, currentTick);
            return true;
        }

        if (currentTick - lastSave >= MirageConfig.chunkSaveInterval) {
            CHUNK_LAST_SAVE_TICK.put(chunkKey, currentTick);
            return true;
        }

        return false;
    }

    /**
     * 更新区块上次保存 tick。
     *
     * @param chunkKey    区块坐标键
     * @param currentTick 当前 tick
     */
    public static void updateChunkSaveTick(long chunkKey, long currentTick) {
        CHUNK_LAST_SAVE_TICK.put(chunkKey, currentTick);
    }

    /**
     * 移除区块保存记录（区块卸载时调用）。
     *
     * @param chunkKey 区块坐标键
     */
    public static void removeChunkSaveRecord(long chunkKey) {
        CHUNK_LAST_SAVE_TICK.remove(chunkKey);
    }

    // ========================================================================
    // 8. 统计和清理
    // ========================================================================

    /**
     * 获取区块优化的统计信息。
     *
     * @return 统计信息 Map
     */
    public static java.util.Map<String, Object> getChunkOptimizationStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("empty_tick_skip_count", emptyTickSkipCount.get());
        stats.put("chunk_active_state_size", CHUNK_ACTIVE_STATE.size());
        stats.put("random_tick_chunks_processed", randomTickChunksProcessed.get());
        stats.put("random_tick_skip_count", randomTickSkipCount.get());
        stats.put("fast_hash_count", fastHashCount.get());
        stats.put("pending_unload_count", PENDING_UNLOAD_QUEUE.size());
        stats.put("batch_unload_count", batchUnloadCount.get());
        stats.put("pending_compression_tasks", pendingCompressionTasks.get());
        stats.put("compression_completed_count", compressionCompletedCount.get());
        stats.put("chunk_save_records", CHUNK_LAST_SAVE_TICK.size());
        return stats;
    }

    /**
     * 清理指定区块的所有优化记录。在区块从世界卸载时调用。
     *
     * <p><b>调用位置：</b>patch 中区块卸载方法中。</p>
     *
     * @param chunkKey 区块坐标键
     */
    public static void cleanupChunk(long chunkKey) {
        CHUNK_ACTIVE_STATE.remove(chunkKey);
        CHUNK_LAST_SAVE_TICK.remove(chunkKey);
        PENDING_UNLOAD_QUEUE.remove(chunkKey);
        MirageOptimizer.invalidateChunkPacket(chunkKey);
        MirageMemoryOptimizer.removeWeakChunk(chunkKey);
    }

    /**
     * 清空所有区块优化缓存。在服务器关闭时调用。
     */
    public static void clearAll() {
        CHUNK_ACTIVE_STATE.clear();
        CHUNK_LAST_SAVE_TICK.clear();
        PENDING_UNLOAD_QUEUE.clear();
        emptyTickSkipCount.set(0);
        randomTickChunksProcessed.set(0);
        randomTickSkipCount.set(0);
        fastHashCount.set(0);
        batchUnloadCount.set(0);
        compressionCompletedCount.set(0);
    }
}
