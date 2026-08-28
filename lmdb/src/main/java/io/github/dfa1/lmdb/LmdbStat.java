package io.github.dfa1.lmdb;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/// Statistics for a database (from `mdb_stat`) or the whole environment's
/// main database (from `mdb_env_stat`) — LMDB's `MDB_stat` struct.
///
/// A plain class rather than a public record: every instance is a snapshot
/// read from a live native call, and a public canonical constructor would let
/// callers fabricate one with arbitrary values that were never actually
/// reported by LMDB.
public final class LmdbStat {

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

    private final LmdbByteSize pageSize;
    private final int depth;
    private final long branchPages;
    private final long leafPages;
    private final long overflowPages;
    private final long entries;

    private LmdbStat(
            LmdbByteSize pageSize, int depth, long branchPages, long leafPages, long overflowPages, long entries) {
        this.pageSize = pageSize;
        this.depth = depth;
        this.branchPages = branchPages;
        this.leafPages = leafPages;
        this.overflowPages = overflowPages;
        this.entries = entries;
    }

    static LmdbStat of(MemorySegment stat) {
        return new LmdbStat(
                LmdbByteSize.ofBytes((int) PSIZE.get(stat, 0L)),
                (int) DEPTH.get(stat, 0L),
                (long) BRANCH_PAGES.get(stat, 0L),
                (long) LEAF_PAGES.get(stat, 0L),
                (long) OVERFLOW_PAGES.get(stat, 0L),
                (long) ENTRIES.get(stat, 0L));
    }

    /// The size of a database page (the same for every database).
    ///
    /// @return the page size
    public LmdbByteSize pageSize() {
        return pageSize;
    }

    /// The depth (height) of the B-tree.
    ///
    /// @return the tree depth
    public int depth() {
        return depth;
    }

    /// The number of internal (non-leaf) pages.
    ///
    /// @return the branch page count
    public long branchPages() {
        return branchPages;
    }

    /// The number of leaf pages.
    ///
    /// @return the leaf page count
    public long leafPages() {
        return leafPages;
    }

    /// The number of overflow pages.
    ///
    /// @return the overflow page count
    public long overflowPages() {
        return overflowPages;
    }

    /// The number of data items.
    ///
    /// @return the entry count
    public long entries() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LmdbStat other)) {
            return false;
        }
        return pageSize.equals(other.pageSize)
                && depth == other.depth
                && branchPages == other.branchPages
                && leafPages == other.leafPages
                && overflowPages == other.overflowPages
                && entries == other.entries;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageSize, depth, branchPages, leafPages, overflowPages, entries);
    }

    @Override
    public String toString() {
        return "LmdbStat[pageSize=" + pageSize + ", depth=" + depth + ", branchPages=" + branchPages
                + ", leafPages=" + leafPages + ", overflowPages=" + overflowPages + ", entries=" + entries + "]";
    }
}
