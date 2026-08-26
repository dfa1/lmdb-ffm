package io.github.dfa1.lmdb;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Central registry of bound LMDB C functions.
///
/// Signatures and semantics follow `lmdb.h` in the vendored
/// `third_party/lmdb` submodule.
///
/// `size_t` (`mdb_size_t`) is modeled as
/// [java.lang.foreign.ValueLayout#JAVA_LONG] (LP64, and LMDB is not built here
/// with `MDB_VL32`). `MDB_dbi` (`unsigned int`) and `mdb_mode_t`/`mode_t`
/// (POSIX) both fit [java.lang.foreign.ValueLayout#JAVA_INT].
final class Bindings {

    // MDB_val { size_t mv_size; void* mv_data; } — verified via offsetof against
    // the vendored header (see scripts/build-lmdb.sh's sibling checks); not
    // derived from a C ABI header at build time since LMDB ships none.
    static final StructLayout VAL_LAYOUT =
            MemoryLayout.structLayout(JAVA_LONG.withName("mv_size"), ADDRESS.withName("mv_data"));

    // MDB_stat { unsigned ms_psize; unsigned ms_depth; size_t ms_branch_pages;
    //            size_t ms_leaf_pages; size_t ms_overflow_pages; size_t ms_entries; }
    static final StructLayout STAT_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("ms_psize"),
            JAVA_INT.withName("ms_depth"),
            JAVA_LONG.withName("ms_branch_pages"),
            JAVA_LONG.withName("ms_leaf_pages"),
            JAVA_LONG.withName("ms_overflow_pages"),
            JAVA_LONG.withName("ms_entries"));

    // MDB_envinfo { void* me_mapaddr; size_t me_mapsize; size_t me_last_pgno;
    //               size_t me_last_txnid; unsigned me_maxreaders; unsigned me_numreaders; }
    static final StructLayout ENVINFO_LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("me_mapaddr"),
            JAVA_LONG.withName("me_mapsize"),
            JAVA_LONG.withName("me_last_pgno"),
            JAVA_LONG.withName("me_last_txnid"),
            JAVA_INT.withName("me_maxreaders"),
            JAVA_INT.withName("me_numreaders"));

    // char* mdb_version(int *major, int *minor, int *patch)
    static final MethodHandle VERSION =
            NativeLibrary.lookup("mdb_version", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    // char* mdb_strerror(int err)
    static final MethodHandle STRERROR =
            NativeLibrary.lookup("mdb_strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));

    // --- environment ---

    // int mdb_env_create(MDB_env **env)
    static final MethodHandle ENV_CREATE =
            NativeLibrary.lookup("mdb_env_create", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // int mdb_env_open(MDB_env *env, const char *path, unsigned int flags, mdb_mode_t mode)
    static final MethodHandle ENV_OPEN =
            NativeLibrary.lookup("mdb_env_open",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    // void mdb_env_close(MDB_env *env)
    static final MethodHandle ENV_CLOSE =
            NativeLibrary.lookup("mdb_env_close", FunctionDescriptor.ofVoid(ADDRESS));
    // int mdb_env_set_mapsize(MDB_env *env, mdb_size_t size)
    static final MethodHandle ENV_SET_MAPSIZE =
            NativeLibrary.lookup("mdb_env_set_mapsize", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    // int mdb_env_set_maxdbs(MDB_env *env, MDB_dbi dbs)
    static final MethodHandle ENV_SET_MAXDBS =
            NativeLibrary.lookup("mdb_env_set_maxdbs", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    // int mdb_env_set_maxreaders(MDB_env *env, unsigned int readers)
    static final MethodHandle ENV_SET_MAXREADERS =
            NativeLibrary.lookup("mdb_env_set_maxreaders", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    // int mdb_env_get_maxkeysize(MDB_env *env)
    static final MethodHandle ENV_GET_MAXKEYSIZE =
            NativeLibrary.lookup("mdb_env_get_maxkeysize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // int mdb_env_sync(MDB_env *env, int force)
    static final MethodHandle ENV_SYNC =
            NativeLibrary.lookup("mdb_env_sync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    // int mdb_env_stat(MDB_env *env, MDB_stat *stat)
    static final MethodHandle ENV_STAT =
            NativeLibrary.lookup("mdb_env_stat", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    // int mdb_env_info(MDB_env *env, MDB_envinfo *stat)
    static final MethodHandle ENV_INFO =
            NativeLibrary.lookup("mdb_env_info", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    // int mdb_env_copy2(MDB_env *env, const char *path, unsigned int flags)
    static final MethodHandle ENV_COPY2 =
            NativeLibrary.lookup("mdb_env_copy2", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

    // --- transactions ---

    // int mdb_txn_begin(MDB_env *env, MDB_txn *parent, unsigned int flags, MDB_txn **txn)
    static final MethodHandle TXN_BEGIN =
            NativeLibrary.lookup("mdb_txn_begin",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    // int mdb_txn_commit(MDB_txn *txn)
    static final MethodHandle TXN_COMMIT =
            NativeLibrary.lookup("mdb_txn_commit", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // void mdb_txn_abort(MDB_txn *txn)
    static final MethodHandle TXN_ABORT =
            NativeLibrary.lookup("mdb_txn_abort", FunctionDescriptor.ofVoid(ADDRESS));
    // int mdb_txn_prepare(MDB_txn *txn)
    static final MethodHandle TXN_PREPARE =
            NativeLibrary.lookup("mdb_txn_prepare", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // void mdb_txn_reset(MDB_txn *txn)
    static final MethodHandle TXN_RESET =
            NativeLibrary.lookup("mdb_txn_reset", FunctionDescriptor.ofVoid(ADDRESS));
    // int mdb_txn_renew(MDB_txn *txn)
    static final MethodHandle TXN_RENEW =
            NativeLibrary.lookup("mdb_txn_renew", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // --- databases ---

    // int mdb_dbi_open(MDB_txn *txn, const char *name, unsigned int flags, MDB_dbi *dbi)
    static final MethodHandle DBI_OPEN =
            NativeLibrary.lookup("mdb_dbi_open",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    // void mdb_dbi_close(MDB_env *env, MDB_dbi dbi)
    static final MethodHandle DBI_CLOSE =
            NativeLibrary.lookup("mdb_dbi_close", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    // int mdb_drop(MDB_txn *txn, MDB_dbi dbi, int del)
    static final MethodHandle DROP =
            NativeLibrary.lookup("mdb_drop", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    // int mdb_stat(MDB_txn *txn, MDB_dbi dbi, MDB_stat *stat)
    static final MethodHandle STAT =
            NativeLibrary.lookup("mdb_stat", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    // --- data access ---

    // int mdb_get(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data)
    //
    // Linker.Option.critical(false) — same call shape and same justification
    // as CURSOR_GET above (reads through LMDB's memory map on the hottest
    // per-record path outside a cursor: getSegment/get/the Mapper
    // overloads), so see that binding's comment for the measured tradeoff
    // and the accepted cold-page-fault risk. Not extended to PUT/DEL/etc.:
    // those touch the mmap too but weren't a diagnosed bottleneck
    // (benchmark/WriteBenchmark already tracked lmdbjava within ~2-15%), so
    // there is little to gain for the same risk.
    static final MethodHandle GET =
            NativeLibrary.lookup("mdb_get",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    new Linker.Option[] {Linker.Option.critical(false)});
    // int mdb_put(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data, unsigned int flags)
    static final MethodHandle PUT =
            NativeLibrary.lookup("mdb_put",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    // int mdb_del(MDB_txn *txn, MDB_dbi dbi, MDB_val *key, MDB_val *data)
    static final MethodHandle DEL =
            NativeLibrary.lookup("mdb_del", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    // --- cursors ---

    // int mdb_cursor_open(MDB_txn *txn, MDB_dbi dbi, MDB_cursor **cursor)
    static final MethodHandle CURSOR_OPEN =
            NativeLibrary.lookup("mdb_cursor_open",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    // void mdb_cursor_close(MDB_cursor *cursor)
    static final MethodHandle CURSOR_CLOSE =
            NativeLibrary.lookup("mdb_cursor_close", FunctionDescriptor.ofVoid(ADDRESS));
    // int mdb_cursor_renew(MDB_txn *txn, MDB_cursor *cursor)
    static final MethodHandle CURSOR_RENEW =
            NativeLibrary.lookup("mdb_cursor_renew", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    // int mdb_cursor_get(MDB_cursor *cursor, MDB_val *key, MDB_val *data, MDB_cursor_op op)
    //
    // Linker.Option.critical(false): every downcall normally pays a JVM
    // thread-state transition (Java -> native -> Java, with its GC-safepoint
    // interaction) around the call; critical skips it. Measured on
    // benchmark/ReadBenchmark's readSeq/readRev (cursor NEXT/PREV in a tight
    // loop, the hottest possible caller of this binding): that transition,
    // not the ~40 B/op MemorySegment LmdbVal.data() allocates per read, was
    // the dominant remaining cost versus lmdbjava's JNR-FFI call path —
    // removing it took ffmReadSeq/ffmReadRev from ~0.012ms to ~0.008-0.009ms
    // (lmdbjava: ~0.010ms), *faster* than lmdbjava despite still allocating.
    //
    // KNOWN RISK, accepted deliberately: per the JDK's own Linker.Option
    // javadoc, critical requires "an extremely short running time in all
    // cases" and warns that violating this "is likely to have adverse
    // effects, such as loss of performance or JVM crashes." mdb_cursor_get
    // reads through LMDB's memory map, which CAN page-fault (disk I/O) on a
    // cold page — normal for a database larger than RAM, or after eviction
    // under memory pressure, exactly the workloads this project targets via
    // LmdbEnv#mapSize. That stall would sit inside a call the JVM has been
    // told never blocks. The benchmark that justified this change can't
    // surface that risk: its whole dataset stays resident in the page cache
    // for the run's duration. Scoped to this one binding only — not applied
    // to GET/PUT/etc. — since it is the only one actually measured.
    static final MethodHandle CURSOR_GET =
            NativeLibrary.lookup("mdb_cursor_get",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                    new Linker.Option[] {Linker.Option.critical(false)});
    // int mdb_cursor_put(MDB_cursor *cursor, MDB_val *key, MDB_val *data, unsigned int flags)
    static final MethodHandle CURSOR_PUT =
            NativeLibrary.lookup("mdb_cursor_put",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    // int mdb_cursor_del(MDB_cursor *cursor, unsigned int flags)
    static final MethodHandle CURSOR_DEL =
            NativeLibrary.lookup("mdb_cursor_del", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    // int mdb_cursor_count(MDB_cursor *cursor, mdb_size_t *countp)
    static final MethodHandle CURSOR_COUNT =
            NativeLibrary.lookup("mdb_cursor_count", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private Bindings() {
        // no instances
    }
}
