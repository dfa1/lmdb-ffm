package io.github.dfa1.lmdb;

/// `mdb_dbi_open` flags, OR-able bits from `lmdb.h`'s "Database Flags" group.
/// Kept as named `int` constants rather than a single validated enum — see
/// [LmdbEnvFlags] for why.
public final class LmdbDbiFlags {

    /// Keys are compared in reverse byte order.
    public static final int REVERSEKEY = 0x02;
    /// Allow duplicate keys (each key may have multiple sorted data values).
    public static final int DUPSORT = 0x04;
    /// Keys are binary integers in native byte order, compared as such.
    public static final int INTEGERKEY = 0x08;
    /// With [#DUPSORT], duplicate data items are fixed-size.
    public static final int DUPFIXED = 0x10;
    /// With [#DUPSORT], duplicate data items are binary integers, compared as such.
    public static final int INTEGERDUP = 0x20;
    /// With [#DUPSORT], duplicate data items are compared in reverse byte order.
    public static final int REVERSEDUP = 0x40;
    /// Create the named database if it does not already exist.
    public static final int CREATE = 0x40000;

    private LmdbDbiFlags() {
        // no instances
    }
}
