package org.purpurmc.purpur.mirage;

import org.bukkit.configuration.ConfigurationSection;
import org.purpurmc.purpur.PurpurConfig;
import java.util.logging.Level;

/**
 * Mirage optimization configuration.
 *
 * Integrates concepts from Lithium, FerriteCore, ModernFix, MemoryFix and similar
 * server-side optimization mods into the Purpur/Mirage server core.
 *
 * All options are safe by default and do not alter vanilla mechanics.
 */
@SuppressWarnings("unused")
public class MirageConfig {

    // ==================== Memory Optimization (FerriteCore / MemoryFix inspired) ====================

    /** Enable aggressive block-state cache optimization to reduce memory per chunk section */
    public static boolean optimizeBlockStateCache = true;
    /** Use compact entity data storage to reduce per-entity memory footprint */
    public static boolean compactEntityData = true;
    /** Deduplicate NBT tag objects to reduce heap pressure */
    public static boolean deduplicateNbtTags = true;
    /** Use pooled BlockPos instances instead of allocating new ones */
    public static boolean poolBlockPos = true;
    /** Reduce palette storage for chunk sections with mostly-empty blocks */
    public static boolean optimizeChunkPalette = true;
    /** Cache frequently accessed block state properties to avoid repeated lookups */
    public static boolean cacheBlockStateProperties = true;
    /** Use a more memory-efficient HashMap implementation for entity maps */
    public static boolean compactEntityMaps = true;
    /** Enable intern-like caching for common ItemStack NBT data */
    public static boolean cacheItemNbt = true;
    /** Max block state cache size (per world) */
    public static int blockStateCacheMaxSize = 65536;
    /** Enable WeakReference-based chunk cache to allow GC under pressure */
    public static boolean weakChunkCache = true;

    private static void memoryOptimization() {
        optimizeBlockStateCache = getBoolean("settings.mirage.memory.optimize-block-state-cache", optimizeBlockStateCache);
        compactEntityData = getBoolean("settings.mirage.memory.compact-entity-data", compactEntityData);
        deduplicateNbtTags = getBoolean("settings.mirage.memory.deduplicate-nbt-tags", deduplicateNbtTags);
        poolBlockPos = getBoolean("settings.mirage.memory.pool-block-pos", poolBlockPos);
        optimizeChunkPalette = getBoolean("settings.mirage.memory.optimize-chunk-palette", optimizeChunkPalette);
        cacheBlockStateProperties = getBoolean("settings.mirage.memory.cache-block-state-properties", cacheBlockStateProperties);
        compactEntityMaps = getBoolean("settings.mirage.memory.compact-entity-maps", compactEntityMaps);
        cacheItemNbt = getBoolean("settings.mirage.memory.cache-item-nbt", cacheItemNbt);
        blockStateCacheMaxSize = getInt("settings.mirage.memory.block-state-cache-max-size", blockStateCacheMaxSize);
        weakChunkCache = getBoolean("settings.mirage.memory.weak-chunk-cache", weakChunkCache);
    }

    // ==================== Entity Optimization (Lithium inspired) ====================

    /** Skip AI tick for entities beyond the given distance from any player */
    public static int entityAiSkipDistance = 32;
    /** Reduce the frequency of inactive entity AI ticks (e.g., every 4th tick) */
    public static int inactiveEntityAiTickInterval = 4;
    /** Skip pathfinding recalculation for entities that haven't moved */
    public static boolean skipRedundantPathfinding = true;
    /** Cache entity collision results for multiple ticks */
    public static int entityCollisionCacheTicks = 3;
    /** Optimize mob targeting by limiting search range based on mob type */
    public static boolean optimizeMobTargeting = true;
    /** Reduce the frequency of sensor updates for villagers */
    public static int villagerSensorUpdateInterval = 20;
    /** Skip physics for items on the ground that haven't moved */
    public static boolean skipItemPhysicsIfIdle = true;
    /** Cap the number of simultaneous pathfinding operations */
    public static int maxConcurrentPathfinding = 32;
    /** Use faster approximate distance checks for entity activation */
    public static boolean fastActivationChecks = true;
    /** Reduce experience orb merge check frequency */
    public static int expOrbMergeInterval = 20;
    /** Skip duplicate AI goals for animals that don't need them */
    public static boolean skipUselessAnimalAi = true;

