package io.github.dfa1.lmdb;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LmdbStatTest {

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

    @Nested
    class Parsing {

        @Test
        void readsEveryFieldFromTheNativeStruct() {
            // Given a native MDB_stat struct with distinct field values
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment stat = statSegment(arena, 4096, 2, 10, 20, 3, 42);

                // When it is parsed
                LmdbStat sut = LmdbStat.of(stat);

                // Then every accessor reflects the struct's own field
                assertThat(sut.pageSize()).isEqualTo(LmdbByteSize.ofBytes(4096));
                assertThat(sut.depth()).isEqualTo(2);
                assertThat(sut.branchPages()).isEqualTo(10);
                assertThat(sut.leafPages()).isEqualTo(20);
                assertThat(sut.overflowPages()).isEqualTo(3);
                assertThat(sut.entries()).isEqualTo(42);
            }
        }
    }

    @Nested
    class EqualityAndHashCode {

        @Test
        void statsWithTheSameFieldsAreEqual() {
            // Given two stats parsed from structs with identical field values
            try (Arena arena = Arena.ofConfined()) {
                LmdbStat a = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 42));
                LmdbStat b = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 42));

                // Then they are equal and share a hash code
                assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
            }
        }

        @Test
        void statsDifferingInAnyFieldAreNotEqual() {
            // Given a baseline stat and one differing only in entries
            try (Arena arena = Arena.ofConfined()) {
                LmdbStat baseline = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 42));
                LmdbStat differentEntries = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 43));

                // Then they are not equal
                assertThat(baseline).isNotEqualTo(differentEntries);
            }
        }

        @Test
        void isNotEqualToAnUnrelatedType() {
            // Given a stat and an unrelated object
            try (Arena arena = Arena.ofConfined()) {
                LmdbStat sut = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 42));

                // Then it is not equal to it
                assertThat(sut).isNotEqualTo("not a stat");
            }
        }
    }

    @Nested
    class Rendering {

        @Test
        void toStringIncludesEveryField() {
            // Given a stat
            try (Arena arena = Arena.ofConfined()) {
                LmdbStat sut = LmdbStat.of(statSegment(arena, 4096, 2, 10, 20, 3, 42));

                // Then its string form names and shows every field
                assertThat(sut).hasToString("LmdbStat[pageSize=LmdbByteSize[bytes=4096], depth=2, branchPages=10, "
                        + "leafPages=20, overflowPages=3, entries=42]");
            }
        }
    }

    private static MemorySegment statSegment(
            Arena arena, int pageSize, int depth, long branchPages, long leafPages, long overflowPages,
            long entries) {
        MemorySegment stat = arena.allocate(Bindings.STAT_LAYOUT);
        PSIZE.set(stat, 0L, pageSize);
        DEPTH.set(stat, 0L, depth);
        BRANCH_PAGES.set(stat, 0L, branchPages);
        LEAF_PAGES.set(stat, 0L, leafPages);
        OVERFLOW_PAGES.set(stat, 0L, overflowPages);
        ENTRIES.set(stat, 0L, entries);
        return stat;
    }
}
