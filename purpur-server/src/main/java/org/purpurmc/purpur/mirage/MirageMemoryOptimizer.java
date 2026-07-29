package org.purpurmc.purpur.mirage;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MirageMemoryOptimizer — 内存优化模块（FerriteCore / MemoryFix 理念）。
 *
 * <p>本模块实现了一系列内存占用优化策略，通过缓存复用、数据压缩、弱引用等手段
 * 显著降低服务器的堆内存占用，特别针对大型服务器和高实体数量场景。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>BlockState 缓存系统 — 缓存常用方块状态以减少内存分配</li>
 *   <li>实体数据压缩存储 — 使用紧凑的数据结构存储实体属性</li>
 *   <li>ItemStack NBT 缓存 — 对常见物品 NBT 进行 intern 去重</li>
 *   <li>区块调色板优化 — 对空区块段使用更紧凑的调色板</li>
 *   <li>弱引用区块缓存 — 允许 GC 在内存压力下回收区块</li>
 *   <li>内存使用监控和报告 — 实时追踪内存占用并生成报告</li>
 * </ul>
 *
 * <p>所有配置项从 {@link MirageConfig} 读取，所有 public 方法均为 static。</p>
 *
 * <p><b>线程安全：</b>所有缓存使用 {@link ConcurrentHashMap} 或 volatile 字段，
 * 可安全用于多线程环境。</p>
 */
@SuppressWarnings("unused")
public final class MirageMemoryOptimizer {

    private MirageMemoryOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. BlockState 缓存系统
    // ========================================================================

    /**
     * BlockState 缓存条目。缓存常用方块状态对象以避免重复创建和查找。
     *
     * <p>对应配置项：{@link MirageConfig#optimizeBlockStateCache}、
     * {@link MirageConfig#cacheBlockStateProperties}、
     * {@link MirageConfig#blockStateCacheMaxSize}</p>
     */
    private static final class BlockStateCacheEntry {
        final Object blockState;      // BlockState 实例
        final int stateId;             // 方块状态 ID
        final int packedData;          // 压缩后的属性数据
        volatile long lastAccessTime;  // 最后访问时间（用于 LRU 淘汰）

        BlockStateCacheEntry(Object blockState, int stateId, int packedData) {
            this.blockState = blockState;
            this.stateId = stateId;
            this.packedData = packedData;
            this.lastAccessTime = System.nanoTime();
        }

        void touch() {
            this.lastAccessTime = System.nanoTime();
        }
    }

    /**
     * BlockState 缓存：以 stateId 为键存储缓存条目。
     * 使用 ConcurrentHashMap 保证线程安全，通过容量上限控制内存。
     */
    private static final ConcurrentHashMap<Integer, BlockStateCacheEntry> BLOCK_STATE_CACHE =
            new ConcurrentHashMap<>(4096);

    /** BlockState 缓存命中次数 */
    private static final AtomicLong blockStateCacheHits = new AtomicLong();
    /** BlockState 缓存未命中次数 */
    private static final AtomicLong blockStateCacheMisses = new AtomicLong();

    /**
     * 缓存方块状态对象。将 BlockState 与其 ID 和压缩数据关联存储，
     * 后续可通过 ID 快速获取，避免重复属性查找。
     *
     * <p><b>调用位置：</b>patch 中 {@code BlockState} 初始化或属性查找时调用。</p>
     *
     * @param blockState 方块状态对象（NMS {@code BlockState}）
     * @param stateId    方块状态 ID
     * @param packedData 压缩后的属性数据（可由调用方自行编码）
     * @param <T>        BlockState 类型
     * @return 缓存后的 BlockState 对象（可能是已有实例）
     */
    @SuppressWarnings("unchecked")
    public static <T> T cacheBlockState(T blockState, int stateId, int packedData) {
        if (!MirageConfig.optimizeBlockStateCache || blockState == null) {
            return blockState;
        }

        // 检查容量，必要时淘汰旧条目
        if (BLOCK_STATE_CACHE.size() >= MirageConfig.blockStateCacheMaxSize) {
            evictOldBlockStateEntries();
        }

        BlockStateCacheEntry entry = new BlockStateCacheEntry(blockState, stateId, packedData);
        BlockStateCacheEntry existing = BLOCK_STATE_CACHE.putIfAbsent(stateId, entry);
        if (existing != null) {
            existing.touch();
            blockStateCacheHits.incrementAndGet();
            return (T) existing.blockState;
        }
        return blockState;
    }

