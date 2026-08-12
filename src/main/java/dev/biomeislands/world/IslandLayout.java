package dev.biomeislands.world;

import dev.biomeislands.config.IslandSettings;
import dev.biomeislands.noise.ValueNoise;

/**
 * Deterministic staggered island placement with a strongly non-circular coastline.
 */
public final class IslandLayout {
    private static final long CENTER_X_SALT = 0x1E4A8B72D53CF901L;
    private static final long CENTER_Z_SALT = 0x5F2790C3A86D41BEL;
    private static final long SIZE_SALT = 0x6D0149A7B2C8E35FL;
    private static final long HARMONIC_2_SALT = 0x3A9C56E12D70B84FL;
    private static final long HARMONIC_3_SALT = 0x24A7D91E5C60B38FL;
    private static final long HARMONIC_5_SALT = 0x79A4C52E16D0B38FL;
    private static final long SHAPE_SALT = 0x53A9E2D1C7B4F601L;
    private static final long SHAPE_DETAIL_SALT = 0x2C56A4B90FD38177L;
    private static final long OCEAN_SALT = 0x6C8E9CF570932BD5L;
    private static final long COAST_RUNUP_2_SALT = 0x68A7D1C35B902E4FL;
    private static final long COAST_RUNUP_3_SALT = 0x14C9B7E26A503DF1L;
    private static final long COAST_RUNUP_7_SALT = 0x7B1E45A9C203D86FL;
    private static final long SLOPE_2_SALT = 0x2F8C4A71D95E306BL;
    private static final long SLOPE_5_SALT = 0x6B31E7A4C8D2059FL;
    private static final long SLOPE_MID_SALT = 0x4A23D98C17B65EF0L;
    private static final long BIOME_SALT = 0x4F1BBCDCB2A9137DL;
    private static final long EXTREME_REROLL_SALT = 0x71C8A4E2D5903BF6L;

    private static final long OCEAN_REGION_CELL_SALT = 0x5C917A2ED4B0638FL;
    private static final long OCEAN_REGION_X_SALT = 0x71A4E3C95D2B806FL;
    private static final long OCEAN_REGION_Z_SALT = 0x27D9B540AC6E318FL;
    private static final long OCEAN_REGION_BIOME_SALT = 0x4E0AB937C2D8165FL;
    private static final long OCEAN_REGION_WARP_X_SALT = 0x19C5E7A23D8B604FL;
    private static final long OCEAN_REGION_WARP_Z_SALT = 0x63B20D9E45A71C8FL;

    private final IslandSettings settings;
    private final int rowSpacing;
    private final double[] cumulativeBiomeWeights;
    private final double totalBiomeWeight;
    private final double[] safeCumulativeBiomeWeights;
    private final double totalSafeBiomeWeight;
    private final double[] cumulativeOceanBiomeWeights;
    private final double totalOceanBiomeWeight;

    public IslandLayout(IslandSettings settings) {
        this.settings = settings;
        this.rowSpacing = Math.max(1, (int) Math.round(settings.spacing() * Math.sqrt(3.0) / 2.0));

        this.cumulativeBiomeWeights = new double[settings.biomeWeights().size()];
        this.safeCumulativeBiomeWeights = new double[settings.biomeWeights().size()];
        double running = 0.0;
        double safeRunning = 0.0;
        for (int i = 0; i < settings.biomeWeights().size(); i++) {
            double weight = Math.max(0.0, settings.biomeWeights().get(i));
            running += weight;
            cumulativeBiomeWeights[i] = running;

            if (!IslandSettings.isExtremeTerrainBiome(settings.biomeKeys().get(i))) {
                safeRunning += weight;
            }
            safeCumulativeBiomeWeights[i] = safeRunning;
        }
        this.totalBiomeWeight = running;
        this.totalSafeBiomeWeight = safeRunning;
        if (totalBiomeWeight <= 0.0) {
            throw new IllegalArgumentException("At least one island biome must have a positive weight");
        }
        if (!settings.allowExtremeTerrainBiomes() && totalSafeBiomeWeight <= 0.0) {
            throw new IllegalArgumentException("At least one non-extreme island biome must have a positive weight");
        }

        this.cumulativeOceanBiomeWeights = new double[settings.oceanBiomeWeights().size()];
        double oceanRunning = 0.0;
        for (int i = 0; i < settings.oceanBiomeWeights().size(); i++) {
            oceanRunning += Math.max(0.0, settings.oceanBiomeWeights().get(i));
            cumulativeOceanBiomeWeights[i] = oceanRunning;
        }
        this.totalOceanBiomeWeight = oceanRunning;
        if (totalOceanBiomeWeight <= 0.0) {
            throw new IllegalArgumentException("At least one ocean biome family must have a positive weight");
        }
    }

