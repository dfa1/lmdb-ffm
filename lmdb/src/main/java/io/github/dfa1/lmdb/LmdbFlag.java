package io.github.dfa1.lmdb;

import java.util.EnumSet;
import java.util.Set;

/// Common shape for the OR-able native flag enums ([LmdbEnvFlag],
/// [LmdbDbiFlag], [LmdbWriteFlag]): each constant carries the single native
/// bit it sets. [#toBits] folds a caller's `Set` of them back into the
/// native `unsigned int` bitmask LMDB's C API expects; [#fromBits] is the
/// reverse, for a call that reports back which flags are active.
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

    /// Splits a native bitmask back into the subset of `type`'s constants it
    /// sets.
    ///
    /// @param bits the native bitmask, e.g. from `mdb_env_get_flags`
    /// @param type the flag enum to check bits against
    /// @param <E>  the flag enum type
    /// @return the constants of `type` whose bit is set in `bits`
    static <E extends Enum<E> & LmdbFlag> Set<E> fromBits(int bits, Class<E> type) {
        Set<E> flags = EnumSet.noneOf(type);
        for (E flag : type.getEnumConstants()) {
            if ((bits & flag.bits()) == flag.bits()) {
                flags.add(flag);
            }
        }
        return flags;
    }
}
