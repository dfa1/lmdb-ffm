package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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
///     for (LmdbCursor.Entry e = cursor.get(LmdbCursorOp.FIRST); e != null; e = cursor.get(LmdbCursorOp.NEXT)) {
///         System.out.println(e.key());
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

    // Reused across every get(LmdbCursorOp[, key]) call, keyed or not: these
    // two 16-byte MDB_val out-param slots can live for the cursor's whole
    // lifetime instead of being allocated (inside a freshly opened Arena) on
    // every single call — the dominant cost of a tight scan or point-lookup
    // loop otherwise. Safe to reuse: LMDB only ever writes through these
    // pointers, and the MemorySegment handed back in an Entry is read out of
    // them (LmdbVal.data) before the next call overwrites their fields,
    // exactly like reading any other out-parameter.
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment keyVal = LmdbVal.allocate(arena);
    private final MemorySegment dataVal = LmdbVal.allocate(arena);

    // Backing storage for the byte[]-key get(LmdbCursorOp, byte[]) overload's
    // copied key content — grown by doubling as needed (LmdbVal.growBuffer),
    // never reallocated per call once a caller's key sizes stabilize.
    private MemorySegment keyBuffer;

    private LmdbCursor(MemorySegment ptr) {
        super(ptr);
    }

    static LmdbCursor open(LmdbTxn txn, LmdbDbi dbi) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment out = scratch.allocate(ADDRESS);
            int code;
            try {
                code = (int) Bindings.CURSOR_OPEN.invokeExact(txn.ptr(), dbi.handle(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return new LmdbCursor(out.get(ADDRESS, 0));
        }
    }

    /// Positions this cursor per `op` (one with no explicit key/data input,
    /// such as [LmdbCursorOp#FIRST], [LmdbCursorOp#LAST] or [LmdbCursorOp#NEXT])
    /// and returns the entry found there.
    ///
    /// @param op the positioning operation
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    public Entry get(LmdbCursorOp op) {
        Objects.requireNonNull(op, "op");
        int code;
        try {
            code = (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return NativeCall.checkFound(code) ? new Entry(LmdbVal.data(keyVal), LmdbVal.data(dataVal)) : null;
    }

    /// Positions this cursor per `op` (one that takes a key input, such as
    /// [LmdbCursorOp#SET], [LmdbCursorOp#SET_RANGE] or [LmdbCursorOp#GET_BOTH])
    /// and returns the entry found there.
    ///
    /// @param op  the positioning operation
    /// @param key the key to search for
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    public Entry get(LmdbCursorOp op, byte[] key) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(key, "key");
        keyBuffer = LmdbVal.growBuffer(arena, keyBuffer, Math.max(key.length, 1));
        MemorySegment.copy(key, 0, keyBuffer, JAVA_BYTE, 0, key.length);
        LmdbVal.set(keyVal, keyBuffer.asSlice(0, key.length));
        int code;
        try {
            code = (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return NativeCall.checkFound(code) ? new Entry(LmdbVal.data(keyVal), LmdbVal.data(dataVal)) : null;
    }

    /// [#get(LmdbCursorOp, byte[])] with a zero-copy key.
    ///
    /// @param op  the positioning operation
    /// @param key native key bytes to search for (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    public Entry get(LmdbCursorOp op, MemorySegment key) {
        Objects.requireNonNull(op, "op");
        NativeCall.requireNative(key, "key");
        LmdbVal.set(keyVal, key);
        int code;
        try {
            code = (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return NativeCall.checkFound(code) ? new Entry(LmdbVal.data(keyVal), LmdbVal.data(dataVal)) : null;
    }

    /// [#get(LmdbCursorOp, MemorySegment)] for a direct [ByteBuffer] key. The
    /// key is `[position, limit)` of `key` ([MemorySegment#ofBuffer]), not its
    /// full capacity; a heap-backed buffer is rejected the same way a heap
    /// [MemorySegment] is.
    ///
    /// @param op  the positioning operation
    /// @param key native key bytes to search for, as a direct buffer's remaining content
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    public Entry get(LmdbCursorOp op, ByteBuffer key) {
        Objects.requireNonNull(key, "key");
        return get(op, MemorySegment.ofBuffer(key));
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
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(scratch, key);
            MemorySegment dataVal = LmdbVal.of(scratch, data);
            int code;
            try {
                code = (int) Bindings.CURSOR_PUT.invokeExact(ptr(), keyVal, dataVal, bits);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// [#put(byte[], byte[], Set)] with a zero-copy key and data.
    ///
    /// @param key   native key bytes to store (not retained after this call)
    /// @param data  native data bytes to store (likewise not retained)
    /// @param flags the flags to write with
    /// @throws LmdbException if the write fails
    public void put(MemorySegment key, MemorySegment data, Set<LmdbWriteFlag> flags) {
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(scratch, key);
            MemorySegment dataVal = LmdbVal.of(scratch, data);
            int code;
            try {
                code = (int) Bindings.CURSOR_PUT.invokeExact(ptr(), keyVal, dataVal, bits);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// [#put(MemorySegment, MemorySegment, Set)] for direct [ByteBuffer]
    /// key/data — see [#get(LmdbCursorOp, ByteBuffer)] for the
    /// key-range/heap-buffer caveats (both apply here too).
    ///
    /// @param key   the key to store, as a direct buffer's remaining content
    /// @param data  the data to store, as a direct buffer's remaining content
    /// @param flags the flags to write with
    /// @throws LmdbException if the write fails
    public void put(ByteBuffer key, ByteBuffer data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        put(MemorySegment.ofBuffer(key), MemorySegment.ofBuffer(data), flags);
    }

    /// Deletes the key/data pair at this cursor's current position.
    ///
    /// @throws LmdbException if the delete fails
    public void delete() {
        int code;
        try {
            code = (int) Bindings.CURSOR_DEL.invokeExact(ptr(), 0);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// The number of duplicate data items for the current key (`MDB_DUPSORT`
    /// databases only; `1` for a database without duplicates).
    ///
    /// @return the duplicate count at the current position
    /// @throws LmdbException if the native call fails
    public long count() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment countPtr = scratch.allocate(JAVA_LONG);
            int code;
            try {
                code = (int) Bindings.CURSOR_COUNT.invokeExact(ptr(), countPtr);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return countPtr.get(JAVA_LONG, 0L);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            Bindings.CURSOR_CLOSE.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }
}
