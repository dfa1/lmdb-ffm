package io.github.dfa1.lmdb;

import java.lang.foreign.MemorySegment;

/// A custom key-ordering function (`MDB_cmp_func`), replacing LMDB's default
/// lexicographic byte comparison — see
/// [LmdbTxn#setComparator(LmdbDbi, LmdbComparator)] and
/// [LmdbTxn#setDupComparator(LmdbDbi, LmdbComparator)].
///
/// The sharp edge, straight from `mdb_set_compare`'s own doc comment: this
/// must be installed before any data is read or written through the
/// database, and the exact same ordering must be used every time that
/// database is opened, by every program that ever opens it. LMDB's B+tree is
/// built and balanced according to whichever comparator was active when data
/// went in; swapping comparators between writes (or between programs sharing
/// one database file) breaks the tree's invariants silently — no crash, just
/// wrong lookups and range scans, or a corruption LMDB has no way to detect.
///
/// [#compare(MemorySegment, MemorySegment)] must also never throw: it runs
/// inside a native upcall from deep within LMDB's B+tree traversal, with no
/// way to propagate a Java exception back through that C call. An exception
/// that escapes is caught and reported to prevent a JVM crash, but the
/// comparison it was making is then treated as `0` (equal) — silently wrong,
/// not a thrown failure a caller can react to.
@FunctionalInterface
public interface LmdbComparator {

    /// Compares two keys (or, for [LmdbTxn#setDupComparator(LmdbDbi, LmdbComparator)],
    /// two values stored under the same key) — the same contract as
    /// `java.util.Comparator#compare`: negative if `a` orders before `b`,
    /// positive if after, `0` if equal.
    ///
    /// Both segments are zero-copy, read-only views straight into LMDB's
    /// memory map, valid only for the duration of this call; do not retain
    /// either past it.
    ///
    /// @param a the first key/value
    /// @param b the second key/value
    /// @return negative, zero, or positive per `java.util.Comparator#compare`
    int compare(MemorySegment a, MemorySegment b);
}
