package dev.biomeislands.stats;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local, in-memory generation counters. Nothing is transmitted or persisted.
 * Counts reset whenever the server/plugin restarts.
 */
public final class GenerationStats {
    private final AtomicLong generatedChunks = new AtomicLong();
    private final Set<Long> uniqueIslandSeeds = ConcurrentHashMap.newKeySet();
    private final AtomicLong[] islandsByBiome;

    public GenerationStats(int biomeCount) {
        this.islandsByBiome = new AtomicLong[Math.max(1, biomeCount)];
        for (int i = 0; i < this.islandsByBiome.length; i++) {
            this.islandsByBiome[i] = new AtomicLong();
        }
    }

    public void observeChunk() {
        generatedChunks.incrementAndGet();
    }

    public void observeIsland(long islandSeed, int biomeIndex) {
        if (!uniqueIslandSeeds.add(islandSeed)) {
            return;
        }
        if (biomeIndex >= 0 && biomeIndex < islandsByBiome.length) {
            islandsByBiome[biomeIndex].incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        long[] byBiome = new long[islandsByBiome.length];
        for (int i = 0; i < islandsByBiome.length; i++) {
            byBiome[i] = islandsByBiome[i].get();
        }
        return new Snapshot(generatedChunks.get(), uniqueIslandSeeds.size(), byBiome);
    }

    public record Snapshot(long generatedChunks, long uniqueIslands, long[] islandsByBiome) {
        public Snapshot {
            islandsByBiome = Arrays.copyOf(islandsByBiome, islandsByBiome.length);
        }

        @Override
        public long[] islandsByBiome() {
            return Arrays.copyOf(islandsByBiome, islandsByBiome.length);
        }
    }
}