    public IslandSample sample(long worldSeed, int x, int z) {
        int baseRow = nearestInt((double) z / rowSpacing);
        Candidate nearest = null;

        // A wider search makes center jitter safe even around negative-coordinate cell seams.
        for (int row = baseRow - 2; row <= baseRow + 2; row++) {
            double rowOffset = Math.floorMod(row, 2) == 0 ? 0.0 : settings.spacing() / 2.0;
            int baseColumn = nearestInt((x - rowOffset) / settings.spacing());
            for (int column = baseColumn - 2; column <= baseColumn + 2; column++) {
                long cellSeed = cellSeed(worldSeed, column, row);
                double centerX = (column * (double) settings.spacing()) + rowOffset
                        + signedUnit(cellSeed ^ CENTER_X_SALT) * settings.centerJitter();
                double centerZ = row * (double) rowSpacing
                        + signedUnit(cellSeed ^ CENTER_Z_SALT) * settings.centerJitter();
                double dx = x - centerX;
                double dz = z - centerZ;
                double distanceSquared = (dx * dx) + (dz * dz);
                if (nearest == null || distanceSquared < nearest.distanceSquared()) {
                    nearest = new Candidate(column, row, centerX, centerZ, dx, dz, distanceSquared, cellSeed);
                }
            }
        }

        if (nearest == null) {
            throw new IllegalStateException("Unable to locate nearest island cell");
        }

        double nominalRadius = settings.radius()
                + (settings.radiusVariation() * signedUnit(nearest.cellSeed() ^ SIZE_SALT));
        nominalRadius = Math.max(32.0, nominalRadius);

        double distance = Math.sqrt(nearest.distanceSquared());
        double edgeDepth = organicEdgeDepth(
                nearest.cellSeed(), nearest.dx(), nearest.dz(), distance, nominalRadius);
        boolean inside = edgeDepth >= 0.0;
        int biomeIndex = biomeIndex(nearest.cellSeed());

        return new IslandSample(
                inside,
                nearest.column(),
                nearest.row(),
                nearest.centerX(),
                nearest.centerZ(),
                nearest.dx(),
                nearest.dz(),
                distance,
                nominalRadius,
                edgeDepth,
                biomeIndex,
                nearest.cellSeed()
        );
    }

    private double organicEdgeDepth(long cellSeed, double dx, double dz, double distance, double radius) {
        double angle = Math.atan2(dz, dx);

        double phase2 = unit(cellSeed ^ HARMONIC_2_SALT) * Math.PI * 2.0;
        double phase3 = unit(cellSeed ^ HARMONIC_3_SALT) * Math.PI * 2.0;
        double phase5 = unit(cellSeed ^ HARMONIC_5_SALT) * Math.PI * 2.0;
        double harmonic =
                (0.185 * Math.sin((angle * 2.0) + phase2))
                        + (0.135 * Math.sin((angle * 3.0) + phase3))
                        + (0.080 * Math.sin((angle * 5.0) + phase5));

        double scale = settings.shapeNoiseScale();
        double broad = ValueNoise.fbm(cellSeed ^ SHAPE_SALT, dx * scale, dz * scale, 4);
        double detail = ValueNoise.fbm(cellSeed ^ SHAPE_DETAIL_SALT,
                dx * scale * 2.35, dz * scale * 2.35, 2);

        double rawFactor = 0.76 + harmonic + (broad * 0.155) + (detail * 0.050);
        double boundaryFactor = 0.74 + (0.22 * Math.tanh((rawFactor - 0.74) / 0.22));
        double boundaryRadius = radius * boundaryFactor;

        return boundaryRadius - distance;
    }

