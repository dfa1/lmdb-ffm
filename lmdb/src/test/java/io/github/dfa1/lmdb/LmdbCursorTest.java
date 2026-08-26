package io.github.dfa1.lmdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

class LmdbCursorTest {

    private LmdbEnv env;

    @BeforeEach
    void openEnvironment(@TempDir Path dir) {
        env = LmdbEnv.create().mapSize(10L << 20).maxDatabases(4).open(dir, Set.of());
    }

    @AfterEach
    void closeEnvironment() {
        env.close();
    }

    @Nested
    class Iteration {

        @Test
        void firstAndNextWalkEntriesInKeyOrder() {
            // Given three keys inserted out of order
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("b"), bytes("2"), Set.of());
                txn.put(dbi, bytes("a"), bytes("1"), Set.of());
                txn.put(dbi, bytes("c"), bytes("3"), Set.of());
                txn.commit();
            }

            // When walked from FIRST via NEXT
            List<String> keys = new ArrayList<>();
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                for (Optional<LmdbCursor.Entry> e = cursor.get(LmdbCursorOp.FIRST);
                        e.isPresent();
                        e = cursor.get(LmdbCursorOp.NEXT)) {
                    keys.add(text(e.orElseThrow().key()));
                }
            }

            // Then they come back in LMDB's sorted (lexicographic) key order
            assertThat(keys).containsExactly("a", "b", "c");
        }

        @Test
        void nextOnAnEmptyDatabaseIsEmpty() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When positioned at FIRST
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                Optional<LmdbCursor.Entry> result = cursor.get(LmdbCursorOp.FIRST);

                // Then there is nothing to find
                assertThat(result).isEmpty();
            }
        }

        @Test
        void setRangePositionsAtTheFirstKeyGreaterOrEqual() {
            // Given keys "a" and "c" (no "b")
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("a"), bytes("1"), Set.of());
                txn.put(dbi, bytes("c"), bytes("3"), Set.of());
                txn.commit();
            }

            // When positioned with SET_RANGE at "b"
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                Optional<LmdbCursor.Entry> result = cursor.get(LmdbCursorOp.SET_RANGE, bytes("b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isPresent();
                assertThat(text(result.orElseThrow().key())).isEqualTo("c");
            }
        }

        @Test
        void getAcceptsANativeKeySegment() {
            // Given keys "a" and "c"
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("a"), bytes("1"), Set.of());
                txn.put(dbi, bytes("c"), bytes("3"), Set.of());
                txn.commit();
            }

            // When positioned with SET_RANGE via a native key segment
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi);
                    Arena arena = Arena.ofConfined()) {
                Optional<LmdbCursor.Entry> result = cursor.get(LmdbCursorOp.SET_RANGE, nativeBytes(arena, "b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isPresent();
                assertThat(text(result.orElseThrow().key())).isEqualTo("c");
            }
        }

        @Test
        void getAcceptsADirectByteBufferKey() {
            // Given keys "a" and "c"
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("a"), bytes("1"), Set.of());
                txn.put(dbi, bytes("c"), bytes("3"), Set.of());
                txn.commit();
            }

            // When positioned with SET_RANGE via a direct ByteBuffer key
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                Optional<LmdbCursor.Entry> result = cursor.get(LmdbCursorOp.SET_RANGE, directBuffer("b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isPresent();
                assertThat(text(result.orElseThrow().key())).isEqualTo("c");
            }
        }
    }

    @Nested
    class Mutation {

        @Test
        void cursorPutThenDeleteRemovesTheEntry() {
            // Given a database and an open write cursor
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // The cursor must close before the transaction commits: LMDB frees a
            // write transaction's cursors as part of the commit itself, so
            // closing one afterward would touch already-freed native memory.
            try (LmdbTxn txn = env.beginTxn()) {
                try (LmdbCursor cursor = txn.openCursor(dbi)) {
                    // When an entry is put via the cursor, then deleted at its position
                    cursor.put(bytes("k"), bytes("v"), Set.of());
                    assertThat(cursor.get(LmdbCursorOp.SET, bytes("k"))).isPresent();

                    cursor.delete();
                }
                txn.commit();
            }

            // Then it is gone
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, bytes("k"))).isEmpty();
            }
        }

        @Test
        void cursorPutAcceptsNativeKeyAndDataSegments() {
            // Given a database and an open write cursor
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When an entry is put via the cursor using native key/data segments
            try (LmdbTxn txn = env.beginTxn(); Arena arena = Arena.ofConfined()) {
                try (LmdbCursor cursor = txn.openCursor(dbi)) {
                    cursor.put(nativeBytes(arena, "k"), nativeBytes(arena, "v"), Set.of());
                }
                txn.commit();
            }

            // Then it is readable back
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, bytes("k"))).contains(bytes("v"));
            }
        }

        @Test
        void cursorPutAcceptsDirectByteBufferKeyAndData() {
            // Given a database and an open write cursor
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When an entry is put via the cursor using direct ByteBuffer key/data
            try (LmdbTxn txn = env.beginTxn()) {
                try (LmdbCursor cursor = txn.openCursor(dbi)) {
                    cursor.put(directBuffer("k"), directBuffer("v"), Set.of());
                }
                txn.commit();
            }

            // Then it is readable back
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, bytes("k"))).contains(bytes("v"));
            }
        }

        @Test
        void countReportsTheNumberOfDuplicatesUnderTheCurrentKey() {
            // Given an MDB_DUPSORT database with two values under the same key
            // (mdb_cursor_count is only valid on DUPSORT databases — plain
            // databases reject it with MDB_INCOMPATIBLE, one key/data pair each)
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.put(dbi, bytes("k"), bytes("v1"), Set.of());
                txn.put(dbi, bytes("k"), bytes("v2"), Set.of());
                txn.commit();
            }

            // When positioned on the key and counted
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                cursor.get(LmdbCursorOp.SET, bytes("k"));

                // Then both duplicates are counted
                assertThat(cursor.count()).isEqualTo(2L);
            }
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MemorySegment nativeBytes(Arena arena, String s) {
        byte[] b = bytes(s);
        MemorySegment seg = arena.allocate(b.length);
        MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
        return seg;
    }

    private static ByteBuffer directBuffer(String s) {
        byte[] b = bytes(s);
        return ByteBuffer.allocateDirect(b.length).put(b).flip();
    }

    private static String text(MemorySegment segment) {
        return new String(segment.toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }
}
