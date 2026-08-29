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
/// A cursor is confined to the [LmdbTxn] it was opened in, and its lifetime
/// nests inside that transaction's. **Close every cursor before ending its
/// write transaction**: LMDB frees a write transaction's cursors as a side
/// effect of ending it (`mdb_txn_commit`/`mdb_txn_abort`), so ending the
/// transaction with one still open throws [LmdbContractException] to report
/// it — once whatever writes were pending have already been committed or
/// discarded — and the cursor itself becomes safely inert: any further
/// positional use of it throws [IllegalStateException], and closing it
/// becomes a harmless no-op rather than touching already-freed native
/// memory. A cursor opened on a *read-only* transaction is not reported this
/// way — LMDB does not free those, and leaving one open across the
/// transaction's end to [#renew(LmdbTxn)] it onto a fresh one is the
/// documented, intended pattern — but it still becomes unsafe to use or
/// close *directly*: positional use throws the same
/// [IllegalStateException], and closing it without renewing first leaks its
/// native memory rather than crash (calling `mdb_cursor_close` on it
/// touches the ended transaction's own already-freed handle either way,
/// regardless of the cursor's own memory ever being freed). Renew it onto a
/// live transaction before closing to avoid that leak. Not thread-safe.
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

    // Same as keyBuffer, for the byte[]-data get(LmdbCursorOp, byte[], byte[])
    // overload's copied search data (GET_BOTH/GET_BOTH_RANGE only — see
    // #requireKeyDataOp).
    private MemorySegment dataBuffer;

    // mdb_cursor_dbi/mdb_cursor_txn are tracked here in Java instead of bound
    // as native calls: this cursor already knows both from #open, and #renew
    // is the only way either ever changes (dbi never does — see #renew's own
    // doc — only txn does), so a native round-trip would learn nothing this
    // object doesn't already have.
    private final LmdbDbi dbi;
    private LmdbTxn txn;

    private LmdbCursor(MemorySegment ptr, LmdbTxn txn, LmdbDbi dbi) {
        super(ptr);
        this.txn = txn;
        this.dbi = dbi;
        txn.registerCursor(this);
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
            return new LmdbCursor(out.get(ADDRESS, 0), txn, dbi);
        }
    }

    /// The database this cursor navigates (`mdb_cursor_dbi`) — set when it
    /// was opened and never changes afterward, since [#renew(LmdbTxn)]
    /// re-associates only the transaction, not the database.
    ///
    /// @return this cursor's database
    public LmdbDbi dbi() {
        return dbi;
    }

    /// The transaction this cursor currently operates within (`mdb_cursor_txn`)
    /// — the one it was opened with, or the most recent one passed to
    /// [#renew(LmdbTxn)].
    ///
    /// @return this cursor's current transaction
    public LmdbTxn txn() {
        return txn;
    }

    /// Re-associates this cursor — only ever valid on a read-only
    /// transaction — with `txn`, reusing it rather than opening a fresh
    /// cursor. `txn` must reference the same database this cursor was
    /// opened with; the transaction this cursor was previously on may be
    /// live or already ended ([LmdbTxn#reset()], [LmdbTxn#abort()], or even
    /// [LmdbTxn#commit()]).
    ///
    /// @param txn the (read-only) transaction to associate this cursor with
    /// @throws LmdbException if the renew fails
    public void renew(LmdbTxn txn) {
        Objects.requireNonNull(txn, "txn");
        int code;
        try {
            code = (int) Bindings.CURSOR_RENEW.invokeExact(txn.ptr(), ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
        this.txn.unregisterCursor(this);
        txn.registerCursor(this);
        this.txn = txn;
    }

    /// Positions this cursor per `op` (one with no explicit key/data input,
    /// such as [LmdbCursorOp#FIRST], [LmdbCursorOp#LAST] or [LmdbCursorOp#NEXT])
    /// and returns the entry found there.
    ///
    /// @param op the positioning operation
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op) {
        Objects.requireNonNull(op, "op");
        requireNoDataOp(op);
        return cursorGet(op) ? new Entry(LmdbVal.data(keyVal, txn.arena()), LmdbVal.data(dataVal, txn.arena())) : null;
    }

    /// Positions this cursor per `op` (one that takes a key input, such as
    /// [LmdbCursorOp#SET] or [LmdbCursorOp#SET_RANGE]) and returns the entry
    /// found there. [LmdbCursorOp#GET_BOTH]/[LmdbCursorOp#GET_BOTH_RANGE]
    /// search on a data value too, so this overload rejects them — use
    /// [#get(LmdbCursorOp, byte[], byte[])] instead.
    ///
    /// @param op  the positioning operation
    /// @param key the key to search for
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op, byte[] key) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(key, "key");
        requireNoDataOp(op);
        keyBuffer = LmdbVal.growBuffer(arena, keyBuffer, Math.max(key.length, 1));
        MemorySegment.copy(key, 0, keyBuffer, JAVA_BYTE, 0, key.length);
        LmdbVal.set(keyVal, keyBuffer.asSlice(0, key.length));
        return cursorGet(op) ? new Entry(LmdbVal.data(keyVal, txn.arena()), LmdbVal.data(dataVal, txn.arena())) : null;
    }

    /// [#get(LmdbCursorOp, byte[])] with a zero-copy key.
    ///
    /// @param op  the positioning operation
    /// @param key native key bytes to search for (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op, MemorySegment key) {
        Objects.requireNonNull(op, "op");
        NativeCall.requireNative(key, "key");
        requireNoDataOp(op);
        LmdbVal.set(keyVal, key);
        return cursorGet(op) ? new Entry(LmdbVal.data(keyVal, txn.arena()), LmdbVal.data(dataVal, txn.arena())) : null;
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

    /// Positions this cursor per `op` — [LmdbCursorOp#GET_BOTH] or
    /// [LmdbCursorOp#GET_BOTH_RANGE], the only two `MDB_cursor_op` values
    /// that search on a data value as well as a key — and returns the entry
    /// found there.
    ///
    /// @param op   the positioning operation
    /// @param key  the key to search for
    /// @param data the data value to search for (for `GET_BOTH_RANGE`, the
    ///             lower bound to search from)
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is neither [LmdbCursorOp#GET_BOTH]
    ///                                   nor [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op, byte[] key, byte[] data) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        requireKeyDataOp(op);
        keyBuffer = LmdbVal.growBuffer(arena, keyBuffer, Math.max(key.length, 1));
        MemorySegment.copy(key, 0, keyBuffer, JAVA_BYTE, 0, key.length);
        LmdbVal.set(keyVal, keyBuffer.asSlice(0, key.length));
        dataBuffer = LmdbVal.growBuffer(arena, dataBuffer, Math.max(data.length, 1));
        MemorySegment.copy(data, 0, dataBuffer, JAVA_BYTE, 0, data.length);
        LmdbVal.set(dataVal, dataBuffer.asSlice(0, data.length));
        return cursorGet(op) ? new Entry(LmdbVal.data(keyVal, txn.arena()), LmdbVal.data(dataVal, txn.arena())) : null;
    }

    /// [#get(LmdbCursorOp, byte[], byte[])] with a zero-copy key and data.
    ///
    /// @param op   the positioning operation
    /// @param key  native key bytes to search for (copied into a temporary
    ///             `MDB_val`; not retained after this call)
    /// @param data native data bytes to search for (likewise not retained)
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is neither [LmdbCursorOp#GET_BOTH]
    ///                                   nor [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op, MemorySegment key, MemorySegment data) {
        Objects.requireNonNull(op, "op");
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        requireKeyDataOp(op);
        LmdbVal.set(keyVal, key);
        LmdbVal.set(dataVal, data);
        return cursorGet(op) ? new Entry(LmdbVal.data(keyVal, txn.arena()), LmdbVal.data(dataVal, txn.arena())) : null;
    }

    /// [#get(LmdbCursorOp, MemorySegment, MemorySegment)] for direct
    /// [ByteBuffer] key/data — see [#get(LmdbCursorOp, ByteBuffer)] for the
    /// key-range/heap-buffer caveats (both apply here too).
    ///
    /// @param op   the positioning operation
    /// @param key  the key to search for, as a direct buffer's remaining content
    /// @param data the data value to search for, as a direct buffer's remaining content
    /// @return the key/data pair at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is neither [LmdbCursorOp#GET_BOTH]
    ///                                   nor [LmdbCursorOp#GET_BOTH_RANGE]
    public Entry get(LmdbCursorOp op, ByteBuffer key, ByteBuffer data) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        return get(op, MemorySegment.ofBuffer(key), MemorySegment.ofBuffer(data));
    }

    /// [#get(LmdbCursorOp)], without the key-segment construction that call
    /// pays even when the key is never touched — the shape a whole-database
    /// value-only scan (`FIRST`/`NEXT`/`LAST`/`PREV`) needs.
    ///
    /// @param op the positioning operation
    /// @return the value at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public MemorySegment getValue(LmdbCursorOp op) {
        Objects.requireNonNull(op, "op");
        requireNoDataOp(op);
        return cursorGet(op) ? LmdbVal.data(dataVal, txn.arena()) : null;
    }

    /// [#get(LmdbCursorOp, byte[])], without the key-segment construction —
    /// for a caller who already knows `key` and only wants the value back.
    /// Discards whatever key LMDB actually matched; use
    /// [#get(LmdbCursorOp, byte[])] instead if that matters (e.g.
    /// [LmdbCursorOp#SET_RANGE], where the matched key can differ from `key`).
    ///
    /// @param op  the positioning operation
    /// @param key the key to search for
    /// @return the value at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public MemorySegment getValue(LmdbCursorOp op, byte[] key) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(key, "key");
        requireNoDataOp(op);
        keyBuffer = LmdbVal.growBuffer(arena, keyBuffer, Math.max(key.length, 1));
        MemorySegment.copy(key, 0, keyBuffer, JAVA_BYTE, 0, key.length);
        LmdbVal.set(keyVal, keyBuffer.asSlice(0, key.length));
        return cursorGet(op) ? LmdbVal.data(dataVal, txn.arena()) : null;
    }

    /// [#getValue(LmdbCursorOp, byte[])] with a zero-copy key.
    ///
    /// @param op  the positioning operation
    /// @param key native key bytes to search for (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return the value at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    /// @throws IllegalArgumentException if `op` is [LmdbCursorOp#GET_BOTH] or
    ///                                   [LmdbCursorOp#GET_BOTH_RANGE]
    public MemorySegment getValue(LmdbCursorOp op, MemorySegment key) {
        Objects.requireNonNull(op, "op");
        NativeCall.requireNative(key, "key");
        requireNoDataOp(op);
        LmdbVal.set(keyVal, key);
        return cursorGet(op) ? LmdbVal.data(dataVal, txn.arena()) : null;
    }

    /// [#getValue(LmdbCursorOp, MemorySegment)] for a direct [ByteBuffer] key
    /// — see [#get(LmdbCursorOp, ByteBuffer)] for the key-range/heap-buffer
    /// caveats.
    ///
    /// @param op  the positioning operation
    /// @param key native key bytes to search for, as a direct buffer's remaining content
    /// @return the value at the new position, or `null` if there isn't one
    /// @throws LmdbException if the native call fails
    public MemorySegment getValue(LmdbCursorOp op, ByteBuffer key) {
        Objects.requireNonNull(key, "key");
        return getValue(op, MemorySegment.ofBuffer(key));
    }

    /// Stores `data` under `key` at this cursor's current database, per `flags`
    /// (e.g. `EnumSet.of(LmdbWriteFlag.CURRENT)` to overwrite the entry at the
    /// cursor's current position).
    ///
    /// @param key   the key to store
    /// @param data  the data to store
    /// @param flags the flags to write with, e.g. `Set.of()` for none
    /// @throws LmdbException if the write fails
    /// @throws IllegalArgumentException if `flags` contains [LmdbWriteFlag#RESERVE]
    public void put(byte[] key, byte[] data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(flags, "flags");
        LmdbWriteFlag.requireNoReserve(flags);
        requireTransactionNotEnded();
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
    /// @throws IllegalArgumentException if `flags` contains [LmdbWriteFlag#RESERVE]
    public void put(MemorySegment key, MemorySegment data, Set<LmdbWriteFlag> flags) {
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        Objects.requireNonNull(flags, "flags");
        LmdbWriteFlag.requireNoReserve(flags);
        requireTransactionNotEnded();
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
        requireTransactionNotEnded();
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
        requireTransactionNotEnded();
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

    /// Whether this cursor is currently positioned on a named-database
    /// record (`mdb_cursor_is_db`) — meaningful only for a cursor navigating
    /// the environment's unnamed database, where each named database opened
    /// via [LmdbTxn#openDatabase(String, Set)] appears as one entry
    /// alongside any plain user keys.
    ///
    /// @return `true` if the current entry is itself a named database
    /// @throws LmdbException if the native call fails
    public boolean isDb() {
        requireTransactionNotEnded();
        int result;
        try {
            result = (int) Bindings.CURSOR_IS_DB.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return result != 0;
    }

    // Guards the key-only get/getValue overloads against GET_BOTH/
    // GET_BOTH_RANGE: both need a data value to search on, which those
    // overloads never set, so the native call would search against whatever
    // this cursor's reused dataVal slot last held from an unrelated earlier
    // call — a plausible-looking but wrong result, not an error (see
    // #requireKeyDataOp, and dfa1/lmdb-ffm#12).
    private static void requireNoDataOp(LmdbCursorOp op) {
        if (op == LmdbCursorOp.GET_BOTH || op == LmdbCursorOp.GET_BOTH_RANGE) {
            throw new IllegalArgumentException(op + " needs a data value to search on, not just a key; "
                    + "use get(LmdbCursorOp, byte[], byte[]) (or its MemorySegment/ByteBuffer siblings) instead");
        }
    }

    // Guards the key+data get overloads the other way: GET_BOTH/
    // GET_BOTH_RANGE are the only two MDB_cursor_op values that take a data
    // input, so any other op here would silently ignore the caller's data
    // argument instead of doing what its name suggests.
    private static void requireKeyDataOp(LmdbCursorOp op) {
        if (op != LmdbCursorOp.GET_BOTH && op != LmdbCursorOp.GET_BOTH_RANGE) {
            throw new IllegalArgumentException(op + " does not search on a data value; only GET_BOTH and "
                    + "GET_BOTH_RANGE do. Use get(LmdbCursorOp, byte[]) (or its MemorySegment/ByteBuffer "
                    + "siblings) instead.");
        }
    }

    // The one place mdb_cursor_get is called: every #get/#getValue overload
    // passes the same reused keyVal/dataVal slots and differs only in what it
    // reads back out of them afterward. Two arms rather than one call through
    // a selected handle, so each keeps a direct invokeExact against a static
    // final MethodHandle — the shape the JIT inlines. See
    // Bindings#CURSOR_GET_CRITICAL for why a custom comparator rules the
    // critical handle out.
    private boolean cursorGet(LmdbCursorOp op) {
        requireTransactionNotEnded();
        int code;
        try {
            if (txn.env().usesComparators()) {
                code = (int) Bindings.CURSOR_GET.invokeExact(ptr(), keyVal, dataVal, op.value());
            } else {
                code = (int) Bindings.CURSOR_GET_CRITICAL.invokeExact(ptr(), keyVal, dataVal, op.value());
            }
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return NativeCall.checkFound(code);
    }

    // Guards every operation except #close()/#renew(LmdbTxn) against a
    // cursor whose transaction has since ended. Checked live against the
    // current txn's own NativeObject#isClosed() rather than a flag this
    // cursor tracks itself, so it needs no separate bookkeeping to stay in
    // sync — a positional operation on a stale cursor reads through an ended
    // snapshot regardless of whether LMDB actually freed the cursor's own
    // memory (see #tryClose for that distinction), and either way is
    // undefined — SIGSEGV in mdb_cursor_sibling, dfa1/lmdb-ffm#4.
    private void requireTransactionNotEnded() {
        if (txn.isClosed()) {
            throw new IllegalStateException(
                    "cursor's transaction has ended; renew it onto a live one before further use");
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            // Skip mdb_cursor_close if this cursor's transaction has already
            // ended: empirically, closing directly (without a
            // #renew(LmdbTxn) onto a live transaction first) dereferences
            // the ended transaction's own already-freed handle and segfaults
            // — true even for a read-only transaction's cursor, whose own
            // memory LMDB left untouched (dfa1/lmdb-ffm#4's repro; contrary
            // to mdb_cursor_open's own doc, which reads as if closing
            // directly were always safe). The cursor's native memory leaks
            // in that case rather than risk the crash — bounded, and only
            // under this misuse; renewing it onto a live transaction before
            // closing avoids the leak entirely.
            if (!txn.isClosed()) {
                Bindings.CURSOR_CLOSE.invokeExact(ptr);
            }
        } finally {
            txn.unregisterCursor(this);
            arena.close();
        }
    }
}
