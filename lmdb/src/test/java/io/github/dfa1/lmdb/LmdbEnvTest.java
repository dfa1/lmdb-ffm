package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbEnvTest {

    @Nested
    class Lifecycle {

        @Test
        void opensAndClosesAnEnvironment(@TempDir Path dir) {
            // Given a fresh directory
            // When an environment is created and opened in it
            LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of());

            // Then it closes cleanly and can be closed again (idempotent)
            sut.close();
            sut.close();
        }

        @Test
        void opensAsASingleFileWithNosubdir(@TempDir Path dir) {
            // Given a path naming a file rather than a directory
            Path file = dir.resolve("single.mdb");

            // When opened with NOSUBDIR
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(file, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
                // Then it succeeds and creates exactly that file
                assertThat(sut).isNotNull();
                assertThat(file).exists();
            }
        }

        @Test
        void beginningATransactionAfterCloseFails(@TempDir Path dir) {
            // Given a closed environment
            LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of());
            sut.close();

            // When a transaction is begun on it
            ThrowingCallable result = sut::beginTxn;

            // Then it fails fast rather than crashing the JVM
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class Introspection {

        @Test
        void reportsAPositiveMaxKeySize(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // Then it reports LMDB's (page-size-dependent) key size limit
                assertThat(sut.maxKeySize()).isPositive();
            }
        }

        @Test
        void statReportsAnEmptyFreshEnvironment(@TempDir Path dir) {
            // Given a freshly opened, empty environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // When its statistics are read
                LmdbStat stat = sut.stat();

                // Then the unnamed database is empty, with a positive page size
                assertThat(stat.entries()).isZero();
                assertThat(stat.pageSize()).isPositive();
            }
        }

        @Test
        void infoReportsTheConfiguredMapSize(@TempDir Path dir) {
            // Given an environment opened with a specific map size
            long mapSize = 10L << 20;
            try (LmdbEnv sut = LmdbEnv.create().mapSize(mapSize).open(dir, Set.of())) {
                // When its info is read
                LmdbEnvInfo info = sut.info();

                // Then the map size and reader bookkeeping are as configured
                assertThat(info.mapSize()).isEqualTo(mapSize);
                assertThat(info.numReaders()).isZero();
                assertThat(info.maxReaders()).isPositive();
            }
        }

        @Test
        void infoReportsTheConfiguredMaxReaders(@TempDir Path dir) {
            // Given an environment configured with an explicit reader slot count
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).maxReaders(42).open(dir, Set.of())) {
                // Then info reflects that same count
                assertThat(sut.info().maxReaders()).isEqualTo(42);
            }
        }

        @Test
        void maxReadersGetterMatchesTheConfiguredCount(@TempDir Path dir) {
            // Given an environment configured with an explicit reader slot count
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).maxReaders(42).open(dir, Set.of())) {
                // Then the direct getter reports the same count as info()
                assertThat(sut.maxReaders()).isEqualTo(42);
            }
        }

        @Test
        void pageSizeIsHonoredByTheOpenedEnvironment(@TempDir Path dir) {
            // Given an environment configured with an explicit page size
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).pageSize(4096).open(dir, Set.of())) {
                // Then it reports that same page size
                assertThat(sut.stat().pageSize()).isEqualTo(4096);
            }
        }

        @Test
        void pathReturnsWhatItWasOpenedWith(@TempDir Path dir) {
            // Given an environment opened at a specific path
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // Then it reports that same path back
                assertThat(sut.path()).isEqualTo(dir);
            }
        }

        @Test
        void fdReportsANonNegativeDescriptor(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // Then it reports a plausible native file descriptor/handle
                assertThat(sut.fd()).isGreaterThanOrEqualTo(0L);
            }
        }
    }

    @Nested
    class Flags {

        @Test
        void flagsReportsWhatItWasOpenedWith(@TempDir Path dir) {
            // Given an environment opened with NOSUBDIR
            Path file = dir.resolve("single.mdb");
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(file, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
                // Then flags() reports it
                assertThat(sut.flags()).contains(LmdbEnvFlag.NOSUBDIR);
            }
        }

        @Test
        void setFlagsAddsAndClearsAFlagAtRuntime(@TempDir Path dir) {
            // Given an environment opened without NOSYNC
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                assertThat(sut.flags()).doesNotContain(LmdbEnvFlag.NOSYNC);

                // When NOSYNC is set at runtime
                sut.setFlags(EnumSet.of(LmdbEnvFlag.NOSYNC), true);

                // Then it is reflected
                assertThat(sut.flags()).contains(LmdbEnvFlag.NOSYNC);

                // When cleared again
                sut.setFlags(EnumSet.of(LmdbEnvFlag.NOSYNC), false);

                // Then it is gone
                assertThat(sut.flags()).doesNotContain(LmdbEnvFlag.NOSYNC);
            }
        }
    }

    @Nested
    class Sync {

        @Test
        void syncSucceedsOnAFreshEnvironment(@TempDir Path dir) {
            // Given an open environment with nothing written yet
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // When flushed, both unconditionally and only-if-needed
                ThrowingCallable forced = () -> sut.sync(true);
                ThrowingCallable unforced = () -> sut.sync(false);

                // Then neither reports an error
                assertThatCode(forced).doesNotThrowAnyException();
                assertThatCode(unforced).doesNotThrowAnyException();
            }
        }
    }

    @Nested
    class NestedTransactions {

        @Test
        void aChildTransactionsWritesAreVisibleAfterBothCommit(@TempDir Path dir) {
            // Given an environment and a parent write transaction
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                LmdbDbi dbi;
                try (LmdbTxn parent = sut.beginTxn()) {
                    dbi = parent.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));

                    // When a child transaction writes and both commit
                    try (LmdbTxn child = sut.beginTxn(parent, Set.of())) {
                        child.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                        child.commit();
                    }
                    parent.commit();
                }

                // Then the write is visible afterward
                try (LmdbTxn txn = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    assertThat(txn.get(dbi, "k".getBytes())).isEqualTo("v".getBytes());
                }
            }
        }

        @Test
        void aChildTransactionsAbortLeavesTheParentUnaffected(@TempDir Path dir) {
            // Given a parent write transaction
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                LmdbDbi dbi;
                try (LmdbTxn parent = sut.beginTxn()) {
                    dbi = parent.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));

                    // When a child transaction writes but aborts
                    try (LmdbTxn child = sut.beginTxn(parent, Set.of())) {
                        child.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                        child.abort();
                    }
                    parent.commit();
                }

                // Then the parent never sees the child's write
                try (LmdbTxn txn = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    assertThat(txn.get(dbi, "k".getBytes())).isNull();
                }
            }
        }

        @Test
        void rejectsANullParent(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(dir, Set.of())) {
                // When a nested transaction is begun with a null parent
                ThrowingCallable result = () -> sut.beginTxn(null, Set.of());

                // Then it is rejected rather than passed through to the native call
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }
    }

    @Nested
    class Copy {

        @Test
        void copiesEntriesToAFreshDirectory(@TempDir Path sourceDir, @TempDir Path destDir) {
            // Given an environment with an entry written and committed
            LmdbDbi dbi;
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(sourceDir, Set.of())) {
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txn.commit();
                }

                // When copied to an empty destination directory
                sut.copyTo(destDir, Set.of());
            }

            // Then the copy opens independently and contains the same entry
            try (LmdbEnv copy = LmdbEnv.create().mapSize(10L << 20).open(destDir, Set.of())) {
                try (LmdbTxn txn = copy.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    LmdbDbi copyDbi = txn.openDatabase(Set.of());
                    assertThat(txn.get(copyDbi, "k".getBytes())).isEqualTo("v".getBytes());
                }
            }
        }

        @Test
        void compactFlagAlsoProducesAReadableCopy(@TempDir Path sourceDir, @TempDir Path destDir) {
            // Given an environment with an entry written and committed
            LmdbDbi dbi;
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(sourceDir, Set.of())) {
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txn.commit();
                }

                // When copied with COMPACT
                sut.copyTo(destDir, EnumSet.of(LmdbCopyFlag.COMPACT));
            }

            // Then the copy still opens and reads back the same entry
            try (LmdbEnv copy = LmdbEnv.create().mapSize(10L << 20).open(destDir, Set.of())) {
                try (LmdbTxn txn = copy.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    LmdbDbi copyDbi = txn.openDatabase(Set.of());
                    assertThat(txn.get(copyDbi, "k".getBytes())).isEqualTo("v".getBytes());
                }
            }
        }

        @Test
        void rejectsANonExistentDestination(@TempDir Path sourceDir, @TempDir Path destDir) {
            // Given an open environment and a destination directory that doesn't exist
            try (LmdbEnv sut = LmdbEnv.create().mapSize(10L << 20).open(sourceDir, Set.of())) {
                Path missing = destDir.resolve("does-not-exist");

                // When copied there
                ThrowingCallable result = () -> sut.copyTo(missing, Set.of());

                // Then the native call fails rather than silently creating it
                assertThatThrownBy(result).isInstanceOf(LmdbException.class);
            }
        }
    }
}