    /**
     * Approximate signed perpendicular distance to the organic shoreline.
     */
    public double coastDistance(IslandSample sample) {
        double f = sample.edgeDepth();
        double configured = sample.inside()
                ? settings.coastRunUpWidth()
                : settings.oceanSlopeWidth();
        if (configured <= 0.0 || Math.abs(f) > configured * 1.75) {
            return f;
        }

        double dx = sample.dx();
        double dz = sample.dz();
        double radius = sample.nominalRadius();
        long seed = sample.cellSeed();

        double fxPlus = organicEdgeDepth(seed, dx + 1.0, dz, Math.hypot(dx + 1.0, dz), radius);
        double fxMinus = organicEdgeDepth(seed, dx - 1.0, dz, Math.hypot(dx - 1.0, dz), radius);
        double fzPlus = organicEdgeDepth(seed, dx, dz + 1.0, Math.hypot(dx, dz + 1.0), radius);
        double fzMinus = organicEdgeDepth(seed, dx, dz - 1.0, Math.hypot(dx, dz - 1.0), radius);

        double gx = (fxPlus - fxMinus) * 0.5;
        double gz = (fzPlus - fzMinus) * 0.5;
        double gradient = Math.hypot(gx, gz);
        return f / Math.max(1.0, gradient);
    }

    public double coastRunUpWidth(IslandSample sample) {
        if (!sample.inside()) {
            return 0.0;
        }
        return coastRunUpWidthProfile(sample);
    }

    /**
     * The local land-side coast character, evaluated on either side of the mask. Keeping this
     * profile available outside the island lets the seabed continue the same local coast style
     * instead of switching to an unrelated ocean curve on the exact boundary.
     */
    private double coastRunUpWidthProfile(IslandSample sample) {
        double configured = settings.coastRunUpWidth();
        if (configured <= 0.0) {
            return 0.0;
        }

        double angle = Math.atan2(sample.dz(), sample.dx());
        long seed = sample.cellSeed();
        double phase2 = unit(seed ^ COAST_RUNUP_2_SALT) * Math.PI * 2.0;
        double phase3 = unit(seed ^ COAST_RUNUP_3_SALT) * Math.PI * 2.0;
        double phase7 = unit(seed ^ COAST_RUNUP_7_SALT) * Math.PI * 2.0;

        double profile =
                (0.54 * Math.sin((angle * 2.0) + phase2))
                        + (0.31 * Math.sin((angle * 3.0) + phase3))
                        + (0.15 * Math.sin((angle * 7.0) + phase7));
        double softness = smootherStep(clamp((profile + 0.58) / 1.16, 0.0, 1.0));
        return configured * (0.30 + (1.05 * softness));
    }

    /**
     * Nominal ocean floor used when no Vanilla column height is available (for example from the
     * biome provider). This remains the 1.4.1 dry-coast profile so ocean-biome region/depth
     * selection stays deterministic and backwards-compatible.
     */
    public int oceanFloorHeight(long worldSeed, int x, int z, IslandSample sample) {
        int deepFloor = deepOceanFloorHeight(worldSeed, x, z);

        if (sample.inside() || settings.oceanSlopeWidth() <= 0.0) {
            return deepFloor;
        }

        double distanceOutside = Math.max(0.0, -coastDistance(sample));
        double slopeWidth = oceanSlopeWidth(sample);
        if (distanceOutside >= slopeWidth) {
            return deepFloor;
        }

        double submergedShore = settings.seaLevel() - 2.0;
        double configuredRunUp = Math.max(1.0, settings.coastRunUpWidth());
        double localRunUpRatio = clamp(coastRunUpWidthProfile(sample) / configuredRunUp, 0.30, 1.35);
        double slopeNoise = ValueNoise.fbm(worldSeed ^ sample.cellSeed() ^ SLOPE_MID_SALT,
                x * 0.0105, z * 0.0105, 2);
        double initialSlope = clamp(0.66 - ((localRunUpRatio - 0.70) * 0.18)
                + (slopeNoise * 0.055), 0.48, 0.82);
        double coastContinuation = submergedShore - (distanceOutside * initialSlope);
        double normalized = clamp(distanceOutside / slopeWidth, 0.0, 1.0);
        double basinBlend = smootherStep(normalized);
        double middleVariation = slopeNoise * 0.22 * Math.sin(Math.PI * normalized);
        double floor = lerp(coastContinuation, deepFloor, basinBlend) + middleVariation;
        return (int) Math.round(Math.min(submergedShore, floor));
    }

