package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// A database environment — wraps `MDB_env*`. An environment supports
/// multiple databases, all residing in the same shared memory map at one
/// filesystem path.
///
/// Configuration ([#mapSize(LmdbByteSize)], [#maxDatabases(int)],
/// [#maxReaders(int)]) must happen before [#open(Path, Set, int)]; LMDB
/// rejects changing them on an open environment. Not thread-safe to configure
/// or open concurrently, but the resulting environment (via its transactions)
/// is safe to share across threads.
///
/// {@snippet :
/// try (LmdbEnv env = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dbPath, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
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

    // Backs every upcall stub built by #upcallStub(LmdbComparator) for
    // LmdbTxn#setComparator/#setDupComparator. Those stubs must stay valid
    // for as long as LMDB may call back into them — every future access to
    // the dbi they were installed on, across every transaction, not just the
    // call that installed them — so this is scoped to the environment's own
    // lifetime rather than a per-call Arena.
    private final Arena arena = Arena.ofConfined();

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
    /// @param bytes the map size
    /// @return `this`, for chaining
    /// @throws LmdbException if the native call fails
    public LmdbEnv mapSize(LmdbByteSize bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int code;
        try {
            code = (int) Bindings.ENV_SET_MAPSIZE.invokeExact(ptr(), bytes.bytes());
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

    /// The maximum number of concurrent reader slots configured via
    /// [#maxReaders(int)] (or LMDB's default of 126). Equivalent to
    /// [LmdbEnvInfo#maxReaders()] (from [#info()]), via a direct call instead
    /// of reading the whole `MDB_envinfo` struct.
    ///
    /// @return the maximum number of concurrent reader slots
    /// @throws LmdbException if the native call fails
    public int maxReaders() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.ENV_GET_MAXREADERS.invokeExact(ptr(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return out.get(JAVA_INT, 0L);
        }
    }

    /// Sets the size of database pages. Must be called before
    /// [#open(Path, Set, int)]; defaults to the OS page size. Rarely needed
    /// outside filesystems (e.g. ZFS) that don't share the OS's own page size.
    ///
    /// @param bytes the page size
    /// @return `this`, for chaining
    /// @throws LmdbException      if the native call fails
    /// @throws ArithmeticException if `bytes` exceeds `Integer.MAX_VALUE`
    ///                             (`mdb_env_set_pagesize` takes a native `int`)
    public LmdbEnv pageSize(LmdbByteSize bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int code;
        try {
            code = (int) Bindings.ENV_SET_PAGESIZE.invokeExact(ptr(), bytes.toIntBytes());
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

    /// The maximum size of a key (or of a data value in an `MDB_DUPSORT`
    /// database) this environment accepts. Depends on the compile-time page
    /// size; typically 511 bytes.
    ///
    /// @return the maximum key size
    public LmdbByteSize maxKeySize() {
        try {
            return LmdbByteSize.ofBytes((int) Bindings.ENV_GET_MAXKEYSIZE.invokeExact(ptr()));
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

    /// Sets or clears `flags` on this already-open environment, in addition
    /// to (or overriding) whatever was passed to [#open(Path, Set)]. Only
    /// flags meaningful to change at runtime should be used here (e.g.
    /// [LmdbEnvFlag#NOSYNC]/[LmdbEnvFlag#MAPASYNC]/[LmdbEnvFlag#NOMEMINIT]);
    /// LMDB does not itself validate that a given flag makes sense to flip
    /// post-open. Undefined if multiple threads change flags at once.
    ///
    /// @param flags the flags to set or clear, e.g. `EnumSet.of(LmdbEnvFlag.NOSYNC)`
    /// @param on    `true` to set them, `false` to clear them
    /// @throws LmdbException if the native call fails
    public void setFlags(Set<LmdbEnvFlag> flags, boolean on) {
        Objects.requireNonNull(flags, "flags");
        int bits = LmdbFlag.toBits(flags);
        int code;
        try {
            code = (int) Bindings.ENV_SET_FLAGS.invokeExact(ptr(), bits, on ? 1 : 0);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
    }

    /// The flags currently active on this environment — those passed to
    /// [#open(Path, Set)] plus any later change via [#setFlags(Set, boolean)].
    ///
    /// @return the active flags
    /// @throws LmdbException if the native call fails
    public Set<LmdbEnvFlag> flags() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.ENV_GET_FLAGS.invokeExact(ptr(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return LmdbFlag.fromBits(out.get(JAVA_INT, 0L), LmdbEnvFlag.class);
        }
    }

    /// The filesystem path this environment was [#open(Path, Set)]ed with.
    ///
    /// @return the environment's path
    /// @throws LmdbException if the native call fails
    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    public Path path() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int code;
            try {
                code = (int) Bindings.ENV_GET_PATH.invokeExact(ptr(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            MemorySegment pathPtr = out.get(ADDRESS, 0L);
            return Path.of(pathPtr.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8));
        }
    }

    /// This environment's underlying file descriptor (POSIX) or file handle
    /// (Windows), widened to a `long` either way. Meant for POSIX use cases
    /// like closing the descriptor across `fork()`+`exec()`; LMDB's own doc
    /// notes this may be called after `fork()` for exactly that reason.
    ///
    /// @return the native file descriptor or handle, as a raw numeric value
    /// @throws LmdbException if the native call fails
    public long fd() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG);
            int code;
            try {
                code = (int) Bindings.ENV_GET_FD.invokeExact(ptr(), out);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            // mdb_filehandle_t is `int` on POSIX, `void*` (HANDLE) on Windows —
            // only as many bytes as the platform's own type actually are were
            // written into `out`, so the read width must match.
            return NativeLibrary.classifier().startsWith("windows")
                    ? out.get(JAVA_LONG, 0L)
                    : out.get(JAVA_INT, 0L);
        }
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

    /// Performs an incremental (delta-since-`sinceTxnid`) backup of this
    /// environment to `path` (`mdb_env_incr_dump`), for pairing with
    /// [#rollback(long)]-style two-phase-commit recovery: `sinceTxnid` is the
    /// last transaction ID already captured by an earlier full or
    /// incremental backup.
    ///
    /// This has a real side effect on this environment beyond writing
    /// `path`: LMDB's implementation of this call unconditionally sets
    /// [LmdbEnvFlag#NOSUBDIR] on this environment's own flags, so
    /// [#flags()] reports it afterward even if this environment was never
    /// opened with it. That is an LMDB implementation quirk in this call,
    /// not something this binding adds or can suppress.
    ///
    /// @param path        the file to write the incremental backup to —
    ///                    must not already exist
    /// @param sinceTxnid  the transaction ID of a previous backup; must be
    ///                    greater than `0`
    /// @throws LmdbException if the dump fails
    public void incrementalDumpTo(Path path, long sinceTxnid) {
        Objects.requireNonNull(path, "path");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathPtr = arena.allocateFrom(path.toString());
            int code;
            try {
                code = (int) Bindings.ENV_INCR_DUMP.invokeExact(ptr(), pathPtr, sinceTxnid);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
        }
    }

    /// Rolls back the last committed transaction, undoing its writes as if it
    /// had never committed (`mdb_env_rollback`) — for two-phase commit
    /// protocols only: use this when the local phase of a multi-phase
    /// transaction has fully committed, but some other remote phase that had
    /// successfully prepared has since failed to commit.
    ///
    /// `txnid` must be that exact transaction's [LmdbTxn#id()], captured
    /// before its [LmdbTxn#commit()] (the transaction handle itself is freed
    /// by commit). This must be called immediately after that commit — no
    /// other operation may run on this environment, by any process, in
    /// between — and must not be called twice in a row. It also can never
    /// undo an environment's very first commit: LMDB rolls back to the
    /// *other* of its two metapages, and that one is still in its unwritten
    /// initial state until a second transaction commits.
    ///
    /// @param txnid the ID of the committed transaction to roll back
    /// @throws LmdbException if the rollback fails, e.g. [LmdbErrorCode#CANT_ROLLBACK]
    ///                        if a rollback was already done, there is no
    ///                        other valid metapage to roll back to, or
    ///                        another transaction has already been
    ///                        committed over `txnid`
    public void rollback(long txnid) {
        int code;
        try {
            code = (int) Bindings.ENV_ROLLBACK.invokeExact(ptr(), txnid);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        NativeCall.check(code);
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

    /// Dumps the reader lock table, one line at a time, to `handler`
    /// (`mdb_reader_list`) — a header line followed by one line per active
    /// reader slot, or a single explanatory line if there are none.
    ///
    /// @param handler receives each line; return `false` from it to stop early
    public void listReaders(LmdbMessageHandler handler) {
        Objects.requireNonNull(handler, "handler");
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment stub = LmdbMessageHandlers.upcallStub(scratch, handler);
            // The returned int only ever reflects handler's own continue/stop
            // protocol (see LmdbMessageHandlers#invoke), never a genuine
            // MDB_* error code, so it is intentionally discarded rather than
            // passed to NativeCall.check.
            try {
                int ignored = (int) Bindings.READER_LIST.invokeExact(ptr(), stub, MemorySegment.NULL);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
        }
    }

    /// Clears stale entries in the reader lock table (`mdb_reader_check`) —
    /// reader slots left behind by a process that held a read-only
    /// transaction open and then crashed or was killed without releasing it.
    ///
    /// @return the number of stale slots that were cleared
    /// @throws LmdbException if the native call fails
    public int checkReaders() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment deadPtr = arena.allocate(JAVA_INT);
            int code;
            try {
                code = (int) Bindings.READER_CHECK.invokeExact(ptr(), deadPtr);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            NativeCall.check(code);
            return deadPtr.get(JAVA_INT, 0L);
        }
    }

    /// Builds a native upcall stub trampolining into `comparator`, kept alive
    /// for this environment's whole lifetime — see
    /// [LmdbTxn#setComparator(LmdbDbi, LmdbComparator)].
    MemorySegment upcallStub(LmdbComparator comparator) {
        return LmdbComparators.upcallStub(arena, comparator);
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        try {
            Bindings.ENV_CLOSE.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }
}