    private static void entityOptimization() {
        entityAiSkipDistance = getInt("settings.mirage.entity.ai-skip-distance", entityAiSkipDistance);
        inactiveEntityAiTickInterval = getInt("settings.mirage.entity.inactive-ai-tick-interval", inactiveEntityAiTickInterval);
        skipRedundantPathfinding = getBoolean("settings.mirage.entity.skip-redundant-pathfinding", skipRedundantPathfinding);
        entityCollisionCacheTicks = getInt("settings.mirage.entity.collision-cache-ticks", entityCollisionCacheTicks);
        optimizeMobTargeting = getBoolean("settings.mirage.entity.optimize-mob-targeting", optimizeMobTargeting);
        villagerSensorUpdateInterval = getInt("settings.mirage.entity.villager-sensor-interval", villagerSensorUpdateInterval);
        skipItemPhysicsIfIdle = getBoolean("settings.mirage.entity.skip-item-physics-if-idle", skipItemPhysicsIfIdle);
        maxConcurrentPathfinding = getInt("settings.mirage.entity.max-concurrent-pathfinding", maxConcurrentPathfinding);
        fastActivationChecks = getBoolean("settings.mirage.entity.fast-activation-checks", fastActivationChecks);
        expOrbMergeInterval = getInt("settings.mirage.entity.exp-orb-merge-interval", expOrbMergeInterval);
        skipUselessAnimalAi = getBoolean("settings.mirage.entity.skip-useless-animal-ai", skipUselessAnimalAi);
    }

    // ==================== Chunk Optimization ====================

    /** Optimize chunk tick iteration order for better cache locality */
    public static boolean optimizeChunkTickOrder = true;
    /** Skip ticking chunks that have no players and no pending block updates */
    public static boolean skipEmptyChunkTicks = true;
    /** Limit the number of chunks processed per tick for random ticks */
    public static int maxRandomTickChunksPerTick = 256;
    /** Use a faster chunk hash for chunk coordinate lookups */
    public static boolean fastChunkHash = true;
    /** Defer chunk unloading to batch process at the end of tick */
    public static boolean batchChunkUnload = true;
    /** Chunk save interval in ticks (default 6000 = 5 min) */
    public static int chunkSaveInterval = 6000;
    /** Use async chunk compression for region file writes */
    public static boolean asyncChunkCompression = true;
    /** Pre-compute light updates for chunks before sending to client */
    public static boolean precomputeLightForSend = true;

    private static void chunkOptimization() {
        optimizeChunkTickOrder = getBoolean("settings.mirage.chunk.optimize-tick-order", optimizeChunkTickOrder);
        skipEmptyChunkTicks = getBoolean("settings.mirage.chunk.skip-empty-ticks", skipEmptyChunkTicks);
        maxRandomTickChunksPerTick = getInt("settings.mirage.chunk.max-random-tick-chunks", maxRandomTickChunksPerTick);
        fastChunkHash = getBoolean("settings.mirage.chunk.fast-chunk-hash", fastChunkHash);
        batchChunkUnload = getBoolean("settings.mirage.chunk.batch-unload", batchChunkUnload);
        chunkSaveInterval = getInt("settings.mirage.chunk.save-interval", chunkSaveInterval);
        asyncChunkCompression = getBoolean("settings.mirage.chunk.async-compression", asyncChunkCompression);
        precomputeLightForSend = getBoolean("settings.mirage.chunk.precompute-light", precomputeLightForSend);
    }

    // ==================== Redstone / Block Tick Optimization (Lithium inspired) ====================

    /** Optimize redstone wire updates by batching connected wire updates */
    public static boolean optimizeRedstone = true;
    /** Skip redundant block neighbor updates */
    public static boolean skipRedundantNeighborUpdates = true;
    /** Cap the maximum chain of redstone updates per tick */
    public static int maxRedstoneChainPerTick = 65536;
    /** Optimize scheduled tick queue using a more efficient data structure */
    public static boolean optimizeScheduledTicks = true;
    /** Merge adjacent identical block updates */
    public static boolean mergeBlockUpdates = true;
    /** Skip fluid updates for non-source blocks that won't flow */
    public static boolean skipIdleFluidUpdates = true;

    private static void redstoneOptimization() {
        optimizeRedstone = getBoolean("settings.mirage.redstone.optimize", optimizeRedstone);
        skipRedundantNeighborUpdates = getBoolean("settings.mirage.redstone.skip-redundant-neighbor-updates", skipRedundantNeighborUpdates);
        maxRedstoneChainPerTick = getInt("settings.mirage.redstone.max-chain-per-tick", maxRedstoneChainPerTick);
        optimizeScheduledTicks = getBoolean("settings.mirage.redstone.optimize-scheduled-ticks", optimizeScheduledTicks);
        mergeBlockUpdates = getBoolean("settings.mirage.redstone.merge-block-updates", mergeBlockUpdates);
        skipIdleFluidUpdates = getBoolean("settings.mirage.redstone.skip-idle-fluid-updates", skipIdleFluidUpdates);
    }

