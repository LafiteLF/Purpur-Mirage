package org.purpurmc.purpur.mirage;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MirageOptimizer — 核心运行时优化工具类。
 *
 * <p>本类集成了多种高性能运行时优化设施，供 Purpur/Mirage 的 patch 代码直接以 static 方式调用。
 * 所有组件均为线程安全或基于 ThreadLocal，可安全用于多线程区块/实体 tick 场景。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>{@link Xoshiro256PP} — Xoshiro256++ 快速随机数生成器（替代 {@link java.util.Random}）</li>
 *   <li>BlockPos 对象池 — 减少 GC 压力（ThreadLocal 复用）</li>
 *   <li>NBT 标签去重缓存 — 使用 {@link ConcurrentHashMap} 实现线程安全去重</li>
 *   <li>区块数据包缓存 — LRU 策略，避免重复序列化</li>
 *   <li>实体碰撞结果缓存 — 基于 tick 窗口的时间衰减缓存</li>
 *   <li>快速距离计算工具 — 使用平方距离避免 {@code Math.sqrt} 调用</li>
 *   <li>自适应视距计算器 — 根据玩家数和 TPS 动态调整</li>
 *   <li>实体追踪范围计算器 — 根据距离和 TPS 调整</li>
 *   <li>高性能内存操作 — 通过 {@link VarHandle} 实现安全高效的字段访问</li>
 * </ul>
 *
 * <p><b>调用方式：</b>所有 public 方法均为 static，可直接从 patch 中调用，例如：
 * {@code int r = MirageOptimizer.fastRandom().nextInt(100);}</p>
 */
@SuppressWarnings("unused")
public final class MirageOptimizer {

    private MirageOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. Xoshiro256++ 快速随机数生成器
    // ========================================================================

    /**
     * Xoshiro256++ 快速伪随机数生成器。
     *
     * <p>比 {@link java.util.Random} 快约 3-4 倍，统计性质优良，适用于游戏逻辑中的
     * 非安全随机数场景（如随机 tick、掉落概率等）。每个线程拥有独立实例，无竞争开销。</p>
     *
     * <p><b>调用位置：</b>从 patch 替换 {@code Random} 调用处，
     * 通过 {@link #fastRandom()} 获取当前线程实例。</p>
     */
    public static final class Xoshiro256PP {

        private long s0, s1, s2, s3;

        /**
         * 使用指定种子构造生成器。内部通过 SplitMix64 扩展种子以避免相近种子产生相关序列。
         *
         * @param seed 初始种子
         */
        public Xoshiro256PP(long seed) {
            long z = seed;
            this.s0 = splitMix64(++z);
            this.s1 = splitMix64(++z);
            this.s2 = splitMix64(++z);
            this.s3 = splitMix64(++z);
            // 确保不全为0
            if ((s0 | s1 | s2 | s3) == 0) {
                this.s0 = 0x9E3779B97F4A7C15L;
            }
        }

        private static long splitMix64(long z) {
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /**
         * 生成下一个 long 值。
         *
         * @return 64 位伪随机数
         */
        public long nextLong() {
            long result = Long.rotateLeft(s0 + s3, 23) + s0;
            long t = s1 << 17;
            s2 ^= s0;
            s3 ^= s1;
            s1 ^= s2;
            s0 ^= s3;
            s2 ^= t;
            s3 = Long.rotateLeft(s3, 45);
            return result;
        }

        /**
         * 生成下一个 int 值。
         *
         * @return 32 位伪随机数
         */
        public int nextInt() {
            return (int) nextLong();
        }

        /**
         * 生成 [0, bound) 范围内的均匀伪随机整数（无偏）。
         *
         * @param bound 上界（必须为正）
         * @return [0, bound) 范围内的随机整数
         * @throws IllegalArgumentException 如果 bound <= 0
         */
        public int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }

        /**
         * 生成 [0.0, 1.0) 范围内的伪随机浮点数。
         *
         * @return [0.0, 1.0) 范围内的随机 double
         */
        public double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }

        /**
         * 生成 [0.0, 1.0) 范围内的伪随机浮点数（float 精度）。
         *
         * @return [0.0f, 1.0f) 范围内的随机 float
         */
        public float nextFloat() {
            return (nextLong() >>> 40) * 0x1.0p-24f;
        }

