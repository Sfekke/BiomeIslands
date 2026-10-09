package dev.biomeislands.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record IslandSettings(
        String oceanBiome,
        List<String> oceanBiomeKeys,
        List<String> deepOceanBiomeKeys,
        List<Double> oceanBiomeWeights,
        double oceanBiomeRegionSize,
        int deepOceanBiomeDepth,
        List<String> biomeKeys,
        List<Double> biomeWeights,
        boolean allowExtremeTerrainBiomes,
        int seaLevel,
        int spacing,
        double radius,
        double radiusVariation,
        double centerJitter,
        double coastWidth,
        double shapeNoiseScale,
        int islandHeightOffset,
        boolean tameExtremeRelief,
        int maxCoastalRelief,
        boolean protectCoastalShell,
        double coastalShellWidth,
        double coastRunUpWidth,
        int oceanFloorY,
        int oceanFloorVariation,
        double oceanSlopeWidth,
        boolean caves,
        boolean decorations,
        boolean mobs,
        boolean structures,
        boolean legacyGeometryAdjusted
) {
    private static final String FALLBACK_BIOME = "minecraft:plains";
    private static final String DEFAULT_OCEAN_BIOME = "minecraft:deep_ocean";

    public static IslandSettings from(FileConfiguration config) {
        String legacyOceanBiome = normalizeBiomeKey(config.getString("ocean-biome", DEFAULT_OCEAN_BIOME));
        if (legacyOceanBiome.isEmpty()) {
            legacyOceanBiome = DEFAULT_OCEAN_BIOME;
        }

        OceanBiomeConfig oceanBiomeConfig = readOceanBiomes(config, legacyOceanBiome);
        double oceanBiomeRegionSize = Math.max(128.0,
                config.getDouble("ocean-settings.region-size", 640.0));
        int deepOceanBiomeDepth = clamp(
                config.getInt("ocean-settings.deep-biome-depth", 14), 6, 48);

        boolean allowExtremeTerrainBiomes = config.getBoolean(
                "biome-settings.allow-extreme-terrain-biomes", false);

        BiomeConfig biomeConfig = readBiomes(config, allowExtremeTerrainBiomes);
        List<String> biomeKeys = biomeConfig.keys();
        List<Double> biomeWeights = biomeConfig.weights();

        int seaLevel = config.getInt("sea-level", 63);
        String configVersion = config.getString("config-version", "");
        boolean modern13Plus = configVersion != null
                && (configVersion.startsWith("1.3") || configVersion.startsWith("1.4") || configVersion.startsWith("1.5"));
        boolean pre13Geometry = !modern13Plus;
        boolean v130Defaults = "1.3.0".equals(configVersion)
                && config.getInt("islands.spacing", 272) == 272
                && close(config.getDouble("islands.radius", 88.0), 88.0)
                && close(config.getDouble("islands.radius-variation", 18.0), 18.0)
                && close(config.getDouble("islands.center-jitter", 14.0), 14.0);

        // Pre-1.3 configs used the old large radius model. Keep the existing migration.
        // A stock 1.3.0 config is also migrated to the denser 1.3.1 defaults so replacing
        // the JAR is enough to get the newer IslandCraft-like spacing without deleting config.yml.
        double legacySpacingScale = pre13Geometry ? 0.71 : 1.0;
        double legacyRadiusScale = pre13Geometry ? 0.65 : 1.0;
        double legacyVariationScale = pre13Geometry ? 0.55 : 1.0;
        double legacyCoastScale = pre13Geometry ? 0.34 : 1.0;
        double legacyShelfScale = pre13Geometry ? 0.45 : 1.0;

        int spacing = v130Defaults ? 240 : Math.max(160, (int) Math.round(
                config.getInt("islands.spacing", 240) * legacySpacingScale));
        double radius = v130Defaults ? 100.0 : Math.max(40.0,
                config.getDouble("islands.radius", 100.0) * legacyRadiusScale);
        double radiusVariation = v130Defaults ? 6.0 : Math.max(0.0,
                config.getDouble("islands.radius-variation", 6.0) * legacyVariationScale);
        double centerJitter = v130Defaults ? 0.0 : Math.max(0.0,
                config.getDouble("islands.center-jitter", 0.0));
        double coastWidth = Math.max(4.0,
                config.getDouble("islands.coast-width", 10.0) * legacyCoastScale);
        double shapeNoiseScale = positive(
                config.getDouble("islands.shape-noise-scale", 0.018), 0.018);

        int islandHeightOffset = clamp(config.getInt("terrain.island-height-offset", 1), 0, 8);
        boolean tameExtremeRelief = config.getBoolean("terrain.tame-extreme-relief", true);
        int maxCoastalRelief = clamp(config.getInt("terrain.max-coastal-relief", 24), 8, 96);
        boolean protectCoastalShell = config.getBoolean("terrain.protect-coastal-shell", true);
        double coastalShellWidth = Math.max(0.0,
                config.getDouble("terrain.coastal-shell-width", 12.0));
        double coastRunUpWidth = Math.max(0.0,
                config.getDouble("terrain.coast-run-up-width", 16.0));
        int oceanFloorY = config.getInt("terrain.ocean-floor-y", 44);
        int oceanFloorVariation = Math.max(0,
                config.getInt("terrain.ocean-floor-variation", 4));

        // 1.4 replaces the old raised shallow shelf with a continuously descending ocean-side
        // slope. Existing 1.3.x configs do not have ocean-slope-width, so interpret their old
        // shallow-shelf width as roughly half of the new transition distance. This lets users
        // replace the JAR without deleting config.yml and immediately removes the terrace/ridge.
        double oldShelfWidth = Math.max(0.0,
                config.getDouble("terrain.shallow-shelf-width", 16.0) * legacyShelfScale);
        double defaultSlopeWidth = modern13Plus && !configVersion.startsWith("1.4")
                ? Math.max(24.0, oldShelfWidth * 2.0)
                : 32.0;
        double oceanSlopeWidth = Math.max(0.0,
                config.getDouble("terrain.ocean-slope-width", defaultSlopeWidth));

        // Keep a hard water gap even after per-cell center jitter. The sqrt(2) term bounds
        // how far a center can move toward a neighbour when both X and Z jitter cooperate.
        double worstCaseCenterShift = 2.0 * Math.sqrt(2.0) * centerJitter;
        double maxSafeRadius = Math.max(40.0, (spacing - worstCaseCenterShift - 24.0) / 2.0);
        if (radius + radiusVariation > maxSafeRadius) {
            radiusVariation = Math.max(0.0, maxSafeRadius - radius);
            if (radius > maxSafeRadius) {
                radius = maxSafeRadius;
                radiusVariation = 0.0;
            }
        }
        coastWidth = Math.min(coastWidth, Math.max(4.0, radius * 0.28));
        coastRunUpWidth = Math.min(coastRunUpWidth, Math.max(0.0, radius * 0.34));
        coastalShellWidth = Math.min(coastalShellWidth, Math.max(0.0, radius * 0.24));
        oceanSlopeWidth = Math.min(oceanSlopeWidth, Math.max(0.0, spacing * 0.26));

        return new IslandSettings(
                legacyOceanBiome,
                oceanBiomeConfig.shallowKeys(),
                oceanBiomeConfig.deepKeys(),
                oceanBiomeConfig.weights(),
                oceanBiomeRegionSize,
                deepOceanBiomeDepth,
                biomeKeys,
                biomeWeights,
                allowExtremeTerrainBiomes,
                seaLevel,
                spacing,
                radius,
                radiusVariation,
                centerJitter,
                coastWidth,
                shapeNoiseScale,
                islandHeightOffset,
                tameExtremeRelief,
                maxCoastalRelief,
                protectCoastalShell,
                coastalShellWidth,
                coastRunUpWidth,
                oceanFloorY,
                oceanFloorVariation,
                oceanSlopeWidth,
                config.getBoolean("vanilla-features.caves", true),
                config.getBoolean("vanilla-features.decorations", true),
                config.getBoolean("vanilla-features.mobs", true),
                config.getBoolean("vanilla-features.structures", true),
                pre13Geometry
        );
    }

    /**
     * Read broad ocean climate families. Each entry has a shallow biome and, where Vanilla has
     * one, a deep counterpart. Region selection is weighted; depth only chooses which counterpart
     * is used inside the already-selected climate region.
     *
     * <p>Backward compatibility: if an old config still has only the stock
     * {@code ocean-biome: minecraft:deep_ocean}, 1.4 enables the new diverse defaults. If the
     * user explicitly chose a different legacy ocean biome, that custom single-biome ocean is
     * preserved until they add an {@code ocean-biomes} list.</p>
     */
    private static OceanBiomeConfig readOceanBiomes(FileConfiguration config, String legacyOceanBiome) {
        List<?> raw = config.getList("ocean-biomes");
        LinkedHashMap<String, OceanBiomeEntry> merged = new LinkedHashMap<>();

        if (raw != null) {
            for (Object entry : raw) {
                if (entry instanceof String rawKey) {
                    String shallow = normalizeBiomeKey(rawKey);
                    if (!shallow.isEmpty()) {
                        addOceanEntry(merged, shallow, shallow, defaultOceanBiomeWeight(shallow));
                    }
                    continue;
                }

                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }

                Object biomeValue = map.containsKey("biome") ? map.get("biome")
                        : map.containsKey("shallow-biome") ? map.get("shallow-biome")
                        : map.containsKey("key") ? map.get("key")
                        : map.get("name");
                if (biomeValue == null) {
                    continue;
                }

                String shallow = normalizeBiomeKey(String.valueOf(biomeValue));
                if (shallow.isEmpty()) {
                    continue;
                }

                Object deepValue = map.containsKey("deep-biome") ? map.get("deep-biome")
                        : map.get("deep");
                String deep = deepValue == null
                        ? defaultDeepOceanBiome(shallow)
                        : normalizeBiomeKey(String.valueOf(deepValue));
                if (deep.isEmpty()) {
                    deep = shallow;
                }

                double weight = parseWeight(map.get("weight"), defaultOceanBiomeWeight(shallow));
                addOceanEntry(merged, shallow, deep, weight);
            }
        }

        if (merged.isEmpty()) {
            if (!DEFAULT_OCEAN_BIOME.equals(legacyOceanBiome)) {
                // Respect an explicit pre-1.4 custom ocean-biome choice.
                addOceanEntry(merged, legacyOceanBiome, legacyOceanBiome, 1.0);
            } else {
                addOceanEntry(merged, "minecraft:ocean", "minecraft:deep_ocean", 1.45);
                addOceanEntry(merged, "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", 0.80);
                addOceanEntry(merged, "minecraft:cold_ocean", "minecraft:deep_cold_ocean", 0.80);
                addOceanEntry(merged, "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", 0.35);
                // Vanilla has no deep warm-ocean counterpart, so warm remains warm at depth.
                addOceanEntry(merged, "minecraft:warm_ocean", "minecraft:warm_ocean", 0.40);
            }
        }

        boolean anyPositive = merged.values().stream().anyMatch(entry -> entry.weight() > 0.0);
        if (!anyPositive) {
            merged.put("minecraft:ocean|minecraft:deep_ocean",
                    new OceanBiomeEntry("minecraft:ocean", "minecraft:deep_ocean", 1.0));
        }

        List<String> shallow = new ArrayList<>(merged.size());
        List<String> deep = new ArrayList<>(merged.size());
        List<Double> weights = new ArrayList<>(merged.size());
        for (OceanBiomeEntry entry : merged.values()) {
            shallow.add(entry.shallowKey());
            deep.add(entry.deepKey());
            weights.add(clamp(entry.weight(), 0.0, 10000.0));
        }
        return new OceanBiomeConfig(List.copyOf(shallow), List.copyOf(deep), List.copyOf(weights));
    }

    private static void addOceanEntry(
            LinkedHashMap<String, OceanBiomeEntry> merged,
            String shallow,
            String deep,
            double weight
    ) {
        String pairKey = shallow + "|" + deep;
        OceanBiomeEntry existing = merged.get(pairKey);
        double combined = clamp(weight, 0.0, 10000.0)
                + (existing == null ? 0.0 : existing.weight());
        merged.put(pairKey, new OceanBiomeEntry(shallow, deep, clamp(combined, 0.0, 10000.0)));
    }

    /**
     * Reads both the 1.3.4 weighted format and the old list-of-strings format.
     *
     * <p>Old string entries remain valid and receive sensible built-in default weights, so
     * merely replacing the JAR already makes visually dominant rare biomes (especially the
     * badlands family) less common. A map entry can override that weight; weight 0 disables it.</p>
     */
    private static BiomeConfig readBiomes(FileConfiguration config, boolean allowExtremeTerrainBiomes) {
        List<?> raw = config.getList("biomes");
        LinkedHashMap<String, Double> merged = new LinkedHashMap<>();

        if (raw != null) {
            for (Object entry : raw) {
                if (entry instanceof String rawKey) {
                    String key = normalizeBiomeKey(rawKey);
                    if (!key.isEmpty()) {
                        merged.merge(key, defaultBiomeWeight(key), Double::sum);
                    }
                    continue;
                }

                if (entry instanceof Map<?, ?> map) {
                    Object biomeValue = map.containsKey("biome") ? map.get("biome")
                            : map.containsKey("key") ? map.get("key")
                            : map.get("name");
                    if (biomeValue == null) {
                        continue;
                    }

                    String key = normalizeBiomeKey(String.valueOf(biomeValue));
                    if (key.isEmpty()) {
                        continue;
                    }
                    double weight = parseWeight(map.get("weight"), defaultBiomeWeight(key));
                    merged.merge(key, weight, Double::sum);
                }
            }
        }

        if (merged.isEmpty()) {
            merged.put(FALLBACK_BIOME, defaultBiomeWeight(FALLBACK_BIOME));
        }

        // IMPORTANT: do not zero extreme-biome weights here. IslandLayout keeps the original
        // weighted intervals stable and only rerolls a cell when its first pick is an excluded
        // extreme biome. Zeroing them before building the cumulative distribution (1.3.6)
        // renormalized every interval and therefore reassigned the biome of nearly every island.
        boolean anySelectable = merged.entrySet().stream().anyMatch(entry ->
                entry.getValue() > 0.0
                        && (allowExtremeTerrainBiomes || !isExtremeTerrainBiome(entry.getKey())));
        if (!anySelectable) {
            // A world with every selectable biome disabled cannot pick an island theme. Keep the
            // configured entries intact and add a safe runtime plains interval.
            merged.merge(FALLBACK_BIOME, 1.0, Math::max);
        }

        List<String> keys = new ArrayList<>(merged.size());
        List<Double> weights = new ArrayList<>(merged.size());
        for (Map.Entry<String, Double> entry : merged.entrySet()) {
            keys.add(entry.getKey());
            weights.add(clamp(entry.getValue(), 0.0, 10000.0));
        }
        return new BiomeConfig(List.copyOf(keys), List.copyOf(weights));
    }

    /**
     * Biomes whose Vanilla terrain commonly produces mountain-scale relief or extreme-hills
     * cliffs that are visually awkward on a small isolated island. The global config switch
     * only affects selection weight; it does not alter terrain generation for any biome.
     */
    public static boolean isExtremeTerrainBiome(String rawKey) {
        String key = normalizeBiomeKey(rawKey);
        String path = key.substring(key.indexOf(':') + 1);
        return switch (path) {
            case "windswept_hills",
                    "windswept_forest",
                    "windswept_gravelly_hills",
                    "windswept_savanna",
                    "snowy_slopes",
                    "frozen_peaks",
                    "jagged_peaks",
                    "stony_peaks" -> true;
            default -> false;
        };
    }

    private static String normalizeBiomeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }
        return key.contains(":") ? key : "minecraft:" + key;
    }

    private static double parseWeight(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return clamp(number.doubleValue(), 0.0, 10000.0);
        }
        try {
            return clamp(Double.parseDouble(String.valueOf(raw).trim()), 0.0, 10000.0);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Relative generation weights used when an old config still contains simple strings.
     * The exact values are intentionally broad categories, not claims about Vanilla rarity.
     */
    public static double defaultBiomeWeight(String rawKey) {
        String key = normalizeBiomeKey(rawKey);
        String path = key.substring(key.indexOf(':') + 1);
        return switch (path) {
            case "plains" -> 1.60;
            case "sunflower_plains" -> 0.55;
            case "forest" -> 1.40;
            case "dappled_forest" -> 0.80;
            case "flower_forest" -> 0.55;
            case "birch_forest" -> 0.80;
            case "old_growth_birch_forest" -> 0.50;
            case "dark_forest" -> 0.75;
            case "pale_garden" -> 0.25;
            case "taiga" -> 1.10;
            case "snowy_taiga" -> 0.80;
            case "old_growth_pine_taiga", "old_growth_spruce_taiga" -> 0.50;
            case "jungle" -> 0.80;
            case "sparse_jungle" -> 0.55;
            case "bamboo_jungle" -> 0.35;
            case "swamp" -> 0.65;
            case "mangrove_swamp" -> 0.35;
            case "desert" -> 1.00;
            case "savanna" -> 0.85;
            case "savanna_plateau" -> 0.45;
            case "windswept_savanna" -> 0.25;
            case "badlands" -> 0.20;
            case "wooded_badlands" -> 0.14;
            case "eroded_badlands" -> 0.12;
            case "snowy_plains" -> 0.80;
            case "ice_spikes" -> 0.18;
            case "meadow" -> 0.50;
            case "grove" -> 0.45;
            case "snowy_slopes" -> 0.35;
            case "frozen_peaks", "jagged_peaks", "stony_peaks" -> 0.22;
            case "windswept_hills" -> 0.40;
            case "windswept_forest" -> 0.35;
            case "windswept_gravelly_hills" -> 0.22;
            case "cherry_grove" -> 0.35;
            case "mushroom_fields" -> 0.08;
            default -> 1.00;
        };
    }

    public static double defaultOceanBiomeWeight(String rawKey) {
        String key = normalizeBiomeKey(rawKey);
        String path = key.substring(key.indexOf(':') + 1);
        return switch (path) {
            case "ocean", "deep_ocean" -> 1.45;
            case "lukewarm_ocean", "deep_lukewarm_ocean" -> 0.80;
            case "cold_ocean", "deep_cold_ocean" -> 0.80;
            case "frozen_ocean", "deep_frozen_ocean" -> 0.35;
            case "warm_ocean" -> 0.40;
            default -> 1.00;
        };
    }

    public static String defaultDeepOceanBiome(String rawKey) {
        String key = normalizeBiomeKey(rawKey);
        String path = key.substring(key.indexOf(':') + 1);
        return switch (path) {
            case "ocean" -> "minecraft:deep_ocean";
            case "lukewarm_ocean" -> "minecraft:deep_lukewarm_ocean";
            case "cold_ocean" -> "minecraft:deep_cold_ocean";
            case "frozen_ocean" -> "minecraft:deep_frozen_ocean";
            default -> key;
        };
    }

    public int enabledBiomeCount() {
        int enabled = 0;
        for (int i = 0; i < biomeWeights.size(); i++) {
            if (biomeWeights.get(i) > 0.0
                    && (allowExtremeTerrainBiomes || !isExtremeTerrainBiome(biomeKeys.get(i)))) {
                enabled++;
            }
        }
        return enabled;
    }

    public int enabledOceanBiomeFamilyCount() {
        int enabled = 0;
        for (double weight : oceanBiomeWeights) {
            if (weight > 0.0) {
                enabled++;
            }
        }
        return enabled;
    }

    public double totalBiomeWeight() {
        double total = 0.0;
        for (double weight : biomeWeights) {
            total += Math.max(0.0, weight);
        }
        return total;
    }

    public double totalOceanBiomeWeight() {
        double total = 0.0;
        for (double weight : oceanBiomeWeights) {
            total += Math.max(0.0, weight);
        }
        return total;
    }

    public double maximumRadius() {
        return radius + radiusVariation;
    }

    public double minimumWaterGap() {
        double worstCaseCenterShift = 2.0 * Math.sqrt(2.0) * centerJitter;
        return spacing - worstCaseCenterShift - (2.0 * maximumRadius());
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 1.0e-9;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double positive(double value, double fallback) {
        return value > 0.0 ? value : fallback;
    }

    private record BiomeConfig(List<String> keys, List<Double> weights) {
    }

    private record OceanBiomeEntry(String shallowKey, String deepKey, double weight) {
    }

    private record OceanBiomeConfig(
            List<String> shallowKeys,
            List<String> deepKeys,
            List<Double> weights
    ) {
    }
}
