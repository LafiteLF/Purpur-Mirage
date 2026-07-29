package org.purpurmc.purpur.mirage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MirageClientOptimizer — 客户端优化模块（服务端驱动）。
 *
 * <p>本模块通过服务端控制发往客户端的数据包频率和内容，在不影响游戏体验的前提下
 * 显著降低客户端的渲染和计算负载，从而提升玩家端的 FPS。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>实体追踪范围优化 — 缩减实体追踪范围以减少客户端渲染的实体数</li>
 *   <li>实体更新包频率控制 — 远距离实体降低位置更新频率</li>
 *   <li>动态视距管理 — 根据服务器负载动态调整玩家视距</li>
 *   <li>区块数据包优先级排序 — 按距离优先发送玩家附近的区块</li>
 *   <li>实体元数据批量打包 — 合并多个实体元数据包减少包数量</li>
 *   <li>粒子包频率控制 — 降低粒子效果包的发送频率</li>
 *   <li>自适应实体追踪 — TPS 低时自动缩减追踪范围</li>
 * </ul>
 *
 * <p>所有配置项从 {@link MirageConfig} 读取，所有 public 方法均为 static。</p>
 *
 * <p><b>线程安全：</b>数据包发送主要在网络线程执行，所有计数器使用原子类型，
 * 缓存使用 {@link ConcurrentHashMap}。</p>
 */
@SuppressWarnings("unused")
public final class MirageClientOptimizer {

    private MirageClientOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. 实体追踪范围优化计算
    // ========================================================================

    /**
     * 计算优化后的实体追踪范围。
     *
     * <p>根据 {@link MirageConfig#entityTrackingRangeMultiplier} 缩减追踪范围，
     * 当 TPS 低于阈值时进一步自适应缩减。</p>
     *
     * <p>追踪范围决定了服务端向客户端发送实体数据的距离。
     * 缩小此范围可直接减少客户端需要渲染的实体数量。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ChunkMap.TrackedEntity} 构造时计算追踪范围处。</p>
     *
     * @param baseRange    基础追踪范围（来自实体类型配置）
     * @param currentTps   当前服务器 TPS
     * @return 优化后的追踪范围
     */
    public static int calculateOptimizedTrackingRange(int baseRange, double currentTps) {
        return MirageOptimizer.calculateEntityTrackingRange(baseRange, currentTps);
    }

    /**
     * 计算特定实体类型在特定距离下是否应该被追踪。
     *
     * <p><b>调用位置：</b>patch 中 {@code ChunkMap.TrackedEntity#updatePlayer} 方法中。</p>
     *
     * @param entityX     实体 X 坐标
     * @param entityZ     实体 Z 坐标
     * @param playerX     玩家 X 坐标
     * @param playerZ     玩家 Z 坐标
     * @param trackingRange 当前追踪范围
     * @param tps         当前 TPS
     * @return true 如果实体应该被追踪（在范围内）
     */
    public static boolean shouldTrackEntity(double entityX, double entityZ,
                                            double playerX, double playerZ,
                                            int trackingRange, double tps) {
        int range = calculateOptimizedTrackingRange(trackingRange, tps);
        return MirageOptimizer.isWithinRange2D(entityX, entityZ, playerX, playerZ, range);
    }

    // ========================================================================
    // 2. 实体更新包频率控制
    // ========================================================================

    /** 每个玩家的实体更新包计数器：key = playerId + entityId, value = tick 计数 */
    private static final ConcurrentHashMap<Long, AtomicInteger> ENTITY_UPDATE_COUNTERS =
            new ConcurrentHashMap<>(1024);

    /** 实体更新包跳过次数 */
    private static final AtomicLong entityUpdateSkipCount = new AtomicLong();

    /**
     * 生成玩家-实体复合键。
     *
     * @param playerId 玩家 ID
     * @param entityId 实体 ID
     * @return 复合键
     */
    private static long playerEntityKey(int playerId, int entityId) {
        return ((long) playerId << 32) | (entityId & 0xFFFFFFFFL);
    }

