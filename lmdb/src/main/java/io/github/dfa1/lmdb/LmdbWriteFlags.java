package io.github.dfa1.lmdb;

/// `mdb_put`/`mdb_cursor_put`/`mdb_cursor_del` flags, OR-able bits from
/// `lmdb.h`'s "Write Flags" group. Kept as named `int` constants rather than
/// a single validated enum — see [LmdbEnvFlags] for why.
public final class LmdbWriteFlags {

    /// Don't overwrite an existing key: fail with `MDB_KEYEXIST` instead.
    public static final int NOOVERWRITE = 0x10;
    /// With `MDB_DUPSORT`, don't add a duplicate that already exists.
    public static final int NODUPDATA = 0x20;
    /// Replace the item at the cursor's current position (`mdb_cursor_put` only).
    public static final int CURRENT = 0x40;
    /// Reserve space of the data's size but leave it unwritten; the caller then
    /// writes directly into the returned data value — a zero-copy write path.
    public static final int RESERVE = 0x10000;
    /// Append the key without comparing against existing keys — the caller
    /// guarantees keys are inserted in sorted order.
    public static final int APPEND = 0x20000;
    /// Like [#APPEND], for duplicate data under `MDB_DUPSORT`.
    public static final int APPENDDUP = 0x40000;

    private LmdbWriteFlags() {
        // no instances
    }
}
