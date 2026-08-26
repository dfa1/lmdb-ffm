package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbTxnTest {

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
    class ReadWrite {

        @Test
        void roundTripsAPutValueThroughGet() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read back in a fresh transaction
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                byte[] result = txn.get(dbi, key("k"));

                // Then it returns the same bytes
                assertThat(result).isEqualTo(value("v"));
            }
        }

        @Test
        void getSegmentIsAZeroCopyViewOfTheSameBytes() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read back as a native segment
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                MemorySegment seg = txn.getSegment(dbi, key("k"));

                // Then it is a native segment with the same content
                assertThat(seg).isNotNull();
                assertThat(seg.isNative()).isTrue();
                assertThat(seg.toArray(JAVA_BYTE)).isEqualTo(value("v"));
            }
        }

        @Test
        void getOnAMissingKeyIsEmptyNotAnException() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When a nonexistent key is read
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                byte[] result = txn.get(dbi, key("missing"));

                // Then it is null, not a thrown exception
                assertThat(result).isNull();
            }
        }

        @Test
        void deleteRemovesAnExistingKeyAndReportsTrue() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When it is deleted
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, key("k"));
                txn.commit();

                assertThat(deleted).isTrue();
            }

            // Then it is gone
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isNull();
            }
        }

        @Test
        void deletingAMissingKeyReportsFalse() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When a nonexistent key is deleted
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, key("missing"));
                txn.commit();

                // Then it reports false rather than throwing
                assertThat(deleted).isFalse();
            }
        }

        @Test
        void statReportsTheNumberOfEntries() {
            // Given a database with two entries
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k1"), value("v1"), Set.of());
                txn.put(dbi, key("k2"), value("v2"), Set.of());
                txn.commit();
            }

            // When its statistics are read
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                LmdbStat stat = txn.stat(dbi);

                // Then it reports both entries
                assertThat(stat.entries()).isEqualTo(2L);
            }
        }

        @Test
        void deletesOneDuplicateDataValueLeavingOthersInPlace() {
            // Given a DUPSORT database with two data values under the same key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.put(dbi, key("k"), value("v1"), Set.of());
                txn.put(dbi, key("k"), value("v2"), Set.of());
                txn.commit();
            }

            // When one specific duplicate is deleted
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, key("k"), value("v1"));
                txn.commit();

                assertThat(deleted).isTrue();
            }

            // Then only the other duplicate remains
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v2"));
            }
        }

        @Test
        void deletingAMissingDuplicateReportsFalse() {
            // Given a DUPSORT database with one data value under a key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.put(dbi, key("k"), value("v1"), Set.of());
                txn.commit();
            }

            // When a duplicate that was never stored is deleted
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, key("k"), value("v2"));
                txn.commit();

                // Then it reports false rather than throwing
                assertThat(deleted).isFalse();
            }
        }

        @Test
        void envReturnsTheOwningEnvironment() {
            // Given a transaction begun on an environment
            try (LmdbTxn txn = env.beginTxn()) {
                // Then it reports that same environment back
                assertThat(txn.env()).isSameAs(env);
            }
        }

        @Test
        void noOverwriteRejectsAnExistingKey() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When put again with NOOVERWRITE
            try (LmdbTxn txn = env.beginTxn()) {
                ThrowingCallable result = () -> txn.put(dbi, key("k"), value("v2"), EnumSet.of(LmdbWriteFlag.NOOVERWRITE));

                // Then it fails with the named KEY_EXIST category
                assertThatThrownBy(result)
                        .isInstanceOf(LmdbException.class)
                        .extracting(e -> ((LmdbException) e).code())
                        .isEqualTo(LmdbErrorCode.KEY_EXIST);
            }
        }
    }

    @Nested
    class ZeroCopy {

        @Test
        void roundTripsThroughMemorySegmentKeyAndData() {
            // Given a value put via native key/data segments
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn(); Arena arena = Arena.ofConfined()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, nativeBytes(arena, "k"), nativeBytes(arena, "v"), Set.of());
                txn.commit();
            }

            // When read back via a native key segment
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY)); Arena arena = Arena.ofConfined()) {
                MemorySegment result = txn.getSegment(dbi, nativeBytes(arena, "k"));

                // Then it returns the same bytes
                assertThat(result).isNotNull();
                assertThat(result.toArray(JAVA_BYTE)).isEqualTo(value("v"));
            }
        }

        @Test
        void deleteAcceptsANativeKeySegment() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When deleted via a native key segment
            try (LmdbTxn txn = env.beginTxn(); Arena arena = Arena.ofConfined()) {
                boolean deleted = txn.delete(dbi, nativeBytes(arena, "k"));
                txn.commit();

                // Then it is reported deleted and gone
                assertThat(deleted).isTrue();
            }
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isNull();
            }
        }

        @Test
        void putRejectsAHeapKeySegment() {
            // Given a heap-backed segment, which cannot be dereferenced by native code
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            MemorySegment heapKey = MemorySegment.ofArray(key("k"));

            // When put with it as the key
            try (LmdbTxn txn = env.beginTxn()) {
                ThrowingCallable result = () -> txn.put(dbi, heapKey, MemorySegment.ofArray(value("v")), Set.of());

                // Then it fails fast naming the problem, not with a native crash
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }
    }

    @Nested
    class WithByteBuffer {

        @Test
        void roundTripsThroughADirectByteBufferKeyAndData() {
            // Given a value put via direct ByteBuffer key/data
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, directBuffer("k"), directBuffer("v"), Set.of());
                txn.commit();
            }

            // When read back via a direct ByteBuffer key
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                MemorySegment result = txn.getSegment(dbi, directBuffer("k"));

                // Then it returns the same bytes
                assertThat(result).isNotNull();
                assertThat(result.toArray(JAVA_BYTE)).isEqualTo(value("v"));
            }
        }

        @Test
        void onlyTheRemainingBytesOfTheBufferAreUsedAsTheKey() {
            // Given a direct buffer whose position/limit mark out just "k" in
            // the middle of a larger backing buffer
            ByteBuffer buf = ByteBuffer.allocateDirect(16);
            buf.put((byte) 'x').put(key("k")).put((byte) 'y');
            buf.position(1).limit(2);

            // When put with it as the key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, buf, directBuffer("v"), Set.of());
                txn.commit();
            }

            // Then only "k" (not "xky") was stored as the key
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v"));
            }
        }

        @Test
        void deleteAcceptsADirectByteBufferKey() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When deleted via a direct ByteBuffer key
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, directBuffer("k"));
                txn.commit();

                assertThat(deleted).isTrue();
            }
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isNull();
            }
        }

        @Test
        void deletesOneDuplicateDataValueViaDirectByteBuffers() {
            // Given a DUPSORT database with two data values under the same key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.put(dbi, key("k"), value("v1"), Set.of());
                txn.put(dbi, key("k"), value("v2"), Set.of());
                txn.commit();
            }

            // When one specific duplicate is deleted via direct ByteBuffer key/data
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, directBuffer("k"), directBuffer("v1"));
                txn.commit();

                assertThat(deleted).isTrue();
            }

            // Then only the other duplicate remains
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v2"));
            }
        }

        @Test
        void putRejectsAHeapByteBufferKey() {
            // Given a non-direct (heap-backed) buffer, which cannot be
            // dereferenced by native code
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            ByteBuffer heapKey = ByteBuffer.wrap(key("k"));

            // When put with it as the key
            try (LmdbTxn txn = env.beginTxn()) {
                ThrowingCallable result = () -> txn.put(dbi, heapKey, directBuffer("v"), Set.of());

                // Then it fails fast naming the problem, not with a native crash
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void mapperAcceptsADirectByteBufferKey() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read back with a direct ByteBuffer key and a Mapper
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                String result = txn.get(dbi, directBuffer("k"), LmdbTxnTest::decode);

                // Then the mapped result is returned
                assertThat(result).isEqualTo("v");
            }
        }
    }

    @Nested
    class WithMapper {

        @Test
        void mapsTheStoredValueWithoutMaterializingAByteArray() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read back through a Mapper that decodes it as text
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                String result = txn.get(dbi, key("k"), LmdbTxnTest::decode);

                // Then the mapped result is returned
                assertThat(result).isEqualTo("v");
            }
        }

        @Test
        void mapsTheStoredValueWithAZeroCopyKeyToo() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read back with both a native key and a Mapper
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY)); Arena arena = Arena.ofConfined()) {
                String result = txn.get(dbi, nativeBytes(arena, "k"), LmdbTxnTest::decode);

                // Then the mapped result is returned
                assertThat(result).isEqualTo("v");
            }
        }

        @Test
        void mapperOnAMissingKeyIsEmptyAndNeverInvoked() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When read through a Mapper that would fail if ever called
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                String result = txn.get(dbi, key("missing"), v -> {
                    throw new AssertionError("Mapper must not run for a missing key");
                });

                // Then it is null, and the mapper never ran
                assertThat(result).isNull();
            }
        }

        @Test
        void mapperRejectsANullReturn() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When read through a Mapper that returns null
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                ThrowingCallable result = () -> txn.get(dbi, key("k"), v -> null);

                // Then it is rejected rather than silently accepted
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void theMappedViewIsInaccessibleOnceGetReturns() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When a Mapper smuggles the raw view out by reference
            AtomicReference<MemorySegment> leaked = new AtomicReference<>();
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                txn.get(dbi, key("k"), v -> {
                    leaked.set(v);
                    return "captured";
                });
            }

            // Then touching it after the call returns fails fast instead of reading
            // through a dangling pointer — the view's arena closed with the call.
            ThrowingCallable result = () -> leaked.get().get(JAVA_BYTE, 0);
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class TransactionLifecycle {

        @Test
        void abortedWritesAreNotVisibleAfterwards() {
            // Given a write that is aborted, not committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.abort();
            }

            // Then a later transaction sees nothing
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isNull();
            }
        }

        @Test
        void closeWithoutCommitAbortsAsASafetyNet() {
            // Given a write transaction whose block ends without an explicit commit
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                txn.put(dbi, key("k"), value("v"), Set.of());
                // no commit() — close() below must abort, not leak a dangling write
            }

            // Then the write never happened
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isNull();
            }
        }

        @Test
        void commitEndsTheTransactionSoFurtherUseFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When it is used again
            ThrowingCallable result = () -> sut.get(new LmdbDbi(0), key("k"));

            // Then it fails fast rather than touching a freed native pointer
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void doubleCommitFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When committed again
            ThrowingCallable result = sut::commit;

            // Then it fails fast rather than double-freeing
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void closeAfterCommitIsANoOp() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When close() runs as the try-with-resources safety net
            ThrowingCallable result = sut::close;

            // Then it does not attempt to abort the already-committed transaction
            assertThatCode(result).doesNotThrowAnyException();
        }
    }

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static MemorySegment nativeBytes(Arena arena, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    private static ByteBuffer directBuffer(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
    }

    private static String decode(MemorySegment value) {
        return new String(value.toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }
}
