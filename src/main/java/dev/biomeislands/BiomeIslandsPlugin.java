package dev.biomeislands;

import dev.biomeislands.command.BiomeIslandsCommands;
import dev.biomeislands.config.IslandSettings;
import dev.biomeislands.stats.GenerationStats;
import dev.biomeislands.world.IslandBiomeProvider;
import dev.biomeislands.world.IslandChunkGenerator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BiomeIslandsPlugin extends JavaPlugin {
    public static final String VERSION = "1.5.1";
    public static final String CREATOR = "Sfekke";

    private volatile IslandSettings settings;
    private volatile BiomeIslandsCommands commands;
    private volatile int validationWarningCount;
    private final ConcurrentHashMap<String, GenerationStats> statsByWorld = new ConcurrentHashMap<>();
    private final Set<String> generatorWorlds = ConcurrentHashMap.newKeySet();

    @Override
    public void onLoad() {
        saveDefaultConfig();
        this.settings = IslandSettings.from(getConfig());
    }

    @Override
    public void onEnable() {
        this.commands = new BiomeIslandsCommands(this);
        IslandSettings current = currentSettings();
        this.validationWarningCount = validateConfiguration(current);

        getLogger().info("BiomeIslands v" + VERSION + " by " + CREATOR + " enabled. Inspired by IslandCraft. "
                + current.enabledBiomeCount() + "/" + current.biomeKeys().size()
                + " island biomes enabled, " + current.enabledOceanBiomeFamilyCount()
                + " ocean climate families enabled, Vanilla terrain masking active, minimum geometric water gap ~"
                + String.format("%.1f", current.minimumWaterGap()) + " blocks.");
        getLogger().info("Admin QoL: /biomeislands (/bi) with info, status, here, biomes, stats and debug tools. "
                + "All command permissions default to operators.");
        if (validationWarningCount == 0) {
            getLogger().info("Configuration validation completed with no warnings.");
        } else {
            getLogger().warning("Configuration validation completed with " + validationWarningCount + " warning(s).");
        }
        if (current.legacyGeometryAdjusted()) {
            getLogger().info("Detected a pre-1.3 config. Legacy island dimensions are automatically scaled down "
                    + "for the newer organic-island layout; replace config.yml with current defaults to tune them directly.");
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        IslandSettings current = currentSettings();
        String key = normalizeWorldName(worldName);
        generatorWorlds.add(key);
        GenerationStats stats = statsByWorld.computeIfAbsent(key,
                ignored -> new GenerationStats(current.biomeKeys().size()));
        IslandBiomeProvider provider = new IslandBiomeProvider(current, getLogger());
        return new IslandChunkGenerator(current, provider, stats);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        if (worldName != null && !worldName.isBlank()) {
            generatorWorlds.add(worldName);
        }
        return new IslandBiomeProvider(currentSettings(), getLogger());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"biomeislands".equalsIgnoreCase(command.getName())) {
            return false;
        }
        BiomeIslandsCommands current = commands;
        if (current == null) {
            current = new BiomeIslandsCommands(this);
            commands = current;
        }
        return current.execute(sender, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!"biomeislands".equalsIgnoreCase(command.getName())) {
            return List.of();
        }
        BiomeIslandsCommands current = commands;
        if (current == null) {
            current = new BiomeIslandsCommands(this);
            commands = current;
        }
        return current.tabComplete(sender, args);
    }

    public IslandSettings currentSettings() {
        IslandSettings current = this.settings;
        if (current == null) {
            synchronized (this) {
                current = this.settings;
                if (current == null) {
                    saveDefaultConfig();
                    current = IslandSettings.from(getConfig());
                    this.settings = current;
                }
            }
        }
        return current;
    }

    public GenerationStats statsForWorld(String worldName) {
        return statsByWorld.get(normalizeWorldName(worldName));
    }

    public List<String> statWorldNames() {
        List<String> names = new ArrayList<>(statsByWorld.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    public boolean isGeneratorWorld(String worldName) {
        return generatorWorlds.contains(normalizeWorldName(worldName));
    }

    public int validationWarningCount() {
        return validationWarningCount;
    }

    private int validateConfiguration(IslandSettings current) {
        int warnings = 0;

        if (current.minimumWaterGap() < 24.0) {
            warnings += warn("islands.spacing/radius leave less than 24 blocks of guaranteed water gap ("
                    + String.format("%.1f", current.minimumWaterGap()) + "). Islands may visually crowd each other.");
        }
        if (current.enabledBiomeCount() < 2) {
            warnings += warn("Only " + current.enabledBiomeCount()
                    + " selectable island biome remains. This is valid, but world variety will be very low.");
        }
        if (current.enabledOceanBiomeFamilyCount() < 1) {
            warnings += warn("No selectable ocean climate family remained; the runtime fallback will be used.");
        }
        if (current.oceanFloorY() >= current.seaLevel() - 3) {
            warnings += warn("terrain.ocean-floor-y is too close to sea-level; the ocean may become extremely shallow.");
        }
        if (getConfig().getInt("islands.spacing", current.spacing()) < 160) {
            warnings += warn("islands.spacing is below the supported minimum and is being clamped to " + current.spacing() + ".");
        }
        if (getConfig().getDouble("islands.radius", current.radius()) < 40.0) {
            warnings += warn("islands.radius is below the supported minimum and is being clamped to " + current.radius() + ".");
        }
        if (getConfig().getDouble("terrain.ocean-slope-width", current.oceanSlopeWidth()) < 0.0) {
            warnings += warn("terrain.ocean-slope-width cannot be negative; the runtime value is clamped.");
        }

        return warnings;
    }

    private int warn(String message) {
        getLogger().warning("Config: " + message);
        return 1;
    }

    private static String normalizeWorldName(String worldName) {
        return worldName == null || worldName.isBlank() ? "<unknown>" : worldName;
    }
}
