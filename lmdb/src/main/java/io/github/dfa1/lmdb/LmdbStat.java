package io.github.dfa1.lmdb;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/// Statistics for a database (from `mdb_stat`) or the whole environment's
/// main database (from `mdb_env_stat`) — LMDB's `MDB_stat` struct.
///
/// @param pageSize       the size of a database page, in bytes (the same for every database)
/// @param depth          the depth (height) of the B-tree
/// @param branchPages    the number of internal (non-leaf) pages
/// @param leafPages      the number of leaf pages
/// @param overflowPages  the number of overflow pages
/// @param entries        the number of data items
public record LmdbStat(int pageSize, int depth, long branchPages, long leafPages, long overflowPages, long entries) {

    private static final VarHandle PSIZE =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_psize"));
    private static final VarHandle DEPTH =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_depth"));
    private static final VarHandle BRANCH_PAGES =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_branch_pages"));
    private static final VarHandle LEAF_PAGES =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_leaf_pages"));
    private static final VarHandle OVERFLOW_PAGES =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_overflow_pages"));
    private static final VarHandle ENTRIES =
            Bindings.STAT_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ms_entries"));

    static LmdbStat of(MemorySegment stat) {
        return new LmdbStat(
                (int) PSIZE.get(stat, 0L),
                (int) DEPTH.get(stat, 0L),
                (long) BRANCH_PAGES.get(stat, 0L),
                (long) LEAF_PAGES.get(stat, 0L),
                (long) OVERFLOW_PAGES.get(stat, 0L),
                (long) ENTRIES.get(stat, 0L));
    }
}
