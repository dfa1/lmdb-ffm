package io.github.dfa1.lmdb;

import java.util.Set;

/// Common shape for the OR-able native flag enums ([LmdbEnvFlag],
/// [LmdbDbiFlag], [LmdbWriteFlag]): each constant carries the single native
/// bit it sets. [#toBits] folds a caller's `Set` of them back into the
/// native `unsigned int` bitmask LMDB's C API expects.
interface LmdbFlag {

    /// The native bit this constant sets.
    int bits();

    /// OR-combines every flag's [#bits] into one native bitmask.
    ///
    /// @param flags the flags to combine, e.g. an `EnumSet.of(...)` (empty for none)
    /// @return the combined native bitmask
    static int toBits(Set<? extends LmdbFlag> flags) {
        int bits = 0;
        for (LmdbFlag flag : flags) {
            bits |= flag.bits();
        }
        return bits;
    }
}
