package org.purpurmc.purpur.mirage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageWarmup — JVM warmup and class pre-loading during server startup.
 *
 * During server startup, this module:
 * - Pre-loads critical Minecraft classes to force JIT compilation
 * - Warms up the Mirage optimization caches with dummy data
 * - Pre-compiles hot code paths to avoid JIT compilation lag during gameplay
 *
 * Inspired by Project Panamabind warmup techniques and CDS (Class Data Sharing).
 */
public final class MirageWarmup {

    private static final Logger LOGGER = Logger.getLogger("Mirage");
    private static final AtomicBoolean warmedUp = new AtomicBoolean(false);
    private static final AtomicLong warmupTimeNanos = new AtomicLong(0);

    // Critical classes to pre-load
    private static final String[] CRITICAL_CLASSES = {
        "net.minecraft.world.level.block.Block",
        "net.minecraft.world.level.block.state.BlockState",
        "net.minecraft.world.level.chunk.LevelChunk",
        "net.minecraft.world.entity.Entity",
        "net.minecraft.world.entity.EntityType",
        "net.minecraft.world.item.Item",
        "net.minecraft.world.item.ItemStack",
        "net.minecraft.server.level.ServerLevel",
        "net.minecraft.server.level.ServerPlayer",
        "net.minecraft.network.protocol.Packet",
        "net.minecraft.nbt.CompoundTag",
        "net.minecraft.core.BlockPos",
        "net.minecraft.world.level.ChunkPos",
        "net.minecraft.world.phys.Vec3",
        "net.minecraft.world.level.levelgen.RandomSource",
        "net.minecraft.util.RandomSource",
    };

    private MirageWarmup() {}

    /**
     * Perform JVM warmup. Should be called during server startup,
     * after the main classes are loaded but before accepting connections.
     */
    public static void warmup() {
        if (!MirageConfig.enableJvmWarmup) return;
        if (!warmedUp.compareAndSet(false, true)) return;

        long startTime = System.nanoTime();
        LOGGER.log(Level.INFO, "[Mirage] Starting JVM warmup...");

        // Phase 1: Pre-load critical classes
        int loaded = 0;
        for (String className : CRITICAL_CLASSES) {
            try {
                Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                loaded++;
            } catch (Throwable ignored) {
                // Some classes may not exist in this version
            }
        }
        LOGGER.log(Level.INFO, "[Mirage] Pre-loaded {0}/{1} critical classes",
            new Object[]{loaded, CRITICAL_CLASSES.length});

        // Phase 2: Warm up MirageOptimizer caches
        warmupCaches();

        // Phase 3: Warm up JIT with hot code paths
        warmupJitPaths();

        long elapsed = System.nanoTime() - startTime;
        warmupTimeNanos.set(elapsed);

        LOGGER.log(Level.INFO, "[Mirage] JVM warmup complete in {0} ms",
            String.format("%.1f", elapsed / 1_000_000.0));
    }

    /**
     * Warm up the MirageOptimizer caches with dummy data
     * to pre-allocate internal data structures.
     */
    private static void warmupCaches() {
        // Warm up BlockPos pool
        for (int i = 0; i < 64; i++) {
            MirageOptimizer.PooledBlockPos pos = MirageOptimizer.acquireBlockPos(i, i, i);
            MirageOptimizer.releaseBlockPos(pos);
        }

        // Warm up NBT dedup cache with dummy entries
        for (int i = 0; i < 128; i++) {
            MirageOptimizer.deduplicateNbt("warmup_" + i);
        }

        // Warm up chunk packet cache
        for (int i = 0; i < 16; i++) {
            long key = MirageOptimizer.chunkKey(i, i);
            MirageOptimizer.putCachedChunkPacket(key, new byte[0]);
        }

        // Warm up collision cache
        for (int i = 0; i < 32; i++) {
            MirageOptimizer.putCachedCollision(0, i, 0, 0, 0, 1, 1, 1, 0, null);
        }

        // Clear dummy data after warmup
        MirageOptimizer.clearAllCaches();
    }

    /**
     * Warm up JIT compilation by exercising hot code paths.
     * This forces the JIT compiler to compile frequently used methods.
     */
    private static void warmupJitPaths() {
        // Exercise distance calculations
        for (int i = 0; i < 10000; i++) {
            MirageOptimizer.distanceSquared(i, i, i, i * 2, i * 2, i * 2);
            MirageOptimizer.distanceSquared2D(i, i, i * 2, i * 2);
            MirageOptimizer.isWithinRange(i, i, i, i * 2, i * 2, i * 2, 100);
            MirageOptimizer.isWithinRange2D(i, i, i * 2, i * 2, 100);
        }

        // Exercise hash functions
        for (int i = 0; i < 10000; i++) {
            MirageOptimizer.hashInt(i);
            MirageOptimizer.hashLong(i);
            MirageOptimizer.chunkKey(i, i);
        }

        // Exercise random number generator
        MirageOptimizer.Xoshiro256PP rng = MirageOptimizer.seededRandom(42);
        for (int i = 0; i < 10000; i++) {
            rng.nextInt(100);
            rng.nextDouble();
            rng.nextBoolean();
            rng.nextFloat();
        }

        // Exercise adaptive view distance calculation
        for (int i = 0; i < 100; i++) {
            MirageOptimizer.calculateAdaptiveViewDistance(i % 50, 20.0 - (i % 15), i);
        }

        // Exercise entity tracking range calculation
        for (int i = 0; i < 100; i++) {
            MirageOptimizer.calculateEntityTrackingRange(64 + i, 20.0 - (i % 5));
            MirageOptimizer.calculateEntityUpdateInterval(i * i * 1.0, 20.0 - (i % 5));
        }
    }

    /**
     * Get warmup status and statistics.
     */
    public static boolean isWarmedUp() {
        return warmedUp.get();
    }

    public static long getWarmupTimeMs() {
        return warmupTimeNanos.get() / 1_000_000;
    }

    /**
     * Get warmup info for display.
     */
    public static String getInfoString() {
        if (!warmedUp.get()) return "Not warmed up";
        return String.format("Warmed up in %d ms", getWarmupTimeMs());
    }
}
