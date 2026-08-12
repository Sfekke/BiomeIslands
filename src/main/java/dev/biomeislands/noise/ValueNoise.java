package dev.biomeislands.noise;

public final class ValueNoise {
    private ValueNoise() {
    }

    public static double fbm(long seed, double x, double z, int octaves) {
        double sum = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double amplitudeSum = 0.0;

        for (int octave = 0; octave < octaves; octave++) {
            sum += noise(seed + (0x9E3779B97F4A7C15L * octave), x * frequency, z * frequency) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return amplitudeSum == 0.0 ? 0.0 : sum / amplitudeSum;
    }

    public static double noise(long seed, double x, double z) {
        long x0 = fastFloor(x);
        long z0 = fastFloor(z);
        long x1 = x0 + 1;
        long z1 = z0 + 1;

        double tx = smooth(x - x0);
        double tz = smooth(z - z0);

        double a = lerp(hashToSignedUnit(seed, x0, z0), hashToSignedUnit(seed, x1, z0), tx);
        double b = lerp(hashToSignedUnit(seed, x0, z1), hashToSignedUnit(seed, x1, z1), tx);
        return lerp(a, b, tz);
    }

    public static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    private static double hashToSignedUnit(long seed, long x, long z) {
        long h = seed;
        h ^= x * 0x632BE59BD9B4E019L;
        h ^= z * 0x9E3779B97F4A7C15L;
        h = mix64(h);
        // Use 53 random bits, mapped to [-1, 1].
        double unit = (h >>> 11) * 0x1.0p-53;
        return (unit * 2.0) - 1.0;
    }

    private static long fastFloor(double value) {
        long i = (long) value;
        return value < i ? i - 1 : i;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - (2.0 * t));
    }

    private static double lerp(double a, double b, double t) {
        return a + ((b - a) * t);
    }
}
