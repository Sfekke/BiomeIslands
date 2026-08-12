package dev.biomeislands.world;

import dev.biomeislands.config.IslandSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class IslandBiomeProvider extends BiomeProvider {
    private final IslandSettings settings;
    private final IslandLayout layout;
    private final Biome fallbackOceanBiome;
    private final Biome beachBiome;
    private final Biome snowyBeachBiome;
    private final Biome stonyShoreBiome;
    private final List<Biome> oceanBiomes;
    private final List<Biome> deepOceanBiomes;
    private final List<Biome> islandBiomes;
    private final List<Biome> advertisedBiomes;

    public IslandBiomeProvider(IslandSettings settings, Logger logger) {
        this.settings = settings;
        this.layout = new IslandLayout(settings);
        this.fallbackOceanBiome = resolve("minecraft:deep_ocean", logger, true);
        this.beachBiome = resolveOr("minecraft:beach", fallbackOceanBiome, logger);
        this.snowyBeachBiome = resolveOr("minecraft:snowy_beach", beachBiome, logger);
        this.stonyShoreBiome = resolveOr("minecraft:stony_shore", beachBiome, logger);

        List<Biome> resolvedOceans = new ArrayList<>();
        List<Biome> resolvedDeepOceans = new ArrayList<>();
        for (int i = 0; i < settings.oceanBiomeKeys().size(); i++) {
            double weight = settings.oceanBiomeWeights().get(i);
            if (weight <= 0.0) {
                resolvedOceans.add(fallbackOceanBiome);
                resolvedDeepOceans.add(fallbackOceanBiome);
                continue;
            }

            String shallowKey = settings.oceanBiomeKeys().get(i);
            String deepKey = settings.deepOceanBiomeKeys().get(i);
            Biome shallow = resolve(shallowKey, logger, false);
            if (shallow == null) {
                logger.warning("Replacing unknown ocean biome '" + shallowKey
                        + "' with minecraft:deep_ocean while preserving region ordering.");
                shallow = fallbackOceanBiome;
            }
            Biome deep = resolve(deepKey, logger, false);
            if (deep == null) {
                logger.warning("Replacing unknown deep ocean biome '" + deepKey
                        + "' with its shallow counterpart '" + shallowKey + "'.");
                deep = shallow;
            }
            resolvedOceans.add(shallow);
            resolvedDeepOceans.add(deep);
        }
        this.oceanBiomes = List.copyOf(resolvedOceans);
        this.deepOceanBiomes = List.copyOf(resolvedDeepOceans);

        Biome plains = resolve("minecraft:plains", logger, true);
        List<Biome> resolved = new ArrayList<>();
        for (int i = 0; i < settings.biomeKeys().size(); i++) {
            String key = settings.biomeKeys().get(i);
            double weight = settings.biomeWeights().get(i);
            if (weight <= 0.0) {
                resolved.add(plains);
                continue;
            }

            Biome biome = resolve(key, logger, false);
            if (biome == null) {
                logger.warning("Replacing unknown island biome '" + key
                        + "' with minecraft:plains to preserve deterministic island ordering.");
                biome = plains;
            }
            resolved.add(biome);
        }
        if (resolved.isEmpty()) {
            resolved.add(plains);
        }
        this.islandBiomes = List.copyOf(resolved);

        Set<Biome> all = new LinkedHashSet<>();
        for (int i = 0; i < oceanBiomes.size(); i++) {
            if (settings.oceanBiomeWeights().get(i) > 0.0) {
                all.add(oceanBiomes.get(i));
                all.add(deepOceanBiomes.get(i));
            }
        }
        all.add(beachBiome);
        all.add(snowyBeachBiome);
        all.add(stonyShoreBiome);
        for (int i = 0; i < islandBiomes.size(); i++) {
            if (settings.biomeWeights().get(i) > 0.0) {
                all.add(islandBiomes.get(i));
            }
        }
        this.advertisedBiomes = Collections.unmodifiableList(new ArrayList<>(all));
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        long seed = worldInfo.getSeed();
        IslandLayout.IslandSample sample = layout.sample(seed, x, z);
        if (!sample.inside()) {
            int oceanIndex = layout.oceanBiomeIndex(seed, x, z);
            int floorY = layout.oceanFloorHeight(seed, x, z, sample);
            return layout.useDeepOceanBiome(floorY)
                    ? deepOceanBiomes.get(oceanIndex)
                    : oceanBiomes.get(oceanIndex);
        }

        if (sample.edgeDepth() < settings.coastWidth() * 0.82) {
            return coastBiome(settings.biomeKeys().get(sample.biomeIndex()), sample.biomeIndex());
        }

        return islandBiomes.get(sample.biomeIndex());
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return advertisedBiomes;
    }

    private Biome coastBiome(String islandKey, int biomeIndex) {
        String key = biomePath(islandKey);
        if (key.contains("swamp") || key.contains("badlands")) {
            return islandBiomes.get(biomeIndex);
        }
        if (key.contains("snow") || key.contains("frozen") || key.equals("grove") || key.equals("ice_spikes")) {
            return snowyBeachBiome;
        }
        if (key.contains("peak") || key.contains("windswept") || key.equals("stony_peaks")) {
            return stonyShoreBiome;
        }
        return beachBiome;
    }

    private static String biomePath(String raw) {
        String key = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(colon + 1) : key;
    }

    private static Biome resolveOr(String key, Biome fallback, Logger logger) {
        Biome biome = resolve(key, logger, false);
        return biome == null ? fallback : biome;
    }

    private static Biome resolve(String rawKey, Logger logger, boolean required) {
        NamespacedKey key = NamespacedKey.fromString(rawKey);
        Biome biome = key == null ? null : findBiome(key);
        if (biome == null && required) {
            if (!"minecraft:deep_ocean".equals(rawKey)) {
                logger.warning("Invalid required biome '" + rawKey + "'; falling back to minecraft:deep_ocean.");
            }
            NamespacedKey fallback = NamespacedKey.minecraft("deep_ocean");
            biome = findBiome(fallback);
            if (biome == null) {
                throw new IllegalStateException("Required biome minecraft:deep_ocean is not present in Registry.BIOME");
            }
        }
        return biome;
    }

    /**
     * Resolve a biome without linking bytecode directly against org.bukkit.Registry.
     * This preserves the 1.2.1 fix for 26.2 Registry class/interface ABI changes.
     */
    private static Biome findBiome(NamespacedKey key) {
        try {
            Class<?> registryType = Class.forName("org.bukkit.Registry", false,
                    IslandBiomeProvider.class.getClassLoader());
            Object biomeRegistry = registryType.getField("BIOME").get(null);
            if (biomeRegistry == null) {
                return null;
            }

            Object value = registryType.getMethod("get", NamespacedKey.class)
                    .invoke(biomeRegistry, key);
            if (value instanceof Biome biome) {
                return biome;
            }

            if (biomeRegistry instanceof Iterable<?> iterable) {
                for (Object candidate : iterable) {
                    if (candidate instanceof Biome biome && key.equals(biome.getKey())) {
                        return biome;
                    }
                }
            }
            return null;
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalStateException("Unable to access Bukkit biome registry", ex);
        }
    }
}
