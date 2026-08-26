package io.github.dfa1.lmdb.bench;

import java.nio.charset.StandardCharsets;

/// Deterministic key/value payloads shared by the benchmarks, matching the
/// shape of lmdbjava's own benchmark suite
/// (github.com/lmdbjava/benchmarks — `Common`/`CommonLmdbJava`) so results
/// are directly comparable to its published methodology: fixed-width,
/// zero-padded string keys (their `intKey=false` mode; `padKey`/
/// `STRING_KEY_LENGTH`) inserted in ascending order, and a fixed-size value.
final class BenchData {

    /// Matches lmdbjava's `Common.STRING_KEY_LENGTH`.
    static final int KEY_SIZE = 16;
    /// Matches lmdbjava's `Common.valSize` default (`@Param("100")`).
    static final int VALUE_SIZE = 100;

    /// `num` sequential keys, zero-padded exactly like lmdbjava's `padKey`
    /// (`Common.sequential=true`, its default) — both bindings insert and
    /// read keys in ascending order, enabling each one's append-mode
    /// fast path.
    static byte[][] keys(int num) {
        byte[][] out = new byte[num][];
        for (int i = 0; i < num; i++) {
            out[i] = ("%0" + KEY_SIZE + "d").formatted(i).getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    /// One fixed value, reused for every key — point-lookup/scan/write
    /// benchmarks care about per-call overhead, not value content (lmdbjava's
    /// own default, `valRandom=false`, is likewise not per-key-random content).
    static byte[] value() {
        return new byte[VALUE_SIZE];
    }

    /// Matches lmdbjava's `CommonLmdbJava.mapSize(int, int)` exactly: a 3.2x
    /// multiplier over the raw key+value payload, generous enough for B-tree
    /// overhead without the ad hoc per-entry-page guess a different formula
    /// would need re-tuning for every dataset size.
    static long mapSize(int num, int valSize) {
        return num * (long) valSize * 32L / 10L;
    }

    private BenchData() {
        // no instances
    }
}
