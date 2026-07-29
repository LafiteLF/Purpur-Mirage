package org.purpurmc.purpur.mirage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MirageEntityOptimizer — 实体优化模块（Lithium 理念）。
 *
 * <p>本模块实现了多种实体 AI 和物理计算优化策略，通过距离判断、降频执行、
 * 结果缓存等手段显著减少实体 tick 的 CPU 开销，同时保持对玩家可见的游戏行为不变。</p>
 *
 * <h2>包含组件</h2>
 * <ul>
 *   <li>实体 AI 跳过逻辑 — 距离玩家过远的实体跳过 AI tick</li>
 *   <li>非活跃实体 AI 降频 — 降低远处实体 AI 执行频率</li>
 *   <li>冗余寻路跳过 — 未移动的实体不重复计算寻路</li>
 *   <li>实体碰撞缓存 — 缓存碰撞结果减少重复计算</li>
 *   <li>生物目标搜索优化 — 限制目标搜索范围和频率</li>
 *   <li>村民传感器降频 — 降低村民传感器更新频率</li>
 *   <li>掉落物物理跳过 — 静止掉落物跳过物理计算</li>
 * </ul>
 *
 * <p>所有配置项从 {@link MirageConfig} 读取，所有 public 方法均为 static。</p>
 *
 * <p><b>线程安全：</b>使用 {@link ConcurrentHashMap}、{@link AtomicInteger} 等线程安全结构。
 * 实体 tick 主要在主线程执行，但部分缓存可能被异步任务访问。</p>
 */
@SuppressWarnings("unused")
public final class MirageEntityOptimizer {

    private MirageEntityOptimizer() {
        // 工具类，禁止实例化
    }

    // ========================================================================
    // 1. 实体 AI 跳过逻辑（距离判断）
    // ========================================================================

    /**
     * 判断实体是否应该跳过 AI tick。
     *
     * <p>当实体距离最近的玩家超过 {@link MirageConfig#entityAiSkipDistance} 格时，
     * 跳过该实体的 AI tick 以节省 CPU。此方法使用平方距离比较，避免 sqrt 调用。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Mob#serverAiStep} 或 {@code LivingEntity#aiStep} 方法入口处。
     * 替换原有的激活范围检查。</p>
     *
     * @param entityX     实体 X 坐标
     * @param entityY     实体 Y 坐标
     * @param entityZ     实体 Z 坐标
     * @param nearestPlayerX 最近玩家 X 坐标（无玩家时传 Double.MAX_VALUE）
     * @param nearestPlayerY 最近玩家 Y 坐标
     * @param nearestPlayerZ 最近玩家 Z 坐标
     * @return true 如果应该跳过该实体的 AI tick
     */
    public static boolean shouldSkipEntityAi(double entityX, double entityY, double entityZ,
                                             double nearestPlayerX, double nearestPlayerY, double nearestPlayerZ) {
        if (MirageConfig.entityAiSkipDistance <= 0) {
            return false;
        }
        double skipDistance = MirageConfig.entityAiSkipDistance;
        double skipDistanceSq = skipDistance * skipDistance;
        double distSq = MirageOptimizer.distanceSquared(
                entityX, entityY, entityZ,
                nearestPlayerX, nearestPlayerY, nearestPlayerZ
        );
        return distSq > skipDistanceSq;
    }

    /**
     * 快速检查实体是否应该跳过 AI tick（仅检查 XZ 平面距离）。
     * 适用于不需要精确 Y 轴判断的场景，性能更高。
     *
     * <p><b>调用位置：</b>patch 中实体 AI tick 前的快速过滤。</p>
     *
     * @param entityX  实体 X 坐标
     * @param entityZ  实体 Z 坐标
     * @param playerX  最近玩家 X 坐标
     * @param playerZ  最近玩家 Z 坐标
     * @return true 如果应该跳过 AI tick
     */
    public static boolean shouldSkipEntityAi2D(double entityX, double entityZ,
                                               double playerX, double playerZ) {
        if (MirageConfig.entityAiSkipDistance <= 0) {
            return false;
        }
        if (MirageConfig.fastActivationChecks) {
            double skipDistanceSq = (double) MirageConfig.entityAiSkipDistance * MirageConfig.entityAiSkipDistance;
            return MirageOptimizer.distanceSquared2D(entityX, entityZ, playerX, playerZ) > skipDistanceSq;
        }
        // 非快速模式也使用平方距离
        double skipDistanceSq = (double) MirageConfig.entityAiSkipDistance * MirageConfig.entityAiSkipDistance;
        return MirageOptimizer.distanceSquared2D(entityX, entityZ, playerX, playerZ) > skipDistanceSq;
    }