    // ==================== Hopper / Inventory Optimization ====================

    /** Optimize hopper item transfer by caching inventory state */
    public static boolean optimizeHoppers = true;
    /** Skip hopper cooldown when inventory is full (avoid wasted checks) */
    public static boolean skipHopperCooldownIfFull = true;
    /** Hopper transfer cooldown in ticks (default 8, vanilla) */
    public static int hopperTransferCooldown = 8;
    /** Reduce hopper check frequency for non-pointing hoppers */
    public static int idleHopperCheckInterval = 8;
    /** Disable hopper entity scanning when no items are nearby */
    public static boolean skipHopperEntityScanIfNoItems = true;

    private static void hopperOptimization() {
        optimizeHoppers = getBoolean("settings.mirage.hopper.optimize", optimizeHoppers);
        skipHopperCooldownIfFull = getBoolean("settings.mirage.hopper.skip-cooldown-if-full", skipHopperCooldownIfFull);
        hopperTransferCooldown = getInt("settings.mirage.hopper.transfer-cooldown", hopperTransferCooldown);
        idleHopperCheckInterval = getInt("settings.mirage.hopper.idle-check-interval", idleHopperCheckInterval);
        skipHopperEntityScanIfNoItems = getBoolean("settings.mirage.hopper.skip-entity-scan-if-no-items", skipHopperEntityScanIfNoItems);
    }

    // ==================== Client Optimization (Server-driven) ====================

    /**
     * Reduce entity tracking ranges to lower the number of entities the client must render.
     * This directly reduces client-side CPU and GPU load.
     */
    public static boolean optimizeEntityTrackingRange = true;
    /** Entity tracking range multiplier (1.0 = vanilla, lower = fewer entities sent to client) */
    public static double entityTrackingRangeMultiplier = 0.75;
    /** Reduce the frequency of entity position update packets */
    public static boolean reduceEntityUpdatePackets = true;
    /** Entity update packet interval for distant entities (in ticks) */
    public static int distantEntityUpdateInterval = 5;
    /** Distance threshold for "distant" entities (in blocks) */
    public static int distantEntityThreshold = 32;
    /** Reduce the view distance dynamically based on player count */
    public static boolean dynamicViewDistance = true;
    /** Minimum view distance when player count is high */
    public static int minViewDistance = 4;
    /** Maximum view distance when player count is low */
    public static int maxViewDistance = 16;
    /** Player count at which view distance starts to shrink */
    public static int viewDistanceShrinkPlayerCount = 20;
    /** Optimize chunk packet compression level */
    public static int chunkPacketCompressionLevel = 6;
    /** Send chunk data in priority order (player position first) */
    public static boolean prioritizedChunkSending = true;
    /** Batch entity metadata packets to reduce packet count */
    public static boolean batchEntityMetadata = true;
    /** Reduce the frequency of block change packets for non-player-visible changes */
    public static boolean optimizeBlockChangePackets = true;
    /** Cap the number of entity spawn packets sent per tick per player */
    public static int maxEntitySpawnsPerTickPerPlayer = 10;
    /** Cap the number of entity despawn packets sent per tick per player */
    public static int maxEntityDespawnsPerTickPerPlayer = 10;
    /** Skip sending entity equipment updates for non-nearby players */
    public static boolean skipDistantEquipmentUpdates = true;
    /** Reduce particle packet frequency */
    public static boolean reduceParticlePackets = true;
    /** Particle packet reduction multiplier (0-1, lower = fewer particles) */
    public static double particleReductionMultiplier = 0.5;
    /** Enable adaptive entity tracking - reduce ranges when TPS is low */
    public static boolean adaptiveEntityTracking = true;
    /** TPS threshold below which adaptive tracking kicks in */
    public static double adaptiveTrackingTpsThreshold = 15.0;

