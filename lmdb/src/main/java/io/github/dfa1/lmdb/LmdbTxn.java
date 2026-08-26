package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
/// the environment was opened with [LmdbEnvFlag#NOTLS].
///
/// {@snippet :
/// try (LmdbTxn txn = env.beginTxn()) {
///     txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), Set.of());
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
            int dbi = NativeCall.createIntHandle(arena,
                    out -> (int) Bindings.DBI_OPEN.invokeExact(ptr(), namePtr, bits, out));
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
            return getInto(dbi, keyVal, dataVal) ? Optional.of(LmdbVal.data(dataVal)) : Optional.empty();
        }
    }

    /// Zero-copy read with a zero-copy key: like [#getSegment(LmdbDbi, byte[])],
    /// but for a caller whose key is already off-heap (e.g. an mmap slice) —
    /// no `byte[]` bounce on either side of the call.
    ///
    /// @param dbi the database to read from
    /// @param key native key bytes to look up (copied into a temporary
    ///            `MDB_val`; not retained after this call)
    /// @return the stored data, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public Optional<MemorySegment> getSegment(LmdbDbi dbi, MemorySegment key) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            return getInto(dbi, keyVal, dataVal) ? Optional.of(LmdbVal.data(dataVal)) : Optional.empty();
        }
    }

    /// [#getSegment(LmdbDbi, MemorySegment)] for a direct [ByteBuffer] key.
    /// The key is `[position, limit)` of `key` ([MemorySegment#ofBuffer]), not
    /// its full capacity; a heap-backed buffer is rejected the same way a
    /// heap [MemorySegment] is.
    ///
    /// @param dbi the database to read from
    /// @param key native key bytes to look up, as a direct buffer's remaining content
    /// @return the stored data, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public Optional<MemorySegment> getSegment(LmdbDbi dbi, ByteBuffer key) {
        Objects.requireNonNull(key, "key");
        return getSegment(dbi, MemorySegment.ofBuffer(key));
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
            return getInto(dbi, keyVal, dataVal) ? Optional.of(LmdbVal.toByteArray(dataVal)) : Optional.empty();
        }
    }

    /// Zero-copy read that maps the stored value straight to a result via
    /// `mapper`, with no `byte[]` copy and no [MemorySegment] left over
    /// afterward to manage: unlike [#getSegment(LmdbDbi, byte[])], the view
    /// passed to `mapper` becomes inaccessible the instant this call returns
    /// (see [Mapper]). Use this when the value only needs to be parsed into a
    /// plain result — a `String`, a record, a checksum.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    the key to look up
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> Optional<R> get(LmdbDbi dbi, byte[] key, Mapper<R> mapper) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            if (!getInto(dbi, keyVal, dataVal)) {
                return Optional.empty();
            }
            return Optional.of(mapValue(arena, dataVal, mapper));
        }
    }

    /// [#get(LmdbDbi, byte[], Mapper)] with a zero-copy key.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    native key bytes to look up (copied into a temporary
    ///               `MDB_val`; not retained after this call)
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> Optional<R> get(LmdbDbi dbi, MemorySegment key, Mapper<R> mapper) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        Objects.requireNonNull(mapper, "mapper");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.allocate(arena);
            if (!getInto(dbi, keyVal, dataVal)) {
                return Optional.empty();
            }
            return Optional.of(mapValue(arena, dataVal, mapper));
        }
    }

    /// [#get(LmdbDbi, byte[], Mapper)] for a direct [ByteBuffer] key — see
    /// [#getSegment(LmdbDbi, ByteBuffer)] for the key-range/heap-buffer caveats.
    ///
    /// @param <R>    the type produced by `mapper`
    /// @param dbi    the database to read from
    /// @param key    native key bytes to look up, as a direct buffer's remaining content
    /// @param mapper callback invoked with a zero-copy view of the stored value
    /// @return the mapped result, or empty if `key` is not present
    /// @throws LmdbException if the native call fails
    public <R> Optional<R> get(LmdbDbi dbi, ByteBuffer key, Mapper<R> mapper) {
        Objects.requireNonNull(key, "key");
        return get(dbi, MemorySegment.ofBuffer(key), mapper);
    }

    private static <R> R mapValue(Arena arena, MemorySegment dataVal, Mapper<R> mapper) {
        R result = mapper.map(LmdbVal.dataScoped(arena, dataVal));
        return Objects.requireNonNull(result, "Mapper.map(MemorySegment) must not return null");
    }

    private boolean getInto(LmdbDbi dbi, MemorySegment keyVal, MemorySegment dataVal) {
        return NativeCall.checkFound(() -> (int) Bindings.GET.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
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
    public void put(LmdbDbi dbi, byte[] key, byte[] data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(dbi, "dbi");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            NativeCall.check(() -> (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal, bits));
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
    public void put(LmdbDbi dbi, MemorySegment key, MemorySegment data, Set<LmdbWriteFlag> flags) {
        Objects.requireNonNull(dbi, "dbi");
        NativeCall.requireNative(key, "key");
        NativeCall.requireNative(data, "data");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyVal = LmdbVal.of(arena, key);
            MemorySegment dataVal = LmdbVal.of(arena, data);
            NativeCall.check(() -> (int) Bindings.PUT.invokeExact(ptr(), dbi.handle(), keyVal, dataVal, bits));
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
            return NativeCall.checkFound(() ->
                    (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, MemorySegment.NULL));
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
            return NativeCall.checkFound(() ->
                    (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, MemorySegment.NULL));
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
            return NativeCall.checkFound(() -> (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
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
            return NativeCall.checkFound(() -> (int) Bindings.DEL.invokeExact(ptr(), dbi.handle(), keyVal, dataVal));
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
