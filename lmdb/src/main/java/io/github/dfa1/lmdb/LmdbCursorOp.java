package io.github.dfa1.lmdb;

/// Positioning operation for [LmdbCursor#get(LmdbCursorOp)] and its key/data
/// overloads — the native `MDB_cursor_op` enum from `lmdb.h`. Unlike the
/// OR-able flag groups ([LmdbEnvFlags], [LmdbDbiFlags], [LmdbWriteFlags]),
/// exactly one of these applies per call, so this is a real enum.
public enum LmdbCursorOp {

    /// Position at the first key/data item.
    FIRST(0),
    /// Position at the first data item of the current key. `MDB_DUPSORT` only.
    FIRST_DUP(1),
    /// Position at a given key/data pair. `MDB_DUPSORT` only.
    GET_BOTH(2),
    /// Position at a given key, at the nearest data value at or after it. `MDB_DUPSORT` only.
    GET_BOTH_RANGE(3),
    /// Return the key/data at the current cursor position.
    GET_CURRENT(4),
    /// Return up to a page of duplicate data items from the current position. `MDB_DUPFIXED` only.
    GET_MULTIPLE(5),
    /// Position at the last key/data item.
    LAST(6),
    /// Position at the last data item of the current key. `MDB_DUPSORT` only.
    LAST_DUP(7),
    /// Position at the next data item.
    NEXT(8),
    /// Position at the next data item of the current key. `MDB_DUPSORT` only.
    NEXT_DUP(9),
    /// Return up to a page of duplicate data items from the next position. `MDB_DUPFIXED` only.
    NEXT_MULTIPLE(10),
    /// Position at the first data item of the next key.
    NEXT_NODUP(11),
    /// Position at the previous data item.
    PREV(12),
    /// Position at the previous data item of the current key. `MDB_DUPSORT` only.
    PREV_DUP(13),
    /// Position at the last data item of the previous key.
    PREV_NODUP(14),
    /// Position at the given key.
    SET(15),
    /// Position at the given key, returning both key and data.
    SET_KEY(16),
    /// Position at the first key greater than or equal to the given key.
    SET_RANGE(17),
    /// Position at the previous page and return up to a page of duplicate data items. `MDB_DUPFIXED` only.
    PREV_MULTIPLE(18);

    private final int value;

    LmdbCursorOp(int value) {
        this.value = value;
    }

    /// The native `MDB_cursor_op` value.
    ///
    /// @return the native enum value
    int value() {
        return value;
    }
}