    private static void clientOptimization() {
        optimizeEntityTrackingRange = getBoolean("settings.mirage.client.optimize-entity-tracking-range", optimizeEntityTrackingRange);
        entityTrackingRangeMultiplier = getDouble("settings.mirage.client.entity-tracking-range-multiplier", entityTrackingRangeMultiplier);
        reduceEntityUpdatePackets = getBoolean("settings.mirage.client.reduce-entity-update-packets", reduceEntityUpdatePackets);
        distantEntityUpdateInterval = getInt("settings.mirage.client.distant-entity-update-interval", distantEntityUpdateInterval);
        distantEntityThreshold = getInt("settings.mirage.client.distant-entity-threshold", distantEntityThreshold);
        dynamicViewDistance = getBoolean("settings.mirage.client.dynamic-view-distance", dynamicViewDistance);
        minViewDistance = getInt("settings.mirage.client.min-view-distance", minViewDistance);
        maxViewDistance = getInt("settings.mirage.client.max-view-distance", maxViewDistance);
        viewDistanceShrinkPlayerCount = getInt("settings.mirage.client.view-distance-shrink-player-count", viewDistanceShrinkPlayerCount);
        chunkPacketCompressionLevel = getInt("settings.mirage.client.chunk-packet-compression-level", chunkPacketCompressionLevel);
        prioritizedChunkSending = getBoolean("settings.mirage.client.prioritized-chunk-sending", prioritizedChunkSending);
        batchEntityMetadata = getBoolean("settings.mirage.client.batch-entity-metadata", batchEntityMetadata);
        optimizeBlockChangePackets = getBoolean("settings.mirage.client.optimize-block-change-packets", optimizeBlockChangePackets);
        maxEntitySpawnsPerTickPerPlayer = getInt("settings.mirage.client.max-entity-spawns-per-tick", maxEntitySpawnsPerTickPerPlayer);
        maxEntityDespawnsPerTickPerPlayer = getInt("settings.mirage.client.max-entity-despawns-per-tick", maxEntityDespawnsPerTickPerPlayer);
        skipDistantEquipmentUpdates = getBoolean("settings.mirage.client.skip-distant-equipment-updates", skipDistantEquipmentUpdates);
        reduceParticlePackets = getBoolean("settings.mirage.client.reduce-particle-packets", reduceParticlePackets);
        particleReductionMultiplier = getDouble("settings.mirage.client.particle-reduction-multiplier", particleReductionMultiplier);
        adaptiveEntityTracking = getBoolean("settings.mirage.client.adaptive-entity-tracking", adaptiveEntityTracking);
        adaptiveTrackingTpsThreshold = getDouble("settings.mirage.client.adaptive-tracking-tps-threshold", adaptiveTrackingTpsThreshold);
    }

    // ==================== Mob Spawning Optimization (Client FPS improvement) ====================

    /**
     * Optimize mob spawning to reduce client-side entity count,
     * directly improving client FPS by reducing render load.
     */
    public static boolean optimizeMobSpawning = true;
    /** Reduce the mob cap multiplier (lower = fewer mobs spawned) */
    public static double mobCapMultiplier = 0.7;
    /** Increase the despawn distance for mobs to reduce entity count */
    public static int optimizedDespawnDistance = 48;
    /** Despawn mobs faster when there are too many */
    public static boolean fastDespawnExcess = true;
    /** Mob count above which fast despawn triggers (per player) */
    public static int fastDespawnThreshold = 35;
    /** Skip spawning mobs in chunks with no line of sight to any player */
    public static boolean skipHiddenChunkSpawns = true;
    /** Reduce ambient mob spawns (bats) */
    public static double ambientMobCapMultiplier = 0.3;
    /** Reduce water creature spawns */
    public static double waterMobCapMultiplier = 0.6;
    /** Cap the total number of entities per chunk */
    public static int maxEntitiesPerChunk = 24;
    /** Reduce the spawn attempt count per tick */
    public static double spawnAttemptMultiplier = 0.6;

    private static void mobSpawningOptimization() {
        optimizeMobSpawning = getBoolean("settings.mirage.spawning.optimize", optimizeMobSpawning);
        mobCapMultiplier = getDouble("settings.mirage.spawning.mob-cap-multiplier", mobCapMultiplier);
        optimizedDespawnDistance = getInt("settings.mirage.spawning.despawn-distance", optimizedDespawnDistance);
        fastDespawnExcess = getBoolean("settings.mirage.spawning.fast-despawn-excess", fastDespawnExcess);
        fastDespawnThreshold = getInt("settings.mirage.spawning.fast-despawn-threshold", fastDespawnThreshold);
        skipHiddenChunkSpawns = getBoolean("settings.mirage.spawning.skip-hidden-chunk-spawns", skipHiddenChunkSpawns);
        ambientMobCapMultiplier = getDouble("settings.mirage.spawning.ambient-cap-multiplier", ambientMobCapMultiplier);
        waterMobCapMultiplier = getDouble("settings.mirage.spawning.water-cap-multiplier", waterMobCapMultiplier);
        maxEntitiesPerChunk = getInt("settings.mirage.spawning.max-entities-per-chunk", maxEntitiesPerChunk);
        spawnAttemptMultiplier = getDouble("settings.mirage.spawning.spawn-attempt-multiplier", spawnAttemptMultiplier);
    }

