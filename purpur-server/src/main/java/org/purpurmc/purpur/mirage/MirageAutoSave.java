package org.purpurmc.purpur.mirage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageAutoSave — Smart chunk auto-save scheduler.
 *
 * Instead of saving all chunks at once (which causes lag spikes),
 * this spreads chunk saves across multiple ticks to smooth out
 * the performance impact of auto-save.
 *
 * Inspired by C2ME's parallel save system and Krypton's I/O optimization.
 */
public final class MirageAutoSave {

    private static final Logger LOGGER = Logger.getLogger("Mirage");

    // Save queue: chunks pending save, spread across ticks
    private static final List<SaveEntry> saveQueue = new ArrayList<>();
    private static final Object queueLock = new Object();

    // Statistics
    private static final AtomicLong totalSavesProcessed = new AtomicLong(0);
    private static final AtomicLong totalSaveTimeNanos = new AtomicLong(0);
    private static final AtomicInteger currentQueueSize = new AtomicInteger(0);

    // Current save batch state
    private static volatile int currentTick = 0;
    private static volatile int lastSaveTick = 0;

    private MirageAutoSave() {}

    /**
     * A single chunk save entry in the queue.
     */
    public interface SaveEntry {
        /** Execute the save for this chunk */
        void save();
        /** Get the chunk coordinates for priority calculation */
        int chunkX();
        int chunkZ();
        /** Get the world name */
        String worldName();
    }

    /**
     * Queue a chunk for deferred saving.
     *
     * @param entry The save entry to queue
     */
    public static void queueSave(SaveEntry entry) {
        if (!MirageConfig.enableSmartAutoSave) return;
        synchronized (queueLock) {
            saveQueue.add(entry);
            currentQueueSize.set(saveQueue.size());
        }
    }

    /**
     * Process the save queue. Called every tick from the monitor task.
     * Processes a limited number of saves per tick to spread the load.
     */
    public static void tick(long tick) {
        if (!MirageConfig.enableSmartAutoSave) return;
        currentTick = (int) (tick & Integer.MAX_VALUE);

        int toProcess;
        synchronized (queueLock) {
            if (saveQueue.isEmpty()) return;
            // Calculate how many saves to process this tick
            int remaining = saveQueue.size();
            int ticksUntilNextSave = MirageConfig.autoSaveIntervalTicks - (currentTick - lastSaveTick);
            if (ticksUntilNextSave <= 0) ticksUntilNextSave = 1;

            // Spread saves across remaining ticks before next full save
            toProcess = Math.min(remaining, Math.max(1, remaining / ticksUntilNextSave));
        }

        long startTime = System.nanoTime();
        int processed = 0;

        for (int i = 0; i < toProcess; i++) {
            SaveEntry entry;
            synchronized (queueLock) {
                if (saveQueue.isEmpty()) break;
                entry = saveQueue.remove(0);
            }

            try {
                entry.save();
                processed++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[Mirage] AutoSave error saving chunk at ({0}, {1}) in {2}: {3}",
                    new Object[]{entry.chunkX(), entry.chunkZ(), entry.worldName(), e.getMessage()});
            }
        }

        long elapsed = System.nanoTime() - startTime;
        totalSavesProcessed.addAndGet(processed);
        totalSaveTimeNanos.addAndGet(elapsed);

        // Update queue size
        synchronized (queueLock) {
            currentQueueSize.set(saveQueue.size());
        }
    }

    /**
     * Force flush all pending saves immediately.
     * Called on server shutdown or /mirage optimize.
     */
    public static int flushAll() {
        int count = 0;
        long startTime = System.nanoTime();

        synchronized (queueLock) {
            for (SaveEntry entry : saveQueue) {
                try {
                    entry.save();
                    count++;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[Mirage] AutoSave flush error: {0}", e.getMessage());
                }
            }
            saveQueue.clear();
            currentQueueSize.set(0);
        }

        long elapsed = System.nanoTime() - startTime;
        totalSavesProcessed.addAndGet(count);
        totalSaveTimeNanos.addAndGet(elapsed);

        LOGGER.log(Level.INFO, "[Mirage] AutoSave flushed {0} chunks in {1} ms",
            new Object[]{count, String.format("%.2f", elapsed / 1_000_000.0)});

        return count;
    }

    /**
     * Mark that a full auto-save cycle just completed.
     */
    public static void markFullSaveComplete(long tick) {
        lastSaveTick = (int) (tick & Integer.MAX_VALUE);
    }

    // ========== Statistics ==========

    public static long getTotalSavesProcessed() { return totalSavesProcessed.get(); }
    public static double getAvgSaveTimeMs() {
        long count = totalSavesProcessed.get();
        if (count == 0) return 0;
        return (double) totalSaveTimeNanos.get() / count / 1_000_000.0;
    }
    public static int getQueueSize() { return currentQueueSize.get(); }

    /**
     * Get statistics as a formatted string for display.
     */
    public static String getStatsString() {
        return String.format("Queue: %d, Total saved: %d, Avg time: %.3f ms",
            getQueueSize(), getTotalSavesProcessed(), getAvgSaveTimeMs());
    }

    /**
     * Reset statistics.
     */
    public static void resetStats() {
        totalSavesProcessed.set(0);
        totalSaveTimeNanos.set(0);
    }
}
