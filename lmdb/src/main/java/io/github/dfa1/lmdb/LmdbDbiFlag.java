package io.github.dfa1.lmdb;

/// `mdb_dbi_open` flags, OR-able bits from `lmdb.h`'s "Database Flags" group.
/// Combine several with an `EnumSet.of(...)` — see [LmdbFlag].
public enum LmdbDbiFlag implements LmdbFlag {

    /// Keys are compared in reverse byte order.
    REVERSEKEY(0x02),
    /// Allow duplicate keys (each key may have multiple sorted data values).
    DUPSORT(0x04),
    /// Keys are binary integers in native byte order, compared as such.
    INTEGERKEY(0x08),
    /// With [#DUPSORT], duplicate data items are fixed-size.
    DUPFIXED(0x10),
    /// With [#DUPSORT], duplicate data items are binary integers, compared as such.
    INTEGERDUP(0x20),
    /// With [#DUPSORT], duplicate data items are compared in reverse byte order.
    REVERSEDUP(0x40),
    /// Create the named database if it does not already exist.
    CREATE(0x40000);

    private final int bits;

    LmdbDbiFlag(int bits) {
        this.bits = bits;
    }

    @Override
    public int bits() {
        return bits;
    }
}
