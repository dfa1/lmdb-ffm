package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;

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
/// Not thread-safe, and (per LMDB) confined to the thread that began it unless
/// the environment was opened with [LmdbEnvFlags#NOTLS].
///
/// {@snippet :
/// try (LmdbTxn txn = env.beginTxn()) {
///     txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), 0);
///     txn.commit();
/// }
/// }
public final class LmdbTxn extends NativeObject {

    private final LmdbEnv env;

    private LmdbTxn(MemorySegment ptr, LmdbEnv env) {
        super(ptr);
        this.env = env;
    }

    static LmdbTxn begin(LmdbEnv env, LmdbTxn parent, int flags) {
        MemorySegment parentPtr = parent == null ? MemorySegment.NULL : parent.ptr();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = NativeCall.createHandle(arena,
                    out -> (int) Bindings.TXN_BEGIN.invokeExact(env.ptr(), parentPtr, flags, out));
            return new LmdbTxn(ptr, env);
        }
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
        NativeCall.check(() -> (int) Bindings.TXN_COMMIT.invokeExact(p));
    }

    /// Aborts this transaction, discarding its writes (a read-only transaction
    /// has none to discard — this just releases its snapshot) and ending it.
    /// Ends this object's lifecycle exactly like [#commit()].
    ///
    /// @throws IllegalStateException if this transaction already ended
    public void abort() {
        MemorySegment p = take();
        try {
            Bindings.TXN_ABORT.invokeExact(p);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Opens the environment's unnamed database.
    ///
    /// @param flags OR of [LmdbDbiFlags] bits (e.g. `0` to open an existing
    ///              database, or [LmdbDbiFlags#CREATE] to create it if missing)
    /// @return the database handle
    /// @throws LmdbException if the open fails
    public LmdbDbi openDatabase(int flags) {
        return openDatabase(null, flags);
    }

    /// Opens a named database within the environment (the environment must
    /// have been configured with a large enough [LmdbEnv#maxDatabases(int)]).
    ///
    /// @param name  the database name, or `null` for the unnamed database
    /// @param flags OR of [LmdbDbiFlags] bits
    /// @return the database handle
    /// @throws LmdbException if the open fails
    public LmdbDbi openDatabase(String name, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment namePtr = name == null ? MemorySegment.NULL : arena.allocateFrom(name);
            int dbi = NativeCall.createIntHandle(arena,
                    out -> (int) Bindings.DBI_OPEN.invokeExact(ptr(), namePtr, flags, out));
            return new LmdbDbi(dbi);
        }
    }

    /// Empties `dbi`, and optionally deletes it from the environment.
    ///
    /// @param dbi    the database to drop
    /// @param delete if `true`, also delete the database handle itself
    /// @throws LmdbException if the drop fails
    public void drop(LmdbDbi dbi, boolean delete) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.check(() -> (int) Bindings.DROP.invokeExact(ptr(), dbi.handle(), delete ? 1 : 0));
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
            NativeCall.check(() -> (int) Bindings.STAT.invokeExact(ptr(), dbi.handle(), stat));
            return LmdbStat.of(stat);
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
    /// @return the stored data, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public Optional<MemorySegment> getSegment(LmdbDbi dbi, byte[] key) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            boolean found = NativeCall.checkFound(() ->
                    (int) Bindings.GET.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
            return found ? Optional.of(LmdbVal.data(dataVal)) : Optional.empty();
        }
    }

    /// Looks up `key` in `dbi`, copying the stored data into a heap array. See
    /// [#getSegment(LmdbDbi, byte[])] for the zero-copy path.
    ///
    /// @param dbi the database to read from
    /// @param key the key to look up
    /// @return the stored data, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public Optional<byte[]> get(LmdbDbi dbi, byte[] key) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            boolean found = NativeCall.checkFound(() ->
                    (int) Bindings.GET.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
            return found ? Optional.of(LmdbVal.toByteArray(dataVal)) : Optional.empty();
        }
    }

    /// Stores `data` under `key` in `dbi`.
    ///
    /// @param dbi   the database to write to
    /// @param key   the key to store
    /// @param data  the data to store
    /// @param flags OR of [LmdbWriteFlags] bits, or `0`
    /// @throws LmdbException if the write fails (e.g. [LmdbErrorCode#KEY_EXIST]
    ///                       with [LmdbWriteFlags#NOOVERWRITE])
    public void put(LmdbDbi dbi, byte[] key, byte[] data, int flags) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            NativeCall.check(() -> (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal, flags));
        }
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
            return NativeCall.checkFound(() ->
                    (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, MemorySegment.NULL));
        }
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
            return NativeCall.checkFound(() -> (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
        }
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

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        Bindings.TXN_ABORT.invokeExact(ptr);
    }
}
