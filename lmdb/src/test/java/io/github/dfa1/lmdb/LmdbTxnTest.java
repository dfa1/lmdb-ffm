package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbTxnTest {

    private LmdbEnv env;

    @BeforeEach
    void openEnvironment(@TempDir Path dir) {
        env = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).maxDatabases(4).open(dir, Set.of());
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
    class FlagsIntrospection {

        @Test
        void flagsReportsRdonlyForAReadOnlyTransaction() {
            // When a read-only transaction is begun
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // Then it reports RDONLY among its flags
                assertThat(txn.flags()).contains(LmdbEnvFlag.RDONLY);
            }
        }

        @Test
        void flagsDoesNotReportRdonlyForAWriteTransaction() {
            // When a read-write transaction is begun
            try (LmdbTxn txn = env.beginTxn()) {
                // Then it does not report RDONLY
                assertThat(txn.flags()).doesNotContain(LmdbEnvFlag.RDONLY);
            }
        }

        @Test
        void dbiFlagsReportsHowTheDatabaseWasOpened() {
            // Given a database opened with DUPSORT
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.commit();
            }

            // When its flags are inspected
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // Then DUPSORT is reported, but CREATE (an open-time-only directive) is not
                assertThat(txn.dbiFlags(dbi)).contains(LmdbDbiFlag.DUPSORT);
            }
        }

        @Test
        void idReportsANonNegativeValue() {
            // When a transaction is begun
            try (LmdbTxn txn = env.beginTxn()) {
                // Then it has a non-negative transaction ID
                assertThat(txn.id()).isNotNegative();
            }
        }

        @Test
        void idOnAnEndedTransactionFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When its ID is read
            ThrowingCallable result = sut::id;

            // Then it fails fast rather than touching a freed native pointer
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
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

        @Test
        void putRejectsAKeySegmentFromAClosedArena() {
            // Given a native key segment whose owning arena has since closed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }
            Arena scratch = Arena.ofConfined();
            MemorySegment closedKey = scratch.allocateFrom("k", StandardCharsets.UTF_8);
            scratch.close();

            // When put with it as the key
            try (LmdbTxn txn = env.beginTxn(); Arena arena = Arena.ofConfined()) {
                ThrowingCallable result = () -> txn.put(dbi, closedKey, nativeBytes(arena, "v"), Set.of());

                // Then it fails fast naming the problem, not by silently
                // copying a stale address into the stored entry
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
            }
        }

        @Test
        void aRetainedSegmentBecomesInaccessibleOnceItsTransactionEnds() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When a zero-copy read view is retained past its transaction's end
            MemorySegment dangling;
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                dangling = txn.getSegment(dbi, key("k"));
            }

            // Then its scope reports closed, and reading it throws instead of
            // dereferencing memory the transaction may have released
            assertThat(dangling.scope().isAlive()).isFalse();
            ThrowingCallable result = () -> dangling.get(JAVA_BYTE, 0);
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
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
        void theMappedViewStaysValidForTheRestOfTheTransaction() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When a Mapper retains the raw view by reference past the call
            AtomicReference<MemorySegment> retained = new AtomicReference<>();
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                txn.get(dbi, key("k"), v -> {
                    retained.set(v);
                    return "captured";
                });

                // Then it is still readable, matching getSegment's lifetime
                assertThat(retained.get().toArray(JAVA_BYTE)).isEqualTo(value("v"));
            }
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

    @Nested
    class ThreadConfinement {

        @Test
        void putFromAnotherThreadFailsInsteadOfCorruptingMemory() throws InterruptedException {
            // Given a write transaction begun on this (the test) thread
            LmdbDbi dbi;
            try (LmdbTxn setup = env.beginTxn()) {
                dbi = setup.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                setup.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                AtomicReference<Throwable> caught = new AtomicReference<>();

                // When put is called from a different thread
                Thread other = new Thread(() -> {
                    try {
                        txn.put(dbi, key("k"), value("v"), Set.of());
                    } catch (Throwable t) {
                        caught.set(t);
                    }
                });
                other.start();
                other.join();

                // Then it fails fast, exactly like a cross-thread read already
                // does via its own confined arena, rather than sailing through
                // to native memory corruption (dfa1/lmdb-ffm#8)
                assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void deleteFromAnotherThreadFails() throws InterruptedException {
            // Given a write transaction with a committed key to delete
            LmdbDbi dbi;
            try (LmdbTxn setup = env.beginTxn()) {
                dbi = setup.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                setup.put(dbi, key("k"), value("v"), Set.of());
                setup.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                AtomicReference<Throwable> caught = new AtomicReference<>();

                // When delete is called from a different thread
                Thread other = new Thread(() -> {
                    try {
                        txn.delete(dbi, key("k"));
                    } catch (Throwable t) {
                        caught.set(t);
                    }
                });
                other.start();
                other.join();

                // Then it fails fast rather than touching native memory from
                // the wrong thread
                assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void putFromTheOwningThreadStillWorks() {
            // Given a write transaction begun on this thread
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));

                // When put is called from that same thread
                ThrowingCallable putIt = () -> txn.put(dbi, key("k"), value("v"), Set.of());

                // Then the guard does not get in the way of ordinary use
                assertThatCode(putIt).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    class ResetRenewPrepare {

        @Test
        void resetThenRenewAllowsContinuedReading() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());
                txn.commit();
            }

            // When a read-only transaction reads, is reset, then renewed
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v"));

                txn.reset();
                txn.renew();

                // Then it can read again as if nothing happened
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v"));
            }
        }

        @Test
        void resetOnAnEndedTransactionFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When reset
            ThrowingCallable result = sut::reset;

            // Then it fails fast rather than touching a freed native pointer
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void renewOnAnEndedTransactionFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When renewed
            ThrowingCallable result = sut::renew;

            // Then it fails fast rather than touching a freed native pointer
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void prepareThenCommitPersistsTheWrite() {
            // Given a write transaction with a pending put
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.put(dbi, key("k"), value("v"), Set.of());

                // When prepared, then committed
                txn.prepare();
                txn.commit();
            }

            // Then the write is visible afterward
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                assertThat(txn.get(dbi, key("k"))).isEqualTo(value("v"));
            }
        }

        @Test
        void prepareOnAnEndedTransactionFails() {
            // Given a committed transaction
            LmdbTxn sut = env.beginTxn();
            sut.commit();

            // When prepared
            ThrowingCallable result = sut::prepare;

            // Then it fails fast rather than touching a freed native pointer
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class CustomComparator {

        // A custom comparator turns every keyed read into an upcall from
        // inside LMDB's B+tree search, which mdb_get may not be linked
        // Linker.Option.critical for — that combination aborts the VM
        // (upcallLinker.cpp's "wrong thread state for upcall" guarantee), so
        // a regression here fails the build by killing the surefire fork
        // rather than by a failed assertion. Cursor FIRST/NEXT (covered by
        // setComparatorChangesKeyIterationOrder below) cannot catch it:
        // those steps compare nothing, so they never enter the comparator.
        @ParameterizedTest
        @MethodSource("keyedReads")
        void everyKeyedReadFlavorWorksWithACustomComparator(
                BiFunction<LmdbTxn, LmdbDbi, String> read) {
            // Given a database written through a custom key comparator
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.setComparator(dbi, reverseByteComparator());
                txn.put(dbi, key("a"), value("1"), Set.of());
                txn.put(dbi, key("b"), value("2"), Set.of());
                txn.commit();
            }

            // When a key is looked up, running the comparator inside the search
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                String found = read.apply(txn, dbi);

                // Then the stored value comes back
                assertThat(found).isEqualTo("2");
            }
        }

        @Test
        void setComparatorTakesTheEnvironmentOffTheCriticalReadPath() {
            // Given an environment that has never had a comparator installed
            assertThat(env.usesComparators()).isFalse();

            // When a key comparator is installed
            try (LmdbTxn txn = env.beginTxn()) {
                LmdbDbi dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.setComparator(dbi, reverseByteComparator());
                txn.commit();
            }

            // Then reads stop selecting the critical handle, which may not upcall
            assertThat(env.usesComparators()).isTrue();
        }

        @Test
        void setDupComparatorTakesTheEnvironmentOffTheCriticalReadPath() {
            // Given an environment that has never had a comparator installed
            assertThat(env.usesComparators()).isFalse();

            // When a duplicate-value comparator is installed
            try (LmdbTxn txn = env.beginTxn()) {
                LmdbDbi dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.setDupComparator(dbi, reverseByteComparator());
                txn.commit();
            }

            // Then reads stop selecting the critical handle, which may not upcall
            assertThat(env.usesComparators()).isTrue();
        }

        @Test
        void setComparatorChangesKeyIterationOrder() {
            // Given a database opened with a reverse-order key comparator
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.setComparator(dbi, reverseByteComparator());
                txn.put(dbi, key("a"), value("1"), Set.of());
                txn.put(dbi, key("b"), value("2"), Set.of());
                txn.put(dbi, key("c"), value("3"), Set.of());
                txn.commit();
            }

            // When iterating from the first entry
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                LmdbCursor.Entry first = cursor.get(LmdbCursorOp.FIRST);
                LmdbCursor.Entry second = cursor.get(LmdbCursorOp.NEXT);
                LmdbCursor.Entry third = cursor.get(LmdbCursorOp.NEXT);

                // Then keys come back high-to-low instead of LMDB's default low-to-high
                assertThat(decode(first.key())).isEqualTo("c");
                assertThat(decode(second.key())).isEqualTo("b");
                assertThat(decode(third.key())).isEqualTo("a");
            }
        }

        @Test
        void setDupComparatorChangesDuplicateValueOrder() {
            // Given a DUPSORT database opened with a reverse-order value comparator
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.setDupComparator(dbi, reverseByteComparator());
                txn.put(dbi, key("k"), value("1"), Set.of());
                txn.put(dbi, key("k"), value("2"), Set.of());
                txn.put(dbi, key("k"), value("3"), Set.of());
                txn.commit();
            }

            // When iterating the duplicates under that key
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
                    LmdbCursor cursor = txn.openCursor(dbi)) {
                LmdbCursor.Entry first = cursor.get(LmdbCursorOp.FIRST);
                LmdbCursor.Entry second = cursor.get(LmdbCursorOp.NEXT);
                LmdbCursor.Entry third = cursor.get(LmdbCursorOp.NEXT);

                // Then values come back high-to-low instead of LMDB's default low-to-high
                assertThat(decode(first.data())).isEqualTo("3");
                assertThat(decode(second.data())).isEqualTo("2");
                assertThat(decode(third.data())).isEqualTo("1");
            }
        }

        @Test
        void setComparatorRejectsANullDbi() {
            // Given a transaction
            try (LmdbTxn sut = env.beginTxn()) {
                // When installing a comparator on a null dbi
                ThrowingCallable result = () -> sut.setComparator(null, reverseByteComparator());

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void setComparatorRejectsANullComparator() {
            // Given an open database
            try (LmdbTxn sut = env.beginTxn()) {
                LmdbDbi dbi = sut.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));

                // When installing a null comparator
                ThrowingCallable result = () -> sut.setComparator(dbi, null);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void setDupComparatorRejectsANullComparator() {
            // Given an open DUPSORT database
            try (LmdbTxn sut = env.beginTxn()) {
                LmdbDbi dbi = sut.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));

                // When installing a null dup comparator
                ThrowingCallable result = () -> sut.setDupComparator(dbi, null);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void aComparatorThatThrowsDoesNotCrashTheJvm() {
            // Given a database opened with a comparator that always throws
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.setComparator(dbi, (a, b) -> {
                    throw new RuntimeException("boom");
                });

                // When keys are written, forcing key comparisons in the B+tree
                ThrowingCallable result = () -> {
                    txn.put(dbi, key("a"), value("1"), Set.of());
                    txn.put(dbi, key("b"), value("2"), Set.of());
                };

                // Then the exception is contained (an upcall cannot propagate a Java
                // exception into LMDB's C code) rather than crashing the process
                assertThatCode(result).doesNotThrowAnyException();
            }
        }

        // Every read that looks a key up, and so runs the comparator: the
        // byte[], MemorySegment and Mapper flavors of get and getSegment.
        private static Stream<Arguments> keyedReads() {
            return Stream.of(
                    named("get(byte[])",
                            (txn, dbi) -> new String(txn.get(dbi, key("b")), StandardCharsets.UTF_8)),
                    named("getSegment(byte[])",
                            (txn, dbi) -> decode(txn.getSegment(dbi, key("b")))),
                    named("get(byte[], Mapper)",
                            (txn, dbi) -> txn.get(dbi, key("b"), LmdbTxnTest::decode)),
                    named("getSegment(MemorySegment)", (txn, dbi) -> {
                        try (Arena arena = Arena.ofConfined()) {
                            return decode(txn.getSegment(dbi, nativeBytes(arena, "b")));
                        }
                    }),
                    named("get(MemorySegment, Mapper)", (txn, dbi) -> {
                        try (Arena arena = Arena.ofConfined()) {
                            return txn.get(dbi, nativeBytes(arena, "b"), LmdbTxnTest::decode);
                        }
                    }));
        }

        private static Arguments named(String name, BiFunction<LmdbTxn, LmdbDbi, String> read) {
            return Arguments.of(Named.of(name, read));
        }
    }

    @Nested
    class Compare {

        @Test
        void compareOrdersLexicographicallyByDefault() {
            // Given a plain database (default byte-lexicographic key order)
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.commit();
            }

            // When two keys are compared
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // Then the result matches plain byte-lexicographic order
                assertThat(txn.compare(dbi, key("a"), key("b"))).isNegative();
                assertThat(txn.compare(dbi, key("b"), key("a"))).isPositive();
                assertThat(txn.compare(dbi, key("a"), key("a"))).isZero();
            }
        }

        @Test
        void compareUsesACustomComparatorWhenInstalled() {
            // Given a database with a reverse-order key comparator installed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                txn.setComparator(dbi, reverseByteComparator());
                txn.commit();
            }

            // When two keys are compared
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // Then the result reflects the custom (reversed) order, not the default
                assertThat(txn.compare(dbi, key("a"), key("b"))).isPositive();
            }
        }

        @Test
        void compareDataComparesDuplicateValuesInADupsortDatabase() {
            // Given a DUPSORT database (default byte-lexicographic value order)
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE, LmdbDbiFlag.DUPSORT));
                txn.commit();
            }

            // When two values are compared
            try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // Then the result matches plain byte-lexicographic order
                assertThat(txn.compareData(dbi, value("1"), value("2"))).isNegative();
                assertThat(txn.compareData(dbi, value("2"), value("1"))).isPositive();
            }
        }

        @Test
        void compareRejectsANullDbi() {
            // Given a transaction
            try (LmdbTxn sut = env.beginTxn()) {
                // When comparing with a null dbi
                ThrowingCallable result = () -> sut.compare(null, key("a"), key("b"));

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void compareDataRejectsANullDbi() {
            // Given a transaction
            try (LmdbTxn sut = env.beginTxn()) {
                // When comparing data with a null dbi
                ThrowingCallable result = () -> sut.compareData(null, value("1"), value("2"));

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
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

    private static LmdbComparator reverseByteComparator() {
        return (a, b) -> {
            int len = Math.min((int) a.byteSize(), (int) b.byteSize());
            for (int i = 0; i < len; i++) {
                int cmp = Byte.compareUnsigned(a.get(JAVA_BYTE, i), b.get(JAVA_BYTE, i));
                if (cmp != 0) {
                    return -cmp;
                }
            }
            return -Long.compare(a.byteSize(), b.byteSize());
        };
    }
}
