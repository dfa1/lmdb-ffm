package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Objects;

/// A database environment — wraps `MDB_env*`. An environment supports
/// multiple databases, all residing in the same shared memory map at one
/// filesystem path.
///
/// Configuration ([#mapSize(long)], [#maxDatabases(int)], [#maxReaders(int)])
/// must happen before [#open(Path, int, int)]; LMDB rejects changing them on
/// an open environment. Not thread-safe to configure or open concurrently,
/// but the resulting environment (via its transactions) is safe to share
/// across threads.
///
/// {@snippet :
/// try (LmdbEnv env = LmdbEnv.create().mapSize(10L << 20).open(dbPath, LmdbEnvFlags.NOSUBDIR)) {
///     try (LmdbTxn txn = env.beginTxn()) {
///         LmdbDbi dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
///         txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), 0);
///         txn.commit();
///     }
/// }
/// }
public final class LmdbEnv extends NativeObject {

    /// Default Unix file mode (`rw-r--r--`) for the data/lock files [#open]
    /// creates, matching upstream `mdb_stat`/`mdb_dump`'s own default.
    private static final int DEFAULT_MODE = 0644;

    private LmdbEnv(MemorySegment ptr) {
        super(ptr);
    }

    /// Creates a new environment handle. It must still be configured and
    /// [#open(Path, int, int)]ed before use.
    ///
    /// @return the new, unopened environment
    /// @throws LmdbException if the native call fails
    public static LmdbEnv create() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ptr = NativeCall.createHandle(arena,
                    out -> (int) Bindings.ENV_CREATE.invokeExact(out));
            return new LmdbEnv(ptr);
        }
    }

    /// Sets the size of the memory map (and so the maximum size of all
    /// databases combined). Must be called before [#open(Path, int, int)].
    ///
    /// @param bytes the map size, in bytes
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv mapSize(long bytes) {
        NativeCall.check(() -> (int) Bindings.ENV_SET_MAPSIZE.invokeExact(ptr(), bytes));
        return this;
    }

    /// Sets the maximum number of named databases this environment can hold.
    /// Must be called before [#open(Path, int, int)]; the default is `0`
    /// (only the unnamed database).
    ///
    /// @param count the maximum number of named databases
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv maxDatabases(int count) {
        NativeCall.check(() -> (int) Bindings.ENV_SET_MAXDBS.invokeExact(ptr(), count));
        return this;
    }

    /// Sets the maximum number of threads/reader slots for concurrent read
    /// transactions. Must be called before [#open(Path, int, int)].
    ///
    /// @param count the maximum number of concurrent reader slots
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv maxReaders(int count) {
        NativeCall.check(() -> (int) Bindings.ENV_SET_MAXREADERS.invokeExact(ptr(), count));
        return this;
    }

    /// Opens the environment at `path`, creating it with permissions
    /// `rw-r--r--` if it does not already exist.
    ///
    /// @param path  the filesystem path (a directory unless
    ///              [LmdbEnvFlags#NOSUBDIR] is set, in which case it names the
    ///              data file directly)
    /// @param flags OR of [LmdbEnvFlags] bits, or `0`
    /// @return `this`, for chaining
    /// @throws LmdbException if the open fails
    public LmdbEnv open(Path path, int flags) {
        return open(path, flags, DEFAULT_MODE);
    }

    /// Like [#open(Path, int)], with an explicit Unix file mode for a newly
    /// created data/lock file (ignored on platforms with no concept of one).
    ///
    /// @param path  the filesystem path
    /// @param flags OR of [LmdbEnvFlags] bits, or `0`
    /// @param mode  the Unix file mode for a newly created file
    /// @return `this`, for chaining
    /// @throws LmdbException if the open fails
    public LmdbEnv open(Path path, int flags, int mode) {
        Objects.requireNonNull(path, "path");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path.toString());
            NativeCall.check(() -> (int) Bindings.ENV_OPEN.invokeExact(ptr(), pathPtr, flags, mode));
        }
        return this;
    }

    /// The maximum size, in bytes, of a key (or of a data value in an
    /// `MDB_DUPSORT` database) this environment accepts. Depends on the
    /// compile-time page size; typically 511 bytes.
    ///
    /// @return the maximum key size, in bytes
    public int maxKeySize() {
        try {
            return (int) Bindings.ENV_GET_MAXKEYSIZE.invokeExact(ptr());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Flushes buffered data to disk. A no-op if the environment is read-only
    /// or if [LmdbEnvFlags#NOSYNC]/[LmdbEnvFlags#MAPASYNC] don't apply and
    /// every commit already synced.
    ///
    /// @param force flush unconditionally, even without [LmdbEnvFlags#NOSYNC]/[LmdbEnvFlags#MAPASYNC]
    /// @throws LmdbException if the sync fails
    public void sync(boolean force) {
        NativeCall.check(() -> (int) Bindings.ENV_SYNC.invokeExact(ptr(), force ? 1 : 0));
    }

    /// Statistics for the environment's unnamed database.
    ///
    /// @return the environment's statistics
    /// @throws LmdbException if the native call fails
    public LmdbStat stat() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stat = arena.allocate(Bindings.STAT_LAYOUT);
            NativeCall.check(() -> (int) Bindings.ENV_STAT.invokeExact(ptr(), stat));
            return LmdbStat.of(stat);
        }
    }

    /// Information about the environment (map address/size, last page/txn id,
    /// reader slot usage).
    ///
    /// @return the environment's information
    /// @throws LmdbException if the native call fails
    public LmdbEnvInfo info() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(Bindings.ENVINFO_LAYOUT);
            NativeCall.check(() -> (int) Bindings.ENV_INFO.invokeExact(ptr(), info));
            return LmdbEnvInfo.of(info);
        }
    }

    /// Begins a new read-write transaction with no parent.
    ///
    /// @return the new transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn() {
        return beginTxn(0);
    }

    /// Begins a new transaction with no parent.
    ///
    /// @param flags OR of [LmdbEnvFlags] bits — only [LmdbEnvFlags#RDONLY] is
    ///              meaningful here, for a read-only transaction
    /// @return the new transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn(int flags) {
        return LmdbTxn.begin(this, null, flags);
    }

    /// Begins a new transaction nested inside `parent`. `parent` may have no
    /// other open child and does no work of its own until every child either
    /// commits or aborts.
    ///
    /// @param parent the parent transaction
    /// @param flags  OR of [LmdbEnvFlags] bits
    /// @return the new nested transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn(LmdbTxn parent, int flags) {
        Objects.requireNonNull(parent, "parent");
        return LmdbTxn.begin(this, parent, flags);
    }

    /// Closes `dbi`'s handle in this environment. LMDB itself discourages
    /// this in a multi-threaded environment unless the caller can guarantee no
    /// other thread is using `dbi` — see [LmdbDbi].
    ///
    /// @param dbi the database handle to close
    public void closeDatabase(LmdbDbi dbi) {
        Objects.requireNonNull(dbi, "dbi");
        try {
            Bindings.DBI_CLOSE.invokeExact(ptr(), dbi.handle());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        Bindings.ENV_CLOSE.invokeExact(ptr);
    }
}