    // ========================================================================
    // 2. 非活跃实体 AI 降频
    // ========================================================================

    /**
     * 实体 AI 降频计数器。以实体 ID 为键，记录每个实体的 tick 计数。
     * 只有当计数达到 {@link MirageConfig#inactiveEntityAiTickInterval} 时才执行 AI。
     */
    private static final ConcurrentHashMap<Integer, AtomicInteger> ENTITY_AI_TICK_COUNTERS =
            new ConcurrentHashMap<>(512);

    /** AI 降频跳过次数 */
    private static final AtomicLong aiSkipCount = new AtomicLong();

    /**
     * 判断非活跃实体是否应该在当前 tick 执行 AI。
     *
     * <p>对于距离玩家较远但仍需部分 AI 的实体，按 {@link MirageConfig#inactiveEntityAiTickInterval}
     * 指定的间隔执行 AI（例如每 4 tick 执行一次），大幅减少 CPU 开销。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Mob#serverAiStep} 方法中，
     * 在确认实体不在跳过范围后调用此方法决定是否执行完整 AI。</p>
     *
     * @param entityId    实体 ID
     * @param isInactive  实体是否处于非活跃状态（距离玩家较远但未超出跳过范围）
     * @return true 如果当前 tick 应该执行 AI
     */
    public static boolean shouldTickEntityAi(int entityId, boolean isInactive) {
        if (!isInactive || MirageConfig.inactiveEntityAiTickInterval <= 1) {
            return true;
        }

        AtomicInteger counter = ENTITY_AI_TICK_COUNTERS.computeIfAbsent(entityId, id -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current >= MirageConfig.inactiveEntityAiTickInterval) {
            counter.set(0);
            return true;
        }
        aiSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 移除实体的 AI 降频计数器（实体移除时调用）。
     *
     * @param entityId 实体 ID
     */
    public static void removeEntityAiCounter(int entityId) {
        ENTITY_AI_TICK_COUNTERS.remove(entityId);
    }

    /**
     * 获取 AI 降频跳过的总次数。
     *
     * @return AI 跳过次数
     */
    public static long getAiSkipCount() {
        return aiSkipCount.get();
    }

    // ========================================================================
    // 3. 冗余寻路跳过
    // ========================================================================

    /**
     * 实体上次寻路位置记录。以实体 ID 为键，记录上次寻路时的坐标。
     * 如果实体位置未发生显著变化，则跳过重复寻路计算。
     */
    private static final ConcurrentHashMap<Integer, long[]> ENTITY_LAST_PATH_POS =
            new ConcurrentHashMap<>(256);

    /** 寻路跳过次数 */
    private static final AtomicLong pathfindingSkipCount = new AtomicLong();

    /** 位置变化阈值（平方距离），小于此值认为实体未移动 */
    private static final double PATHFINDING_MOVE_THRESHOLD_SQ = 4.0; // 2 格

    /**
     * 判断是否应该跳过寻路计算。
     *
     * <p>如果实体自上次寻路以来移动距离小于 2 格，且 {@link MirageConfig#skipRedundantPathfinding}
     * 开启，则跳过本次寻路计算，复用上次的路径。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Mob#navigation} 寻路方法入口处。</p>
     *
     * @param entityId 实体 ID
     * @param x        实体当前 X 坐标
     * @param y        实体当前 Y 坐标
     * @param z        实体当前 Z 坐标
     * @return true 如果应该跳过寻路计算
     */
    public static boolean shouldSkipPathfinding(int entityId, double x, double y, double z) {
        if (!MirageConfig.skipRedundantPathfinding) {
            return false;
        }

        long[] lastPos = ENTITY_LAST_PATH_POS.get(entityId);
        if (lastPos == null) {
            // 首次寻路，记录位置
            ENTITY_LAST_PATH_POS.put(entityId, packPosition(x, y, z));
            return false;
        }

        double lastX = unpackX(lastPos);
        double lastY = unpackY(lastPos);
        double lastZ = unpackZ(lastPos);

        double distSq = MirageOptimizer.distanceSquared(x, y, z, lastX, lastY, lastZ);
        if (distSq < PATHFINDING_MOVE_THRESHOLD_SQ) {
            pathfindingSkipCount.incrementAndGet();
            return true;
        }

        // 位置变化显著，更新记录
        ENTITY_LAST_PATH_POS.put(entityId, packPosition(x, y, z));
        return false;
    }

    /**
     * 打包双精度坐标为 long 数组（使用定点编码）。
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 包含一个 long 的数组（编码了三个坐标）
     */
    private static long[] packPosition(double x, double y, double z) {
        int ix = (int) (x * 32);
        int iy = (int) (y * 32);
        int iz = (int) (z * 32);
        return new long[]{((long) ix << 32) | ((long) iy << 16) | (iz & 0xFFFFL)};
    }

    private static double unpackX(long[] packed) {
        return (int) (packed[0] >> 32) / 32.0;
    }

    private static double unpackY(long[] packed) {
        return (short) ((packed[0] >> 16) & 0xFFFFL) / 32.0;
    }

    private static double unpackZ(long[] packed) {
        return (short) (packed[0] & 0xFFFFL) / 32.0;
    }

    /**
     * 移除实体的寻路位置记录（实体移除时调用）。
     *
     * @param entityId 实体 ID
     */
    public static void removePathfindingRecord(int entityId) {
        ENTITY_LAST_PATH_POS.remove(entityId);
    }

    /**
     * 获取寻路跳过次数。
     *
     * @return 寻路跳过次数
     */
    public static long getPathfindingSkipCount() {
        return pathfindingSkipCount.get();
    }

    // ========================================================================
    // 4. 实体碰撞缓存
    // ========================================================================

    /** 碰撞缓存命中次数 */
    private static final AtomicLong collisionCacheHits = new AtomicLong();
    /** 碰撞缓存未命中次数 */
    private static final AtomicLong collisionCacheMisses = new AtomicLong();

    /**
     * 查询实体碰撞缓存。
     *
     * <p>委托给 {@link MirageOptimizer#getCachedCollision} 实现，
     * 提供实体特定的缓存接口。</p>
     *
     * <p><b>调用位置：</b>patch 中实体碰撞检测方法入口处。</p>
     *
     * @param worldId      世界 ID
     * @param entityId     实体 ID
     * @param minX,minY,minZ AABB 最小坐标
     * @param maxX,maxY,maxZ AABB 最大坐标
     * @param currentTick  当前 tick
     * @param <T>          碰撞结果类型
     * @return 缓存的碰撞结果，或 null 如果未命中
     */
    public static <T> T getCachedEntityCollision(long worldId, int entityId,
                                                  double minX, double minY, double minZ,
                                                  double maxX, double maxY, double maxZ,
                                                  long currentTick) {
        T result = MirageOptimizer.getCachedCollision(worldId, entityId,
                minX, minY, minZ, maxX, maxY, maxZ, currentTick);
        if (result != null) {
            collisionCacheHits.incrementAndGet();
        } else {
            collisionCacheMisses.incrementAndGet();
        }
        return result;
    }

    /**
     * 存入实体碰撞结果到缓存。
     *
     * <p><b>调用位置：</b>patch 中完成碰撞计算后存入缓存。</p>
     *
     * @param worldId      世界 ID
     * @param entityId     实体 ID
     * @param minX,minY,minZ AABB 最小坐标
     * @param maxX,maxY,maxZ AABB 最大坐标
     * @param currentTick  当前 tick
     * @param collisionResult 碰撞结果对象
     */
    public static void putCachedEntityCollision(long worldId, int entityId,
                                                 double minX, double minY, double minZ,
                                                 double maxX, double maxY, double maxZ,
                                                 long currentTick, Object collisionResult) {
        MirageOptimizer.putCachedCollision(worldId, entityId,
                minX, minY, minZ, maxX, maxY, maxZ, currentTick, collisionResult);
    }

    /**
     * 使指定实体的碰撞缓存失效。
     *
     * @param entityId 实体 ID
     */
    public static void invalidateEntityCollisionCache(int entityId) {
        MirageOptimizer.invalidateCollisionCache(entityId);
    }

    // ========================================================================
    // 5. 生物目标搜索优化
    // ========================================================================

    /**
     * 计算生物目标搜索范围。根据实体类型和距离动态缩减搜索范围。
     *
     * <p>当 {@link MirageConfig#optimizeMobTargeting} 开启时，对于距离玩家较远的生物，
     * 缩减其目标搜索范围以减少计算量。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code Mob} 目标搜索（{@code target} goal）方法中，
     * 替换固定的搜索范围。</p>
     *
     * @param baseRange      基础搜索范围
     * @param nearestPlayerDistSq 到最近玩家的平方距离
     * @return 优化后的搜索范围
     */
    public static double calculateTargetSearchRange(double baseRange, double nearestPlayerDistSq) {
        if (!MirageConfig.optimizeMobTargeting) {
            return baseRange;
        }

        // 如果最近玩家距离超过基础搜索范围的 2 倍，缩减搜索范围
        double baseRangeSq = baseRange * baseRange;
        if (nearestPlayerDistSq > baseRangeSq * 4) {
            // 缩减为 50%
            return baseRange * 0.5;
        } else if (nearestPlayerDistSq > baseRangeSq * 2) {
            // 缩减为 75%
            return baseRange * 0.75;
        }

        return baseRange;
    }

    /** 实体目标搜索降频计数器 */
    private static final ConcurrentHashMap<Integer, AtomicInteger> TARGET_SEARCH_COUNTERS =
            new ConcurrentHashMap<>(256);

    /**
     * 判断生物是否应该在当前 tick 执行目标搜索。
     *
     * <p>对于非活跃生物，每 10 tick 执行一次目标搜索而非每 tick 执行。</p>
     *
     * <p><b>调用位置：</b>patch 中生物目标搜索 goal 的 {@code canContinueToUse} 或
     * {@code tick} 方法中。</p>
     *
     * @param entityId  实体 ID
     * @param isInactive 实体是否非活跃
     * @return true 如果当前 tick 应该执行目标搜索
     */
    public static boolean shouldSearchTargets(int entityId, boolean isInactive) {
        if (!MirageConfig.optimizeMobTargeting || !isInactive) {
            return true;
        }

        AtomicInteger counter = TARGET_SEARCH_COUNTERS.computeIfAbsent(entityId, id -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current >= 10) {
            counter.set(0);
            return true;
        }
        return false;
    }

    /**
     * 移除生物目标搜索计数器。
     *
     * @param entityId 实体 ID
     */
    public static void removeTargetSearchCounter(int entityId) {
        TARGET_SEARCH_COUNTERS.remove(entityId);
    }

    // ========================================================================
    // 6. 村民传感器降频
    // ========================================================================

    /** 村民传感器更新计数器：以实体 ID 为键 */
    private static final ConcurrentHashMap<Integer, AtomicInteger> VILLAGER_SENSOR_COUNTERS =
            new ConcurrentHashMap<>(128);

    /** 村民传感器跳过次数 */
    private static final AtomicLong villagerSensorSkipCount = new AtomicLong();

    /**
     * 判断村民传感器是否应该在当前 tick 更新。
     *
     * <p>村民传感器（如 {@code PlayerSensor}、{@code SecondaryPointsSensor}）更新频率较高，
     * 通过 {@link MirageConfig#villagerSensorUpdateInterval} 降低更新频率可显著节省 CPU。</p>
     *
     * <p><b>调用位置：</b>patch 中村民传感器 ({@code Sensor#tick}) 方法入口处。</p>
     *
     * @param villagerId 村民实体 ID
     * @return true 如果当前 tick 应该执行传感器更新
     */
    public static boolean shouldTickVillagerSensor(int villagerId) {
        if (MirageConfig.villagerSensorUpdateInterval <= 1) {
            return true;
        }

        AtomicInteger counter = VILLAGER_SENSOR_COUNTERS.computeIfAbsent(villagerId, id -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current >= MirageConfig.villagerSensorUpdateInterval) {
            counter.set(0);
            return true;
        }
        villagerSensorSkipCount.incrementAndGet();
        return false;
    }

    /**
     * 移除村民传感器计数器。
     *
     * @param villagerId 村民实体 ID
     */
    public static void removeVillagerSensorCounter(int villagerId) {
        VILLAGER_SENSOR_COUNTERS.remove(villagerId);
    }

    /**
     * 获取村民传感器跳过次数。
     *
     * @return 传感器跳过次数
     */
    public static long getVillagerSensorSkipCount() {
        return villagerSensorSkipCount.get();
    }

    // ========================================================================
    // 7. 掉落物物理跳过
    // ========================================================================

    /**
     * 掉落物静止状态记录。以实体 ID 为键，记录掉落物的上次位置和速度。
     * 如果掉落物位置和速度均未变化，跳过物理计算。
     */
    private static final ConcurrentHashMap<Integer, double[]> ITEM_LAST_STATE =
            new ConcurrentHashMap<>(256);

    /** 掉落物物理跳过次数 */
    private static final AtomicLong itemPhysicsSkipCount = new AtomicLong();

    /** 静止判定阈值（平方速度） */
    private static final double VELOCITY_THRESHOLD_SQ = 0.0001;

    /**
     * 判断掉落物是否应该跳过物理 tick。
     *
     * <p>如果掉落物的速度接近零且位置未变化，则跳过物理计算（重力、碰撞等），
     * 直至有外部因素（如玩家拾取、水流推动）改变其状态。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ItemEntity#tick} 方法的物理计算部分。</p>
     *
     * @param entityId 实体 ID
     * @param x        当前 X 坐标
     * @param y        当前 Y 坐标
     * @param z        当前 Z 坐标
     * @param velX     X 方向速度
     * @param velY     Y 方向速度
     * @param velZ     Z 方向速度
     * @return true 如果应该跳过物理 tick
     */
    public static boolean shouldSkipItemPhysics(int entityId,
                                                double x, double y, double z,
                                                double velX, double velY, double velZ) {
        if (!MirageConfig.skipItemPhysicsIfIdle) {
            return false;
        }

        // 速度接近零才考虑跳过
        double velSq = velX * velX + velY * velY + velZ * velZ;
        if (velSq > VELOCITY_THRESHOLD_SQ) {
            // 有速度，更新状态并正常 tick
            ITEM_LAST_STATE.put(entityId, new double[]{x, y, z, velX, velY, velZ});
            return false;
        }

        double[] lastState = ITEM_LAST_STATE.get(entityId);
        if (lastState == null) {
            // 首次记录
            ITEM_LAST_STATE.put(entityId, new double[]{x, y, z, velX, velY, velZ});
            return false;
        }

        // 检查位置是否变化
        double distSq = MirageOptimizer.distanceSquared(x, y, z, lastState[0], lastState[1], lastState[2]);
        if (distSq < 0.01) {
            // 位置和速度都未变化，跳过物理
            itemPhysicsSkipCount.incrementAndGet();
            return true;
        }

        // 位置变化了，更新状态
        ITEM_LAST_STATE.put(entityId, new double[]{x, y, z, velX, velY, velZ});
        return false;
    }

    /**
     * 移除掉落物状态记录（实体移除时调用）。
     *
     * @param entityId 实体 ID
     */
    public static void removeItemPhysicsRecord(int entityId) {
        ITEM_LAST_STATE.remove(entityId);
    }

    /**
     * 获取掉落物物理跳过次数。
     *
     * @return 物理跳过次数
     */
    public static long getItemPhysicsSkipCount() {
        return itemPhysicsSkipCount.get();
    }

    // ========================================================================
    // 8. 并发寻路限制
    // ========================================================================

    /** 当前并发寻路操作计数器 */
    private static final AtomicInteger concurrentPathfindingCount = new AtomicInteger(0);
    /** 寻路被拒绝次数（超过并发上限） */
    private static final AtomicLong pathfindingRejectedCount = new AtomicLong();

    /**
     * 尝试获取寻路许可。如果当前并发寻路数已达上限则拒绝。
     *
     * <p>通过 {@link MirageConfig#maxConcurrentPathfinding} 限制同时进行的寻路计算数量，
     * 防止大量实体同时寻路导致卡顿。</p>
     *
     * <p><b>调用位置：</b>patch 中寻路计算开始前调用。获取许可后必须在计算完成后
     * 调用 {@link #releasePathfindingPermit()}。</p>
     *
     * @return true 如果获取了寻路许可（可以执行寻路）
     */
    public static boolean acquirePathfindingPermit() {
        if (MirageConfig.maxConcurrentPathfinding <= 0) {
            return true; // 不限制
        }
        while (true) {
            int current = concurrentPathfindingCount.get();
            if (current >= MirageConfig.maxConcurrentPathfinding) {
                pathfindingRejectedCount.incrementAndGet();
                return false;
            }
            if (concurrentPathfindingCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * 释放寻路许可。必须与 {@link #acquirePathfindingPermit()} 配对使用。
     */
    public static void releasePathfindingPermit() {
        while (true) {
            int current = concurrentPathfindingCount.get();
            if (current <= 0) break;
            if (concurrentPathfindingCount.compareAndSet(current, current - 1)) {
                break;
            }
        }
    }

    /**
     * 获取当前并发寻路数量。
     *
     * @return 当前并发寻路数
     */
    public static int getConcurrentPathfindingCount() {
        return concurrentPathfindingCount.get();
    }

    /**
     * 获取寻路被拒绝次数。
     *
     * @return 被拒绝次数
     */
    public static long getPathfindingRejectedCount() {
        return pathfindingRejectedCount.get();
    }

    // ========================================================================
    // 9. 经验球合并优化
    // ========================================================================

    /** 经验球合并计数器：以实体 ID 为键 */
    private static final ConcurrentHashMap<Integer, AtomicInteger> EXP_ORB_MERGE_COUNTERS =
            new ConcurrentHashMap<>(128);

    /**
     * 判断经验球是否应该在当前 tick 尝试合并。
     *
     * <p>经验球合并检查频率降为每 {@link MirageConfig#expOrbMergeInterval} tick 一次。</p>
     *
     * <p><b>调用位置：</b>patch 中 {@code ExperienceOrb#tick} 的合并检查部分。</p>
     *
     * @param orbId 经验球实体 ID
     * @return true 如果当前 tick 应该执行合并检查
     */
    public static boolean shouldCheckExpOrbMerge(int orbId) {
        if (MirageConfig.expOrbMergeInterval <= 1) {
            return true;
        }

        AtomicInteger counter = EXP_ORB_MERGE_COUNTERS.computeIfAbsent(orbId, id -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current >= MirageConfig.expOrbMergeInterval) {
            counter.set(0);
            return true;
        }
        return false;
    }

    /**
     * 移除经验球合并计数器。
     *
     * @param orbId 经验球实体 ID
     */
    public static void removeExpOrbMergeCounter(int orbId) {
        EXP_ORB_MERGE_COUNTERS.remove(orbId);
    }

    // ========================================================================
    // 10. 无用动物 AI 跳过
    // ========================================================================

    /**
     * 判断动物是否应该跳过无用 AI（如交配、跟随等）。
     *
     * <p>对于距离玩家很远且无交互需求的动物，跳过部分非必要的 AI goal
     * （如 {@code BreedGoal}、{@code FollowParentGoal} 等）。</p>
     *
     * <p><b>调用位置：</b>patch 中动物 AI goal 的 {@code canUse} 方法中。</p>
     *
     * @param nearestPlayerDistSq 到最近玩家的平方距离
     * @return true 如果应该跳过无用 AI
     */
    public static boolean shouldSkipUselessAnimalAi(double nearestPlayerDistSq) {
        if (!MirageConfig.skipUselessAnimalAi) {
            return false;
        }
        // 距离玩家超过 48 格时跳过无用 AI
        double threshold = 48.0;
        return nearestPlayerDistSq > threshold * threshold;
    }

    // ========================================================================
    // 11. 统计和清理
    // ========================================================================

    /**
     * 获取实体优化的统计信息。
     *
     * @return 统计信息 Map
     */
    public static java.util.Map<String, Object> getEntityOptimizationStats() {
        java.util.Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("ai_tick_counters", ENTITY_AI_TICK_COUNTERS.size());
        stats.put("ai_skip_count", aiSkipCount.get());
        stats.put("pathfinding_records", ENTITY_LAST_PATH_POS.size());
        stats.put("pathfinding_skip_count", pathfindingSkipCount.get());
        stats.put("pathfinding_rejected_count", pathfindingRejectedCount.get());
        stats.put("concurrent_pathfinding", concurrentPathfindingCount.get());
        stats.put("collision_cache_hits", collisionCacheHits.get());
        stats.put("collision_cache_misses", collisionCacheMisses.get());
        stats.put("target_search_counters", TARGET_SEARCH_COUNTERS.size());
        stats.put("villager_sensor_counters", VILLAGER_SENSOR_COUNTERS.size());
        stats.put("villager_sensor_skip_count", villagerSensorSkipCount.get());
        stats.put("item_physics_records", ITEM_LAST_STATE.size());
        stats.put("item_physics_skip_count", itemPhysicsSkipCount.get());
        stats.put("exp_orb_merge_counters", EXP_ORB_MERGE_COUNTERS.size());
        return stats;
    }

    /**
     * 清理指定实体的所有优化记录。在实体从世界移除时调用。
     *
     * <p><b>调用位置：</b>patch 中 {@code Entity#discard} / {@code Entity#remove} 方法中。</p>
     *
     * @param entityId 实体 ID
     */
    public static void cleanupEntity(int entityId) {
        ENTITY_AI_TICK_COUNTERS.remove(entityId);
        ENTITY_LAST_PATH_POS.remove(entityId);
        TARGET_SEARCH_COUNTERS.remove(entityId);
        VILLAGER_SENSOR_COUNTERS.remove(entityId);
        ITEM_LAST_STATE.remove(entityId);
        EXP_ORB_MERGE_COUNTERS.remove(entityId);
        MirageOptimizer.invalidateCollisionCache(entityId);
        MirageMemoryOptimizer.removeCompactEntityData(entityId);
    }

    /**
     * 清空所有实体优化缓存和计数器。在服务器关闭时调用。
     */
    public static void clearAll() {
        ENTITY_AI_TICK_COUNTERS.clear();
        ENTITY_LAST_PATH_POS.clear();
        TARGET_SEARCH_COUNTERS.clear();
        VILLAGER_SENSOR_COUNTERS.clear();
        ITEM_LAST_STATE.clear();
        EXP_ORB_MERGE_COUNTERS.clear();
        aiSkipCount.set(0);
        pathfindingSkipCount.set(0);
        pathfindingRejectedCount.set(0);
        collisionCacheHits.set(0);
        collisionCacheMisses.set(0);
        villagerSensorSkipCount.set(0);
        itemPhysicsSkipCount.set(0);
        concurrentPathfindingCount.set(0);
    }
}