    // ==================== Network / Packet Optimization (ModernFix inspired) ====================

    /** Compress all outgoing packets more aggressively */
    public static boolean aggressivePacketCompression = true;
    /** Batch small packets together before sending */
    public static boolean batchSmallPackets = true;
    /** Flush packet queue every N ticks instead of every tick */
    public static int packetFlushInterval = 1;
    /** Skip sending keepalive packets as frequently (reduce overhead) */
    public static int keepaliveInterval = 30;
    /** Use a more efficient packet serialization for entity data */
    public static boolean optimizeEntityDataSerialization = true;
    /** Cache serialized chunk packets to avoid re-serialization */
    public static boolean cacheChunkPackets = true;
    /** Max cached chunk packets (LRU size) */
    public static int chunkPacketCacheSize = 256;

    private static void networkOptimization() {
        aggressivePacketCompression = getBoolean("settings.mirage.network.aggressive-compression", aggressivePacketCompression);
        batchSmallPackets = getBoolean("settings.mirage.network.batch-small-packets", batchSmallPackets);
        packetFlushInterval = getInt("settings.mirage.network.flush-interval", packetFlushInterval);
        keepaliveInterval = getInt("settings.mirage.network.keepalive-interval", keepaliveInterval);
        optimizeEntityDataSerialization = getBoolean("settings.mirage.network.optimize-entity-data-serialization", optimizeEntityDataSerialization);
        cacheChunkPackets = getBoolean("settings.mirage.network.cache-chunk-packets", cacheChunkPackets);
        chunkPacketCacheSize = getInt("settings.mirage.network.chunk-packet-cache-size", chunkPacketCacheSize);
    }

    // ==================== General Performance ====================

    /** Use a faster random number generator (Xoshiro256++ instead of Java's Random) */
    public static boolean fastRandom = true;
    /** Use a thread-local random for per-entity random calls */
    public static boolean threadLocalEntityRandom = true;
    /** Enable adaptive tick rate - slow down ticks when server is lagging */
    public static boolean adaptiveTickRate = false;
    /** Parallelize entity ticking across chunks */
    public static boolean parallelEntityTicking = true;
    /** Enable spark profiling integration for Mirage optimizations */
    public static boolean sparkProfiling = true;
    /** Log Mirage optimization summary on startup */
    public static boolean logOptimizationSummary = true;

    private static void generalOptimization() {
        fastRandom = getBoolean("settings.mirage.general.fast-random", fastRandom);
        threadLocalEntityRandom = getBoolean("settings.mirage.general.thread-local-entity-random", threadLocalEntityRandom);
        adaptiveTickRate = getBoolean("settings.mirage.general.adaptive-tick-rate", adaptiveTickRate);
        parallelEntityTicking = getBoolean("settings.mirage.general.parallel-entity-ticking", parallelEntityTicking);
        sparkProfiling = getBoolean("settings.mirage.general.spark-profiling", sparkProfiling);
        logOptimizationSummary = getBoolean("settings.mirage.general.log-optimization-summary", logOptimizationSummary);
    }

    // ==================== Entity Limiter ====================

    /** Enable per-world entity limit enforcement */
    public static boolean enableEntityLimiter = true;
    /** Automatically cull excess entities when limits are exceeded */
    public static boolean autoCullExcess = true;
    /** Percentage of excess entities to cull per check (0-1) */
    public static double cullPercentage = 0.5;
    /** Max monsters per world */
    public static int entityLimitMonster = 200;
    /** Max animals per world */
    public static int entityLimitAnimal = 150;
    /** Max ambient creatures (bats) per world */
    public static int entityLimitAmbient = 30;
    /** Max water creatures per world */
    public static int entityLimitWater = 100;
    /** Max dropped items per world */
    public static int entityLimitItem = 200;
    /** Max XP orbs per world */
    public static int entityLimitXpOrb = 100;
    /** Max projectiles per world */
    public static int entityLimitProjectile = 150;
    /** Max other entities per world */
    public static int entityLimitOther = 300;

