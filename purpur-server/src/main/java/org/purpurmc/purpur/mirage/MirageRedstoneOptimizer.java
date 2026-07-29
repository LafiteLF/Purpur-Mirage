package org.purpurmc.purpur.mirage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MirageRedstoneOptimizer — 红石 / 方块 tick 优化模块（Lithium 理念）。
 *
 * <p>本模块通过批量处理红石更新、跳过冗余邻居更新、限制红石链长度、
 * 优化计划 tick 队列、合并方块更新和跳过空闲流体更新等手段，
 * 显著降低红石和方块 tick 的 CPU 开销。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>红石线批量更新 — 将相连的红石线更新合并为单次批量处理</li>
 *   <li>冗余邻居更新跳过 — 跳过不会产生实际变化的邻居方块更新</li>
 *   <li>红石链长度限制 — 限制单个 tick 内红石更新的链式传播深度</li>
 *   <li>计划 tick 队列优化 — 使用更高效的数据结构管理计划 tick</li>
 *   <li>方块更新合并 — 合并对同一位置的多次方块更新为一次</li>
 *   <li>流体更新跳过 — 跳过不会流动的非源流体方块更新</li>
 * </ul>
 *
 * <p>所有配置项从 {@link MirageConfig} 读取，所有 public 方法均为 static。</p>
 *
 * <p><b>线程安全：</b>红石和方块更新主要在主线程执行，但使用线程安全结构
 * 以支持可能的异步处理场景。</p>
 */
@SuppressWarnings("unused")
public final class MirageRedstoneOptimizer {

    private MirageRedstoneOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. 红石线批量更新
    // ========================================================================

    /**
     * 红石线批量更新收集器。在一个 tick 内收集所有待更新的红石线位置，
     * 然后一次性处理，避免逐个更新导致的重复计算。
     *
     * <p>对应配置项：{@link MirageConfig#optimizeRedstone}</p>
     */

    /** 当前 tick 的红石线更新集合：key = 区块键+方块索引, value = 更新优先级 */
    private static final ConcurrentHashMap<Long, Integer> PENDING_REDSTONE_UPDATES =
            new ConcurrentHashMap<>(256);

    /** 红石批量更新执行次数 */
    private static final AtomicLong redstoneBatchCount = new AtomicLong();
    /** 红石更新合并节省次数 */
    private static final AtomicLong redstoneUpdatesMerged = new AtomicLong();