    /**
     * 判断是否应该向玩家发送实体位置更新包。
     *
     * <p>对于距离玩家较远的实体，按 {@link MirageConfig#distantEntityUpdateInterval}
     * 指定的间隔发送更新，减少网络带宽和客户端处理负担。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code TrackedEntity#broadcastMoved} 方法入口处。</p>
     *
     * @param playerId   玩家 ID
     * @param entityId   实体 ID
     * @param distanceSq 实体到玩家的平方距离
     * @param tps        当前 TPS
     * @return true 如果应该发送更新包
     */
    public static boolean shouldSendEntityUpdate(int playerId, int entityId,
                                                  double distanceSq, double tps) {
        if (!MirageConfig.reduceEntityUpdatePackets) {
            return true;
        }

        int interval = MirageOptimizer.calculateEntityUpdateInterval(distanceSq, tps);
        if (interval <= 1) {
            return true;
        }

        long key = playerEntityKey(playerId, entityId);
        AtomicInteger counter = ENTITY_UPDATE_COUNTERS.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current >= interval) {
            counter.set(0);
            return true;
        }
        entityUpdateSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 清理指定玩家的所有实体更新计数器（玩家断开连接时调用）。
     *
     * <p><b>调用位置：</b>patch 中玩家下线处理中。</p>
     *
     * @param playerId 玩家 ID
     */
    public static void cleanupPlayerEntityCounters(int playerId) {
        long prefix = (long) playerId << 32;
        ENTITY_UPDATE_COUNTERS.keySet().removeIf(key -> (key & 0xFFFFFFFF00000000L) == prefix);
    }

    /**
     * 获取实体更新包跳过次数。
     *
     * @return 跳过次数
     */
    public static long getEntityUpdateSkipCount() {
        return entityUpdateSkipCount.get();
    }

    // ========================================================================
    // 3. 动态视距管理
    // ========================================================================

    /**
     * 计算动态视距。
     *
     * <p>委托给 {@link MirageOptimizer#calculateAdaptiveViewDistance} 实现。
     * 根据在线玩家数和 TPS 动态调整视距，在服务器负载高时自动缩减。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerPlayer} 视距设置处，
     * 或 {@code MinecraftServer#tickServer} 中定期更新。</p>
     *
     * @param playerCount 当前在线玩家数
     * @param tps         当前 TPS
     * @param currentTick 当前 tick
     * @return 计算后的视距（区块半径）
     */
    public static int calculateDynamicViewDistance(int playerCount, double tps, long currentTick) {
        return MirageOptimizer.calculateAdaptiveViewDistance(playerCount, tps, currentTick);
    }

    /**
     * 获取当前动态视距（不重新计算）。
     *
     * @return 当前视距
     */
    public static int getCurrentViewDistance() {
        return MirageOptimizer.getCurrentAdaptiveViewDistance();
    }

    // ========================================================================
    // 4. 区块数据包优先级排序
    // ========================================================================

    /**
     * 区块发送优先级条目。
     */
    public static final class ChunkSendPriority {
        /** 区块坐标键 */
        public final long chunkKey;
        /** 优先级（越小越高） */
        public final int priority;

        /**
         * 构造区块发送优先级条目。
         *
         * @param chunkKey 区块坐标键
         * @param priority 优先级值
         */
        public ChunkSendPriority(long chunkKey, int priority) {
            this.chunkKey = chunkKey;
            this.priority = priority;
        }
    }

    /**
     * 对待发送的区块列表按优先级排序。
     *
     * <p>优先级策略：以玩家位置为中心，按曼哈顿距离排序。
     * 距离玩家越近的区块优先发送，减少玩家看到空洞的时间。</p>
     *
     * <p><b>调用位置：</b>patch 中区块发送队列处理前调用。</p>
     *
     * @param chunkKeys    待发送区块键列表
     * @param playerChunkX 玩家所在区块 X 坐标
     * @param playerChunkZ 玩家所在区块 Z 坐标
     * @return 按优先级排序后的区块键列表
     */
    public static List<Long> prioritizeChunkSending(List<Long> chunkKeys,
                                                     int playerChunkX, int playerChunkZ) {
        if (!MirageConfig.prioritizedChunkSending || chunkKeys == null || chunkKeys.size() <= 1) {
            return chunkKeys;
        }

        List<Long> sorted = new ArrayList<>(chunkKeys);
        sorted.sort(Comparator.comparingLong(key -> {
            int x = MirageOptimizer.chunkKeyX(key);
            int z = MirageOptimizer.chunkKeyZ(key);
            return (long) (Math.abs(x - playerChunkX) + Math.abs(z - playerChunkZ));
        }));
        return sorted;
    }