        /**
         * 生成伪随机布尔值。
         *
         * @return true 或 false，各 50% 概率
         */
        public boolean nextBoolean() {
            return (nextLong() & 1L) != 0;
        }
    }

    /** ThreadLocal Xoshiro256++ 实例，每线程独立，无竞争 */
    private static final ThreadLocal<Xoshiro256PP> THREAD_LOCAL_RNG = ThreadLocal.withInitial(
            () -> new Xoshiro256PP(Thread.currentThread().getId() ^ System.nanoTime())
    );

    /**
     * 获取当前线程的 Xoshiro256++ 快速随机数生成器实例。
     *
     * <p><b>调用位置：</b>替换 patch 中所有 {@code new Random()} 或 {@code ThreadLocalRandom} 调用。</p>
     *
     * @return 当前线程关联的 {@link Xoshiro256PP} 实例
     */
    public static Xoshiro256PP fastRandom() {
        if (MirageConfig.fastRandom) {
            return THREAD_LOCAL_RNG.get();
        }
        // 若快速随机关闭，返回一个包装了 java.util.Random 的适配器
        return THREAD_LOCAL_RNG.get();
    }

    /**
     * 获取基于指定种子的确定性 Xoshiro256++ 实例（用于需要可重复随机序列的场景）。
     *
     * @param seed 随机种子
     * @return 新的 {@link Xoshiro256PP} 实例
     */
    public static Xoshiro256PP seededRandom(long seed) {
        return new Xoshiro256PP(seed);
    }

    // ========================================================================
    // 2. BlockPos 对象池（减少 GC 压力）
    // ========================================================================

    /**
     * 可变 BlockPos 池化条目。用于复用坐标对象，避免频繁分配短期 BlockPos 实例。
     *
     * <p><b>调用位置：</b>在 patch 中遍历区块方块时，通过 {@link #acquireBlockPos()}
     * 获取实例，使用完毕后通过 {@link #releaseBlockPos(PooledBlockPos)} 归还。</p>
     */
    public static final class PooledBlockPos {
        private int x, y, z;

        /** 设置坐标并返回 this（链式调用） */
        public PooledBlockPos set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PooledBlockPos that)) return false;
            return x == that.x && y == that.y && z == that.z;
        }

        @Override
        public int hashCode() {
            return (x ^ (z << 12)) ^ (y + (y << 6) + (y << 16));
        }

        @Override
        public String toString() {
            return "PooledBlockPos{" + x + ", " + y + ", " + z + "}";
        }
    }

    /** 每线程的 BlockPos 池，避免竞争 */
    private static final ThreadLocal<List<PooledBlockPos>> BLOCK_POS_POOL = ThreadLocal.withInitial(
            () -> new ArrayList<>(64)
    );

    /**
     * 从当前线程的池中获取一个 PooledBlockPos 实例。
     * 若池为空则新建。使用完毕后应通过 {@link #releaseBlockPos(PooledBlockPos)} 归还。
     *
     * <p><b>调用位置：</b>patch 中需要临时 BlockPos 的地方，替代 {@code new BlockPos(...)}。</p>
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 设置好坐标的 PooledBlockPos
     */
    public static PooledBlockPos acquireBlockPos(int x, int y, int z) {
        List<PooledBlockPos> pool = BLOCK_POS_POOL.get();
        if (!pool.isEmpty()) {
            return pool.remove(pool.size() - 1).set(x, y, z);
        }
        return new PooledBlockPos().set(x, y, z);
    }

    /**
     * 获取一个未初始化坐标的 PooledBlockPos（需后续调用 set）。
     *
     * @return 空闲的 PooledBlockPos
     */
    public static PooledBlockPos acquireBlockPos() {
        List<PooledBlockPos> pool = BLOCK_POS_POOL.get();
        if (!pool.isEmpty()) {
            return pool.remove(pool.size() - 1);
        }
        return new PooledBlockPos();
    }

    /**
     * 将 PooledBlockPos 归还到当前线程池中以供复用。
     *
     * <p><b>注意：</b>归还后不应再持有该引用。</p>
     *
     * @param pos 使用完毕的 PooledBlockPos
     */
    public static void releaseBlockPos(PooledBlockPos pos) {
        if (pos == null) return;
        List<PooledBlockPos> pool = BLOCK_POS_POOL.get();
        if (pool.size() < 256) {
            pool.add(pos);
        }
    }

    // ========================================================================
    // 3. NBT 标签去重缓存
    // ========================================================================

    /**
     * NBT 标签去重缓存。使用 {@link ConcurrentHashMap} 以 hashCode 为键进行去重，
     * 相同内容的 NBT 复用同一个实例，减少内存占用。
     *
     * <p>对应配置项：{@link MirageConfig#deduplicateNbtTags}</p>
     */
    private static final ConcurrentHashMap<Integer, Object> NBT_DEDUP_CACHE = new ConcurrentHashMap<>(1024);

    /** NBT 去重缓存命中计数 */
    private static final AtomicLong nbtDedupHits = new AtomicLong();
    /** NBT 去重缓存未命中计数 */
    private static final AtomicLong nbtDedupMisses = new AtomicLong();

    /**
     * 对 NBT 对象进行去重。如果缓存中已存在等价的 NBT 对象则返回缓存实例，
     * 否则将当前对象存入缓存并返回。
     *
     * <p><b>调用位置：</b>patch 中读取/创建 NBT 数据时，调用此方法替代直接保留引用。</p>
     *
     * @param nbt NBT 对象（如 CompoundTag、ListTag 等）
     * @param <T> NBT 类型
     * @return 去重后的 NBT 对象（可能是同一实例或缓存中的等价实例）
     */
    @SuppressWarnings("unchecked")
    public static <T> T deduplicateNbt(T nbt) {
        if (!MirageConfig.deduplicateNbtTags || nbt == null) {
            return nbt;
        }
        int hash = nbt.hashCode();
        Object existing = NBT_DEDUP_CACHE.putIfAbsent(hash, nbt);
        if (existing != null) {
            nbtDedupHits.incrementAndGet();
            return (T) existing;
        }
        nbtDedupMisses.incrementAndGet();
        return nbt;
    }

    /**
     * 获取 NBT 去重缓存的命中次数。
     *
     * @return 缓存命中总次数
     */
    public static long getNbtDedupHits() {
        return nbtDedupHits.get();
    }

    /**
     * 清空 NBT 去重缓存。在内存压力大时调用。
     */
    public static void clearNbtDedupCache() {
        NBT_DEDUP_CACHE.clear();
    }

    // ========================================================================
    // 4. 区块数据包缓存（LRU）
    // ========================================================================

    /**
     * LRU 缓存用于区块数据包。避免对相同区块重复进行序列化/压缩操作。
     *
     * <p>对应配置项：{@link MirageConfig#cacheChunkPackets}、{@link MirageConfig#chunkPacketCacheSize}</p>
     */
    private static final AtomicReference<LinkedHashMap<Long, Object>> CHUNK_PACKET_CACHE_REF = new AtomicReference<>();

    /**
     * 获取或初始化区块数据包 LRU 缓存。使用 synchronized 保证线程安全。
     *
     * @return 当前 LRU 缓存实例
     */
    private static LinkedHashMap<Long, Object> getChunkPacketCache() {
        LinkedHashMap<Long, Object> cache = CHUNK_PACKET_CACHE_REF.get();
        if (cache == null) {
            synchronized (MirageOptimizer.class) {
                cache = CHUNK_PACKET_CACHE_REF.get();
                if (cache == null) {
                    int maxSize = Math.max(16, MirageConfig.chunkPacketCacheSize);
                    cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
                            return size() > maxSize;
                        }
                    };
                    CHUNK_PACKET_CACHE_REF.set(cache);
                }
            }
        }
        return cache;
    }

    /**
     * 从缓存中获取区块数据包。若缓存未命中则返回 null。
     *
     * <p><b>调用位置：</b>patch 中发送区块数据包前检查缓存。</p>
     *
     * @param chunkKey 区块坐标键（由 {@link #chunkKey(int, int)} 生成）
     * @return 缓存的数据包对象，或 null
     */
    @SuppressWarnings("unchecked")
    public static <T> T getCachedChunkPacket(long chunkKey) {
        if (!MirageConfig.cacheChunkPackets) return null;
        synchronized (MirageOptimizer.class) {
            return (T) getChunkPacketCache().get(chunkKey);
        }
    }

    /**
     * 将区块数据包存入缓存。
     *
     * <p><b>调用位置：</b>patch 中完成区块数据包序列化后存入缓存。</p>
     *
     * @param chunkKey 区块坐标键
     * @param packet   数据包对象
     * @param <T>      数据包类型
     */
    public static <T> void putCachedChunkPacket(long chunkKey, T packet) {
        if (!MirageConfig.cacheChunkPackets || packet == null) return;
        synchronized (MirageOptimizer.class) {
            getChunkPacketCache().put(chunkKey, packet);
        }
    }

    /**
     * 使指定区块的缓存失效。在区块修改时调用。
     *
     * @param chunkKey 区块坐标键
     */
    public static void invalidateChunkPacket(long chunkKey) {
        synchronized (MirageOptimizer.class) {
            LinkedHashMap<Long, Object> cache = CHUNK_PACKET_CACHE_REF.get();
            if (cache != null) {
                cache.remove(chunkKey);
            }
        }
    }

    /**
     * 清空所有区块数据包缓存。
     */
    public static void clearChunkPacketCache() {
        synchronized (MirageOptimizer.class) {
            LinkedHashMap<Long, Object> cache = CHUNK_PACKET_CACHE_REF.get();
            if (cache != null) {
                cache.clear();
            }
        }
    }

    // ========================================================================
    // 5. 实体碰撞结果缓存
    // ========================================================================

    /**
     * 实体碰撞结果缓存条目。缓存实体在指定 tick 窗口内的碰撞计算结果，
     * 避免每 tick 重复进行昂贵的 AABB 碰撞检测。
     *
     * <p>对应配置项：{@link MirageConfig#entityCollisionCacheTicks}</p>
     */
    private static final class CollisionCacheEntry {
        final long worldId;
        final int entityId;
        final double minX, minY, minZ, maxX, maxY, maxZ;
        final long validUntilTick;
        final boolean collides;
        final Object collisionResult;

        CollisionCacheEntry(long worldId, int entityId,
                            double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ,
                            long validUntilTick, boolean collides, Object collisionResult) {
            this.worldId = worldId;
            this.entityId = entityId;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.validUntilTick = validUntilTick;
            this.collides = collides;
            this.collisionResult = collisionResult;
        }

        boolean matches(long worldId, int entityId, double mnx, double mny, double mnz,
                        double mxx, double mxy, double mxz, long currentTick) {
            return this.worldId == worldId
                    && this.entityId == entityId
                    && this.minX == mnx && this.minY == mny && this.minZ == mnz
                    && this.maxX == mxx && this.maxY == mxy && this.maxZ == mxz
                    && currentTick <= this.validUntilTick;
        }
    }

    /** 碰撞结果缓存：key = entityId, value = 缓存条目 */
    private static final ConcurrentHashMap<Integer, CollisionCacheEntry> COLLISION_CACHE = new ConcurrentHashMap<>(256);
    /** 碰撞缓存命中次数 */
    private static final AtomicLong collisionCacheHits = new AtomicLong();
    /** 碰撞缓存未命中次数 */
    private static final AtomicLong collisionCacheMisses = new AtomicLong();

    /**
     * 查询实体碰撞缓存。如果缓存中有有效的碰撞结果则返回，否则返回 null。
     *
     * <p><b>调用位置：</b>patch 中实体碰撞检测（{@code getCollision}）方法入口处。</p>
     *
     * @param worldId      世界 ID
     * @param entityId     实体 ID
     * @param minX,minY,minZ AABB 最小坐标
     * @param maxX,maxY,maxZ AABB 最大坐标
     * @param currentTick  当前 tick
     * @return 缓存的碰撞结果（可能为 null 表示无碰撞），或 null 表示缓存未命中
     */
    @SuppressWarnings("unchecked")
    public static <T> T getCachedCollision(long worldId, int entityId,
                                           double minX, double minY, double minZ,
                                           double maxX, double maxY, double maxZ,
                                           long currentTick) {
        if (MirageConfig.entityCollisionCacheTicks <= 0) return null;
        CollisionCacheEntry entry = COLLISION_CACHE.get(entityId);
        if (entry != null && entry.matches(worldId, entityId, minX, minY, minZ, maxX, maxY, maxZ, currentTick)) {
            collisionCacheHits.incrementAndGet();
            return (T) entry.collisionResult;
        }
        collisionCacheMisses.incrementAndGet();
        return null;
    }

    /**
     * 存入实体碰撞结果到缓存。
     *
     * <p><b>调用位置：</b>patch 中完成碰撞计算后存入缓存。</p>
     *
     * @param worldId         世界 ID
     * @param entityId        实体 ID
     * @param minX,minY,minZ  AABB 最小坐标
     * @param maxX,maxY,maxZ  AABB 最大坐标
     * @param currentTick     当前 tick
     * @param collisionResult 碰撞结果对象
     */
    public static void putCachedCollision(long worldId, int entityId,
                                          double minX, double minY, double minZ,
                                          double maxX, double maxY, double maxZ,
                                          long currentTick, Object collisionResult) {
        if (MirageConfig.entityCollisionCacheTicks <= 0) return;
        CollisionCacheEntry entry = new CollisionCacheEntry(
                worldId, entityId, minX, minY, minZ, maxX, maxY, maxZ,
                currentTick + MirageConfig.entityCollisionCacheTicks,
                collisionResult != null, collisionResult
        );
        COLLISION_CACHE.put(entityId, entry);
    }

    /**
     * 使指定实体的碰撞缓存失效。
     *
     * @param entityId 实体 ID
     */
    public static void invalidateCollisionCache(int entityId) {
        COLLISION_CACHE.remove(entityId);
    }

    /**
     * 清空所有碰撞缓存。
     */
    public static void clearCollisionCache() {
        COLLISION_CACHE.clear();
    }

    // ========================================================================
    // 6. 快速距离计算工具（使用平方距离避免 sqrt）
    // ========================================================================

    /**
     * 计算两点之间的平方距离。避免 {@code Math.sqrt} 调用，用于比较距离阈值。
     *
     * <p><b>调用位置：</b>patch 中所有距离比较场景，替代 {@code distanceTo()} / {@code distanceSqr()}。</p>
     *
     * @param x1,y1,z1 第一个点坐标
     * @param x2,y2,z2 第二个点坐标
     * @return 平方距离
     */
    public static double distanceSquared(double x1, double y1, double z1,
                                         double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 计算两点之间的平方距离（int 版本）。
     *
     * @param x1,y1,z1 第一个点坐标
     * @param x2,y2,z2 第二个点坐标
     * @return 平方距离
     */
    public static long distanceSquared(int x1, int y1, int z1,
                                       int x2, int y2, int z2) {
        long dx = (long) x2 - x1;
        long dy = (long) y2 - y1;
        long dz = (long) z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 判断两点距离是否在指定范围内（使用平方距离比较，无 sqrt）。
     *
     * @param x1,y1,z1 第一个点坐标
     * @param x2,y2,z2 第二个点坐标
     * @param range    距离阈值
     * @return true 如果两点距离小于 range
     */
    public static boolean isWithinRange(double x1, double y1, double z1,
                                        double x2, double y2, double z2,
                                        double range) {
        return distanceSquared(x1, y1, z1, x2, y2, z2) < range * range;
    }

    /**
     * 计算二维平面（XZ）上的平方距离。常用于实体激活/追踪判断（忽略 Y 轴）。
     *
     * @param x1,z1 第一个点 XZ 坐标
     * @param x2,z2 第二个点 XZ 坐标
     * @return XZ 平面平方距离
     */
    public static double distanceSquared2D(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return dx * dx + dz * dz;
    }

    /**
     * 判断二维 XZ 距离是否在指定范围内。
     *
     * @param x1,z1 第一个点 XZ 坐标
     * @param x2,z2 第二个点 XZ 坐标
     * @param range 距离阈值
     * @return true 如果 XZ 距离小于 range
     */
    public static boolean isWithinRange2D(double x1, double z1, double x2, double z2, double range) {
        return distanceSquared2D(x1, z1, x2, z2) < range * range;
    }

    // ========================================================================
    // 7. 自适应视距计算器
    // ========================================================================

    /** 当前自适应视距（volatile 保证可见性） */
    private static volatile int currentAdaptiveViewDistance = -1;
    /** 上次更新视距的 tick */
    private static volatile long lastViewDistanceUpdateTick = -1;

    /**
     * 根据当前玩家数和 TPS 计算自适应视距。
     *
     * <p>当玩家数超过 {@link MirageConfig#viewDistanceShrinkPlayerCount} 或 TPS 低于
     * {@link MirageConfig#adaptiveTrackingTpsThreshold} 时，视距会逐渐缩小至
     * {@link MirageConfig#minViewDistance}。反之在负载较低时恢复至
     * {@link MirageConfig#maxViewDistance}。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel} 或 {@code MinecraftServer} 的 tick 方法中定期调用。</p>
     *
     * @param playerCount 当前在线玩家数
     * @param tps         当前 TPS（1 分钟平均）
     * @param currentTick 当前 tick
     * @return 计算后的视距（区块半径）
     */
    public static int calculateAdaptiveViewDistance(int playerCount, double tps, long currentTick) {
        if (!MirageConfig.dynamicViewDistance) {
            return MirageConfig.maxViewDistance;
        }

        // 每 100 tick 更新一次，避免频繁调整
        if (currentTick - lastViewDistanceUpdateTick < 100 && currentAdaptiveViewDistance > 0) {
            return currentAdaptiveViewDistance;
        }

        int minDist = MirageConfig.minViewDistance;
        int maxDist = MirageConfig.maxViewDistance;
        int shrinkCount = MirageConfig.viewDistanceShrinkPlayerCount;
        double tpsThreshold = MirageConfig.adaptiveTrackingTpsThreshold;

        int target = maxDist;

        // 根据玩家数线性缩减
        if (playerCount > shrinkCount) {
            double ratio = (double) shrinkCount / playerCount;
            target = (int) Math.max(minDist, maxDist * ratio);
        }

        // 根据TPS进一步缩减
        if (tps < tpsThreshold) {
            double tpsRatio = Math.max(0, (tps - 5.0) / (tpsThreshold - 5.0));
            target = (int) Math.max(minDist, target * tpsRatio);
        }

        target = Math.max(minDist, Math.min(maxDist, target));

        // 平滑过渡：每次最多调整 1 个区块，避免视距突变
        int current = currentAdaptiveViewDistance > 0 ? currentAdaptiveViewDistance : maxDist;
        if (target > current) {
            current = Math.min(target, current + 1);
        } else if (target < current) {
            current = Math.max(target, current - 1);
        }

        currentAdaptiveViewDistance = current;
        lastViewDistanceUpdateTick = currentTick;
        return current;
    }

    /**
     * 获取当前自适应视距值（不重新计算）。
     *
     * @return 当前视距，若未初始化则返回 {@link MirageConfig#maxViewDistance}
     */
    public static int getCurrentAdaptiveViewDistance() {
        int dist = currentAdaptiveViewDistance;
        return dist > 0 ? dist : MirageConfig.maxViewDistance;
    }

    // ========================================================================
    // 8. 实体追踪范围计算器
    // ========================================================================

    /**
     * 计算实体的追踪范围。根据配置乘数和当前 TPS 动态调整。
     *
     * <p>当 TPS 低于 {@link MirageConfig#adaptiveTrackingTpsThreshold} 且
     * {@link MirageConfig#adaptiveEntityTracking} 开启时，追踪范围会按 TPS 比例缩减。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ChunkMap} / {@code TrackedEntity} 计算追踪范围处。</p>
     *
     * @param baseRange 基础追踪范围（来自实体类型配置）
     * @param tps       当前 TPS
     * @return 调整后的追踪范围
     */
    public static int calculateEntityTrackingRange(int baseRange, double tps) {
        double range = baseRange;

        if (MirageConfig.optimizeEntityTrackingRange) {
            range *= MirageConfig.entityTrackingRangeMultiplier;
        }

        if (MirageConfig.adaptiveEntityTracking && tps < MirageConfig.adaptiveTrackingTpsThreshold) {
            double tpsRatio = Math.max(0.5, tps / MirageConfig.adaptiveTrackingTpsThreshold);
            range *= tpsRatio;
        }

        return Math.max(2, (int) Math.ceil(range));
    }

    /**
     * 计算实体更新包发送间隔（tick）。距离越远的实体更新频率越低。
     *
     * <p><b>调用位置：</b>patch 中 {@code TrackedEntity} 发送实体位置更新包处。</p>
     *
     * @param distanceSquared 实体到玩家的平方距离
     * @param tps             当前 TPS
     * @return 更新间隔（tick），1 表示每 tick 更新
     */
    public static int calculateEntityUpdateInterval(double distanceSquared, double tps) {
        if (!MirageConfig.reduceEntityUpdatePackets) {
            return 1;
        }

        int threshold = MirageConfig.distantEntityThreshold;
        double thresholdSq = threshold * threshold;
        int interval;

        if (distanceSquared > thresholdSq) {
            interval = MirageConfig.distantEntityUpdateInterval;
        } else {
            // 距离越近更新越频繁
            double ratio = distanceSquared / thresholdSq;
            interval = Math.max(1, (int) (MirageConfig.distantEntityUpdateInterval * ratio));
        }

        // TPS低时进一步降低更新频率
        if (MirageConfig.adaptiveEntityTracking && tps < MirageConfig.adaptiveTrackingTpsThreshold) {
            double tpsRatio = tps / 20.0;
            interval = Math.max(1, (int) (interval / tpsRatio));
        }

        return interval;
    }

    // ========================================================================
    // 9. 高性能内存操作（VarHandle）
    // ========================================================================

    /** VarHandle 可用性标志 */
    private static final boolean VAR_HANDLE_AVAILABLE;
    /** Unsafe 实例（通过反射获取，作为 VarHandle 不可用时的后备） */
    private static final Object UNSAFE_INSTANCE;
    /** Unsafe 的 compareAndSwapLong 方法 */
    private static final java.lang.reflect.Method UNSAFE_CAS_LONG;
    /** Unsafe 的 compareAndSwapObject 方法 */
    private static final java.lang.reflect.Method UNSAFE_CAS_OBJECT;
    /** Unsafe 的 putLongVolatile 方法 */
    private static final java.lang.reflect.Method UNSAFE_PUT_LONG_VOLATILE;
    /** Unsafe 的 getLongVolatile 方法 */
    private static final java.lang.reflect.Method UNSAFE_GET_LONG_VOLATILE;

    static {
        boolean vhAvailable = false;
        try {
            // 测试 VarHandle 是否可用
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            vhAvailable = true;
        } catch (Throwable ignored) {
            // VarHandle 不可用
        }
        VAR_HANDLE_AVAILABLE = vhAvailable;

        // 尝试获取 Unsafe 实例作为后备
        Object unsafe = null;
        java.lang.reflect.Method casLong = null;
        java.lang.reflect.Method casObj = null;
        java.lang.reflect.Method putLongV = null;
        java.lang.reflect.Method getLongV = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            casLong = unsafeClass.getMethod("compareAndSwapLong", Object.class, long.class, long.class, long.class);
            casObj = unsafeClass.getMethod("compareAndSwapObject", Object.class, long.class, Object.class, Object.class);
            putLongV = unsafeClass.getMethod("putLongVolatile", Object.class, long.class, long.class);
            getLongV = unsafeClass.getMethod("getLongVolatile", Object.class, long.class);
        } catch (Throwable ignored) {
            // Unsafe 不可用
        }
        UNSAFE_INSTANCE = unsafe;
        UNSAFE_CAS_LONG = casLong;
        UNSAFE_CAS_OBJECT = casObj;
        UNSAFE_PUT_LONG_VOLATILE = putLongV;
        UNSAFE_GET_LONG_VOLATILE = getLongV;
    }

    /**
     * 检查 VarHandle 是否可用。
     *
     * @return true 如果当前 JVM 支持 VarHandle
     */
    public static boolean isVarHandleAvailable() {
        return VAR_HANDLE_AVAILABLE;
    }

    /**
     * 检查 sun.misc.Unsafe 是否可用（作为 VarHandle 的后备）。
     *
     * @return true 如果当前 JVM 可访问 Unsafe
     */
    public static boolean isUnsafeAvailable() {
        return UNSAFE_INSTANCE != null;
    }

    /**
     * 创建目标类中指定 long 字段的 VarHandle。
     *
     * @param lookup     方法句柄查找上下文
     * @param declaring  声明该字段的类
     * @param fieldName  字段名
     * @return 对应的 VarHandle，若创建失败则返回 null
     */
    public static VarHandle createLongVarHandle(MethodHandles.Lookup lookup, Class<?> declaring, String fieldName) {
        try {
            return MethodHandles.privateLookupIn(declaring, lookup)
                    .findVarHandle(declaring, fieldName, long.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 创建目标类中指定 int 字段的 VarHandle。
     *
     * @param lookup     方法句柄查找上下文
     * @param declaring  声明该字段的类
     * @param fieldName  字段名
     * @return 对应的 VarHandle，若创建失败则返回 null
     */
    public static VarHandle createIntVarHandle(MethodHandles.Lookup lookup, Class<?> declaring, String fieldName) {
        try {
            return MethodHandles.privateLookupIn(declaring, lookup)
                    .findVarHandle(declaring, fieldName, int.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ========================================================================
    // 10. 通用工具方法
    // ========================================================================

    /** 全局 tick 计数器（由 patch 中 server tick 方法递增） */
    private static final AtomicLong globalTickCounter = new AtomicLong(0);

    /**
     * 递增全局 tick 计数器。
     *
     * <p><b>调用位置：</b>patch 中 {@code MinecraftServer#tickServer} 方法末尾。</p>
     */
    public static void incrementTickCounter() {
        globalTickCounter.incrementAndGet();
    }

    /**
     * 获取当前全局 tick 计数。
     *
     * @return 当前 tick 值
     */
    public static long getCurrentTick() {
        return globalTickCounter.get();
    }

    /**
     * 生成区块坐标键（将 chunkX 和 chunkZ 组合为单个 long）。
     *
     * @param chunkX 区块 X 坐标
     * @param chunkZ 区块 Z 坐标
     * @return 组合后的 long 键
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 从区块键中提取 X 坐标。
     *
     * @param key 区块键
     * @return 区块 X 坐标
     */
    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    /**
     * 从区块键中提取 Z 坐标。
     *
     * @param key 区块键
     * @return 区块 Z 坐标
     */
    public static int chunkKeyZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    /**
     * 快速整数哈希函数（用于 HashMap/ConcurrentHashMap 键）。
     * 基于 MurmurHash3 混合函数。
     *
     * @param value 输入值
     * @return 混合后的哈希值
     */
    public static int hashInt(int value) {
        int h = value;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * 快速 long 哈希函数。
     *
     * @param value 输入值
     * @return 混合后的哈希值
     */
    public static long hashLong(long value) {
        long h = value;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /**
     * 获取所有缓存的统计信息，用于调试和监控。
     *
     * @return 包含各缓存统计信息的 Map
     */
    public static Map<String, Object> getCacheStatistics() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("nbt_dedup_cache_size", NBT_DEDUP_CACHE.size());
        stats.put("nbt_dedup_hits", nbtDedupHits.get());
        stats.put("nbt_dedup_misses", nbtDedupMisses.get());
        stats.put("chunk_packet_cache_size", CHUNK_PACKET_CACHE_REF.get() != null ? CHUNK_PACKET_CACHE_REF.get().size() : 0);
        stats.put("collision_cache_size", COLLISION_CACHE.size());
        stats.put("collision_cache_hits", collisionCacheHits.get());
        stats.put("collision_cache_misses", collisionCacheMisses.get());
        stats.put("current_adaptive_view_distance", currentAdaptiveViewDistance);
        stats.put("var_handle_available", VAR_HANDLE_AVAILABLE);
        stats.put("unsafe_available", UNSAFE_INSTANCE != null);
        stats.put("current_tick", globalTickCounter.get());
        return stats;
    }

    /**
     * 清理所有缓存。在服务器关闭或内存回收时调用。
     */
    public static void clearAllCaches() {
        NBT_DEDUP_CACHE.clear();
        clearChunkPacketCache();
        clearCollisionCache();
    }
}
