package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbTxnTest {

    private LmdbEnv env;

    @BeforeEach
    void openEnvironment(@TempDir Path dir) {
        env = LmdbEnv.create().mapSize(10L << 20).maxDatabases(4).open(dir, 0);
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
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.put(dbi, key("k"), value("v"), 0);
                txn.commit();
            }

            // When read back in a fresh transaction
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                Optional<byte[]> result = txn.get(dbi, key("k"));

                // Then it returns the same bytes
                assertThat(result).contains(value("v"));
            }
        }

        @Test
        void getSegmentIsAZeroCopyViewOfTheSameBytes() {
            // Given a value put and committed
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.put(dbi, key("k"), value("v"), 0);
                txn.commit();
            }

            // When read back as a native segment
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                Optional<MemorySegment> result = txn.getSegment(dbi, key("k"));

                // Then it is a native segment with the same content
                assertThat(result).isPresent();
                MemorySegment seg = result.orElseThrow();
                assertThat(seg.isNative()).isTrue();
                assertThat(seg.toArray(JAVA_BYTE)).isEqualTo(value("v"));
            }
        }

        @Test
        void getOnAMissingKeyIsEmptyNotAnException() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.commit();
            }

            // When a nonexistent key is read
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                Optional<byte[]> result = txn.get(dbi, key("missing"));

                // Then it is empty, not a thrown exception
                assertThat(result).isEmpty();
            }
        }

        @Test
        void deleteRemovesAnExistingKeyAndReportsTrue() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.put(dbi, key("k"), value("v"), 0);
                txn.commit();
            }

            // When it is deleted
            try (LmdbTxn txn = env.beginTxn()) {
                boolean deleted = txn.delete(dbi, key("k"));
                txn.commit();

                assertThat(deleted).isTrue();
            }

            // Then it is gone
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                assertThat(txn.get(dbi, key("k"))).isEmpty();
            }
        }

        @Test
        void deletingAMissingKeyReportsFalse() {
            // Given an empty database
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
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
        void noOverwriteRejectsAnExistingKey() {
            // Given an existing key
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.put(dbi, key("k"), value("v"), 0);
                txn.commit();
            }

            // When put again with NOOVERWRITE
            try (LmdbTxn txn = env.beginTxn()) {
                ThrowingCallable result = () -> txn.put(dbi, key("k"), value("v2"), LmdbWriteFlags.NOOVERWRITE);

                // Then it fails with the named KEY_EXIST category
                assertThatThrownBy(result)
                        .isInstanceOf(LmdbException.class)
                        .extracting(e -> ((LmdbException) e).code())
                        .isEqualTo(LmdbErrorCode.KEY_EXIST);
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
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                txn.put(dbi, key("k"), value("v"), 0);
                txn.abort();
            }

            // Then a later transaction sees nothing
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                assertThat(txn.get(dbi, key("k"))).isEmpty();
            }
        }

        @Test
        void closeWithoutCommitAbortsAsASafetyNet() {
            // Given a write transaction whose block ends without an explicit commit
            LmdbDbi dbi;
            try (LmdbTxn txn = env.beginTxn()) {
                dbi = txn.openDatabase(LmdbDbiFlags.CREATE);
                txn.commit();
            }
            try (LmdbTxn txn = env.beginTxn()) {
                txn.put(dbi, key("k"), value("v"), 0);
                // no commit() — close() below must abort, not leak a dangling write
            }

            // Then the write never happened
            try (LmdbTxn txn = env.beginTxn(LmdbEnvFlags.RDONLY)) {
                assertThat(txn.get(dbi, key("k"))).isEmpty();
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
}