    /**
     * Generation-time ocean floor with a wet-edge seabed handoff.
     *
     * <p>The stubborn underwater ridge was not a lack of smoothing: the nominal ocean profile
     * always climbed toward {@code seaLevel-2} near every island. When the island mask itself
     * ended under water, its real Vanilla seabed could be many blocks lower, leaving a raised
     * ocean berm around the wet boundary. During noise generation we still have the untouched
     * Vanilla column. That column is the same continuous terrain field used inside the island,
     * so it is the correct local reference for a submerged handoff.</p>
     *
     * <p>Deeply submerged columns therefore fade from their actual Vanilla floor (including the
     * normal island +1 offset) into the broad ocean basin. Dry/near-dry columns retain the exact
     * 1.4.1 profile. A short smooth wetness blend between the two avoids introducing a new
     * threshold contour.</p>
     */
    public int oceanFloorHeight(long worldSeed, int x, int z, IslandSample sample, int vanillaTopY) {
        int nominalFloor = oceanFloorHeight(worldSeed, x, z, sample);
        if (sample.inside() || settings.oceanSlopeWidth() <= 0.0
                || vanillaTopY == Integer.MIN_VALUE) {
            return nominalFloor;
        }

        double distanceOutside = Math.max(0.0, -coastDistance(sample));
        double slopeWidth = oceanSlopeWidth(sample);
        if (distanceOutside >= slopeWidth) {
            return nominalFloor;
        }

        // Match the island-side density offset so the same underlying Vanilla terrain field
        // meets itself at the mask instead of the ocean being pulled up toward sea level.
        double naturalFloor = vanillaTopY + settings.islandHeightOffset();
        double highestSubmergedFloor = settings.seaLevel() - 2.0;

        // A genuinely dry/near-dry coast remains byte-for-byte equivalent to the 1.4.1 formula.
        // Once the underlying Vanilla floor is ~3 blocks or more below that, use it fully as the
        // wet-edge anchor. The smooth middle avoids drawing another material/height contour.
        double wetness = smootherStep(clamp((highestSubmergedFloor - naturalFloor) / 3.0, 0.0, 1.0));
        if (wetness <= 0.0) {
            return nominalFloor;
        }

        naturalFloor = Math.min(highestSubmergedFloor, naturalFloor);
        int deepFloor = deepOceanFloorHeight(worldSeed, x, z);
        double normalized = clamp(distanceOutside / slopeWidth, 0.0, 1.0);
        double basinBlend = smootherStep(normalized);

        // Seabed-to-seabed: at a wet boundary the ocean starts at the actual local Vanilla floor
        // and only then fades into the deep basin. No sea-level target and no raised apron exist.
        double wetFloor = lerp(naturalFloor, deepFloor, basinBlend);
        if (deepFloor <= naturalFloor) {
            // Most coasts are above the basin. In that normal case, the wet transition is never
            // allowed to rise above its local anchor, which makes a boundary berm impossible.
            wetFloor = Math.min(naturalFloor, wetFloor);
        }

        double floor = lerp(nominalFloor, wetFloor, wetness);
        return (int) Math.round(Math.min(highestSubmergedFloor, floor));
    }

    private int deepOceanFloorHeight(long worldSeed, int x, int z) {
        double noise = ValueNoise.fbm(worldSeed ^ OCEAN_SALT, x * 0.0032, z * 0.0032, 3);
        return settings.oceanFloorY() + (int) Math.round(noise * settings.oceanFloorVariation());
    }

    private double oceanSlopeWidth(IslandSample sample) {
        double configured = settings.oceanSlopeWidth();
        if (configured <= 0.0) {
            return 0.0;
        }

        double angle = Math.atan2(sample.dz(), sample.dx());
        long seed = sample.cellSeed();
        double phase2 = unit(seed ^ SLOPE_2_SALT) * Math.PI * 2.0;
        double phase5 = unit(seed ^ SLOPE_5_SALT) * Math.PI * 2.0;
        double profile = (0.68 * Math.sin((angle * 2.0) + phase2))
                + (0.32 * Math.sin((angle * 5.0) + phase5));
        double variation = 0.72 + (0.58 * smootherStep(clamp((profile + 1.0) * 0.5, 0.0, 1.0)));
        return configured * variation;
    }

