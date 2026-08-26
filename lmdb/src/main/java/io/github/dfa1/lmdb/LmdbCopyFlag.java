package io.github.dfa1.lmdb;

/// `mdb_env_copy2` flags, OR-able bits from `lmdb.h`'s "Copy Flags" group.
/// Combine several with an `EnumSet.of(...)` — see [LmdbFlag].
public enum LmdbCopyFlag implements LmdbFlag {

    /// Compact while copying: omit free pages and sequentially renumber
    /// every page in the output, at the cost of more CPU and a slower copy.
    /// Fails if the environment has suffered a page leak.
    COMPACT(0x01);

    private final int bits;

    LmdbCopyFlag(int bits) {
        this.bits = bits;
    }

    @Override
    public int bits() {
        return bits;
    }
}