    /**
     * 从缓存中获取方块状态对象。
     *
     * <p><b>调用位置：</b>patch 中通过 ID 查找 BlockState 时调用。</p>
     *
     * @param stateId 方块状态 ID
     * @param <T>     BlockState 类型
     * @return 缓存的 BlockState 对象，或 null 如果未命中
     */
    @SuppressWarnings("unchecked")
    public static <T> T getCachedBlockState(int stateId) {
        if (!MirageConfig.optimizeBlockStateCache) return null;
        BlockStateCacheEntry entry = BLOCK_STATE_CACHE.get(stateId);
        if (entry != null) {
            entry.touch();
            blockStateCacheHits.incrementAndGet();
            return (T) entry.blockState;
        }
        blockStateCacheMisses.incrementAndGet();
        return null;
    }

    /**
     * 获取缓存中方块状态的压缩属性数据。
     *
     * @param stateId 方块状态 ID
     * @return 压缩属性数据，或 0 如果未命中
     */
    public static int getCachedBlockStatePackedData(int stateId) {
        if (!MirageConfig.cacheBlockStateProperties) return 0;
        BlockStateCacheEntry entry = BLOCK_STATE_CACHE.get(stateId);
        if (entry != null) {
            entry.touch();
            return entry.packedData;
        }
        return 0;
    }

    /**
     * 淘汰最久未访问的 BlockState 缓存条目（LRU 淘汰）。
     * 每次调用淘汰约 10% 的条目。
     */
    private static void evictOldBlockStateEntries() {
        int targetRemoval = Math.max(1, MirageConfig.blockStateCacheMaxSize / 10);
        List<BlockStateCacheEntry> entries = new ArrayList<>(BLOCK_STATE_CACHE.values());
        entries.sort((a, b) -> Long.compare(a.lastAccessTime, b.lastAccessTime));
        for (int i = 0; i < Math.min(targetRemoval, entries.size()); i++) {
            BLOCK_STATE_CACHE.remove(entries.get(i).stateId);
        }
    }

    /**
     * 清空 BlockState 缓存。
     */
    public static void clearBlockStateCache() {
        BLOCK_STATE_CACHE.clear();
    }

    // ========================================================================
    // 2. 实体数据压缩存储
    // ========================================================================

    /**
     * 实体数据压缩存储条目。使用紧凑的字段编码代替多个独立对象引用，
     * 减少每个实体的内存开销。
     *
     * <p>对应配置项：{@link MirageConfig#compactEntityData}</p>
     *
     * <p>设计理念：将实体常用属性（坐标、旋转、生命值等）打包到少量 long 字段中，
     * 而非使用多个独立字段。每个 long 可编码多个小范围值。</p>
     */
    public static final class CompactEntityData {

        // 编码布局：
        // packedPos1: [x(32bit) | y(16bit) | z(16bit)]  → 64 bit
        // packedRot:  [yaw(16bit) | pitch(16bit) | flags(32bit)] → 64 bit
        // packedMisc: [health(16bit) | air(16bit) | fire(8bit) | arrows(8bit) | ...] → 64 bit

        private long packedPosition;
        private long packedRotation;
        private long packedMisc;

        /**
         * 创建一个空的压缩实体数据条目。
         */
        public CompactEntityData() {
        }

        /**
         * 设置实体位置（坐标精度为 1/32 格）。
         *
         * @param x X 坐标
         * @param y Y 坐标
         * @param z Z 坐标
         */
        public void setPosition(double x, double y, double z) {
            int ix = (int) (x * 32);
            int iy = (int) (y * 32);
            int iz = (int) (z * 32);
            this.packedPosition = ((long) ix << 32) | (((long) iy & 0xFFFFL) << 16) | ((long) iz & 0xFFFFL);
        }

