package io.github.dfa1.lmdb;

/// `mdb_put`/`mdb_cursor_put` flags, OR-able bits from `lmdb.h`'s "Write
/// Flags" group. Combine several with an `EnumSet.of(...)` (or `Set.of()`
/// for none) — see [LmdbFlag].
public enum LmdbWriteFlag implements LmdbFlag {

    /// Don't overwrite an existing key: fail with `MDB_KEYEXIST` instead.
    NOOVERWRITE(0x10),
    /// With `MDB_DUPSORT`, don't add a duplicate that already exists.
    NODUPDATA(0x20),
    /// Replace the item at the cursor's current position (`mdb_cursor_put` only).
    CURRENT(0x40),
    /// Reserve space of the data's size but leave it unwritten; the caller then
    /// writes directly into the returned data value — a zero-copy write path.
    RESERVE(0x10000),
    /// Append the key without comparing against existing keys — the caller
    /// guarantees keys are inserted in sorted order.
    APPEND(0x20000),
    /// Like [#APPEND], for duplicate data under `MDB_DUPSORT`.
    APPENDDUP(0x40000);

    private final int bits;

    LmdbWriteFlag(int bits) {
        this.bits = bits;
    }

    @Override
    public int bits() {
        return bits;
    }
}
