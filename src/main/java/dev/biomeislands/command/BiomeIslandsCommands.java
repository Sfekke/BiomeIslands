package dev.biomeislands.command;

import dev.biomeislands.BiomeIslandsPlugin;
import dev.biomeislands.config.IslandSettings;
import dev.biomeislands.stats.GenerationStats;
import dev.biomeislands.world.IslandLayout;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BiomeIslandsCommands {
    private static final int BIOMES_PER_PAGE = 8;

    private final BiomeIslandsPlugin plugin;

    public BiomeIslandsCommands(BiomeIslandsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean execute(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "info", "about" -> withPermission(sender, "biomeislands.command.info", () -> sendInfo(sender));
            case "version" -> withPermission(sender, "biomeislands.command.version", () -> sendVersion(sender));
            case "help", "?" -> withPermission(sender, "biomeislands.command.help", () -> sendHelp(sender));
            case "status" -> withPermission(sender, "biomeislands.command.status", () -> sendStatus(sender));
            case "here" -> withPermission(sender, "biomeislands.command.here", () -> sendHere(sender));
            case "biomes" -> withPermission(sender, "biomeislands.command.biomes", () -> sendBiomes(sender, args));
            case "stats" -> withPermission(sender, "biomeislands.command.stats", () -> sendStats(sender));
            case "debug" -> withPermission(sender, "biomeislands.command.debug", () -> sendDebug(sender));
            default -> {
                sender.sendMessage("[BiomeIslands] Unknown subcommand: " + sub + ". Use /bi help.");
                yield true;
            }
        };
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            addIfAllowed(result, sender, "info", "biomeislands.command.info", prefix);
            addIfAllowed(result, sender, "version", "biomeislands.command.version", prefix);
            addIfAllowed(result, sender, "help", "biomeislands.command.help", prefix);
            addIfAllowed(result, sender, "status", "biomeislands.command.status", prefix);
            addIfAllowed(result, sender, "here", "biomeislands.command.here", prefix);
            addIfAllowed(result, sender, "biomes", "biomeislands.command.biomes", prefix);
            addIfAllowed(result, sender, "stats", "biomeislands.command.stats", prefix);
            addIfAllowed(result, sender, "debug", "biomeislands.command.debug", prefix);
            return result;
        }
        if (args.length == 2 && "biomes".equalsIgnoreCase(args[0]) && sender.hasPermission("biomeislands.command.biomes")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            if ("ocean".startsWith(prefix)) values.add("ocean");
            for (int i = 1; i <= islandBiomePages(); i++) {
                String value = Integer.toString(i);
                if (value.startsWith(prefix)) values.add(value);
            }
            return values;
        }
        return List.of();
    }

    private void sendInfo(CommandSender sender) {
        sender.sendMessage("[BiomeIslands] BiomeIslands v" + BiomeIslandsPlugin.VERSION);
        sender.sendMessage("Creator: " + BiomeIslandsPlugin.CREATOR);
        sender.sendMessage("Inspired by: IslandCraft, the classic Bukkit island world generator.");
        sender.sendMessage("Purpose: organic Vanilla-terrain biome islands in broad, varied oceans.");
        sender.sendMessage("Target: Paper/Spigot 26.3 | /bi help for admin tools.");
    }

    private void sendVersion(CommandSender sender) {
        sender.sendMessage("[BiomeIslands] v" + BiomeIslandsPlugin.VERSION
                + " by " + BiomeIslandsPlugin.CREATOR + " | Paper/Spigot 26.3");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("[BiomeIslands] Admin commands (only permitted entries are shown):");
        help(sender, "biomeislands.command.info", "/bi", "plugin/creator information");
        help(sender, "biomeislands.command.version", "/bi version", "version information");
        help(sender, "biomeislands.command.status", "/bi status", "current generator configuration");
        help(sender, "biomeislands.command.here", "/bi here", "island/ocean information at your position");
        help(sender, "biomeislands.command.biomes", "/bi biomes [page|ocean]", "enabled weighted biomes");
        help(sender, "biomeislands.command.stats", "/bi stats", "local generation counters since startup");
        help(sender, "biomeislands.command.debug", "/bi debug", "detailed layout diagnostics at your position");
    }

    private void sendStatus(CommandSender sender) {
        IslandSettings s = plugin.currentSettings();
        sender.sendMessage("[BiomeIslands] Status - v" + BiomeIslandsPlugin.VERSION);
        sender.sendMessage("Islands: spacing " + s.spacing() + ", radius " + one(s.radius())
                + " +/- " + one(s.radiusVariation()) + ", minimum water gap ~" + one(s.minimumWaterGap()) + " blocks");
        sender.sendMessage("Ocean: sea level " + s.seaLevel() + " (water top Y" + (s.seaLevel() - 1)
                + "), basin Y" + s.oceanFloorY() + " +/- " + s.oceanFloorVariation()
                + ", " + s.enabledOceanBiomeFamilyCount() + " climate families");
        sender.sendMessage("Terrain: extreme relief " + onOff(s.tameExtremeRelief())
                + ", coastal shell " + onOff(s.protectCoastalShell())
                + ", extreme biome themes " + (s.allowExtremeTerrainBiomes() ? "allowed" : "excluded"));
        sender.sendMessage("Vanilla stages: caves " + onOff(s.caves()) + ", decorations " + onOff(s.decorations())
                + ", mobs " + onOff(s.mobs()) + ", structures " + onOff(s.structures()));
        sender.sendMessage("Biomes: " + s.enabledBiomeCount() + "/" + s.biomeKeys().size() + " island themes enabled");
        if (sender instanceof Player player) {
            String worldName = player.getWorld().getName();
            sender.sendMessage("World: " + worldName + " | generator observed this startup: "
                    + (plugin.isGeneratorWorld(worldName) ? "yes" : "no"));
        }
        int warnings = plugin.validationWarningCount();
        sender.sendMessage("Config validation: " + (warnings == 0 ? "no warnings" : warnings + " warning(s); see server log"));
    }

    private void sendHere(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        IslandSettings s = plugin.currentSettings();
        World world = player.getWorld();
        Location location = player.getLocation();
        int x = location.getBlockX();
        int z = location.getBlockZ();
        IslandLayout layout = new IslandLayout(s);
        IslandLayout.IslandSample sample = layout.sample(world.getSeed(), x, z);
        double coast = layout.coastDistance(sample);

        sender.sendMessage("[BiomeIslands] Here: " + world.getName() + " @ " + x + ", " + location.getBlockY() + ", " + z);
        if (!plugin.isGeneratorWorld(world.getName())) {
            sender.sendMessage("Note: this startup has not observed BiomeIslands as this world's generator; layout values are predictive only.");
        }
        if (sample.inside()) {
            String key = safeKey(s.biomeKeys(), sample.biomeIndex(), "minecraft:plains");
            sender.sendMessage("Zone: island | theme: " + key + " | island cell: " + sample.column() + "," + sample.row());
            sender.sendMessage("Coast distance: ~" + one(Math.max(0.0, coast)) + " blocks inland | center distance: " + one(sample.distance()));
        } else {
            int floorY = findTopSolidAtOrBelow(world, x, z, s.seaLevel() - 1);
            int oceanIndex = layout.oceanBiomeIndex(world.getSeed(), x, z);
            boolean deep = floorY != Integer.MIN_VALUE && layout.useDeepOceanBiome(floorY);
            String key = deep
                    ? safeKey(s.deepOceanBiomeKeys(), oceanIndex, s.oceanBiome())
                    : safeKey(s.oceanBiomeKeys(), oceanIndex, s.oceanBiome());
            sender.sendMessage("Zone: ocean | climate: " + key + " | seabed: "
                    + (floorY == Integer.MIN_VALUE ? "unknown" : "Y" + floorY));
            sender.sendMessage("Nearest island edge: ~" + one(Math.max(0.0, -coast)) + " blocks away");
        }
    }

    private void sendBiomes(CommandSender sender, String[] args) {
        IslandSettings s = plugin.currentSettings();
        if (args.length >= 2 && "ocean".equalsIgnoreCase(args[1])) {
            sender.sendMessage("[BiomeIslands] Ocean biome families:");
            double total = s.totalOceanBiomeWeight();
            for (int i = 0; i < s.oceanBiomeWeights().size(); i++) {
                double weight = s.oceanBiomeWeights().get(i);
                if (weight <= 0.0) continue;
                sender.sendMessage("- " + safeKey(s.oceanBiomeKeys(), i, "minecraft:ocean") + " -> "
                        + safeKey(s.deepOceanBiomeKeys(), i, "minecraft:deep_ocean")
                        + " | weight " + two(weight) + " (~" + percent(weight, total) + ")");
            }
            return;
        }

        List<WeightedBiome> enabled = enabledIslandBiomes(s);
        int pages = Math.max(1, (enabled.size() + BIOMES_PER_PAGE - 1) / BIOMES_PER_PAGE);
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage("[BiomeIslands] Use /bi biomes [page] or /bi biomes ocean.");
                return;
            }
        }
        page = Math.max(1, Math.min(pages, page));
        double total = enabled.stream().mapToDouble(WeightedBiome::weight).sum();
        sender.sendMessage("[BiomeIslands] Enabled island biomes - page " + page + "/" + pages
                + " (" + enabled.size() + " selectable)");
        int start = (page - 1) * BIOMES_PER_PAGE;
        int end = Math.min(enabled.size(), start + BIOMES_PER_PAGE);
        for (int i = start; i < end; i++) {
            WeightedBiome biome = enabled.get(i);
            sender.sendMessage("- " + biome.key() + " | weight " + two(biome.weight())
                    + " (~" + percent(biome.weight(), total) + ")");
        }
        if (!s.allowExtremeTerrainBiomes()) {
            sender.sendMessage("Extreme-terrain biome themes are excluded by configuration.");
        }
    }

    private void sendStats(CommandSender sender) {
        if (sender instanceof Player player) {
            sendWorldStats(sender, player.getWorld().getName());
            return;
        }
        List<String> worlds = plugin.statWorldNames();
        if (worlds.isEmpty()) {
            sender.sendMessage("[BiomeIslands] No generation activity recorded since startup.");
            return;
        }
        sender.sendMessage("[BiomeIslands] Local generation stats since startup:");
        for (String world : worlds) {
            GenerationStats stats = plugin.statsForWorld(world);
            if (stats == null) continue;
            GenerationStats.Snapshot snap = stats.snapshot();
            sender.sendMessage("- " + world + ": " + snap.generatedChunks() + " chunks, "
                    + snap.uniqueIslands() + " unique islands observed");
        }
        sender.sendMessage("These counters are local only and reset on restart; nothing is transmitted.");
    }

    private void sendWorldStats(CommandSender sender, String worldName) {
        GenerationStats stats = plugin.statsForWorld(worldName);
        if (stats == null) {
            sender.sendMessage("[BiomeIslands] No generator activity recorded for world '" + worldName + "' since startup.");
            return;
        }
        GenerationStats.Snapshot snap = stats.snapshot();
        sender.sendMessage("[BiomeIslands] " + worldName + " stats since startup: " + snap.generatedChunks()
                + " chunks, " + snap.uniqueIslands() + " unique islands observed.");

        long[] counts = snap.islandsByBiome();
        List<BiomeCount> top = new ArrayList<>();
        IslandSettings s = plugin.currentSettings();
        for (int i = 0; i < counts.length && i < s.biomeKeys().size(); i++) {
            if (counts[i] > 0) top.add(new BiomeCount(s.biomeKeys().get(i), counts[i]));
        }
        top.sort(Comparator.comparingLong(BiomeCount::count).reversed());
        int limit = Math.min(8, top.size());
        for (int i = 0; i < limit; i++) {
            BiomeCount entry = top.get(i);
            sender.sendMessage("- " + entry.key() + ": " + entry.count());
        }
        sender.sendMessage("Local-only counters; reset on restart and never sent anywhere.");
    }

    private void sendDebug(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        IslandSettings s = plugin.currentSettings();
        World world = player.getWorld();
        Location loc = player.getLocation();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        IslandLayout layout = new IslandLayout(s);
        IslandLayout.IslandSample sample = layout.sample(world.getSeed(), x, z);
        double coast = layout.coastDistance(sample);
        int actualSolid = findTopSolid(world, x, z);

        sender.sendMessage("[BiomeIslands] Debug @ " + x + "," + loc.getBlockY() + "," + z);
        sender.sendMessage("World seed: " + world.getSeed() + " | tracked generator world: " + plugin.isGeneratorWorld(world.getName()));
        sender.sendMessage("Cell: " + sample.column() + "," + sample.row() + " | cell seed: 0x" + Long.toHexString(sample.cellSeed()));
        sender.sendMessage("Center: " + one(sample.centerX()) + ", " + one(sample.centerZ())
                + " | radius: " + one(sample.nominalRadius()) + " | center distance: " + one(sample.distance()));
        sender.sendMessage("Inside: " + sample.inside() + " | edge depth: " + two(sample.edgeDepth())
                + " | signed coast distance: " + two(coast) + " | actual top solid: "
                + (actualSolid == Integer.MIN_VALUE ? "unknown" : "Y" + actualSolid));
        if (sample.inside()) {
            sender.sendMessage("Theme: " + safeKey(s.biomeKeys(), sample.biomeIndex(), "minecraft:plains")
                    + " | coast run-up width: " + two(layout.coastRunUpWidth(sample)));
        } else {
            int nominalFloor = layout.oceanFloorHeight(world.getSeed(), x, z, sample);
            int oceanIndex = layout.oceanBiomeIndex(world.getSeed(), x, z);
            sender.sendMessage("Ocean family: " + oceanIndex + " | nominal basin/coast floor: Y" + nominalFloor
                    + " | configured slope width: " + two(s.oceanSlopeWidth()));
        }
    }

    private int islandBiomePages() {
        int size = enabledIslandBiomes(plugin.currentSettings()).size();
        return Math.max(1, (size + BIOMES_PER_PAGE - 1) / BIOMES_PER_PAGE);
    }

    private static List<WeightedBiome> enabledIslandBiomes(IslandSettings s) {
        List<WeightedBiome> enabled = new ArrayList<>();
        for (int i = 0; i < s.biomeKeys().size() && i < s.biomeWeights().size(); i++) {
            String key = s.biomeKeys().get(i);
            double weight = s.biomeWeights().get(i);
            if (weight <= 0.0) continue;
            if (!s.allowExtremeTerrainBiomes() && IslandSettings.isExtremeTerrainBiome(key)) continue;
            enabled.add(new WeightedBiome(key, weight));
        }
        return enabled;
    }

    private static int findTopSolidAtOrBelow(World world, int x, int z, int startY) {
        int max = Math.min(startY, world.getMaxHeight() - 1);
        for (int y = max; y >= world.getMinHeight(); y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static int findTopSolid(World world, int x, int z) {
        for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage("[BiomeIslands] This subcommand must be used by a player in a world.");
        return null;
    }

    private static boolean withPermission(CommandSender sender, String permission, Runnable action) {
        if (!sender.hasPermission(permission) && !sender.hasPermission("biomeislands.admin")) {
            sender.sendMessage("[BiomeIslands] You do not have permission: " + permission);
            return true;
        }
        action.run();
        return true;
    }

    private static void help(CommandSender sender, String permission, String syntax, String description) {
        if (sender.hasPermission(permission) || sender.hasPermission("biomeislands.admin")) {
            sender.sendMessage(syntax + " - " + description);
        }
    }

    private static void addIfAllowed(List<String> output, CommandSender sender, String value, String permission, String prefix) {
        if ((sender.hasPermission(permission) || sender.hasPermission("biomeislands.admin")) && value.startsWith(prefix)) {
            output.add(value);
        }
    }

    private static String safeKey(List<String> values, int index, String fallback) {
        return index >= 0 && index < values.size() ? values.get(index) : fallback;
    }

    private static String onOff(boolean value) { return value ? "on" : "off"; }
    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String two(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String percent(double weight, double total) {
        if (total <= 0.0) return "0.0%";
        return String.format(Locale.ROOT, "%.1f%%", (weight / total) * 100.0);
    }

    private record WeightedBiome(String key, double weight) {}
    private record BiomeCount(String key, long count) {}
}