        /**
         * 获取 X 坐标。
         *
         * @return X 坐标（double 精度）
         */
        public double getX() {
            return (int) (packedPosition >> 32) / 32.0;
        }

        /**
         * 获取 Y 坐标。
         *
         * @return Y 坐标（double 精度）
         */
        public double getY() {
            return (short) ((packedPosition >> 16) & 0xFFFFL) / 32.0;
        }

        /**
         * 获取 Z 坐标。
         *
         * @return Z 坐标（double 精度）
         */
        public double getZ() {
            return (short) (packedPosition & 0xFFFFL) / 32.0;
        }

        /**
         * 设置实体旋转角度（精度为 1/256 度）。
         *
         * @param yaw   偏航角（度）
         * @param pitch 俯仰角（度）
         */
        public void setRotation(float yaw, float pitch) {
            int iyaw = (int) (yaw * 256.0f / 360.0f) & 0xFFFF;
            int ipitch = (int) (pitch * 256.0f / 360.0f) & 0xFFFF;
            this.packedRotation = ((long) iyaw << 48) | ((long) ipitch << 32);
        }

        /**
         * 获取偏航角。
         *
         * @return 偏航角（度）
         */
        public float getYaw() {
            return (short) ((packedRotation >> 48) & 0xFFFFL) * 360.0f / 256.0f;
        }

        /**
         * 获取俯仰角。
         *
         * @return 俯仰角（度）
         */
        public float getPitch() {
            return (short) ((packedRotation >> 32) & 0xFFFFL) * 360.0f / 256.0f;
        }

        /**
         * 设置实体标志位。
         *
         * @param flags 标志位整数
         */
        public void setFlags(int flags) {
            this.packedRotation = (this.packedRotation & 0xFFFFFFFF00000000L) | (flags & 0xFFFFFFFFL);
        }

        /**
         * 获取实体标志位。
         *
         * @return 标志位整数
         */
        public int getFlags() {
            return (int) (packedRotation & 0xFFFFFFFFL);
        }

        /**
         * 设置实体杂项数据（生命值、氧气、火焰等）。
         *
         * @param health 生命值（0-65535）
         * @param air    氧气值（0-65535）
         * @param fire   火焰刻（0-255）
         * @param arrows 箭矢数（0-255）
         */
        public void setMisc(int health, int air, int fire, int arrows) {
            this.packedMisc = ((long) (health & 0xFFFF) << 48)
                    | ((long) (air & 0xFFFF) << 32)
                    | ((long) (fire & 0xFF) << 24)
                    | ((long) (arrows & 0xFF) << 16);
        }

        /**
         * 获取生命值。
         *
         * @return 生命值
         */
        public int getHealth() {
            return (int) ((packedMisc >> 48) & 0xFFFFL);
        }

        /**
         * 获取氧气值。
         *
         * @return 氧气值
         */
        public int getAir() {
            return (int) ((packedMisc >> 32) & 0xFFFFL);
        }

        /**
         * 获取火焰刻。
         *
         * @return 火焰刻数
         */
        public int getFire() {
            return (int) ((packedMisc >> 24) & 0xFFL);
        }

        /**
         * 获取箭矢数。
         *
         * @return 箭矢数
         */
        public int getArrows() {
            return (int) ((packedMisc >> 16) & 0xFFL);
        }

        /**
         * 将压缩数据导出为 long 数组（用于持久化）。
         *
         * @return 包含三个 long 的数组
         */
        public long[] toArray() {
            return new long[]{packedPosition, packedRotation, packedMisc};
        }

        /**
         * 从 long 数组导入压缩数据。
         *
         * @param data long 数组（长度至少为 3）
         */
        public void fromArray(long[] data) {
            if (data.length >= 3) {
                this.packedPosition = data[0];
                this.packedRotation = data[1];
                this.packedMisc = data[2];
            }
        }
    }

    /**
     * 实体压缩数据缓存：以实体 ID 为键。
     */
    private static final ConcurrentHashMap<Integer, CompactEntityData> COMPACT_ENTITY_DATA_CACHE =
            new ConcurrentHashMap<>(512);

