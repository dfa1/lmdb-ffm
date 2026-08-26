package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;

/// A database environment — wraps `MDB_env*`. An environment supports
/// multiple databases, all residing in the same shared memory map at one
/// filesystem path.
///
/// Configuration ([#mapSize(long)], [#maxDatabases(int)], [#maxReaders(int)])
/// must happen before [#open(Path, Set, int)]; LMDB rejects changing them on
/// an open environment. Not thread-safe to configure or open concurrently,
/// but the resulting environment (via its transactions) is safe to share
/// across threads.
///
/// {@snippet :
/// try (LmdbEnv env = LmdbEnv.create().mapSize(10L << 20).open(dbPath, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
///     try (LmdbTxn txn = env.beginTxn()) {
///         LmdbDbi dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
///         txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), Set.of());
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
    /// [#open(Path, Set, int)]ed before use.
    ///
    /// @return the new, unopened environment
    /// @throws LmdbException if the native call fails
    public static LmdbEnv create() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int code;
            try {
                code = (int) Bindings.ENV_CREATE.invokeExact(out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return new LmdbEnv(out.get(ADDRESS, 0));
        }
    }

    /// Sets the size of the memory map (and so the maximum size of all
    /// databases combined). Must be called before [#open(Path, Set, int)].
    ///
    /// @param bytes the map size, in bytes
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv mapSize(long bytes) {
        int code;
        try {
            code = (int) Bindings.ENV_SET_MAPSIZE.invokeExact(ptr(), bytes);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
        return this;
    }

    /// Sets the maximum number of named databases this environment can hold.
    /// Must be called before [#open(Path, Set, int)]; the default is `0`
    /// (only the unnamed database).
    ///
    /// @param count the maximum number of named databases
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv maxDatabases(int count) {
        int code;
        try {
            code = (int) Bindings.ENV_SET_MAXDBS.invokeExact(ptr(), count);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
        return this;
    }

    /// Sets the maximum number of threads/reader slots for concurrent read
    /// transactions. Must be called before [#open(Path, Set, int)].
    ///
    /// @param count the maximum number of concurrent reader slots
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv maxReaders(int count) {
        int code;
        try {
            code = (int) Bindings.ENV_SET_MAXREADERS.invokeExact(ptr(), count);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
        return this;
    }

    /// Opens the environment at `path`, creating it with permissions
    /// `rw-r--r--` if it does not already exist.
    ///
    /// @param path  the filesystem path (a directory unless
    ///              [LmdbEnvFlag#NOSUBDIR] is set, in which case it names the
    ///              data file directly)
    /// @param flags the flags to open with, e.g. `EnumSet.of(LmdbEnvFlag.NOSUBDIR)`
    ///              (`Set.of()` or `EnumSet.noneOf(LmdbEnvFlag.class)` for none)
    /// @return `this`, for chaining
    /// @throws LmdbException if the open fails
    public LmdbEnv open(Path path, Set<LmdbEnvFlag> flags) {
        return open(path, flags, DEFAULT_MODE);
    }

    /// Like [#open(Path, Set)], with an explicit Unix file mode for a newly
    /// created data/lock file (ignored on platforms with no concept of one).
    ///
    /// @param path  the filesystem path
    /// @param flags the flags to open with, e.g. `EnumSet.of(LmdbEnvFlag.NOSUBDIR)`
    /// @param mode  the Unix file mode for a newly created file
    /// @return `this`, for chaining
    /// @throws LmdbException if the open fails
    public LmdbEnv open(Path path, Set<LmdbEnvFlag> flags, int mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path.toString());
            int code;
            try {
                code = (int) Bindings.ENV_OPEN.invokeExact(ptr(), pathPtr, bits, mode);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
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
    /// or if [LmdbEnvFlag#NOSYNC]/[LmdbEnvFlag#MAPASYNC] don't apply and
    /// every commit already synced.
    ///
    /// @param force flush unconditionally, even without [LmdbEnvFlag#NOSYNC]/[LmdbEnvFlag#MAPASYNC]
    /// @throws LmdbException if the sync fails
    public void sync(boolean force) {
        int code;
        try {
            code = (int) Bindings.ENV_SYNC.invokeExact(ptr(), force ? 1 : 0);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// Statistics for the environment's unnamed database.
    ///
    /// @return the environment's statistics
    /// @throws LmdbException if the native call fails
    public LmdbStat stat() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stat = arena.allocate(Bindings.STAT_LAYOUT);
            int code;
            try {
                code = (int) Bindings.ENV_STAT.invokeExact(ptr(), stat);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
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
            int code;
            try {
                code = (int) Bindings.ENV_INFO.invokeExact(ptr(), info);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return LmdbEnvInfo.of(info);
        }
    }

    /// Copies this environment to `path`, for backup or to distribute a
    /// read-only snapshot to another host (LMDB databases must not be opened
    /// directly from a remote filesystem — see [#open(Path, Set)] and copy to
    /// a local path on each destination instead). No lockfile is copied; one
    /// is recreated on demand when the copy is opened. Safe to run
    /// concurrently with write transactions (it reads through its own
    /// read-only transaction), though heavy concurrent writing can grow the
    /// copy's file size.
    ///
    /// @param path  the directory the copy is written into — must already
    ///              exist, be writable, and otherwise be empty
    /// @param flags flags for the copy, e.g. `EnumSet.of(LmdbCopyFlag.COMPACT)`
    ///              (`Set.of()` for a plain copy)
    /// @throws LmdbException if the copy fails
    public void copyTo(Path path, Set<LmdbCopyFlag> flags) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path.toString());
            int code;
            try {
                code = (int) Bindings.ENV_COPY2.invokeExact(ptr(), pathPtr, bits);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// Begins a new read-write transaction with no parent.
    ///
    /// @return the new transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn() {
        return beginTxn(Set.of());
    }

    /// Begins a new transaction with no parent.
    ///
    /// @param flags flags for the new transaction — only [LmdbEnvFlag#RDONLY]
    ///              is meaningful here, for a read-only transaction (`Set.of()`
    ///              for a read-write one)
    /// @return the new transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn(Set<LmdbEnvFlag> flags) {
        Objects.requireNonNull(flags, "flags");
        return LmdbTxn.begin(this, null, LmdbFlag.toBits(flags));
    }

    /// Begins a new transaction nested inside `parent`. `parent` may have no
    /// other open child and does no work of its own until every child either
    /// commits or aborts.
    ///
    /// @param parent the parent transaction
    /// @param flags  flags for the new transaction
    /// @return the new nested transaction
    /// @throws LmdbException if the native call fails
    public LmdbTxn beginTxn(LmdbTxn parent, Set<LmdbEnvFlag> flags) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(flags, "flags");
        return LmdbTxn.begin(this, parent, LmdbFlag.toBits(flags));
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
