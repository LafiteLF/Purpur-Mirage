package org.purpurmc.purpur.mirage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MirageEntityLimiter — Per-world entity limit enforcement.
 *
 * Automatically culls excess entities when they exceed configured limits.
 * Supports per-type caps, per-chunk caps, and global world caps.
 */
public final class MirageEntityLimiter {

    // Entity type category limits (per world)
    public static final int CATEGORY_MONSTER = 0;
    public static final int CATEGORY_ANIMAL = 1;
    public static final int CATEGORY_AMBIENT = 2;
    public static final int CATEGORY_WATER = 3;
    public static final int CATEGORY_ITEM = 4;
    public static final int CATEGORY_XP_ORB = 5;
    public static final int CATEGORY_PROJECTILE = 6;
    public static final int CATEGORY_OTHER = 7;

    private static final String[] CATEGORY_NAMES = {
        "monster", "animal", "ambient", "water", "item", "xp_orb", "projectile", "other"
    };

    // Per-world tracking: worldName -> category -> count
    private static final Map<String, int[]> worldCounts = new HashMap<>();

    private MirageEntityLimiter() {}

    /**
     * Update entity counts for a world.
     */
    public static void updateWorldCounts(String worldName, int[] counts) {
        synchronized (worldCounts) {
            worldCounts.put(worldName, counts);
        }
    }

    /**
     * Get entity counts for a world.
     */
    public static int[] getWorldCounts(String worldName) {
        synchronized (worldCounts) {
            return worldCounts.getOrDefault(worldName, new int[8]);
        }
    }

    /**
     * Check if a specific entity type should be allowed to spawn
     * based on current counts and configured limits.
     *
     * @param worldName The world name
     * @param category  The entity category (see CATEGORY_* constants)
     * @return true if spawning is allowed, false if limit exceeded
     */
    public static boolean canSpawn(String worldName, int category) {
        if (!MirageConfig.enableEntityLimiter) return true;

        int[] counts = getWorldCounts(worldName);
        int current = counts[category];
        int limit = getLimitForCategory(category);

        return current < limit;
    }

    /**
     * Get the configured limit for an entity category.
     */
    public static int getLimitForCategory(int category) {
        return switch (category) {
            case CATEGORY_MONSTER -> MirageConfig.entityLimitMonster;
            case CATEGORY_ANIMAL -> MirageConfig.entityLimitAnimal;
            case CATEGORY_AMBIENT -> MirageConfig.entityLimitAmbient;
            case CATEGORY_WATER -> MirageConfig.entityLimitWater;
            case CATEGORY_ITEM -> MirageConfig.entityLimitItem;
            case CATEGORY_XP_ORB -> MirageConfig.entityLimitXpOrb;
            case CATEGORY_PROJECTILE -> MirageConfig.entityLimitProjectile;
            default -> MirageConfig.entityLimitOther;
        };
    }

    /**
     * Get the name of a category.
     */
    public static String getCategoryName(int category) {
        if (category < 0 || category >= CATEGORY_NAMES.length) return "unknown";
        return CATEGORY_NAMES[category];
    }

    /**
     * Check and cull excess entities for a world.
     * Returns the number of entities that should be removed.
     *
     * @param worldName The world name
     * @return Map of category -> number to cull
     */
    public static Map<Integer, Integer> calculateCullExcess(String worldName) {
        if (!MirageConfig.enableEntityLimiter || !MirageConfig.autoCullExcess) {
            return Map.of();
        }

        int[] counts = getWorldCounts(worldName);
        Map<Integer, Integer> toCull = new HashMap<>();

        for (int cat = 0; cat < 8; cat++) {
            int limit = getLimitForCategory(cat);
            if (counts[cat] > limit) {
                int excess = counts[cat] - limit;
                // Cull a percentage of the excess per check to avoid sudden mass removal
                int toRemove = (int) Math.ceil(excess * MirageConfig.cullPercentage);
                toCull.put(cat, toRemove);
            }
        }

        return toCull;
    }

    /**
     * Check if a chunk has too many entities.
     *
     * @param entityCountInChunk Current entity count in the chunk
     * @return true if the chunk is at capacity
     */
    public static boolean isChunkAtCapacity(int entityCountInChunk) {
        return MirageConfig.enableEntityLimiter && entityCountInChunk >= MirageConfig.maxEntitiesPerChunk;
    }

    /**
     * Get a summary of entity counts across all worlds.
     */
    public static String getSummary() {
        StringBuilder sb = new StringBuilder();
        synchronized (worldCounts) {
            for (Map.Entry<String, int[]> entry : worldCounts.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(":\n");
                int[] counts = entry.getValue();
                for (int cat = 0; cat < 8; cat++) {
                    if (counts[cat] > 0) {
                        int limit = getLimitForCategory(cat);
                        sb.append("    ")
                          .append(getCategoryName(cat))
                          .append(": ")
                          .append(counts[cat])
                          .append("/")
                          .append(limit)
                          .append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * Reset all counts.
     */
    public static void reset() {
        synchronized (worldCounts) {
            worldCounts.clear();
        }
    }
}