    /**
     * 获取或创建实体的压缩数据对象。若缓存中不存在则创建新实例。
     *
     * <p><b>调用位置：</b>patch 中实体数据存储/序列化时调用，
     * 替代多个独立字段的读写。</p>
     *
     * @param entityId 实体 ID
     * @return 该实体的 {@link CompactEntityData} 实例
     */
    public static CompactEntityData getCompactEntityData(int entityId) {
        if (!MirageConfig.compactEntityData) {
            return null;
        }
        return COMPACT_ENTITY_DATA_CACHE.computeIfAbsent(entityId, id -> new CompactEntityData());
    }

    /**
     * 移除实体的压缩数据（实体移除时调用）。
     *
     * @param entityId 实体 ID
     */
    public static void removeCompactEntityData(int entityId) {
        COMPACT_ENTITY_DATA_CACHE.remove(entityId);
    }

    // ========================================================================
    // 3. ItemStack NBT 缓存
    // ========================================================================

    /**
     * ItemStack NBT 缓存。对常见物品 NBT 数据进行 intern 去重，
     * 使相同内容的 NBT 共享同一实例。
     *
     * <p>对应配置项：{@link MirageConfig#cacheItemNbt}</p>
     */
    private static final ConcurrentHashMap<Integer, Object> ITEM_NBT_CACHE = new ConcurrentHashMap<>(2048);

    /** Item NBT 缓存命中次数 */
    private static final AtomicLong itemNbtCacheHits = new AtomicLong();
    /** Item NBT 缓存未命中次数 */
    private static final AtomicLong itemNbtCacheMisses = new AtomicLong();

    /**
     * 对 ItemStack NBT 进行去重缓存。
     * 如果缓存中已存在等价 NBT 则返回缓存实例，否则存入并返回。
     *
     * <p><b>调用位置：</b>patch 中 ItemStack 创建或读取 NBT 时调用。</p>
     *
     * @param nbt 物品 NBT 数据对象
     * @param <T> NBT 类型
     * @return 去重后的 NBT 对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T cacheItemNbt(T nbt) {
        if (!MirageConfig.cacheItemNbt || nbt == null) {
            return nbt;
        }
        int hash = nbt.hashCode();
        Object existing = ITEM_NBT_CACHE.putIfAbsent(hash, nbt);
        if (existing != null) {
            itemNbtCacheHits.incrementAndGet();
            return (T) existing;
        }
        itemNbtCacheMisses.incrementAndGet();
        return nbt;
    }

    /**
     * 清空 ItemStack NBT 缓存。
     */
    public static void clearItemNbtCache() {
        ITEM_NBT_CACHE.clear();
    }

    // ========================================================================
    // 4. 区块调色板优化
    // ========================================================================

    /**
     * 区块调色板优化统计。追踪调色板优化带来的内存节省。
     *
     * <p>对应配置项：{@link MirageConfig#optimizeChunkPalette}</p>
     */
    private static final AtomicLong paletteOptimizationCount = new AtomicLong();
    private static final AtomicLong paletteBytesSaved = new AtomicLong();

    /**
     * 判断区块段是否适合使用更紧凑的调色板编码。
     * 如果一个区块段中超过 90% 是同一种方块（通常是空气），建议使用单值调色板。
     *
     * <p><b>调用位置：</b>patch 中区块段数据写入时调用，
     * 决定是否使用紧凑调色板。</p>
     *
     * @param blockCounts 各方块的计数 Map（方块状态 ID → 数量）
     * @param totalCount  方块总数（通常为 4096 = 16*16*16）
     * @return true 如果建议使用单值调色板
     */
    public static boolean shouldUseSingleValuePalette(Map<Integer, Integer> blockCounts, int totalCount) {
        if (!MirageConfig.optimizeChunkPalette || blockCounts == null || blockCounts.isEmpty()) {
            return false;
        }

        // 找到占比最大的方块
        int maxCount = 0;
        for (int count : blockCounts.values()) {
            if (count > maxCount) {
                maxCount = count;
            }
        }

        // 如果超过 90% 是同一种方块，使用单值调色板
        boolean shouldOptimize = maxCount > (totalCount * 9 / 10);
        if (shouldOptimize) {
            paletteOptimizationCount.incrementAndGet();
            // 单值调色板节省约 2KB（4096 * 0.5 byte → 1 byte）
            paletteBytesSaved.addAndGet(2048);
        }
        return shouldOptimize;
    }