    /**
     * Select a broad ocean climate region using a warped Voronoi field. Region centers are
     * hundreds of blocks apart, so ocean biomes form large coherent areas rather than chunk-sized
     * patches or rings around islands.
     */
    public int oceanBiomeIndex(long worldSeed, int x, int z) {
        if (cumulativeOceanBiomeWeights.length <= 1) {
            return 0;
        }

        double regionSize = settings.oceanBiomeRegionSize();
        double warpScale = 1.0 / (regionSize * 1.65);
        double warpAmount = regionSize * 0.22;
        double warpedX = x + (ValueNoise.fbm(worldSeed ^ OCEAN_REGION_WARP_X_SALT,
                x * warpScale, z * warpScale, 3) * warpAmount);
        double warpedZ = z + (ValueNoise.fbm(worldSeed ^ OCEAN_REGION_WARP_Z_SALT,
                x * warpScale, z * warpScale, 3) * warpAmount);

        int baseX = (int) Math.floor(warpedX / regionSize);
        int baseZ = (int) Math.floor(warpedZ / regionSize);
        OceanRegionCandidate nearest = null;

        for (int rz = baseZ - 1; rz <= baseZ + 1; rz++) {
            for (int rx = baseX - 1; rx <= baseX + 1; rx++) {
                long seed = oceanRegionSeed(worldSeed, rx, rz);
                double centerX = (rx + 0.5) * regionSize
                        + signedUnit(seed ^ OCEAN_REGION_X_SALT) * regionSize * 0.32;
                double centerZ = (rz + 0.5) * regionSize
                        + signedUnit(seed ^ OCEAN_REGION_Z_SALT) * regionSize * 0.32;
                double dx = warpedX - centerX;
                double dz = warpedZ - centerZ;
                double distanceSquared = (dx * dx) + (dz * dz);
                if (nearest == null || distanceSquared < nearest.distanceSquared()) {
                    nearest = new OceanRegionCandidate(distanceSquared, seed);
                }
            }
        }

        if (nearest == null) {
            return 0;
        }

        double pick = unit(nearest.seed() ^ OCEAN_REGION_BIOME_SALT) * totalOceanBiomeWeight;
        return weightedIndex(pick, cumulativeOceanBiomeWeights);
    }

    public boolean useDeepOceanBiome(int floorY) {
        return (settings.seaLevel() - floorY) >= settings.deepOceanBiomeDepth();
    }

    public String biomeKey(IslandSample sample) {
        return settings.biomeKeys().get(sample.biomeIndex());
    }

    private int biomeIndex(long cellSeed) {
        if (cumulativeBiomeWeights.length <= 1) {
            return 0;
        }

        int picked = weightedIndex(unit(cellSeed ^ BIOME_SALT) * totalBiomeWeight,
                cumulativeBiomeWeights);

        if (settings.allowExtremeTerrainBiomes()
                || !IslandSettings.isExtremeTerrainBiome(settings.biomeKeys().get(picked))) {
            return picked;
        }

        double reroll = unit(cellSeed ^ EXTREME_REROLL_SALT) * totalSafeBiomeWeight;
        return weightedIndex(reroll, safeCumulativeBiomeWeights);
    }

    private static int weightedIndex(double pick, double[] cumulativeWeights) {
        for (int i = 0; i < cumulativeWeights.length; i++) {
            if (pick < cumulativeWeights[i]) {
                return i;
            }
        }
        return cumulativeWeights.length - 1;
    }

    private static long cellSeed(long worldSeed, int column, int row) {
        long value = worldSeed;
        value ^= (long) column * 0x632BE59BD9B4E019L;
        value ^= (long) row * 0x9E3779B97F4A7C15L;
        return ValueNoise.mix64(value);
    }

    private static long oceanRegionSeed(long worldSeed, int x, int z) {
        long value = worldSeed ^ OCEAN_REGION_CELL_SALT;
        value ^= (long) x * 0x632BE59BD9B4E019L;
        value ^= (long) z * 0x9E3779B97F4A7C15L;
        return ValueNoise.mix64(value);
    }

    private static double unit(long value) {
        long mixed = ValueNoise.mix64(value);
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static double signedUnit(long value) {
        return (unit(value) * 2.0) - 1.0;
    }

    private static int nearestInt(double value) {
        return (int) Math.floor(value + 0.5);
    }

    private static double smootherStep(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + ((b - a) * t);
    }

    private record Candidate(
            int column,
            int row,
            double centerX,
            double centerZ,
            double dx,
            double dz,
            double distanceSquared,
            long cellSeed
    ) {
    }

    private record OceanRegionCandidate(double distanceSquared, long seed) {
    }

    public record IslandSample(
            boolean inside,
            int column,
            int row,
            double centerX,
            double centerZ,
            double dx,
            double dz,
            double distance,
            double nominalRadius,
            double edgeDepth,
            int biomeIndex,
            long cellSeed
    ) {
    }
}
