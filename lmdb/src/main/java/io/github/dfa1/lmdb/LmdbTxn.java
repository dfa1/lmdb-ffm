package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/// A transaction — wraps `MDB_txn*`. All reads and writes go through one.
///
/// Ends exactly once, via [#commit()] or [#abort()]; [#close()] (from
/// [AutoCloseable]) is the try-with-resources safety net for a transaction
/// that reaches the end of its block without an explicit [#commit()] — it
/// aborts if the transaction is still open, and is a silent no-op if
/// [#commit()] or [#abort()] already ran. A read-only transaction that is
/// simply discarded (never committed) is the normal way to release its
/// snapshot; a write transaction must be [#commit()]ted to keep its changes.
///
/// **Confined to the thread that began it — use it from no other thread.**
/// This is a hard requirement inherited from LMDB itself (`mdb_txn_begin`'s
/// own doc: "A transaction and its cursors must only be used by a single
/// thread"), not a convenience default; violating it is undefined behavior,
/// not a checked error. [LmdbEnvFlag#NOTLS] lifts this in upstream LMDB for
/// a read-only transaction, letting it migrate between threads, but this
/// binding does not offer that either way: the reused `MDB_val` out-param
/// slots ([#keyVal]/[#dataVal]) live in an `Arena.ofConfined()` tied to
/// whichever thread began this transaction, so a cross-thread *read*
/// happens to throw `WrongThreadException` regardless of `NOTLS` — see that
/// flag's own doc. A cross-thread *write*, or any other method, has no such
/// incidental guard and is not otherwise checked: nothing stops it from
/// running, and per LMDB's own contract the result is undefined, up to and
/// including native memory corruption. Enforcing this everywhere it could
/// apply would mean auditing and guarding every native call this class (and
/// [LmdbCursor]) makes, which does not end — so this is deliberately left
/// as a documented contract like the rest of Java's own non-thread-safe
/// collections, not a partially-enforced one.
///
/// {@snippet :
/// try (LmdbTxn txn = env.beginTxn()) {
///     txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), Set.of());
///     txn.commit();
/// }
/// }
public final class LmdbTxn extends NativeObject {

    private final LmdbEnv env;

    // Reused across every keyed read (getSegment/get, keyed or not, byte[]
    // or MemorySegment): these two 16-byte MDB_val out-param slots and the
    // key-content buffer below live for the transaction's whole lifetime
    // instead of being allocated (inside a freshly opened Arena) on every
    // single call — see LmdbCursor's identical fields for the same
    // reasoning and the benchmark that motivated it. Closed explicitly in
    // commit()/abort()/tryClose() since none of them run through each other
    // (see NativeObject#take()).
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment keyVal = LmdbVal.allocate(arena);
    private final MemorySegment dataVal = LmdbVal.allocate(arena);
    private MemorySegment keyBuffer;

    // Whether this transaction was begun with MDB_RDONLY — read only by
    // #stillOpenCursorsIfWriteTransaction, to decide whether a cursor left
    // open when this transaction ends is a caller mistake worth reporting
    // (write only) or the documented, intended renew-later pattern
    // (read-only) — see that method.
    private final boolean readOnly;

    // Cursors currently open on this transaction: added when a LmdbCursor is
    // constructed against it (LmdbCursor's own constructor) or
    // LmdbCursor#renew(LmdbTxn)'d onto it, removed when one closes or renews
    // onto a different transaction. Read only by
    // #stillOpenCursorsIfWriteTransaction, at commit/abort/tryClose, purely
    // to decide what to report — LmdbCursor#tryClose and its own
    // requireTransactionNotEnded guard are what actually keep a stale cursor
    // from touching freed native state, regardless of what this transaction
    // does with the set. Not synchronized: LmdbTxn (like LmdbCursor) is
    // documented not-thread-safe.
    private final Set<LmdbCursor> openCursors = new HashSet<>();

    private LmdbTxn(MemorySegment ptr, LmdbEnv env, boolean readOnly) {
        super(ptr);
        this.env = env;
        this.readOnly = readOnly;
        env.registerTransaction();
    }