    /**
     * 生成区块发送优先级条目列表。
     *
     * <p><b>调用位置：</b>patch 中需要获取优先级信息的场景。</p>
     *
     * @param chunkKeys    区块键列表
     * @param playerChunkX 玩家所在区块 X 坐标
     * @param playerChunkZ 玩家所在区块 Z 坐标
     * @return 优先级条目列表（已排序）
     */
    public static List<ChunkSendPriority> getChunkSendPriorities(List<Long> chunkKeys,
                                                                  int playerChunkX, int playerChunkZ) {
        List<ChunkSendPriority> priorities = new ArrayList<>(chunkKeys.size());
        for (long key : chunkKeys) {
            int x = MirageOptimizer.chunkKeyX(key);
            int z = MirageOptimizer.chunkKeyZ(key);
            int dist = Math.abs(x - playerChunkX) + Math.abs(z - playerChunkZ);
            priorities.add(new ChunkSendPriority(key, dist));
        }
        priorities.sort(Comparator.comparingInt(p -> p.priority));
        return priorities;
    }

    /**
     * 获取区块数据包压缩级别。
     *
     * <p><b>调用位置：</b>patch 中区块数据包序列化/压缩时使用。</p>
     *
     * @return 压缩级别（0-9）
     */
    public static int getChunkPacketCompressionLevel() {
        return Math.max(0, Math.min(9, MirageConfig.chunkPacketCompressionLevel));
    }

    // ========================================================================
    // 5. 实体元数据批量打包
    // ========================================================================

    /**
     * 实体元数据批量打包队列。每个玩家一个队列，在 tick 末尾批量发送。
     */
    private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<Object>> PLAYER_METADATA_QUEUE =
            new ConcurrentHashMap<>(64);

    /** 批量元数据发送次数 */
    private static final AtomicLong batchMetadataSentCount = new AtomicLong();
    /** 元数据包合并节省次数 */
    private static final AtomicLong metadataPacketsSaved = new AtomicLong();

    /**
     * 将实体元数据包加入批量队列，而不是立即发送。
     *
     * <p>多个实体的元数据更新会被合并到一个批量操作中，
     * 减少网络包数量和客户端处理开销。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code TrackedEntity#broadcastAndSend} 发送元数据时调用，
     * 替代直接发包。</p>
     *
     * @param playerId     玩家 ID
     * @param metadataPacket 元数据包对象
     */
    public static void queueEntityMetadata(int playerId, Object metadataPacket) {
        if (!MirageConfig.batchEntityMetadata || metadataPacket == null) {
            return;
        }
        PLAYER_METADATA_QUEUE.computeIfAbsent(playerId, id -> new ConcurrentLinkedQueue<>())
                .add(metadataPacket);
    }

