package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// A cursor for navigating a database within a transaction — wraps `MDB_cursor*`.
///
/// A cursor is confined to the [LmdbTxn] it was opened in. **Close every
/// cursor before calling [LmdbTxn#commit()]** on that transaction: LMDB frees
/// a write transaction's cursors as part of the commit itself, so closing (or
/// using) one afterward touches already-freed native memory — undefined
/// behavior, not a catchable exception. Closing before [LmdbTxn#abort()] (or
/// letting [LmdbTxn#close()] abort as the try-with-resources fallback) is
/// always safe, which is why the snippet below nests a read-only transaction
/// and its cursor in one `try`. Not thread-safe.
///
/// {@snippet :
/// try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
///      LmdbCursor cursor = txn.openCursor(dbi)) {
///     for (Optional<LmdbCursor.Entry> e = cursor.get(LmdbCursorOp.FIRST);
///             e.isPresent();
///             e = cursor.get(LmdbCursorOp.NEXT)) {
///         System.out.println(e.get().key());
///     }
/// }
/// }
public final class LmdbCursor extends NativeObject {

    /// A key/data pair positioned by a cursor. Both segments are zero-copy
    /// views into the memory-mapped database, valid only until the enclosing
    /// transaction ends or the entry is overwritten/deleted.
    ///
    /// @param key  the entry's key
    /// @param data the entry's data
    public record Entry(MemorySegment key, MemorySegment data) {
    }

    private LmdbCursor(MemorySegment ptr) {
        super(ptr);
    }

    static LmdbCursor open(LmdbTxn txn, LmdbDbi dbi) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = NativeCall.createHandle(arena,
                    out -> (int) Bindings.CURSOR_OPEN.invokeExact(txn.ptr(), dbi.handle(), out));
            return new LmdbCursor(ptr);
        }
    }

    /// Positions this cursor per `op` (one with no explicit key/data input,
    /// such as [LmdbCursorOp#FIRST], [LmdbCursorOp#LAST] or [LmdbCursorOp#NEXT])
    /// and returns the entry found there.
    ///
    /// @param op the positioning operation
    /// @return the key/data pair at the new position, or empty if there isn't one
    /// @throws LmdbException if the native call fails
    public Optional<Entry> get(LmdbCursorOp op) {
        Objects.requireNonNull(op, "op");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.allocate(arena);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            boolean found = NativeCall.checkFound(() ->
                    (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value()));
            return found ? Optional.of(new Entry(LmdbVal.data(keyVal), LmdbVal.data(dataVal))) : Optional.empty();
        }
    }

    /// Positions this cursor per `op` (one that takes a key input, such as
    /// [LmdbCursorOp#SET], [LmdbCursorOp#SET_RANGE] or [LmdbCursorOp#GET_BOTH])
    /// and returns the entry found there.
    ///
    /// @param op  the positioning operation
    /// @param key the key to search for
    /// @return the key/data pair at the new position, or empty if there isn't one
    /// @throws LmdbException if the native call fails
    public Optional<Entry> get(LmdbCursorOp op, byte[] key) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            boolean found = NativeCall.checkFound(() ->
                    (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value()));
            return found ? Optional.of(new Entry(LmdbVal.data(keyVal), LmdbVal.data(dataVal))) : Optional.empty();
        }
    }

    /// Stores `data` under `key` at this cursor's current database, per `flags`
    /// (e.g. `EnumSet.of(LmdbWriteFlag.CURRENT)` to overwrite the entry at the
    /// cursor's current position).
    ///
    /// @param key   the key to store
    /// @param data  the data to store
    /// @param flags the flags to write with, e.g. `Set.of()` for none
    /// @throws LmdbException if the write fails
    public void put(byte[] key, byte[] data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            NativeCall.check(() -> (int) Bindings.CURSOR_PUT.invokeExact(ptr(), keyVal, dataVal, bits));
        }
    }

    /// Deletes the key/data pair at this cursor's current position.
    ///
    /// @throws LmdbException if the delete fails
    public void delete() {
        NativeCall.check(() -> (int) Bindings.CURSOR_DEL.invokeExact(ptr(), 0));
    }

    /// The number of duplicate data items for the current key (`MDB_DUPSORT`
    /// databases only; `1` for a database without duplicates).
    ///
    /// @return the duplicate count at the current position
    /// @throws LmdbException if the native call fails
    public long count() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment countPtr = arena.allocate(JAVA_LONG);
            NativeCall.check(() -> (int) Bindings.CURSOR_COUNT.invokeExact(ptr(), countPtr));
            return countPtr.get(JAVA_LONG, 0L);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        Bindings.CURSOR_CLOSE.invokeExact(ptr);
    }
}