    static LmdbTxn begin(LmdbEnv env, LmdbTxn parent, int flags) {
        MemorySegment parentPtr = parent == null ? MemorySegment.NULL : parent.ptr();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int code;
            try {
                code = (int) Bindings.TXN_BEGIN.invokeExact(env.ptr(), parentPtr, flags, out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            boolean readOnly = (flags & LmdbEnvFlag.RDONLY.bits()) != 0;
            return new LmdbTxn(out.get(ADDRESS, 0), env, readOnly);
        }
    }

    /// Registers a cursor as open against this transaction — called once a
    /// new [LmdbCursor] is constructed against it, or
    /// [LmdbCursor#renew(LmdbTxn)]'d onto it. See #openCursors.
    void registerCursor(LmdbCursor cursor) {
        openCursors.add(cursor);
    }

    /// Unregisters a cursor that just closed, or renewed onto a different
    /// transaction. See #openCursors.
    void unregisterCursor(LmdbCursor cursor) {
        openCursors.remove(cursor);
    }

    // Called from commit()/abort()/tryClose(), purely to decide what to
    // report — not to act on any cursor. Closing (LmdbCursor#tryClose) and
    // using (LmdbCursor#requireTransactionNotEnded) a cursor whose
    // transaction has ended both already refuse to touch native state on
    // their own, for a write or a read-only transaction alike: empirically,
    // mdb_cursor_close itself dereferences the ended transaction's own freed
    // handle even for a cursor LMDB never freed (a read-only transaction's),
    // so "closed explicitly, before or after its transaction ends" — the doc
    // on mdb_cursor_open — turns out to mean after a LmdbCursor#renew(LmdbTxn)
    // onto a live one, not directly; see dfa1/lmdb-ffm#4 for the repro this
    // was verified against. What differs between write and read-only is only
    // whether leaving a cursor open here is a *mistake*: a write
    // transaction's cursors are meant to be closed before it ends (LMDB
    // frees them as a side effect either way — "only write-transactions free
    // cursors" per mdb_txn_commit/mdb_txn_abort's own docs), so leftover ones
    // are reported via LmdbContractException; a read-only transaction's are
    // not freed, and deferring their close or renewing them onto a fresh
    // transaction later is the documented, intended pattern, not a mistake.
    private List<LmdbCursor> stillOpenCursorsIfWriteTransaction() {
        List<LmdbCursor> stillOpen = readOnly || openCursors.isEmpty() ? List.of() : List.copyOf(openCursors);
        openCursors.clear();
        return stillOpen;
    }

    /// Prepares this transaction for a two-phase commit protocol: persists
    /// all writes to storage, but does not perform the final metapage
    /// update. Every cursor on this transaction is closed by this call. Only
    /// [#commit()] or [#abort()] are valid afterward — this does not end the
    /// transaction's lifecycle itself.
    ///
    /// @throws LmdbException         if the prepare fails
    /// @throws IllegalStateException if this transaction already ended
    public void prepare() {
        int code;
        try {
            code = (int) Bindings.TXN_PREPARE.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Commits this transaction, making its writes durable (subject to the
    /// environment's sync flags) and ending it. Ends this object's lifecycle —
    /// [#ptr()], a further [#commit()]/[#abort()], and any read/write method
    /// below all fail after this call; [#close()] becomes a no-op.
    ///
    /// Close every [LmdbCursor] opened on this transaction first — see
    /// [LmdbCursor]'s class documentation for why closing one after commit is
    /// unsafe rather than merely rejected.
    ///
    /// @throws LmdbException        if the commit fails (e.g. [LmdbErrorCode#MAP_FULL])
    /// @throws IllegalStateException if this transaction already ended
    public void commit() {
        MemorySegment p = take();
        List<LmdbCursor> stillOpen;
        try {
            int code;
            try {
                code = (int) Bindings.TXN_COMMIT.invokeExact(p);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        } finally {
            stillOpen = stillOpenCursorsIfWriteTransaction();
            env.unregisterTransaction();
            arena.close();
        }
        if (!stillOpen.isEmpty()) {
            throw new LmdbContractException(
                    "LmdbTxn#commit() called with " + stillOpen.size() + " LmdbCursor still open; "
                            + "mdb_txn_commit frees a write transaction's cursors as a side effect, "
                            + "so using or closing any of them further is no longer safe. Close "
                            + "every cursor before committing its transaction.");
        }
    }

    /// Aborts this transaction, discarding its writes (a read-only transaction
    /// has none to discard — this just releases its snapshot) and ending it.
    /// Ends this object's lifecycle exactly like [#commit()].
    ///
    /// @throws IllegalStateException if this transaction already ended
    public void abort() {
        MemorySegment p = take();
        List<LmdbCursor> stillOpen;
        try {
            try {
                Bindings.TXN_ABORT.invokeExact(p);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        } finally {
            stillOpen = stillOpenCursorsIfWriteTransaction();
            env.unregisterTransaction();
            arena.close();
        }
        if (!stillOpen.isEmpty()) {
            throw new LmdbContractException(
                    "LmdbTxn#abort() called with " + stillOpen.size() + " LmdbCursor still open on "
                            + "a write transaction; mdb_txn_abort frees a write transaction's "
                            + "cursors as a side effect, so using or closing any of them further is "
                            + "no longer safe. Close every cursor before aborting its write "
                            + "transaction.");
        }
    }

    /// Resets this non-nested, read-only transaction: releases its reader
    /// lock like [#abort()], but keeps the handle alive for [#renew()] —
    /// cheaper than a fresh [LmdbEnv#beginTxn(Set)] when the caller will
    /// start another read-only transaction soon. Does not end this object's
    /// lifecycle the way [#commit()]/[#abort()] do.
    ///
    /// Only [#renew()] is valid on this transaction until it is renewed;
    /// every cursor opened on it must not be used again either, except via
    /// [LmdbCursor#renew(LmdbTxn)]. Neither is enforced here — LMDB's own C
    /// API does not enforce it either, so misuse is undefined behavior, not
    /// a catchable exception, exactly like using a [LmdbCursor] after its
    /// transaction commits (see [LmdbCursor]'s class documentation).
    ///
    /// @throws IllegalStateException if this transaction already ended
    public void reset() {
        try {
            Bindings.TXN_RESET.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Re-acquires a reader lock for a transaction previously [#reset()],
    /// making it usable again. Must be called before any other use of this
    /// transaction (or a cursor opened on it).
    ///
    /// @throws LmdbException         if the renew fails
    /// @throws IllegalStateException if this transaction already ended
    public void renew() {
        int code;
        try {
            code = (int) Bindings.TXN_RENEW.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Opens the environment's unnamed database.
    ///
    /// @param flags the flags to open with (e.g. `Set.of()` to open an
    ///              existing database, or `EnumSet.of(LmdbDbiFlag.CREATE)`
    ///              to create it if missing)
    /// @return the database handle
    /// @throws LmdbException if the open fails
    public LmdbDbi openDatabase(Set<LmdbDbiFlag> flags) {
        return openDatabase(null, flags);
    }

    /// Opens a named database within the environment (the environment must
    /// have been configured with a large enough [LmdbEnv#maxDatabases(int)]).
    ///
    /// @param name  the database name, or `null` for the unnamed database
    /// @param flags the flags to open with
    /// @return the database handle
    /// @throws LmdbException if the open fails
    public LmdbDbi openDatabase(String name, Set<LmdbDbiFlag> flags) {
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment namePtr = name == null ? MemorySegment.NULL : arena.allocateFrom(name);
            MemorySegment out = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.DBI_OPEN.invokeExact(ptr(), namePtr, bits, out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return new LmdbDbi(out.get(JAVA_INT, 0));
        }
    }

    /// Empties `dbi`, and optionally deletes it from the environment.
    ///
    /// @param dbi    the database to drop
    /// @param delete if `true`, also delete the database handle itself
    /// @throws LmdbException if the drop fails
    public void drop(LmdbDbi dbi, boolean delete) {
        Objects.requireNonNull(dbi, "dbi");
        int code;
        try {
            code = (int) Bindings.DROP.invokeExact(ptr(), dbi.handle(), delete ? 1 : 0);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Statistics for `dbi`.
    ///
    /// @param dbi the database to inspect
    /// @return the database's statistics
    /// @throws LmdbException if the native call fails
    public LmdbStat stat(LmdbDbi dbi) {
        Objects.requireNonNull(dbi, "dbi");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stat = arena.allocate(Bindings.STAT_LAYOUT);
            int code;
            try {
                code = (int) Bindings.STAT.invokeExact(ptr(), dbi.handle(), stat);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return LmdbStat.of(stat);
        }
    }

    /// The flags `dbi` was opened with (e.g. [LmdbDbiFlag#DUPSORT]) — the
    /// persisted structural flags, not [LmdbDbiFlag#CREATE], which is only a
    /// directive to [#openDatabase(String, Set)], never a stored property.
    ///
    /// @param dbi the database to inspect
    /// @return the database's flags
    /// @throws LmdbException if the native call fails
    public Set<LmdbDbiFlag> dbiFlags(LmdbDbi dbi) {
        Objects.requireNonNull(dbi, "dbi");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.DBI_FLAGS.invokeExact(ptr(), dbi.handle(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return LmdbFlag.fromBits(out.get(JAVA_INT, 0L), LmdbDbiFlag.class);
        }
    }

    /// Compares `a` and `b` as if they were keys in `dbi` (`mdb_cmp`), using
    /// whatever key-comparison function is active there — LMDB's default
    /// lexicographic order, or a custom [LmdbComparator] installed via
    /// [#setComparator(LmdbDbi, LmdbComparator)].
    ///
    /// @param dbi the database whose key-comparison function to use
    /// @param a   the first key
    /// @param b   the second key
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compare(LmdbDbi dbi, byte[] a, byte[] b) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aVal = LmdbVal.of(arena, a);
            MemorySegment bVal = LmdbVal.of(arena, b);
            try {
                return (int) Bindings.CMP.invokeExact(ptr(), dbi.handle(), aVal, bVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        }
    }

    /// [#compare(LmdbDbi, byte[], byte[])] with zero-copy keys.
    ///
    /// @param dbi the database whose key-comparison function to use
    /// @param a   native bytes for the first key (not retained after this call)
    /// @param b   native bytes for the second key (likewise not retained)
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compare(LmdbDbi dbi, MemorySegment a, MemorySegment b) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(a, "a");
        NativeCall.requireNative(b, "b");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aVal = LmdbVal.of(arena, a);
            MemorySegment bVal = LmdbVal.of(arena, b);
            try {
                return (int) Bindings.CMP.invokeExact(ptr(), dbi.handle(), aVal, bVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        }
    }

    /// [#compare(LmdbDbi, byte[], byte[])] for direct [ByteBuffer] keys — see
    /// [#getSegment(LmdbDbi, ByteBuffer)] for the key-range/heap-buffer caveats.
    ///
    /// @param dbi the database whose key-comparison function to use
    /// @param a   the first key, as a direct buffer's remaining content
    /// @param b   the second key, as a direct buffer's remaining content
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compare(LmdbDbi dbi, ByteBuffer a, ByteBuffer b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return compare(dbi, MemorySegment.ofBuffer(a), MemorySegment.ofBuffer(b));
    }

    /// Compares `a` and `b` as if they were duplicate data values under one
    /// key in `dbi` (`mdb_dcmp`) — the sibling of [#compare(LmdbDbi, byte[], byte[])]
    /// for an `MDB_DUPSORT` database's value ordering instead of its key
    /// ordering, using LMDB's default order or a custom [LmdbComparator]
    /// installed via [#setDupComparator(LmdbDbi, LmdbComparator)].
    ///
    /// @param dbi the `MDB_DUPSORT` database whose value-comparison function to use
    /// @param a   the first value
    /// @param b   the second value
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compareData(LmdbDbi dbi, byte[] a, byte[] b) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aVal = LmdbVal.of(arena, a);
            MemorySegment bVal = LmdbVal.of(arena, b);
            try {
                return (int) Bindings.DCMP.invokeExact(ptr(), dbi.handle(), aVal, bVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        }
    }

    /// [#compareData(LmdbDbi, byte[], byte[])] with zero-copy values.
    ///
    /// @param dbi the `MDB_DUPSORT` database whose value-comparison function to use
    /// @param a   native bytes for the first value (not retained after this call)
    /// @param b   native bytes for the second value (likewise not retained)
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compareData(LmdbDbi dbi, MemorySegment a, MemorySegment b) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(a, "a");
        NativeCall.requireNative(b, "b");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment aVal = LmdbVal.of(arena, a);
            MemorySegment bVal = LmdbVal.of(arena, b);
            try {
                return (int) Bindings.DCMP.invokeExact(ptr(), dbi.handle(), aVal, bVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        }
    }

    /// [#compareData(LmdbDbi, byte[], byte[])] for direct [ByteBuffer] values
    /// — see [#getSegment(LmdbDbi, ByteBuffer)] for the key-range/heap-buffer
    /// caveats.
    ///
    /// @param dbi the `MDB_DUPSORT` database whose value-comparison function to use
    /// @param a   the first value, as a direct buffer's remaining content
    /// @param b   the second value, as a direct buffer's remaining content
    /// @return negative if `a` orders before `b`, positive if after, `0` if equal
    public int compareData(LmdbDbi dbi, ByteBuffer a, ByteBuffer b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        return compareData(dbi, MemorySegment.ofBuffer(a), MemorySegment.ofBuffer(b));
    }

    /// The flags this transaction is running with (e.g. [LmdbEnvFlag#RDONLY]),
    /// as passed to [LmdbEnv#beginTxn(Set)] plus any inherited from
    /// [LmdbEnv#open(Path, Set)].
    ///
    /// @return this transaction's flags
    /// @throws LmdbException if the native call fails
    public Set<LmdbEnvFlag> flags() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.TXN_FLAGS.invokeExact(ptr(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return LmdbFlag.fromBits(out.get(JAVA_INT, 0L), LmdbEnvFlag.class);
        }
    }

    /// This transaction's ID (`mdb_txn_id`) — the identifier of the snapshot
    /// a read-only transaction sees, or of the write about to be committed.
    /// Concurrent read-only transactions frequently share the same ID.
    /// Needed by [LmdbEnv#rollback(long)], which must be given this ID
    /// captured before this transaction's [#commit()] — the transaction
    /// handle itself is freed by the time a caller could otherwise reach for it.
    ///
    /// @return this transaction's ID
    /// @throws IllegalStateException if this transaction already ended
    public long id() {
        try {
            return (long) Bindings.TXN_ID.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Zero-copy read: looks up `key` in `dbi` and returns the stored data as a
    /// [MemorySegment] pointing directly into the memory-mapped database — no
    /// copy. The segment is valid only until this transaction ends or the
    /// entry is overwritten/deleted; copy it out (or use [#get(LmdbDbi, byte[])])
    /// before then if it must outlive that.
    ///
    /// @param dbi the database to read from
    /// @param key the key to look up
    /// @return the stored data, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public MemorySegment getSegment(LmdbDbi dbi, byte[] key) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        setKey(key);
        return getInto(dbi, keyVal, dataVal) ? LmdbVal.data(dataVal, arena) : null;
    }

    /// Zero-copy read with a zero-copy key: like [#getSegment(LmdbDbi, byte[])],
    /// but for a caller whose key is already off-heap (e.g. an mmap slice) —
    /// no `byte[]` bounce on either side of the call.
    ///
    /// @param dbi the database to read from
    /// @param key native key bytes to look up (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return the stored data, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public MemorySegment getSegment(LmdbDbi dbi, MemorySegment key) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        LmdbVal.set(keyVal, key);
        return getInto(dbi, keyVal, dataVal) ? LmdbVal.data(dataVal, arena) : null;
    }

    /// [#getSegment(LmdbDbi, MemorySegment)] for a direct [ByteBuffer] key.
    /// The key is `[position, limit)` of `key` ([MemorySegment#ofBuffer]), not
    /// its full capacity; a heap-backed buffer is rejected the same way a
    /// heap [MemorySegment] is.
    ///
    /// @param dbi the database to read from
    /// @param key native key bytes to look up, as a direct buffer's remaining content
    /// @return the stored data, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public MemorySegment getSegment(LmdbDbi dbi, ByteBuffer key) {
        Objects.requireNonNull(key, "key");
        return getSegment(dbi, MemorySegment.ofBuffer(key));
    }

    /// Looks up `key` in `dbi`, copying the stored data into a heap array. See
    /// [#getSegment(LmdbDbi, byte[])] for the zero-copy path.
    ///
    /// @param dbi the database to read from
    /// @param key the key to look up
    /// @return the stored data, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public byte[] get(LmdbDbi dbi, byte[] key) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        setKey(key);
        return getInto(dbi, keyVal, dataVal) ? LmdbVal.toByteArray(dataVal) : null;
    }

    /// Zero-copy read that maps the stored value straight to a result via
    /// `mapper`, with no `byte[]` copy: unlike [#get(LmdbDbi, byte[])], there
    /// is no heap array to allocate for the value (see [Mapper]). Use this
    /// when the value only needs to be parsed into a plain result — a
    /// `String`, a record, a checksum.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    the key to look up
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> R get(LmdbDbi dbi, byte[] key, Mapper<R> mapper) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        setKey(key);
        if (!getInto(dbi, keyVal, dataVal)) {
            return null;
        }
        return mapValue(dataVal, mapper, arena);
    }

    /// [#get(LmdbDbi, byte[], Mapper)] with a zero-copy key.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    native key bytes to look up (copied into a temporary
    ///               `MDB_val`; not retained after this call)
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> R get(LmdbDbi dbi, MemorySegment key, Mapper<R> mapper) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        LmdbVal.set(keyVal, key);
        if (!getInto(dbi, keyVal, dataVal)) {
            return null;
        }
        return mapValue(dataVal, mapper, arena);
    }

    /// [#get(LmdbDbi, byte[], Mapper)] for a direct [ByteBuffer] key — see
    /// [#getSegment(LmdbDbi, ByteBuffer)] for the key-range/heap-buffer caveats.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    native key bytes to look up, as a direct buffer's remaining content
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or `null` if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> R get(LmdbDbi dbi, ByteBuffer key, Mapper<R> mapper) {
        Objects.requireNonNull(key, "key");
        return get(dbi, MemorySegment.ofBuffer(key), mapper);
    }

    private static <R> R mapValue(MemorySegment dataVal, Mapper<R> mapper, Arena arena) {
        R result = mapper.map(LmdbVal.data(dataVal, arena));
        return Objects.requireNonNull(result, "Mapper.map(MemorySegment) must not return null");
    }

    // Copies key into the reused keyBuffer/keyVal fields (growing keyBuffer
    // if needed) instead of wrapping it in a fresh Arena/MDB_val per call —
    // see the arena/keyVal/dataVal field comment above.
    private void setKey(byte[] key) {
        keyBuffer = LmdbVal.growBuffer(arena, keyBuffer, Math.max(key.length, 1));
        MemorySegment.copy(key, 0, keyBuffer, JAVA_BYTE, 0, key.length);
        LmdbVal.set(keyVal, keyBuffer.asSlice(0, key.length));
    }

    // Two arms rather than one call through a selected handle: each keeps a
    // direct invokeExact against a static final MethodHandle, the shape the
    // JIT inlines. See Bindings#CURSOR_GET_CRITICAL for why a custom
    // comparator rules the critical handle out.
    private boolean getInto(LmdbDbi dbi, MemorySegment keyVal, MemorySegment dataVal) {
        int code;
        try {
            if (env.usesComparators()) {
                code = (int) Bindings.GET.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
            } else {
                code = (int) Bindings.GET_CRITICAL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
            }
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return NativeCall.checkFound(code);
    }

    /// Stores `data` under `key` in `dbi`.
    ///
    /// @param dbi   the database to write to
    /// @param key   the key to store
    /// @param data  the data to store
    /// @param flags the flags to write with, e.g. `Set.of()` or
    ///              `EnumSet.of(LmdbWriteFlag.NOOVERWRITE)`
    /// @throws LmdbException if the write fails (e.g. [LmdbErrorCode#KEY_EXIST]
    ///                       with [LmdbWriteFlag#NOOVERWRITE])
    /// @throws IllegalArgumentException if `flags` contains [LmdbWriteFlag#RESERVE]
    public void put(LmdbDbi dbi, byte[] key, byte[] data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(flags, "flags");
        LmdbWriteFlag.requireNoReserve(flags);
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            int code;
            try {
                code = (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal, bits);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// Zero-copy write: like [#put(LmdbDbi, byte[], byte[], Set)], for a
    /// caller whose key and data are already off-heap — no `byte[]` bounce on
    /// either side of the call.
    ///
    /// @param dbi   the database to write to
    /// @param key   native key bytes to store (copied into a temporary
    ///              `MDB_val`; not retained after this call)
    /// @param data  native data bytes to store (likewise not retained)
    /// @param flags the flags to write with
    /// @throws LmdbException if the write fails
    /// @throws IllegalArgumentException if `flags` contains [LmdbWriteFlag#RESERVE]
    public void put(LmdbDbi dbi, MemorySegment key, MemorySegment data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        Objects.requireNonNull(flags, "flags");
        LmdbWriteFlag.requireNoReserve(flags);
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            int code;
            try {
                code = (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal, bits);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// Reserves `size` bytes of storage under `key` in `dbi` (`mdb_put` with
    /// `MDB_RESERVE`) without copying any Java-side data in, then hands
    /// `filler` the reserved, writable native region to fill directly — the
    /// true zero-copy write path [LmdbWriteFlag#RESERVE] describes.
    /// [#put(LmdbDbi, byte[], byte[], Set)] and its `MemorySegment` sibling
    /// reject that flag rather than accept it and silently discard the
    /// caller's data (see [LmdbWriteFlag#RESERVE]'s own doc).
    ///
    /// @param dbi    the database to write to
    /// @param key    the key to store
    /// @param size   the number of bytes to reserve
    /// @param filler callback invoked once, synchronously, with the reserved
    ///               region — must fill all `size` bytes before returning;
    ///               the region is not valid after this call returns
    /// @throws LmdbException if the write fails
    /// @throws IllegalArgumentException if `size` is negative
    public void put(LmdbDbi dbi, byte[] key, int size, Consumer<MemorySegment> filler) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(filler, "filler");
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            LmdbVal.setSize(dataVal, size);
            int code;
            try {
                code = (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal,
                        LmdbWriteFlag.RESERVE.bits());
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            filler.accept(LmdbVal.reserved(dataVal, arena));
        }
    }

    /// [#put(LmdbDbi, MemorySegment, MemorySegment, Set)] for direct
    /// [ByteBuffer] key/data — see [#getSegment(LmdbDbi, ByteBuffer)] for the
    /// key-range/heap-buffer caveats (both apply here too).
    ///
    /// @param dbi   the database to write to
    /// @param key   the key to store, as a direct buffer's remaining content
    /// @param data  the data to store, as a direct buffer's remaining content
    /// @param flags the flags to write with
    /// @throws LmdbException if the write fails
    public void put(LmdbDbi dbi, ByteBuffer key, ByteBuffer data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        put(dbi, MemorySegment.ofBuffer(key), MemorySegment.ofBuffer(data), flags);
    }

    /// Deletes `key` (and, for an `MDB_DUPSORT` database, every duplicate data
    /// value under it) from `dbi`.
    ///
    /// @param dbi the database to delete from
    /// @param key the key to delete
    /// @return `true` if `key` was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, byte[] key) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            int code;
            try {
                code = (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, MemorySegment.NULL);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return NativeCall.checkFound(code);
        }
    }

    /// [#delete(LmdbDbi, byte[])] with a zero-copy key.
    ///
    /// @param dbi the database to delete from
    /// @param key native key bytes to delete (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return `true` if `key` was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, MemorySegment key) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            int code;
            try {
                code = (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, MemorySegment.NULL);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return NativeCall.checkFound(code);
        }
    }

    /// [#delete(LmdbDbi, MemorySegment)] for a direct [ByteBuffer] key.
    ///
    /// @param dbi the database to delete from
    /// @param key the key to delete, as a direct buffer's remaining content
    /// @return `true` if `key` was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, ByteBuffer key) {
        Objects.requireNonNull(key, "key");
        return delete(dbi, MemorySegment.ofBuffer(key));
    }

    /// Deletes one specific `data` duplicate under `key` from an `MDB_DUPSORT`
    /// database, leaving any other duplicates in place.
    ///
    /// @param dbi  the database to delete from
    /// @param key  the key to delete under
    /// @param data the specific duplicate value to delete
    /// @return `true` if the pair was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, byte[] key, byte[] data) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            int code;
            try {
                code = (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return NativeCall.checkFound(code);
        }
    }

    /// [#delete(LmdbDbi, byte[], byte[])] with zero-copy key and data.
    ///
    /// @param dbi  the database to delete from
    /// @param key  native key bytes to delete under (not retained after this call)
    /// @param data native data bytes identifying the duplicate (likewise not retained)
    /// @return `true` if the pair was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, MemorySegment key, MemorySegment data) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            int code;
            try {
                code = (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return NativeCall.checkFound(code);
        }
    }

    /// [#delete(LmdbDbi, MemorySegment, MemorySegment)] for direct
    /// [ByteBuffer] key/data.
    ///
    /// @param dbi  the database to delete from
    /// @param key  the key to delete under, as a direct buffer's remaining content
    /// @param data the specific duplicate value, as a direct buffer's remaining content
    /// @return `true` if the pair was present and deleted, `false` if it was not found
    /// @throws LmdbException if the delete fails
    public boolean delete(LmdbDbi dbi, ByteBuffer key, ByteBuffer data) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        return delete(dbi, MemorySegment.ofBuffer(key), MemorySegment.ofBuffer(data));
    }

    /// Installs a custom key-ordering function for `dbi` (`mdb_set_compare`),
    /// replacing LMDB's default lexicographic byte comparison. See
    /// [LmdbComparator] for the correctness requirements this carries — in
    /// particular, it must be called before any data is read or written
    /// through `dbi`, and the exact same ordering must be used every time
    /// this database is opened, by every program that opens it.
    ///
    /// Has a permanent cost for the whole environment, not just `dbi`: every
    /// read now has to call back into Java from inside LMDB's B+tree search,
    /// which rules out the faster `mdb_get`/`mdb_cursor_get` binding used
    /// otherwise (see [LmdbEnv]). Reads stay correct either way — they simply
    /// give up a measured 25–33% on cursor scans, and this cannot be undone,
    /// since LMDB offers no way to uninstall a comparator.
    ///
    /// @param dbi        the database to set the comparator for
    /// @param comparator the custom key-ordering function
    /// @throws LmdbException if the native call fails
    public void setComparator(LmdbDbi dbi, LmdbComparator comparator) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(comparator, "comparator");
        MemorySegment stub = env.upcallStub(comparator);
        int code;
        try {
            code = (int) Bindings.SET_COMPARE.invokeExact(ptr(), dbi.handle(), stub);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Like [#setComparator(LmdbDbi, LmdbComparator)], but orders the
    /// multiple values stored under one key in an `MDB_DUPSORT` database
    /// (`mdb_set_dupsort`) instead of the keys themselves. Carries the same
    /// environment-wide read cost described there.
    ///
    /// @param dbi        the `MDB_DUPSORT` database to set the comparator for
    /// @param comparator the custom value-ordering function
    /// @throws LmdbException if the native call fails
    public void setDupComparator(LmdbDbi dbi, LmdbComparator comparator) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(comparator, "comparator");
        MemorySegment stub = env.upcallStub(comparator);
        int code;
        try {
            code = (int) Bindings.SET_DUPSORT.invokeExact(ptr(), dbi.handle(), stub);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Opens a cursor on `dbi` within this transaction.
    ///
    /// @param dbi the database to navigate
    /// @return the new cursor
    /// @throws LmdbException if the open fails
    public LmdbCursor openCursor(LmdbDbi dbi) {
        Objects.requireNonNull(dbi, "dbi");
        return LmdbCursor.open(this, dbi);
    }

    /// The environment this transaction belongs to.
    ///
    /// @return the owning environment
    public LmdbEnv env() {
        return env;
    }

    /// This transaction's lifetime-scoped arena — used by [LmdbCursor] to
    /// scope the zero-copy segments its `get`/`getValue` overloads hand back
    /// to this transaction's lifetime, not the narrower cursor object's:
    /// `mdb_cursor_close` doesn't unmap anything, so a segment a cursor
    /// handed back must stay valid until the transaction itself ends or the
    /// entry is overwritten/deleted, per [LmdbCursor.Entry]'s own doc — not
    /// until the cursor happens to close. Fetched fresh at each call rather
    /// than cached, since [LmdbCursor#renew(LmdbTxn)] can change which
    /// transaction a cursor currently belongs to.
    Arena arena() {
        return arena;
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        List<LmdbCursor> stillOpen;
        try {
            Bindings.TXN_ABORT.invokeExact(ptr);
        } finally {
            stillOpen = stillOpenCursorsIfWriteTransaction();
            env.unregisterTransaction();
            arena.close();
        }
        if (!stillOpen.isEmpty()) {
            throw new LmdbContractException(
                    "LmdbTxn#close() called with " + stillOpen.size() + " LmdbCursor still open on "
                            + "a write transaction; aborting it (the try-with-resources safety net) "
                            + "frees those cursors as a side effect, so using or closing any of them "
                            + "further is no longer safe. Close every cursor before its write "
                            + "transaction ends.");
        }
    }
}