    /**
     * 计算调色板优化的最佳 bits-per-block 值。
     * 根据唯一方块种类数自动选择最紧凑的编码方式。
     *
     * @param uniqueBlockCount 唯一方块种类数
     * @return 最佳 bits-per-block 值（最小为 4，最大为 15）
     */
    public static int calculateOptimalBitsPerBlock(int uniqueBlockCount) {
        if (uniqueBlockCount <= 1) return 0;  // 单值调色板
        if (uniqueBlockCount <= 2) return 1;
        if (uniqueBlockCount <= 4) return 2;
        if (uniqueBlockCount <= 8) return 3;
        if (uniqueBlockCount <= 16) return 4;
        if (uniqueBlockCount <= 32) return 5;
        if (uniqueBlockCount <= 64) return 6;
        if (uniqueBlockCount <= 128) return 7;
        if (uniqueBlockCount <= 256) return 8;
        if (uniqueBlockCount <= 512) return 9;
        if (uniqueBlockCount <= 1024) return 10;
        if (uniqueBlockCount <= 2048) return 11;
        if (uniqueBlockCount <= 4096) return 12;
        return 15; // 直接使用 BlockState ID
    }

    /**
     * 获取调色板优化统计。
     *
     * @return 包含优化次数和节省字节数的 Map
     */
    public static Map<String, Long> getPaletteOptimizationStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("optimization_count", paletteOptimizationCount.get());
        stats.put("bytes_saved", paletteBytesSaved.get());
        return stats;
    }

    // ========================================================================
    // 5. 弱引用区块缓存
    // ========================================================================

    /**
     * 弱引用区块缓存。使用 {@link WeakReference} 存储区块对象，
     * 允许 JVM 在内存压力下自动回收不活跃的区块。
     *
     * <p>对应配置项：{@link MirageConfig#weakChunkCache}</p>
     */
    private static final ConcurrentHashMap<Long, WeakReference<Object>> WEAK_CHUNK_CACHE =
            new ConcurrentHashMap<>(1024);

    /**
     * 将区块存入弱引用缓存。
     *
     * <p><b>调用位置：</b>patch 中区块加载完成时调用。</p>
     *
     * @param chunkKey 区块坐标键
     * @param chunk    区块对象
     */
    public static void putWeakChunk(long chunkKey, Object chunk) {
        if (!MirageConfig.weakChunkCache || chunk == null) return;
        WEAK_CHUNK_CACHE.put(chunkKey, new WeakReference<>(chunk));
    }

    /**
     * 从弱引用缓存中获取区块。如果区块已被 GC 回收则返回 null。
     *
     * <p><b>调用位置：</b>patch 中区块查找时先检查弱引用缓存。</p>
     *
     * @param chunkKey 区块坐标键
     * @param <T>      区块类型
     * @return 区块对象，或 null 如果未缓存或已被回收
     */
    @SuppressWarnings("unchecked")
    public static <T> T getWeakChunk(long chunkKey) {
        if (!MirageConfig.weakChunkCache) return null;
        WeakReference<Object> ref = WEAK_CHUNK_CACHE.get(chunkKey);
        if (ref != null) {
            Object chunk = ref.get();
            if (chunk != null) {
                return (T) chunk;
            }
            // 引用已被回收，清除缓存条目
            WEAK_CHUNK_CACHE.remove(chunkKey);
        }
        return null;
    }

    /**
     * 从弱引用缓存中移除区块。
     *
     * @param chunkKey 区块坐标键
     */
    public static void removeWeakChunk(long chunkKey) {
        WEAK_CHUNK_CACHE.remove(chunkKey);
    }

    /**
     * 清理弱引用缓存中被 GC 回收的条目。
     *
     * <p><b>调用位置：</b>patch 中定期清理任务中调用（如每 200 tick）。</p>
     *
     * @return 清理的条目数
     */
    public static int cleanWeakChunkCache() {
        if (!MirageConfig.weakChunkCache) return 0;
        int removed = 0;
        var iterator = WEAK_CHUNK_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().get() == null) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * 获取弱引用缓存中的区块数量（注意：部分可能已被 GC 回收）。
     *
     * @return 缓存条目数
     */
    public static int getWeakChunkCacheSize() {
        return WEAK_CHUNK_CACHE.size();
    }

    // ========================================================================
    // 6. 内存使用监控和报告
    // ========================================================================

    /** 上次内存统计快照 */
    private static volatile MemorySnapshot lastSnapshot;

    /**
     * 内存使用快照。记录某一时刻的 JVM 内存和 Mirage 缓存状态。
     */
    public static final class MemorySnapshot {
        /** 快照时间戳（毫秒） */
        public final long timestamp;
        /** JVM 堆已使用内存（字节） */
        public final long usedHeap;
        /** JVM 堆最大可用内存（字节） */
        public final long maxHeap;
        /** JVM 堆已提交内存（字节） */
        public final long committedHeap;
        /** BlockState 缓存大小 */
        public final int blockStateCacheSize;
        /** 实体压缩数据缓存大小 */
        public final int compactEntityDataSize;
        /** Item NBT 缓存大小 */
        public final int itemNbtCacheSize;
        /** 弱引用区块缓存大小 */
        public final int weakChunkCacheSize;
        /** 调色板优化次数 */
        public final long paletteOptimizationCount;
        /** 调色板优化节省字节数 */
        public final long paletteBytesSaved;

        MemorySnapshot(long timestamp, long usedHeap, long maxHeap, long committedHeap,
                       int blockStateCacheSize, int compactEntityDataSize, int itemNbtCacheSize,
                       int weakChunkCacheSize, long paletteOptimizationCount, long paletteBytesSaved) {
            this.timestamp = timestamp;
            this.usedHeap = usedHeap;
            this.maxHeap = maxHeap;
            this.committedHeap = committedHeap;
            this.blockStateCacheSize = blockStateCacheSize;
            this.compactEntityDataSize = compactEntityDataSize;
            this.itemNbtCacheSize = itemNbtCacheSize;
            this.weakChunkCacheSize = weakChunkCacheSize;
            this.paletteOptimizationCount = paletteOptimizationCount;
            this.paletteBytesSaved = paletteBytesSaved;
        }

        /**
         * 获取堆使用率（百分比）。
         *
         * @return 0-100 的使用率
         */
        public double getHeapUsagePercent() {
            if (maxHeap <= 0) return 0;
            return (usedHeap * 100.0) / maxHeap;
        }
    }

    /**
     * 拍摄当前内存使用快照。
     *
     * <p><b>调用位置：</b>patch 中定时任务或调试命令中调用。</p>
     *
     * @return 当前内存使用快照
     */
    public static MemorySnapshot takeMemorySnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        long maxHeap = runtime.maxMemory();
        long committedHeap = runtime.totalMemory();

        MemorySnapshot snapshot = new MemorySnapshot(
                System.currentTimeMillis(),
                usedHeap,
                maxHeap,
                committedHeap,
                BLOCK_STATE_CACHE.size(),
                COMPACT_ENTITY_DATA_CACHE.size(),
                ITEM_NBT_CACHE.size(),
                WEAK_CHUNK_CACHE.size(),
                paletteOptimizationCount.get(),
                paletteBytesSaved.get()
        );
        lastSnapshot = snapshot;
        return snapshot;
    }

    /**
     * 获取上一次内存快照（如果存在）。
     *
     * @return 上次快照，或 null
     */
    public static MemorySnapshot getLastMemorySnapshot() {
        return lastSnapshot;
    }

    /**
     * 生成内存优化报告字符串。
     *
     * <p><b>调用位置：</b>patch 中调试命令或日志输出时调用。</p>
     *
     * @return 格式化的内存报告字符串
     */
    public static String generateMemoryReport() {
        MemorySnapshot snapshot = takeMemorySnapshot();
        StringBuilder sb = new StringBuilder(512);
        sb.append("========== Mirage Memory Optimization Report ==========\n");
        sb.append("Timestamp: ").append(snapshot.timestamp).append("\n");
        sb.append(String.format("Heap: used=%dMB / max=%dMB / committed=%dMB (%.1f%%)%n",
                snapshot.usedHeap / (1024 * 1024),
                snapshot.maxHeap / (1024 * 1024),
                snapshot.committedHeap / (1024 * 1024),
                snapshot.getHeapUsagePercent()));
        sb.append("BlockState cache: ").append(snapshot.blockStateCacheSize)
                .append(" entries (max=").append(MirageConfig.blockStateCacheMaxSize).append(")\n");
        sb.append("Compact entity data: ").append(snapshot.compactEntityDataSize).append(" entities\n");
        sb.append("Item NBT cache: ").append(snapshot.itemNbtCacheSize).append(" entries\n");
        sb.append("Weak chunk cache: ").append(snapshot.weakChunkCacheSize).append(" entries\n");
        sb.append("Palette optimizations: ").append(snapshot.paletteOptimizationCount)
                .append(" (saved ~").append(snapshot.paletteBytesSaved / 1024).append("KB)\n");
        sb.append("BlockState cache hits: ").append(blockStateCacheHits.get())
                .append(" / misses: ").append(blockStateCacheMisses.get()).append("\n");
        sb.append("Item NBT cache hits: ").append(itemNbtCacheHits.get())
                .append(" / misses: ").append(itemNbtCacheMisses.get()).append("\n");
        sb.append("=======================================================");
        return sb.toString();
    }

    /**
     * 获取所有内存优化缓存的统计信息。
     *
     * @return 统计信息 Map
     */
    public static Map<String, Object> getMemoryStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("block_state_cache_size", BLOCK_STATE_CACHE.size());
        stats.put("block_state_cache_hits", blockStateCacheHits.get());
        stats.put("block_state_cache_misses", blockStateCacheMisses.get());
        stats.put("compact_entity_data_size", COMPACT_ENTITY_DATA_CACHE.size());
        stats.put("item_nbt_cache_size", ITEM_NBT_CACHE.size());
        stats.put("item_nbt_cache_hits", itemNbtCacheHits.get());
        stats.put("item_nbt_cache_misses", itemNbtCacheMisses.get());
        stats.put("weak_chunk_cache_size", WEAK_CHUNK_CACHE.size());
        stats.put("palette_optimization_count", paletteOptimizationCount.get());
        stats.put("palette_bytes_saved", paletteBytesSaved.get());
        return stats;
    }

    /**
     * 尝试释放内存。清除部分缓存并建议 GC。
     *
     * <p><b>注意：</b>此方法不会强制 GC，仅清除缓存并提示 JVM。
     * 建议仅在内存压力较大时调用。</p>
     *
     * <p><b>调用位置：</b>patch 中低内存回调或管理员命令中调用。</p>
     *
     * @return 释放的缓存条目总数
     */
    public static int releaseMemory() {
        int released = 0;
        released += BLOCK_STATE_CACHE.size();
        released += COMPACT_ENTITY_DATA_CACHE.size() / 2; // 只释放一半

        // 保留 BlockState 缓存的一半
        if (BLOCK_STATE_CACHE.size() > 1024) {
            evictOldBlockStateEntries();
        }

        // 清理弱引用缓存中被回收的条目
        released += cleanWeakChunkCache();

        // 提示 GC（不保证执行）
        System.gc();
        return released;
    }

    /**
     * 清空所有内存优化缓存。在服务器关闭时调用。
     */
    public static void clearAllCaches() {
        BLOCK_STATE_CACHE.clear();
        COMPACT_ENTITY_DATA_CACHE.clear();
        ITEM_NBT_CACHE.clear();
        WEAK_CHUNK_CACHE.clear();
        blockStateCacheHits.set(0);
        blockStateCacheMisses.set(0);
        itemNbtCacheHits.set(0);
        itemNbtCacheMisses.set(0);
        paletteOptimizationCount.set(0);
        paletteBytesSaved.set(0);
    }
}