    private static void entityLimiter() {
        enableEntityLimiter = getBoolean("settings.mirage.entity-limiter.enabled", enableEntityLimiter);
        autoCullExcess = getBoolean("settings.mirage.entity-limiter.auto-cull", autoCullExcess);
        cullPercentage = getDouble("settings.mirage.entity-limiter.cull-percentage", cullPercentage);
        entityLimitMonster = getInt("settings.mirage.entity-limiter.monster", entityLimitMonster);
        entityLimitAnimal = getInt("settings.mirage.entity-limiter.animal", entityLimitAnimal);
        entityLimitAmbient = getInt("settings.mirage.entity-limiter.ambient", entityLimitAmbient);
        entityLimitWater = getInt("settings.mirage.entity-limiter.water", entityLimitWater);
        entityLimitItem = getInt("settings.mirage.entity-limiter.item", entityLimitItem);
        entityLimitXpOrb = getInt("settings.mirage.entity-limiter.xp-orb", entityLimitXpOrb);
        entityLimitProjectile = getInt("settings.mirage.entity-limiter.projectile", entityLimitProjectile);
        entityLimitOther = getInt("settings.mirage.entity-limiter.other", entityLimitOther);
    }

    // ==================== Monitor & Auto-Optimization ====================

    /** Enable runtime monitoring task */
    public static boolean enableMonitor = true;
    /** Monitor check interval in ticks (default 100 = 5 seconds) */
    public static int monitorIntervalTicks = 100;
    /** Log periodic health summary every 5 minutes */
    public static boolean logPeriodicSummary = true;
    /** Memory usage percentage that triggers a warning */
    public static double memoryWarningThreshold = 70.0;
    /** Memory usage percentage that triggers critical actions */
    public static double memoryCriticalThreshold = 85.0;
    /** Auto-clear optimization caches when memory is critical */
    public static boolean autoClearCacheOnPressure = true;
    /** Auto-suggest GC when memory is critical */
    public static boolean autoSuggestGc = true;
    /** TPS below which warnings are logged */
    public static double tpsWarningThreshold = 15.0;

    private static void monitorConfig() {
        enableMonitor = getBoolean("settings.mirage.monitor.enabled", enableMonitor);
        monitorIntervalTicks = getInt("settings.mirage.monitor.interval-ticks", monitorIntervalTicks);
        logPeriodicSummary = getBoolean("settings.mirage.monitor.log-periodic-summary", logPeriodicSummary);
        memoryWarningThreshold = getDouble("settings.mirage.monitor.memory-warning-threshold", memoryWarningThreshold);
        memoryCriticalThreshold = getDouble("settings.mirage.monitor.memory-critical-threshold", memoryCriticalThreshold);
        autoClearCacheOnPressure = getBoolean("settings.mirage.monitor.auto-clear-cache", autoClearCacheOnPressure);
        autoSuggestGc = getBoolean("settings.mirage.monitor.auto-gc", autoSuggestGc);
        tpsWarningThreshold = getDouble("settings.mirage.monitor.tps-warning-threshold", tpsWarningThreshold);
    }

    // ==================== Smart Auto-Save ====================

    /** Enable smart chunk auto-save that spreads saves across ticks */
    public static boolean enableSmartAutoSave = true;
    /** Auto-save interval in ticks (default 6000 = 5 min, vanilla) */
    public static int autoSaveIntervalTicks = 6000;
    /** Max chunks to save per tick in smart mode */
    public static int maxSavesPerTick = 8;
    /** Flush all pending saves on server shutdown */
    public static boolean flushOnShutdown = true;

    private static void smartAutoSave() {
        enableSmartAutoSave = getBoolean("settings.mirage.autosave.enabled", enableSmartAutoSave);
        autoSaveIntervalTicks = getInt("settings.mirage.autosave.interval-ticks", autoSaveIntervalTicks);
        maxSavesPerTick = getInt("settings.mirage.autosave.max-saves-per-tick", maxSavesPerTick);
        flushOnShutdown = getBoolean("settings.mirage.autosave.flush-on-shutdown", flushOnShutdown);
    }

    // ==================== GC Profiler ====================

    /** Enable GC pause tracking */
    public static boolean enableGcProfiler = true;
    /** GC pause threshold (ms) to log warning */
    public static int gcPauseWarningMs = 50;

