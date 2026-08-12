# BiomeIslands 1.5.0

Created by **Sfekke**. Inspired by the classic **IslandCraft** Bukkit world generator.

BiomeIslands is a Paper/Spigot 26.2 world generator that keeps Minecraft's Vanilla terrain inside
organic biome-themed islands and turns the space between them into broad, varied oceans.

## 1.5.0: administration and diagnostics

1.5.0 is intentionally a quality-of-life release. The 1.4.2 island layout, shoreline geometry,
wet-edge seabed handoff, ocean climates, terrain relief and cave safeguards are unchanged.

### Commands

Primary command:

```text
/biomeislands
/bi
```

Subcommands:

```text
/bi                 Plugin, creator and IslandCraft inspiration
/bi version         Plugin version and target
/bi help            Permission-aware command help
/bi status          Active generator settings and validation status
/bi here            Island/ocean theme and coast information at your location
/bi biomes [page]   Enabled weighted island biomes
/bi biomes ocean    Enabled weighted ocean climate families
/bi stats           Local generation counters since server startup
/bi debug           Detailed layout diagnostics for the current position
```

`/bi stats` is local-only. Nothing is transmitted, written to a metrics service, or persisted. The
counters reset on server/plugin restart.

### Permissions

All command permissions default to `op`.

```text
biomeislands.admin
biomeislands.command.info
biomeislands.command.version
biomeislands.command.help
biomeislands.command.status
biomeislands.command.here
biomeislands.command.biomes
biomeislands.command.stats
biomeislands.command.debug
```

`biomeislands.admin` grants every command node. Individual nodes can be delegated separately.

### Startup validation

On enable, BiomeIslands now prints a concise configuration summary and checks for obviously risky or
clamped values such as very small water gaps, an ocean floor too close to sea level, or out-of-range
geometry values. Warnings are advisory and do not rewrite `config.yml`.

`/bi status` shows how many validation warnings were raised on startup.

## Biome weight recipes

The plugin does not impose biome-category switches. Weight `0` remains the simple way to disable an
individual biome, which keeps configuration explicit and predictable.

A few example approaches:

- **Current balanced defaults:** use the bundled config unchanged.
- **Temperate-heavy:** increase plains/forest/taiga/birch weights and lower desert/badlands/snow.
- **Rare exotics:** keep ordinary biomes around `1.0-1.5`, rare biomes around `0.05-0.30`.

These are recipes, not hidden presets; your configured weights remain the source of truth.

## Existing 1.4.2 generation behavior

The wet-edge seabed handoff remains unchanged: if the island boundary is already underwater, the
ocean transition anchors to the actual local Vanilla seabed rather than rising toward a fixed
near-sea-level shelf. Dry coast behavior is unchanged.

Broad ocean climate families remain enabled by default:

```yaml
ocean-settings:
  region-size: 640
  deep-biome-depth: 14
```

Island biome weights and ocean family weights are relative; `weight: 0` disables an entry.

## Multiverse

Create a fresh world with:

```text
/mv create Vanilla normal --generator BiomeIslands
```

Do not add `--biome BiomeIslands`; the generator supplies its own biome provider.

## Compatibility

The project targets Paper/Spigot 26.2. Biome registry lookup remains reflection-based, preserving
the compatibility fix that avoids direct bytecode linkage to `org.bukkit.Registry`.
