package dev.biomeislands.world;

import dev.biomeislands.config.IslandSettings;
import dev.biomeislands.noise.ValueNoise;
import dev.biomeislands.stats.GenerationStats;
import org.bukkit.Material;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Island generator built around Minecraft's Vanilla terrain rather than a custom heightmap.
 *
 * <p>Paper supplies an already-generated Vanilla noise chunk to {@link #generateNoise}.
 * Interior columns keep that terrain. Near the organic shoreline, the upper density surface
 * is blended toward sea level over a varying run-up width so high Vanilla terrain does not get
 * sliced into a vertical wall. Outside the island, the ocean floor descends continuously from
 * the shoreline into a broad basin instead of using a fixed shallow shelf.</p>
 */
public final class IslandChunkGenerator extends ChunkGenerator {
    private static final long RELIEF_SALT = 0x4D7A2B91E63FC805L;
    private final IslandSettings settings;
    private final IslandLayout layout;
    private final BiomeProvider biomeProvider;
    private final GenerationStats stats;

    // Noise generation still has the untouched Vanilla column heights that are needed for the
    // wet-edge seabed handoff. Cache only the final 16x16 ocean floors until the cave callback so
    // post-carver resealing uses the exact same floor and cannot recreate the old raised berm.
    private final ConcurrentHashMap<Long, int[]> pendingOceanFloors = new ConcurrentHashMap<>();

    public IslandChunkGenerator(IslandSettings settings, BiomeProvider biomeProvider, GenerationStats stats) {
        this.settings = settings;
        this.layout = new IslandLayout(settings);
        this.biomeProvider = biomeProvider;
        this.stats = stats;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int seaY = clamp(settings.seaLevel(), minY + 1, maxY - 1);
        long seed = worldInfo.getSeed();
        int blockStartX = chunkX << 4;
        int blockStartZ = chunkZ << 4;

        Map<Long, Integer> observedIslands = stats == null ? null : new HashMap<>();
        int[] plannedOceanFloors = settings.caves() ? new int[16 * 16] : null;
        if (plannedOceanFloors != null) {
            Arrays.fill(plannedOceanFloors, Integer.MIN_VALUE);
        }

        for (int localX = 0; localX < 16; localX++) {
            int worldX = blockStartX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = blockStartZ + localZ;
                IslandLayout.IslandSample sample = layout.sample(seed, worldX, worldZ);
                if (sample.inside()) {
                    if (observedIslands != null) {
                        observedIslands.putIfAbsent(sample.cellSeed(), sample.biomeIndex());
                    }
                    shapeIslandColumn(chunkData, seed, worldX, worldZ, localX, localZ, sample, minY, maxY, seaY);
                } else {
                    // Capture Vanilla's original top before replacing this column. On a wet island
                    // edge this is the local seabed reference we need to meet flush instead of
                    // pulling the ocean floor upward toward sea level.
                    int vanillaTopY = findTopSolidY(chunkData, localX, localZ, minY, maxY);
                    int floorY = makeOceanColumn(chunkData, seed, worldX, worldZ, localX, localZ,
                            sample, vanillaTopY, minY, maxY, seaY);
                    if (plannedOceanFloors != null) {
                        plannedOceanFloors[index(localX, localZ)] = floorY;
                    }
                }
            }
        }

        if (plannedOceanFloors != null) {
            pendingOceanFloors.put(chunkKey(chunkX, chunkZ), plannedOceanFloors);
        }
        if (stats != null) {
            stats.observeChunk();
            for (Map.Entry<Long, Integer> entry : observedIslands.entrySet()) {
                stats.observeIsland(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Preserve Vanilla terrain inland, but give the shoreline a variable land-side run-up.
     *
     * <p>The island mask itself is unchanged. We only alter the top density surface when a
     * high Vanilla column happens to intersect the mask edge. Wide shoreline sections descend
     * gradually until the top of the final dry beach block aligns with the ocean surface; short
     * sections retain a cliffier profile.
     * Vanilla surface rules run afterwards, so beaches/snow/grass are still selected normally.</p>
     */
    private void shapeIslandColumn(
            ChunkData data,
            long worldSeed,
            int worldX,
            int worldZ,
            int x,
            int z,
            IslandLayout.IslandSample sample,
            int minY,
            int maxY,
            int seaY
    ) {
        int topSolidY = Integer.MIN_VALUE;
        Material topMaterial = Material.STONE;
        for (int y = maxY - 1; y >= minY; y--) {
            Material material = data.getType(x, y, z);
            if (!isAirOrFluid(material)) {
                topSolidY = y;
                topMaterial = material;
                break;
            }
        }

        if (topSolidY == Integer.MIN_VALUE) {
            return;
        }

        int liftedTopY = Math.min(maxY - 1, topSolidY + settings.islandHeightOffset());
        int desiredTopY = liftedTopY;

        // seaY is the first block above normal ocean water. Therefore the top ocean water block
        // is seaY - 1, and a solid beach block at the same Y has its top face exactly level with
        // the water surface. 1.3.4 incorrectly blended the dry shoreline toward seaY itself,
        // leaving the beach one full block above the surrounding ocean.
        int shorelineTopY = seaY - 1;

        double localRunUp = layout.coastRunUpWidth(sample);
        double coastDistance = layout.coastDistance(sample);
        if (localRunUp > 0.0 && coastDistance < localRunUp) {
            // Blend high terrain downward exactly as 1.3.3 did, but also pull very shallow
            // submerged Vanilla columns up into the beach profile. Those near-sea submerged
            // contours were another source of thin underwater ridges right at the mask edge.
            if (liftedTopY > seaY || liftedTopY >= seaY - 5) {
                double progress = smootherStep(clamp(coastDistance / localRunUp, 0.0, 1.0));
                int blendedTopY = (int) Math.round(lerp(shorelineTopY, liftedTopY, progress));
                if (liftedTopY >= shorelineTopY) {
                    desiredTopY = clamp(blendedTopY, shorelineTopY, liftedTopY);
                } else {
                    desiredTopY = clamp(blendedTopY, liftedTopY, shorelineTopY);
                }
            }
        }

        // Vanilla can occasionally place mountain-scale density on an otherwise ordinary island.
        // Do not impose a global height ceiling: instead the allowed relief rises with true
        // distance from the coast, then only the excess above that envelope is compressed.
        // The low-frequency allowance noise prevents the envelope itself from reading as a ring.
        if (settings.tameExtremeRelief() && desiredTopY > shorelineTopY) {
            double reliefNoise = ValueNoise.fbm(
                    worldSeed ^ sample.cellSeed() ^ RELIEF_SALT,
                    worldX * 0.0105,
                    worldZ * 0.0105,
                    2
            );
            double localBaseRelief = settings.maxCoastalRelief() + (reliefNoise * 3.5);
            double inlandAllowance = Math.max(0.0, coastDistance) * 0.34;
            int adaptiveCapY = shorelineTopY
                    + (int) Math.round(Math.max(8.0, localBaseRelief + inlandAllowance));

            if (desiredTopY > adaptiveCapY) {
                int excess = desiredTopY - adaptiveCapY;
                // Retain 18% of the excess so tall areas still have natural variation rather than
                // being chopped into a flat plateau, while absurd 40-60 block spikes collapse.
                desiredTopY = adaptiveCapY + (int) Math.round(excess * 0.18);
            }
        }

        if (desiredTopY < topSolidY) {
            // Trim high Vanilla terrain above the coastal profile. Clearing the whole upper
            // column also removes overhang fragments that would otherwise look like the old
            // mask was cut with a vertical cookie cutter.
            data.setRegion(x, desiredTopY + 1, z, x + 1, maxY, z + 1, Material.AIR);
            data.setBlock(x, desiredTopY, z, topMaterial);
        } else if (desiredTopY > topSolidY) {
            // This includes the existing +1 island surface correction. At the noise stage the
            // fill is base terrain; Vanilla surface rules will skin it afterward.
            data.setRegion(x, topSolidY + 1, z, x + 1, desiredTopY + 1, z + 1, topMaterial);
        }
    }

    private static int findTopSolidY(ChunkData data, int x, int z, int minY, int maxY) {
        for (int y = maxY - 1; y >= minY; y--) {
            if (!isAirOrFluid(data.getType(x, y, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int findTopSolidYBelowSea(ChunkData data, int x, int z, int minY, int seaY) {
        for (int y = seaY - 1; y >= minY; y--) {
            if (!isAirOrFluid(data.getType(x, y, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static boolean isAirOrFluid(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR
                || material == Material.WATER
                || material == Material.LAVA;
    }

    private int makeOceanColumn(
            ChunkData data,
            long seed,
            int worldX,
            int worldZ,
            int localX,
            int localZ,
            IslandLayout.IslandSample sample,
            int vanillaTopY,
            int minY,
            int maxY,
            int seaY
    ) {
        int floorY = clamp(layout.oceanFloorHeight(seed, worldX, worldZ, sample, vanillaTopY),
                minY + 3, seaY - 1);

        // Preserve deep Vanilla geology/caves, but establish a deterministic sealed ocean cap.
        data.setRegion(localX, floorY + 1, localZ, localX + 1, maxY, localZ + 1, Material.AIR);
        data.setRegion(localX, floorY - 2, localZ, localX + 1, floorY + 1, localZ + 1, Material.STONE);
        // Vanilla-style sea-level semantics: seaY is the first block ABOVE the ocean.
        // For sea-level 63, water occupies through Y=62, not Y=63.
        data.setRegion(localX, floorY + 1, localZ, localX + 1, seaY, localZ + 1, Material.WATER);
        return floorY;
    }

    @Override
    public void generateCaves(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        // Vanilla cave carvers run before this callback. Re-seal ocean columns afterwards so
        // cave/aquifer carving cannot create isolated one-block dents in the seabed/water.
        // Island cave entrances remain completely Vanilla.
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int seaY = clamp(settings.seaLevel(), minY + 1, maxY - 1);
        long seed = worldInfo.getSeed();
        int blockStartX = chunkX << 4;
        int blockStartZ = chunkZ << 4;
        int[] plannedOceanFloors = pendingOceanFloors.remove(chunkKey(chunkX, chunkZ));

        for (int localX = 0; localX < 16; localX++) {
            int worldX = blockStartX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = blockStartZ + localZ;
                IslandLayout.IslandSample sample = layout.sample(seed, worldX, worldZ);
                if (sample.inside()) {
                    if (settings.protectCoastalShell()) {
                        repairCoastalShell(chunkData, localX, localZ, sample, minY, maxY, seaY);
                    }
                    continue;
                }

                int cachedFloor = plannedOceanFloors == null
                        ? Integer.MIN_VALUE
                        : plannedOceanFloors[index(localX, localZ)];
                int floorY;
                if (cachedFloor != Integer.MIN_VALUE) {
                    floorY = clamp(cachedFloor, minY + 3, seaY - 1);
                } else {
                    // Fallback for an unusual callback/order mismatch: use the shaped column that
                    // already exists rather than recomputing the old sea-level-based profile,
                    // which could reintroduce the wet-edge berm during cave repair.
                    int observed = findTopSolidYBelowSea(chunkData, localX, localZ, minY, seaY);
                    floorY = observed == Integer.MIN_VALUE
                            ? clamp(layout.oceanFloorHeight(seed, worldX, worldZ, sample), minY + 3, seaY - 1)
                            : clamp(observed, minY + 3, seaY - 1);
                }
                // Surface rules have already had a chance to skin this deterministic ocean floor.
                // Preserve that material when possible; only choose a fallback if cave carving
                // removed the exact top block. This avoids painting a sand/gravel contour ring
                // around every island during the post-cave reseal.
                Material existingFloor = chunkData.getType(localX, floorY, localZ);
                Material floor = isAirOrFluid(existingFloor)
                        ? oceanSurfaceMaterial(seed, worldX, worldZ, floorY, seaY)
                        : existingFloor;
                chunkData.setRegion(localX, floorY - 2, localZ,
                        localX + 1, floorY, localZ + 1, Material.STONE);
                chunkData.setBlock(localX, floorY, localZ, floor);
                chunkData.setRegion(localX, floorY + 1, localZ,
                        localX + 1, seaY, localZ + 1, Material.WATER);
            }
        }
    }


    /**
     * Rebuild only the thin ocean-facing shell after Vanilla cave/aquifer carving.
     *
     * <p>The repair is intentionally shallow in horizontal extent and depth: inland caves and
     * lakes remain Vanilla, while cavities beneath an intact outer surface are closed. The repair
     * never raises or repaints the visible shoreline; surface shaping remains the responsibility
     * of the noise/coast stage.</p>
     */
    private void repairCoastalShell(
            ChunkData data,
            int x,
            int z,
            IslandLayout.IslandSample sample,
            int minY,
            int maxY,
            int seaY
    ) {
        double shellWidth = settings.coastalShellWidth();
        if (shellWidth <= 0.0) {
            return;
        }

        double coastDistance = layout.coastDistance(sample);
        if (coastDistance < 0.0 || coastDistance >= shellWidth) {
            return;
        }

        int topSolidY = Integer.MIN_VALUE;
        for (int y = maxY - 1; y >= minY; y--) {
            Material material = data.getType(x, y, z);
            if (!isAirOrFluid(material)) {
                topSolidY = y;
                break;
            }
        }

        // Structural repair must never invent a shoreline. If Vanilla/cave carving left this
        // column with no solid surface, leave it alone rather than raising ocean-floor material
        // (especially gravel) to sea level. Island shaping is the sole owner of surface height.
        if (topSolidY == Integer.MIN_VALUE) {
            return;
        }

        // Only the visually exposed part of the wall is sealed. Deep cave systems remain intact.
        int visibleShellDepth = Math.max(14, (int) Math.round(shellWidth * 1.10));
        int lowerY = Math.max(minY + 2, seaY - visibleShellDepth);

        // 1.3.8 also restored low coastal columns upward to the waterline. That successfully
        // blocked channels, but promoted whatever low seabed material was present (often gravel)
        // into a conspicuous sea-level ring. 1.3.9 is deliberately structural-only: fill voids
        // beneath an already-existing surface, but never change that surface or any block above it.
        int existingShellTop = Math.min(maxY - 1, topSolidY - 1);
        for (int y = lowerY; y <= existingShellTop; y++) {
            if (isAirOrFluid(data.getType(x, y, z))) {
                data.setBlock(x, y, z, Material.STONE);
            }
        }
    }

    private Material oceanSurfaceMaterial(long seed, int worldX, int worldZ, int floorY, int seaY) {
        // Fallback only. Normal ocean surface material is whatever Vanilla surface rules already
        // produced for the selected ocean biome. If a cave removed that exact top block, use a
        // broad sediment field based on world position/depth -- never coastline distance -- so
        // the replacement cannot trace an artificial ring around the island.
        double sediment = ValueNoise.fbm(seed ^ 0x2A6D91C4E7B5038FL,
                worldX * 0.011, worldZ * 0.011, 2);
        int depth = seaY - floorY;
        double sandBias = depth <= 8 ? 0.30 : -0.12;
        return sediment > sandBias ? Material.SAND : Material.GRAVEL;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return settings.caves();
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return settings.decorations();
    }

    @Override
    public boolean shouldGenerateMobs() {
        return settings.mobs();
    }

    @Override
    public boolean shouldGenerateStructures() {
        return settings.structures();
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return biomeProvider;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double smootherStep(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + ((b - a) * t);
    }
}