    /**
     * 生成红石线位置键。
     *
     * @param x 方块 X 坐标
     * @param y 方块 Y 坐标
     * @param z 方块 Z 坐标
     * @return 位置键
     */
    private static long redstonePosKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * 将红石线更新加入批量队列。
     *
     * <p>如果在当前 tick 中该位置已经排队等待更新，则跳过（去重）。
     * 所有排队的更新将在 tick 末尾统一处理。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code RedStoneWireBlock#neighborChanged} 或
     * {@code RedStoneWireBlock#updatePowerStrength} 方法中，
     * 替代立即更新。</p>
     *
     * @param x        方块 X 坐标
     * @param y        方块 Y 坐标
     * @param z        方块 Z 坐标
     * @param priority 更新优先级（值越大越先处理）
     * @return true 如果此更新是新加入的（false 表示已存在被去重）
     */
    public static boolean queueRedstoneUpdate(int x, int y, int z, int priority) {
        if (!MirageConfig.optimizeRedstone) {
            return true;
        }

        long key = redstonePosKey(x, y, z);
        Integer existing = PENDING_REDSTONE_UPDATES.putIfAbsent(key, priority);
        if (existing != null) {
            // 已存在，如果新优先级更高则更新
            if (priority > existing) {
                PENDING_REDSTONE_UPDATES.put(key, priority);
            }
            redstoneUpdatesMerged.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 获取并清空当前 tick 的所有待处理红石线更新。
     *
     * <p>返回的列表按优先级排序（高优先级在前），供 patch 代码批量处理。</p>
     *
     * <p><b>调用位置：</b>patch 中区块 tick 末尾或红石处理阶段结束时调用。</p>
     *
     * @return 待处理的红石线更新列表（每项为 long[2]，{0=位置键, 1=优先级}）
     */
    public static List<long[]> flushRedstoneUpdates() {
        if (!MirageConfig.optimizeRedstone || PENDING_REDSTONE_UPDATES.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<long[]> updates = new ArrayList<>(PENDING_REDSTONE_UPDATES.size());
        PENDING_REDSTONE_UPDATES.forEach((key, priority) -> updates.add(new long[]{key, priority}));
        PENDING_REDSTONE_UPDATES.clear();

        // 按优先级降序排序
        updates.sort((a, b) -> Long.compare(b[1], a[1]));

        redstoneBatchCount.incrementAndGet();
        return updates;
    }

    /**
     * 从红石位置键中提取坐标。
     *
     * @param key 位置键
     * @return int[3] {x, y, z}
     */
    public static int[] redstoneKeyToPos(long key) {
        return new int[]{
                (int) (key >> 38),
                (int) (key & 0xFFF),
                (int) ((key >> 12) & 0x3FFFFFF)
        };
    }

    /**
     * 获取红石批量更新执行次数。
     *
     * @return 批量执行次数
     */
    public static long getRedstoneBatchCount() {
        return redstoneBatchCount.get();
    }

    /**
     * 获取通过批量合并节省的红石更新次数。
     *
     * @return 节省次数
     */
    public static long getRedstoneUpdatesMerged() {
        return redstoneUpdatesMerged.get();
    }

    // ========================================================================
    // 2. 冗余邻居更新跳过
    // ========================================================================

    /** 邻居更新跳过次数 */
    private static final AtomicLong neighborUpdateSkipCount = new AtomicLong();

    /**
     * 邻居更新位置缓存。记录最近处理过的邻居更新位置，
     * 如果同一位置在短时间内重复触发则跳过。
     */
    private static final ConcurrentHashMap<Long, Long> NEIGHBOR_UPDATE_CACHE =
            new ConcurrentHashMap<>(512);

    /**
     * 判断是否应该执行邻居方块更新。
     *
     * <p>如果同一位置在当前 tick 已经触发过邻居更新，则跳过本次更新。
     * 这可以避免红石装置中常见的冗余级联更新。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Level#updateNeighbour} 或
     * {@code BlockState#updateNeighbours} 方法入口处。</p>
     *
     * @param x           方块 X 坐标
     * @param y           方块 Y 坐标
     * @param z           方块 Z 坐标
     * @param currentTick 当前 tick
     * @return true 如果应该执行邻居更新
     */
    public static boolean shouldUpdateNeighbor(int x, int y, int z, long currentTick) {
        if (!MirageConfig.skipRedundantNeighborUpdates) {
            return true;
        }

        long key = redstonePosKey(x, y, z);
        Long lastUpdate = NEIGHBOR_UPDATE_CACHE.get(key);
        if (lastUpdate != null && lastUpdate == currentTick) {
            // 本 tick 已更新过，跳过
            neighborUpdateSkipCount.incrementAndGet();
            return false;
        }

        NEIGHBOR_UPDATE_CACHE.put(key, currentTick);
        return true;
    }

    /**
     * 清理过期的邻居更新缓存。
     *
     * <p><b>调用位置：</b>patch 中定期清理任务中调用（如每 100 tick）。</p>
     *
     * @param currentTick 当前 tick
     * @return 清理的条目数
     */
    public static int cleanNeighborUpdateCache(long currentTick) {
        if (!MirageConfig.skipRedundantNeighborUpdates) return 0;
        int removed = 0;
        long threshold = currentTick - 2; // 保留最近 2 tick 的记录
        var iterator = NEIGHBOR_UPDATE_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue() < threshold) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 获取邻居更新跳过次数。
     *
     * @return 跳过次数
     */
    public static long getNeighborUpdateSkipCount() {
        return neighborUpdateSkipCount.get();
    }

    // ========================================================================
    // 3. 红石链长度限制
    // ========================================================================

    /** 当前 tick 红石更新链计数器 */
    private static final AtomicInteger redstoneChainCounter = new AtomicInteger(0);
    /** 红石链超限截断次数 */
    private static final AtomicLong redstoneChainTruncatedCount = new AtomicLong();

    /**
     * 重置红石链计数器。在每个 tick 开始时调用。
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#tick} 方法开始处。</p>
     */
    public static void resetRedstoneChainCounter() {
        redstoneChainCounter.set(0);
    }

    /**
     * 检查红石更新链是否已达到上限。
     *
     * <p>通过 {@link MirageConfig#maxRedstoneChainPerTick} 限制单个 tick 内的红石更新链长度，
     * 防止超大型红石装置导致服务器卡顿。超过限制的更新将被推迟到下一个 tick。</p>
     *
     * <p><b>调用位置：</b>patch 中红石更新传播（{@code Block#update}）方法中，
     * 每次传播前检查。</p>
     *
     * @return true 如果红石链尚未达到上限（可以继续传播）
     */
    public static boolean checkRedstoneChainLimit() {
        if (MirageConfig.maxRedstoneChainPerTick <= 0) {
            return true;
        }
        int current = redstoneChainCounter.incrementAndGet();
        if (current > MirageConfig.maxRedstoneChainPerTick) {
            redstoneChainTruncatedCount.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * 获取当前 tick 的红石更新链计数。
     *
     * @return 当前链计数
     */
    public static int getRedstoneChainCount() {
        return redstoneChainCounter.get();
    }

    /**
     * 获取红石链截断次数。
     *
     * @return 截断次数
     */
    public static long getRedstoneChainTruncatedCount() {
        return redstoneChainTruncatedCount.get();
    }

    // ========================================================================
    // 4. 计划 tick 队列优化
    // ========================================================================

    /**
     * 优化的计划 tick 队列。使用按 tick 分桶的结构替代优先队列，
     * 减少 tick 调度时的排序开销。
     *
     * <p>对应配置项：{@link MirageConfig#optimizeScheduledTicks}</p>
     */
    private static final class ScheduledTickBucket {
        /** 该 tick 的计划更新列表 */
        final List<ScheduledTickEntry> entries = new ArrayList<>();
    }

    /**
     * 计划 tick 条目。
     */
    public static final class ScheduledTickEntry {
        /** 方块位置键 */
        public final long posKey;
        /** 目标 tick */
        public final long targetTick;
        /** 更新数据（由 patch 代码定义） */
        public final Object data;

        /**
         * 构造计划 tick 条目。
         *
         * @param posKey    方块位置键
         * @param targetTick 目标 tick
         * @param data      更新数据
         */
        public ScheduledTickEntry(long posKey, long targetTick, Object data) {
            this.posKey = posKey;
            this.targetTick = targetTick;
            this.data = data;
        }
    }

    /** 计划 tick 分桶存储：key = 目标 tick, value = 该 tick 的更新列表 */
    private static final ConcurrentHashMap<Long, ScheduledTickBucket> SCHEDULED_TICK_BUCKETS =
            new ConcurrentHashMap<>(64);

    /** 计划 tick 调度次数 */
    private static final AtomicLong scheduledTickCount = new AtomicLong();

    /**
     * 将计划 tick 加入优化的调度队列。
     *
     * <p>使用分桶结构按目标 tick 分组存储，避免每次取最优先更新时的排序开销。
     * 适用于大量计划 tick 的场景（如红石中继器、比较器、观察者等）。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#scheduleTick} 或
     * {@code TickNextTickList} 添加计划更新时调用。</p>
     *
     * @param posKey      方块位置键
     * @param targetTick  目标执行 tick
     * @param data        更新数据
     */
    public static void scheduleTick(long posKey, long targetTick, Object data) {
        if (!MirageConfig.optimizeScheduledTicks) {
            return;
        }

        ScheduledTickEntry entry = new ScheduledTickEntry(posKey, targetTick, data);
        SCHEDULED_TICK_BUCKETS.computeIfAbsent(targetTick, tick -> new ScheduledTickBucket())
                .entries.add(entry);
        scheduledTickCount.incrementAndGet();
    }

    /**
     * 获取指定 tick 的所有计划更新。
     *
     * <p>返回后该 tick 的桶会被移除（已消费）。</p>
     *
     * <p><b>调用位置：</b>patch 中计划 tick 处理阶段调用。</p>
     *
     * @param tick 目标 tick
     * @return 该 tick 的所有计划更新列表（可能为空）
     */
    public static List<ScheduledTickEntry> getScheduledTicks(long tick) {
        if (!MirageConfig.optimizeScheduledTicks) {
            return java.util.Collections.emptyList();
        }

        ScheduledTickBucket bucket = SCHEDULED_TICK_BUCKETS.remove(tick);
        if (bucket == null || bucket.entries.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return new ArrayList<>(bucket.entries);
    }

    /**
     * 获取计划 tick 队列中的待处理数量。
     *
     * @return 待处理数量
     */
    public static int getScheduledTickQueueSize() {
        int size = 0;
        for (ScheduledTickBucket bucket : SCHEDULED_TICK_BUCKETS.values()) {
            size += bucket.entries.size();
        }
        return size;
    }

    /**
     * 获取计划 tick 调度总次数。
     *
     * @return 调度次数
     */
    public static long getScheduledTickCount() {
        return scheduledTickCount.get();
    }

    // ========================================================================
    // 5. 方块更新合并
    // ========================================================================

    /**
     * 方块更新合并缓存。以方块位置为键，记录待处理的更新。
     * 同一位置的多次更新在 tick 内合并为一次。
     *
     * <p>对应配置项：{@link MirageConfig#mergeBlockUpdates}</p>
     */
    private static final ConcurrentHashMap<Long, Object> PENDING_BLOCK_UPDATES =
            new ConcurrentHashMap<>(256);

    /** 方块更新合并次数 */
    private static final AtomicLong blockUpdatesMerged = new AtomicLong();
    /** 方块更新合并执行次数 */
    private static final AtomicLong blockUpdateFlushCount = new AtomicLong();

    /**
     * 生成方块位置键。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 位置键
     */
    private static long blockPosKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * 将方块更新加入合并队列。
     *
     * <p>如果同一位置已有待处理的更新，则用新更新替换（后到的更新覆盖之前的），
     * 并增加合并计数。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Level#setBlock} 或
     * {@code Level#updateBlock} 方法中，替代直接更新。</p>
     *
     * @param x      方块 X 坐标
     * @param y      方块 Y 坐标
     * @param z      方块 Z 坐标
     * @param update 更新数据（如 BlockState 对象）
     */
    public static void queueBlockUpdate(int x, int y, int z, Object update) {
        if (!MirageConfig.mergeBlockUpdates || update == null) {
            return;
        }

        long key = blockPosKey(x, y, z);
        Object existing = PENDING_BLOCK_UPDATES.put(key, update);
        if (existing != null) {
            blockUpdatesMerged.incrementAndGet();
        }
    }

    /**
     * 获取并清空当前所有待处理的方块更新。
     *
     * <p>返回的 Map 中 key 为位置键，value 为更新数据。
     * patch 代码需要从位置键解包坐标并执行实际更新。</p>
     *
     * <p><b>调用位置：</b>patch 中区块 tick 末尾调用。</p>
     *
     * @return 待处理的方块更新 Map（位置键 → 更新数据）
     */
    public static Map<Long, Object> flushBlockUpdates() {
        if (!MirageConfig.mergeBlockUpdates || PENDING_BLOCK_UPDATES.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        Map<Long, Object> updates = new LinkedHashMap<>(PENDING_BLOCK_UPDATES);
        PENDING_BLOCK_UPDATES.clear();
        blockUpdateFlushCount.incrementAndGet();
        return updates;
    }

    /**
     * 从方块位置键中提取坐标。
     *
     * @param key 位置键
     * @return int[3] {x, y, z}
     */
    public static int[] blockUpdateKeyToPos(long key) {
        return new int[]{
                (int) (key >> 38),
                (int) (key & 0xFFF),
                (int) ((key >> 12) & 0x3FFFFFF)
        };
    }

    /**
     * 获取方块更新合并次数。
     *
     * @return 合并次数
     */
    public static long getBlockUpdatesMerged() {
        return blockUpdatesMerged.get();
    }

    /**
     * 获取方块更新合并执行次数。
     *
     * @return 执行次数
     */
    public static long getBlockUpdateFlushCount() {
        return blockUpdateFlushCount.get();
    }

    // ========================================================================
    // 6. 流体更新跳过
    // ========================================================================

    /**
     * 流体状态缓存。记录非源流体的最近状态，
     * 如果流体状态未变化则跳过更新。
     *
     * <p>对应配置项：{@link MirageConfig#skipIdleFluidUpdates}</p>
     */
    private static final ConcurrentHashMap<Long, Integer> FLUID_STATE_CACHE =
            new ConcurrentHashMap<>(256);

    /** 流体更新跳过次数 */
    private static final AtomicLong fluidUpdateSkipCount = new AtomicLong();

    /**
     * 判断是否应该执行流体更新。
     *
     * <p>对于非源流体方块，如果其流级（fluid level）和方块状态未变化，
     * 则跳过本次流体更新。这可以大幅减少静止流体（如湖泊、水池）的
     * 无意义 tick 开销。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code LiquidBlock#tick} 或
     * {@code FlowingFluid#tick} 方法入口处。</p>
     *
     * @param x         方块 X 坐标
     * @param y         方块 Y 坐标
     * @param z         方块 Z 坐标
     * @param fluidState 流体状态编码（流级 + 方向等，由 patch 代码定义编码方式）
     * @return true 如果应该执行流体更新
     */
    public static boolean shouldUpdateFluid(int x, int y, int z, int fluidState) {
        if (!MirageConfig.skipIdleFluidUpdates) {
            return true;
        }

        long key = blockPosKey(x, y, z);
        Integer lastState = FLUID_STATE_CACHE.get(key);
        if (lastState != null && lastState == fluidState) {
            // 流体状态未变化，跳过更新
            fluidUpdateSkipCount.incrementAndGet();
            return false;
        }

        FLUID_STATE_CACHE.put(key, fluidState);
        return true;
    }

    /**
     * 判断流体是否是源方块（不需要跳过）。
     *
     * <p>源流体方块（流级为 0）始终需要正常 tick，因为它们可能向周围流布。</p>
     *
     * @param fluidLevel 流体流级（0 = 源方块，1-7 = 流动流体）
     * @return true 如果是源方块
     */
    public static boolean isSourceFluid(int fluidLevel) {
        return fluidLevel == 0;
    }

    /**
     * 清理流体状态缓存中不再需要的条目。
     *
     * <p><b>调用位置：</b>patch 中区块卸载或定期清理时调用。</p>
     *
     * @param chunkKey 区块坐标键（用于过滤该区块的条目）
     */
    public static void cleanFluidStateCache(long chunkKey) {
        int chunkX = MirageOptimizer.chunkKeyX(chunkKey);
        int chunkZ = MirageOptimizer.chunkKeyZ(chunkKey);
        int minBlockX = chunkX << 4;
        int maxBlockX = minBlockX + 15;
        int minBlockZ = chunkZ << 4;
        int maxBlockZ = minBlockZ + 15;

        FLUID_STATE_CACHE.keySet().removeIf(key -> {
            int x = (int) (key >> 38);
            int z = (int) ((key >> 12) & 0x3FFFFFF);
            return x >= minBlockX && x <= maxBlockX && z >= minBlockZ && z <= maxBlockZ;
        });
    }

    /**
     * 获取流体更新跳过次数。
     *
     * @return 跳过次数
     */
    public static long getFluidUpdateSkipCount() {
        return fluidUpdateSkipCount.get();
    }

    // ========================================================================
    // 7. 统计和清理
    // ========================================================================

    /**
     * 获取红石/方块 tick 优化的统计信息。
     *
     * @return 统计信息 Map
     */
    public static java.util.Map<String, Object> getRedstoneOptimizationStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("pending_redstone_updates", PENDING_REDSTONE_UPDATES.size());
        stats.put("redstone_batch_count", redstoneBatchCount.get());
        stats.put("redstone_updates_merged", redstoneUpdatesMerged.get());
        stats.put("neighbor_update_skip_count", neighborUpdateSkipCount.get());
        stats.put("neighbor_update_cache_size", NEIGHBOR_UPDATE_CACHE.size());
        stats.put("redstone_chain_count", redstoneChainCounter.get());
        stats.put("redstone_chain_truncated_count", redstoneChainTruncatedCount.get());
        stats.put("scheduled_tick_buckets", SCHEDULED_TICK_BUCKETS.size());
        stats.put("scheduled_tick_count", scheduledTickCount.get());
        stats.put("pending_block_updates", PENDING_BLOCK_UPDATES.size());
        stats.put("block_updates_merged", blockUpdatesMerged.get());
        stats.put("block_update_flush_count", blockUpdateFlushCount.get());
        stats.put("fluid_state_cache_size", FLUID_STATE_CACHE.size());
        stats.put("fluid_update_skip_count", fluidUpdateSkipCount.get());
        return stats;
    }

    /**
     * 清理指定区块的所有红石/方块 tick 优化记录。在区块卸载时调用。
     *
     * <p><b>调用位置：</b>patch 中区块卸载方法中。</p>
     *
     * @param chunkKey 区块坐标键
     */
    public static void cleanupChunk(long chunkKey) {
        // 清理流体状态缓存
        cleanFluidStateCache(chunkKey);

        // 清理邻居更新缓存中该区块的条目
        int chunkX = MirageOptimizer.chunkKeyX(chunkKey);
        int chunkZ = MirageOptimizer.chunkKeyZ(chunkKey);
        int minBlockX = chunkX << 4;
        int maxBlockX = minBlockX + 15;
        int minBlockZ = chunkZ << 4;
        int maxBlockZ = minBlockZ + 15;

        NEIGHBOR_UPDATE_CACHE.keySet().removeIf(key -> {
            int x = (int) (key >> 38);
            int z = (int) ((key >> 12) & 0x3FFFFFF);
            return x >= minBlockX && x <= maxBlockX && z >= minBlockZ && z <= maxBlockZ;
        });

        PENDING_BLOCK_UPDATES.keySet().removeIf(key -> {
            int x = (int) (key >> 38);
            int z = (int) ((key >> 12) & 0x3FFFFFF);
            return x >= minBlockX && x <= maxBlockX && z >= minBlockZ && z <= maxBlockZ;
        });

        PENDING_REDSTONE_UPDATES.keySet().removeIf(key -> {
            int x = (int) (key >> 38);
            int z = (int) ((key >> 12) & 0x3FFFFFF);
            return x >= minBlockX && x <= maxBlockX && z >= minBlockZ && z <= maxBlockZ;
        });
    }

    /**
     * 清空所有红石/方块 tick 优化缓存。在服务器关闭时调用。
     */
    public static void clearAll() {
        PENDING_REDSTONE_UPDATES.clear();
        NEIGHBOR_UPDATE_CACHE.clear();
        SCHEDULED_TICK_BUCKETS.clear();
        PENDING_BLOCK_UPDATES.clear();
        FLUID_STATE_CACHE.clear();
        redstoneBatchCount.set(0);
        redstoneUpdatesMerged.set(0);
        neighborUpdateSkipCount.set(0);
        redstoneChainCounter.set(0);
        redstoneChainTruncatedCount.set(0);
        scheduledTickCount.set(0);
        blockUpdatesMerged.set(0);
        blockUpdateFlushCount.set(0);
        fluidUpdateSkipCount.set(0);
    }
}
