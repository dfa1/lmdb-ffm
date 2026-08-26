package io.github.dfa1.lmdb.bench;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/// Deterministic key/value payloads shared by the benchmarks: fixed-width,
/// zero-padded numeric keys (so both LMDB bindings sort them identically) and
/// a fixed-size value, sized like a small OLTP row rather than a compression
/// payload.
final class BenchData {

    /// LMDB's default max key size is 511 bytes; this is comfortably small,
    /// like a typical primary-key lookup.
    static final int KEY_SIZE = 16;
    static final int VALUE_SIZE = 100;

    /// Deterministic `entries` keys, zero-padded so lexicographic byte order
    /// matches numeric order (both bindings iterate LMDB's own sorted keys, so
    /// this only affects how readable the sequence is, not correctness).
    static byte[][] keys(int entries) {
        byte[][] out = new byte[entries][];
        for (int i = 0; i < entries; i++) {
            out[i] = ("%0" + KEY_SIZE + "d").formatted(i).getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    /// One fixed value, reused for every key — point-lookup/write throughput
    /// benchmarks care about per-call overhead, not value content.
    static byte[] value() {
        byte[] v = new byte[VALUE_SIZE];
        new Random(0xC0FFEE).nextBytes(v);
        return v;
    }

    private BenchData() {
        // no instances
    }
}