    /**
     * 获取并清空指定玩家的元数据批量队列。
     *
     * <p>在 tick 末尾调用，将累积的元数据包批量发送给客户端。</p>
     *
     * <p><b>调用位置：</b>patch 中网络 tick 末尾，对每个在线玩家调用。</p>
     *
     * @param playerId 玩家 ID
     * @return 待发送的元数据包列表（可能为空）
     */
    public static List<Object> flushEntityMetadata(int playerId) {
        if (!MirageConfig.batchEntityMetadata) {
            return java.util.Collections.emptyList();
        }

        ConcurrentLinkedQueue<Object> queue = PLAYER_METADATA_QUEUE.get(playerId);
        if (queue == null || queue.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<Object> packets = new ArrayList<>();
        Object packet;
        while ((packet = queue.poll()) != null) {
            packets.add(packet);
        }

        if (packets.size() > 1) {
            batchMetadataSentCount.incrementAndGet();
            metadataPacketsSaved.addAndGet(packets.size() - 1);
        }
        return packets;
    }

    /**
     * 清理指定玩家的元数据队列（玩家断开连接时调用）。
     *
     * @param playerId 玩家 ID
     */
    public static void cleanupPlayerMetadataQueue(int playerId) {
        PLAYER_METADATA_QUEUE.remove(playerId);
    }

    /**
     * 获取批量元数据发送次数。
     *
     * @return 发送次数
     */
    public static long getBatchMetadataSentCount() {
        return batchMetadataSentCount.get();
    }

    /**
     * 获取通过批量打包节省的元数据包数。
     *
     * @return 节省的包数
     */
    public static long getMetadataPacketsSaved() {
        return metadataPacketsSaved.get();
    }

    // ========================================================================
    // 6. 粒子包频率控制
    // ========================================================================

    /** 粒子包发送计数器：以世界 ID + tick 为键 */
    private static final ConcurrentHashMap<Long, AtomicInteger> PARTICLE_PACKET_COUNTERS =
            new ConcurrentHashMap<>(32);

    /** 粒子包跳过次数 */
    private static final AtomicLong particlePacketSkipCount = new AtomicLong();

    /**
     * 判断是否应该发送粒子包。
     *
     * <p>根据 {@link MirageConfig#particleReductionMultiplier} 按概率跳过粒子包发送。
     * 例如乘数为 0.5 时约有一半的粒子包被跳过。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ServerLevel#sendParticles} 方法入口处。</p>
     *
     * @param worldSeed 世界种子（用于生成确定性随机）
     * @return true 如果应该发送粒子包
     */
    public static boolean shouldSendParticle(long worldSeed) {
        if (!MirageConfig.reduceParticlePackets) {
            return true;
        }

        double multiplier = MirageConfig.particleReductionMultiplier;
        if (multiplier >= 1.0) {
            return true;
        }
        if (multiplier <= 0.0) {
            particlePacketSkipCount.incrementAndGet();
            return false;
        }

        // 使用快速随机数判断
        double rand = MirageOptimizer.fastRandom().nextDouble();
        if (rand < multiplier) {
            return true;
        }
        particlePacketSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 判断特定玩家是否应该接收粒子包。
     *
     * <p>可针对不同玩家使用不同的粒子频率，例如距离粒子源较远的玩家更低频率接收。</p>
     *
     * <p><b>调用位置：</b>patch中对每个玩家判断是否发送粒子包时。</p>
     *
     * @param playerId 玩家 ID
     * @param distanceSq 玩家到粒子源的距离平方
     * @return true 如果该玩家应该接收粒子包
     */
    public static boolean shouldSendParticleToPlayer(int playerId, double distanceSq) {
        if (!MirageConfig.reduceParticlePackets) {
            return true;
        }

        double multiplier = MirageConfig.particleReductionMultiplier;
        // 距离越远粒子频率越低
        double distFactor = Math.max(0.3, 1.0 - distanceSq / (64.0 * 64.0));
        double effectiveMultiplier = multiplier * distFactor;

        if (effectiveMultiplier >= 1.0) return true;
        if (effectiveMultiplier <= 0.0) {
            particlePacketSkipCount.incrementAndGet();
            return false;
        }

        double rand = MirageOptimizer.fastRandom().nextDouble();
        if (rand < effectiveMultiplier) {
            return true;
        }
        particlePacketSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 获取粒子包跳过次数。
     *
     * @return 跳过次数
     */
    public static long getParticlePacketSkipCount() {
        return particlePacketSkipCount.get();
    }

    // ========================================================================
    // 7. 自适应实体追踪（根据 TPS）
    // ========================================================================

    /** 上次自适应追踪调整的 tick */
    private static volatile long lastAdaptiveTrackingUpdate = -1;
    /** 当前自适应追踪范围乘数 */
    private static volatile double currentAdaptiveMultiplier = 1.0;

    /**
     * 计算 TPS 自适应追踪范围乘数。
     *
     * <p>当 TPS 低于 {@link MirageConfig#adaptiveTrackingTpsThreshold} 时，
     * 追踪范围乘数逐渐降低（最低 0.5），减少实体追踪开销以恢复 TPS。
     * TPS 恢复后乘数平滑回升至 1.0。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code MinecraftServer#tickServer} 中定期调用。</p>
     *
     * @param tps         当前 TPS
     * @param currentTick 当前 tick
     * @return 自适应追踪范围乘数（0.5-1.0）
     */
    public static double calculateAdaptiveTrackingMultiplier(double tps, long currentTick) {
        if (!MirageConfig.adaptiveEntityTracking) {
            return 1.0;
        }

        // 每 100 tick 更新一次
        if (currentTick - lastAdaptiveTrackingUpdate < 100) {
            return currentAdaptiveMultiplier;
        }

        double threshold = MirageConfig.adaptiveTrackingTpsThreshold;
        double target;

        if (tps < threshold) {
            // TPS 低于阈值，降低追踪范围
            double tpsRatio = Math.max(0.5, tps / threshold);
            target = Math.max(0.5, tpsRatio);
        } else {
            // TPS 正常，恢复追踪范围
            target = 1.0;
        }

        // 平滑过渡
        double current = currentAdaptiveMultiplier;
        if (target > current) {
            current = Math.min(target, current + 0.05);
        } else if (target < current) {
            current = Math.max(target, current - 0.05);
        }

        currentAdaptiveMultiplier = current;
        lastAdaptiveTrackingUpdate = currentTick;
        return current;
    }

    /**
     * 获取当前自适应追踪范围乘数（不重新计算）。
     *
     * @return 当前乘数（0.5-1.0）
     */
    public static double getCurrentAdaptiveMultiplier() {
        return currentAdaptiveMultiplier;
    }

    // ========================================================================
    // 8. 实体生成/消失包限制
    // ========================================================================

    /** 每玩家每 tick 实体生成包计数器 */
    private static final ConcurrentHashMap<Integer, AtomicInteger> ENTITY_SPAWN_COUNTERS =
            new ConcurrentHashMap<>(64);
    /** 每玩家每 tick 实体消失包计数器 */
    private static final ConcurrentHashMap<Integer, AtomicInteger> ENTITY_DESPAWN_COUNTERS =
            new ConcurrentHashMap<>(64);

    /**
     * 判断是否应该向玩家发送实体生成包。
     *
     * <p>通过 {@link MirageConfig#maxEntitySpawnsPerTickPerPlayer} 限制每 tick 发送的实体生成包数，
     * 防止大量实体同时进入视距导致客户端卡顿。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code TrackedEntity} 向玩家发送生成包前调用。</p>
     *
     * @param playerId 玩家 ID
     * @return true 如果应该发送生成包
     */
    public static boolean shouldSendEntitySpawn(int playerId) {
        if (MirageConfig.maxEntitySpawnsPerTickPerPlayer <= 0) {
            return true;
        }
        AtomicInteger counter = ENTITY_SPAWN_COUNTERS.computeIfAbsent(playerId, id -> new AtomicInteger(0));
        return counter.incrementAndGet() <= MirageConfig.maxEntitySpawnsPerTickPerPlayer;
    }

    /**
     * 判断是否应该向玩家发送实体消失包。
     *
     * <p>通过 {@link MirageConfig#maxEntityDespawnsPerTickPerPlayer} 限制每 tick 发送的实体消失包数。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code TrackedEntity} 向玩家发送消失包前调用。</p>
     *
     * @param playerId 玩家 ID
     * @return true 如果应该发送消失包
     */
    public static boolean shouldSendEntityDespawn(int playerId) {
        if (MirageConfig.maxEntityDespawnsPerTickPerPlayer <= 0) {
            return true;
        }
        AtomicInteger counter = ENTITY_DESPAWN_COUNTERS.computeIfAbsent(playerId, id -> new AtomicInteger(0));
        return counter.incrementAndGet() <= MirageConfig.maxEntityDespawnsPerTickPerPlayer;
    }

    /**
     * 重置所有玩家的实体生成/消失包计数器。每 tick 开始时调用。
     *
     * <p><b>调用位置：</b>patch 中网络 tick 开始处。</p>
     */
    public static void resetSpawnDespawnCounters() {
        ENTITY_SPAWN_COUNTERS.values().forEach(c -> c.set(0));
        ENTITY_DESPAWN_COUNTERS.values().forEach(c -> c.set(0));
    }

    /**
     * 判断是否应该向远处玩家发送实体装备更新包。
     *
     * <p>当 {@link MirageConfig#skipDistantEquipmentUpdates} 开启时，
     * 距离玩家较远的实体装备变更不发送更新包。</p>
     *
     * <p><b>调用位置：</b>patch 中实体装备变更发包前调用。</p>
     *
     * @param distanceSq 实体到玩家的平方距离
     * @return true 如果应该发送装备更新包
     */
    public static boolean shouldSendEquipmentUpdate(double distanceSq) {
        if (!MirageConfig.skipDistantEquipmentUpdates) {
            return true;
        }
        // 超过 32 格不发送装备更新
        return distanceSq < 32.0 * 32.0;
    }

    /**
     * 判断是否应该发送方块变更包。
     *
     * <p>当 {@link MirageConfig#optimizeBlockChangePackets} 开启时，
     * 对玩家不可见的方块变更（如远处、视线遮挡）可以跳过。</p>
     *
     * <p><b>调用位置：</b>patch 中方块变更发包前调用。</p>
     *
     * @param playerChunkX 玩家所在区块 X
     * @param playerChunkZ 玩家所在区块 Z
     * @param blockChunkX  方块所在区块 X
     * @param blockChunkZ  方块所在区块 Z
     * @param viewDistance 当前视距
     * @return true 如果应该发送方块变更包
     */
    public static boolean shouldSendBlockChange(int playerChunkX, int playerChunkZ,
                                                int blockChunkX, int blockChunkZ,
                                                int viewDistance) {
        if (!MirageConfig.optimizeBlockChangePackets) {
            return true;
        }
        int dx = Math.abs(blockChunkX - playerChunkX);
        int dz = Math.abs(blockChunkZ - playerChunkZ);
        return dx <= viewDistance && dz <= viewDistance;
    }

    // ========================================================================
    // 9. 统计和清理
    // ========================================================================

    /**
     * 获取客户端优化的统计信息。
     *
     * @return 统计信息 Map
     */
    public static java.util.Map<String, Object> getClientOptimizationStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("entity_update_counters", ENTITY_UPDATE_COUNTERS.size());
        stats.put("entity_update_skip_count", entityUpdateSkipCount.get());
        stats.put("current_view_distance", getCurrentViewDistance());
        stats.put("batch_metadata_sent_count", batchMetadataSentCount.get());
        stats.put("metadata_packets_saved", metadataPacketsSaved.get());
        stats.put("particle_packet_skip_count", particlePacketSkipCount.get());
        stats.put("current_adaptive_multiplier", currentAdaptiveMultiplier);
        stats.put("player_metadata_queues", PLAYER_METADATA_QUEUE.size());
        stats.put("entity_spawn_counters", ENTITY_SPAWN_COUNTERS.size());
        stats.put("entity_despawn_counters", ENTITY_DESPAWN_COUNTERS.size());
        return stats;
    }

    /**
     * 清理指定玩家的所有客户端优化记录。在玩家断开连接时调用。
     *
     * <p><b>调用位置：</b>patch 中玩家下线处理中。</p>
     *
     * @param playerId 玩家 ID
     */
    public static void cleanupPlayer(int playerId) {
        cleanupPlayerEntityCounters(playerId);
        cleanupPlayerMetadataQueue(playerId);
        ENTITY_SPAWN_COUNTERS.remove(playerId);
        ENTITY_DESPAWN_COUNTERS.remove(playerId);
    }

    /**
     * 清空所有客户端优化缓存。在服务器关闭时调用。
     */
    public static void clearAll() {
        ENTITY_UPDATE_COUNTERS.clear();
        PLAYER_METADATA_QUEUE.clear();
        PARTICLE_PACKET_COUNTERS.clear();
        ENTITY_SPAWN_COUNTERS.clear();
        ENTITY_DESPAWN_COUNTERS.clear();
        entityUpdateSkipCount.set(0);
        batchMetadataSentCount.set(0);
        metadataPacketsSaved.set(0);
        particlePacketSkipCount.set(0);
        currentAdaptiveMultiplier = 1.0;
        lastAdaptiveTrackingUpdate = -1;
    }
}