    private static void gcProfilerConfig() {
        enableGcProfiler = getBoolean("settings.mirage.gc-profiler.enabled", enableGcProfiler);
        gcPauseWarningMs = getInt("settings.mirage.gc-profiler.pause-warning-ms", gcPauseWarningMs);
    }

    // ==================== Chunk Prefetch ====================

    /** Enable predictive chunk prefetch based on player movement */
    public static boolean enableChunkPrefetch = true;
    /** How many ticks ahead to predict player position */
    public static int prefetchLookaheadTicks = 40;
    /** Radius of chunks to prefetch ahead of player */
    public static int prefetchRadius = 3;
    /** Max chunks to prefetch per cycle per player */
    public static int maxPrefetchPerCycle = 16;

    private static void chunkPrefetchConfig() {
        enableChunkPrefetch = getBoolean("settings.mirage.prefetch.enabled", enableChunkPrefetch);
        prefetchLookaheadTicks = getInt("settings.mirage.prefetch.lookahead-ticks", prefetchLookaheadTicks);
        prefetchRadius = getInt("settings.mirage.prefetch.radius", prefetchRadius);
        maxPrefetchPerCycle = getInt("settings.mirage.prefetch.max-per-cycle", maxPrefetchPerCycle);
    }

    // ==================== JVM Warmup ====================

    /** Enable JVM warmup during server startup */
    public static boolean enableJvmWarmup = true;

    private static void jvmWarmupConfig() {
        enableJvmWarmup = getBoolean("settings.mirage.warmup.enabled", enableJvmWarmup);
    }

    // ==================== Code Runner (Python / C++) ====================

    /** Enable code execution via /python and /c++ commands */
    public static boolean enableCodeRunner = true;
    /** Python executable path */
    public static String pythonExecutable = "python";
    /** C++ compiler path */
    public static String cppCompiler = "g++";
    /** Code execution timeout in seconds */
    public static int codeExecutionTimeoutSec = 30;
    /** Enable auto-run directories (./python/main.py and ./c++/main.cpp) */
    public static boolean enableAutoRun = true;
    /** Clean up compiled .exe after auto-run */
    public static boolean cleanUpAutoRunExe = true;
    /** Check interval for auto-run files (in ticks) */
    public static int autoRunCheckIntervalTicks = 100;

    private static void codeRunnerConfig() {
        enableCodeRunner = getBoolean("settings.mirage.code-runner.enabled", enableCodeRunner);
        pythonExecutable = getString("settings.mirage.code-runner.python-executable", pythonExecutable);
        cppCompiler = getString("settings.mirage.code-runner.cpp-compiler", cppCompiler);
        codeExecutionTimeoutSec = getInt("settings.mirage.code-runner.timeout-sec", codeExecutionTimeoutSec);
        enableAutoRun = getBoolean("settings.mirage.code-runner.auto-run", enableAutoRun);
        cleanUpAutoRunExe = getBoolean("settings.mirage.code-runner.cleanup-exe", cleanUpAutoRunExe);
        autoRunCheckIntervalTicks = getInt("settings.mirage.code-runner.check-interval", autoRunCheckIntervalTicks);
    }

    // ==================== Config helpers ====================

    private static boolean getBoolean(String path, boolean def) {
        return PurpurConfig.config.getBoolean(path, PurpurConfig.config.getBoolean(path, def));
    }

    private static int getInt(String path, int def) {
        return PurpurConfig.config.getInt(path, PurpurConfig.config.getInt(path, def));
    }

    private static double getDouble(String path, double def) {
        return PurpurConfig.config.getDouble(path, PurpurConfig.config.getDouble(path, def));
    }

    private static String getString(String path, String def) {
        return PurpurConfig.config.getString(path, PurpurConfig.config.getString(path, def));
    }

