package io.github.dfa1.lmdb;

import java.util.Set;

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
    /// Reserve space of the given size but leave it unwritten; the native
    /// call hands back a pointer to that space instead of storing any
    /// supplied data. Only meaningful through
    /// [LmdbTxn#put(LmdbDbi, byte[], int, java.util.function.Consumer)],
    /// which reads that pointer back and hands it to a caller-supplied
    /// filler — the actual zero-copy write path this flag enables. Passed
    /// to a plain data-copying `put` overload instead, it silently discards
    /// whatever data was supplied (see dfa1/lmdb-ffm#9), so those overloads
    /// reject it via [#requireNoReserve(Set)].
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

    /// Rejects `flags` if it contains [#RESERVE] — see that constant's own
    /// doc for why a plain data-copying `put` overload cannot honor it
    /// correctly.
    ///
    /// @param flags the flags a plain `put` overload was called with
    /// @throws IllegalArgumentException if `flags` contains [#RESERVE]
    static void requireNoReserve(Set<LmdbWriteFlag> flags) {
        if (flags.contains(RESERVE)) {
            throw new IllegalArgumentException(
                    "LmdbWriteFlag.RESERVE has no effect through a plain data-copying put overload "
                            + "— it would silently discard the supplied data; use "
                            + "LmdbTxn#put(LmdbDbi, byte[], int, java.util.function.Consumer) instead");
        }
    }
}
