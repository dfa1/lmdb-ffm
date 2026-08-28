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
                for (LmdbCursor.Entry e = cursor.get(LmdbCursorOp.FIRST);
                        e != null;
                        e = cursor.get(LmdbCursorOp.NEXT)) {
                    keys.add(text(e.key()));
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
                LmdbCursor.Entry result = cursor.get(LmdbCursorOp.FIRST);

                // Then there is nothing to find
                assertThat(result).isNull();
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
                LmdbCursor.Entry result = cursor.get(LmdbCursorOp.SET_RANGE, bytes("b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isNotNull();
                assertThat(text(result.key())).isEqualTo("c");
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
                LmdbCursor.Entry result = cursor.get(LmdbCursorOp.SET_RANGE, nativeBytes(arena, "b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isNotNull();
                assertThat(text(result.key())).isEqualTo("c");
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
                LmdbCursor.Entry result = cursor.get(LmdbCursorOp.SET_RANGE, directBuffer("b"));

                // Then it lands on the next key in order, "c"
                assertThat(result).isNotNull();
                assertThat(text(result.key())).isEqualTo("c");
            }
        }
    }

    @Nested
    class ValueOnly {

        @Test
        void getValueWalksTheSameEntriesAsGetWithoutTheKey() {
            // Given three keys inserted out of order
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("b"), bytes("2"), Set.of());
                txn.put(dbi, bytes("a"), bytes("1"), Set.of());
                txn.put(dbi, bytes("c"), bytes("3"), Set.of());
                txn.commit();
            }

            // When walked from FIRST via NEXT using the value-only overload
            List<String> values = new ArrayList<>();
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                for (MemorySegment v = cursor.getValue(LmdbCursorOp.FIRST);
                        v != null;
                        v = cursor.getValue(LmdbCursorOp.NEXT)) {
                    values.add(text(v));
                }
            }

            // Then the values come back in key order, same as get(op)
            assertThat(values).containsExactly("1", "2", "3");
        }

        @Test
        void getValueOnAnEmptyDatabaseIsEmpty() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When positioned at FIRST via the value-only overload
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                MemorySegment result = cursor.getValue(LmdbCursorOp.FIRST);

                // Then there is nothing to find
                assertThat(result).isNull();
            }
        }

        @Test
        void getValueFindsTheValueStoredUnderAKey() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("k"), bytes("v"), Set.of());
                txn.commit();
            }

            // When positioned with SET via the value-only overload
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                MemorySegment result = cursor.getValue(LmdbCursorOp.SET, bytes("k"));

                // Then it returns the stored value
                assertThat(result).isNotNull();
                assertThat(text(result)).isEqualTo("v");
            }
        }

        @Test
        void getValueOnAMissingKeyIsEmpty() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When positioned with SET at a key that was never stored
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                MemorySegment result = cursor.getValue(LmdbCursorOp.SET, bytes("missing"));

                // Then there is nothing to find
                assertThat(result).isNull();
            }
        }

        @Test
        void getValueAcceptsANativeKeySegment() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("k"), bytes("v"), Set.of());
                txn.commit();
            }

            // When positioned with SET via a native key segment
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi);
                    Arena arena = Arena.ofConfined()) {
                MemorySegment result = cursor.getValue(LmdbCursorOp.SET, nativeBytes(arena, "k"));

                // Then it returns the stored value
                assertThat(result).isNotNull();
                assertThat(text(result)).isEqualTo("v");
            }
        }

        @Test
        void getValueAcceptsADirectByteBufferKey() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("k"), bytes("v"), Set.of());
                txn.commit();
            }

            // When positioned with SET via a direct ByteBuffer key
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                MemorySegment result = cursor.getValue(LmdbCursorOp.SET, directBuffer("k"));

                // Then it returns the stored value
                assertThat(result).isNotNull();
                assertThat(text(result)).isEqualTo("v");
            }
        }
    }

    @Nested
    class Renew {

        @Test
        void renewReassociatesTheCursorWithAFreshReadOnlyTransaction() {
            // Given a key committed, and a cursor opened on a read-only transaction
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("k"), bytes("v"), Set.of());
                txn.commit();
            }

            LmdbTxn firstTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
            LmdbCursor cursor = firstTxn.openCursor(dbi);
            assertThat(cursor.get(LmdbCursorOp.SET, bytes("k"))).isNotNull();

            // The first transaction ends (a read-only abort/close does not free its
            // cursors — only a write transaction's commit does) before the second
            // begins: one thread can only hold one read-only transaction at a time,
            // and this is also the documented interesting case for renew — "may be
            // done whether the previous transaction is live or dead."
            firstTxn.close();

            // When the now-orphaned cursor is renewed onto a fresh read-only transaction
            try (LmdbTxn secondTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                cursor.renew(secondTxn);

                // Then it reads through the new transaction
                LmdbCursor.Entry result = cursor.get(LmdbCursorOp.SET, bytes("k"));
                assertThat(result).isNotNull();
                assertThat(text(result.data())).isEqualTo("v");

                // The cursor must close before secondTxn does — using one after its
                // transaction has ended (without a further renew) is undefined
                // behavior, not a catchable exception, exactly like the
                // commit/cursor-close hazard documented on LmdbCursor itself.
                cursor.close();
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
                    assertThat(cursor.get(LmdbCursorOp.SET, bytes("k"))).isNotNull();

                    cursor.delete();
                }
                txn.commit();
            }

            // Then it is gone
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, bytes("k"))).isNull();
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
                assertThat(txn.get(dbi, bytes("k"))).isEqualTo(bytes("v"));
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
                assertThat(txn.get(dbi, bytes("k"))).isEqualTo(bytes("v"));
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

    @Nested
    class Introspection {

        @Test
        void dbiReportsTheDatabaseTheCursorWasOpenedOn() {
            // Given a cursor opened on a database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                // Then it reports that same database
                assertThat(cursor.dbi()).isEqualTo(dbi);
            }
        }

        @Test
        void txnReportsTheOwningTransaction() {
            // Given a cursor opened on a transaction
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                // Then it reports that same transaction
                assertThat(cursor.txn()).isSameAs(txn);
            }
        }

        @Test
        void txnReflectsTheMostRecentRenew() {
            // Given a cursor opened on one read-only transaction, then renewed onto another
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            LmdbTxn firstTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
            LmdbCursor cursor = firstTxn.openCursor(dbi);
            firstTxn.close();

            try (LmdbTxn secondTxn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // When renewed onto secondTxn
                cursor.renew(secondTxn);

                // Then it reports secondTxn, not the transaction it was opened with
                assertThat(cursor.txn()).isSameAs(secondTxn);
                cursor.close();
            }
        }

        @Test
        void isDbIsFalseForAPlainUserKey() {
            // Given a plain key/value in the unnamed database
            try (LmdbTxn txn = env.beginTxn()) {
                LmdbDbi dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, bytes("k"), bytes("v"), Set.of());
                txn.commit();
            }

            // When a cursor on the unnamed database is positioned on it
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                LmdbDbi root = txn.openDatabase(Set.of());
                try (LmdbCursor cursor = txn.openCursor(root)) {
                    cursor.get(LmdbCursorOp.SET, bytes("k"));

                    // Then it is not reported as a named database
                    assertThat(cursor.isDb()).isFalse();
                }
            }
        }

        @Test
        void isDbIsTrueForANamedDatabaseEntry() {
            // Given a named database opened alongside the unnamed one
            try (LmdbTxn txn = env.beginTxn()) {
                txn.openDatabase("named", EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When a cursor on the unnamed (root) database is positioned on
            // the named database's own entry there — SET_RANGE, not SET: the
            // stored key is the name plus a trailing NUL (LMDB's mdb_dbi_open
            // stores strlen(name) + 1 bytes, C-string style), so an exact
            // match on the plain name bytes never hits
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                LmdbDbi root = txn.openDatabase(Set.of());
                try (LmdbCursor cursor = txn.openCursor(root)) {
                    LmdbCursor.Entry entry = cursor.get(LmdbCursorOp.SET_RANGE, bytes("named"));
                    assertThat(entry).isNotNull();

                    // Then it is reported as a named database
                    assertThat(cursor.isDb()).isTrue();
                }
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