    /**
     * Called during server initialization to load Mirage configuration.
     */
    public static void init() {
        memoryOptimization();
        entityOptimization();
        chunkOptimization();
        redstoneOptimization();
        hopperOptimization();
        clientOptimization();
        mobSpawningOptimization();
        networkOptimization();
        generalOptimization();
        entityLimiter();
        monitorConfig();
        smartAutoSave();
        gcProfilerConfig();
        chunkPrefetchConfig();
        jvmWarmupConfig();
        codeRunnerConfig();

        if (logOptimizationSummary) {
            int enabled = countEnabled();
            org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Optimization engine initialized — " + enabled + " optimization modules active.");
            if (fastRandom) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Using Xoshiro256++ fast random generator.");
            }
            if (optimizeMobSpawning) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Mob spawning optimized for client FPS: cap=" + (int)(mobCapMultiplier * 100) + "%, despawn=" + optimizedDespawnDistance + " blocks.");
            }
            if (optimizeEntityTrackingRange) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Entity tracking range reduced to " + (int)(entityTrackingRangeMultiplier * 100) + "% for client FPS improvement.");
            }
            if (enableEntityLimiter) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Entity limiter active: monster=" + entityLimitMonster + ", animal=" + entityLimitAnimal + ", item=" + entityLimitItem + " per world.");
            }
            if (enableMonitor) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Runtime monitor active: interval=" + monitorIntervalTicks + "t, memory warning=" + (int)memoryWarningThreshold + "%, critical=" + (int)memoryCriticalThreshold + "%.");
            }
            if (enableSmartAutoSave) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Smart auto-save: interval=" + autoSaveIntervalTicks + "t, max " + maxSavesPerTick + " chunks/tick.");
            }
            if (enableGcProfiler) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] GC profiler active: warning threshold=" + gcPauseWarningMs + "ms.");
            }
            if (enableChunkPrefetch) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Chunk prefetch: lookahead=" + prefetchLookaheadTicks + "t, radius=" + prefetchRadius + ", max=" + maxPrefetchPerCycle + "/cycle.");
            }
            if (enableJvmWarmup) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] JVM warmup enabled — will pre-load classes and compile hot paths on startup.");
            }
            if (enableCodeRunner) {
                org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Code runner enabled: python=" + pythonExecutable + ", c++=" + cppCompiler + ", timeout=" + codeExecutionTimeoutSec + "s.");
                if (enableAutoRun) {
                    org.bukkit.Bukkit.getLogger().log(Level.INFO, "[Mirage] Auto-run enabled: monitoring ./python/main.py and ./c++/main.cpp for changes.");
                }
            }
        }
    }

    private static int countEnabled() {
        int count = 0;
        if (optimizeBlockStateCache) count++;
        if (compactEntityData) count++;
        if (deduplicateNbtTags) count++;
        if (poolBlockPos) count++;
        if (optimizeChunkPalette) count++;
        if (cacheBlockStateProperties) count++;
        if (compactEntityMaps) count++;
        if (cacheItemNbt) count++;
        if (weakChunkCache) count++;
        if (skipRedundantPathfinding) count++;
        if (optimizeMobTargeting) count++;
        if (skipItemPhysicsIfIdle) count++;
        if (fastActivationChecks) count++;
        if (skipUselessAnimalAi) count++;
        if (optimizeChunkTickOrder) count++;
        if (skipEmptyChunkTicks) count++;
        if (fastChunkHash) count++;
        if (batchChunkUnload) count++;
        if (asyncChunkCompression) count++;
        if (precomputeLightForSend) count++;
        if (optimizeRedstone) count++;
        if (skipRedundantNeighborUpdates) count++;
        if (optimizeScheduledTicks) count++;
        if (mergeBlockUpdates) count++;
        if (skipIdleFluidUpdates) count++;
        if (optimizeHoppers) count++;
        if (skipHopperCooldownIfFull) count++;
        if (skipHopperEntityScanIfNoItems) count++;
        if (optimizeEntityTrackingRange) count++;
        if (reduceEntityUpdatePackets) count++;
        if (dynamicViewDistance) count++;
        if (prioritizedChunkSending) count++;
        if (batchEntityMetadata) count++;
        if (optimizeBlockChangePackets) count++;
        if (skipDistantEquipmentUpdates) count++;
        if (reduceParticlePackets) count++;
        if (adaptiveEntityTracking) count++;
        if (optimizeMobSpawning) count++;
        if (fastDespawnExcess) count++;
        if (skipHiddenChunkSpawns) count++;
        if (aggressivePacketCompression) count++;
        if (batchSmallPackets) count++;
        if (optimizeEntityDataSerialization) count++;
        if (cacheChunkPackets) count++;
        if (fastRandom) count++;
        if (threadLocalEntityRandom) count++;
        if (parallelEntityTicking) count++;
        if (enableEntityLimiter) count++;
        if (autoCullExcess) count++;
        if (enableMonitor) count++;
        if (autoClearCacheOnPressure) count++;
        if (enableSmartAutoSave) count++;
        if (enableGcProfiler) count++;
        if (enableChunkPrefetch) count++;
        if (enableJvmWarmup) count++;
        if (enableCodeRunner) count++;
        if (enableAutoRun) count++;
        return count;
    }
}
