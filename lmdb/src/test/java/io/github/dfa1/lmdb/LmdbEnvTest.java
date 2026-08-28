package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
            LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of());

            // Then it closes cleanly and can be closed again (idempotent)
            sut.close();
            sut.close();
        }

        @Test
        void opensAsASingleFileWithNosubdir(@TempDir Path dir) {
            // Given a path naming a file rather than a directory
            Path file = dir.resolve("single.mdb");

            // When opened with NOSUBDIR
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(file, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
                // Then it succeeds and creates exactly that file
                assertThat(sut).isNotNull();
                assertThat(file).exists();
            }
        }

        @Test
        void beginningATransactionAfterCloseFails(@TempDir Path dir) {
            // Given a closed environment
            LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of());
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // Then it reports LMDB's (page-size-dependent) key size limit
                assertThat(sut.maxKeySize().bytes()).isPositive();
            }
        }

        @Test
        void statReportsAnEmptyFreshEnvironment(@TempDir Path dir) {
            // Given a freshly opened, empty environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When its statistics are read
                LmdbStat stat = sut.stat();

                // Then the unnamed database is empty, with a positive page size
                assertThat(stat.entries()).isZero();
                assertThat(stat.pageSize().bytes()).isPositive();
            }
        }

        @Test
        void infoReportsTheConfiguredMapSize(@TempDir Path dir) {
            // Given an environment opened with a specific map size
            LmdbByteSize mapSize = LmdbByteSize.ofMB(10);
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).maxReaders(42).open(dir, Set.of())) {
                // Then info reflects that same count
                assertThat(sut.info().maxReaders()).isEqualTo(42);
            }
        }

        @Test
        void maxReadersGetterMatchesTheConfiguredCount(@TempDir Path dir) {
            // Given an environment configured with an explicit reader slot count
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).maxReaders(42).open(dir, Set.of())) {
                // Then the direct getter reports the same count as info()
                assertThat(sut.maxReaders()).isEqualTo(42);
            }
        }

        @Test
        void pageSizeIsHonoredByTheOpenedEnvironment(@TempDir Path dir) {
            // Given an environment configured with an explicit page size
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).pageSize(LmdbByteSize.ofBytes(4096))
                    .open(dir, Set.of())) {
                // Then it reports that same page size
                assertThat(sut.stat().pageSize()).isEqualTo(LmdbByteSize.ofBytes(4096));
            }
        }

        @Test
        void pathReturnsWhatItWasOpenedWith(@TempDir Path dir) {
            // Given an environment opened at a specific path
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // Then it reports that same path back
                assertThat(sut.path()).isEqualTo(dir);
            }
        }

        @Test
        void fdReportsANonNegativeDescriptor(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(file, EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {
                // Then flags() reports it
                assertThat(sut.flags()).contains(LmdbEnvFlag.NOSUBDIR);
            }
        }

        @Test
        void setFlagsAddsAndClearsAFlagAtRuntime(@TempDir Path dir) {
            // Given an environment opened without NOSYNC
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(sourceDir, Set.of())) {
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txn.commit();
                }

                // When copied to an empty destination directory
                sut.copyTo(destDir, Set.of());
            }

            // Then the copy opens independently and contains the same entry
            try (LmdbEnv copy = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(destDir, Set.of())) {
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
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(sourceDir, Set.of())) {
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txn.commit();
                }

                // When copied with COMPACT
                sut.copyTo(destDir, EnumSet.of(LmdbCopyFlag.COMPACT));
            }

            // Then the copy still opens and reads back the same entry
            try (LmdbEnv copy = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(destDir, Set.of())) {
                try (LmdbTxn txn = copy.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    LmdbDbi copyDbi = txn.openDatabase(Set.of());
                    assertThat(txn.get(copyDbi, "k".getBytes())).isEqualTo("v".getBytes());
                }
            }
        }

        @Test
        void rejectsANonExistentDestination(@TempDir Path sourceDir, @TempDir Path destDir) {
            // Given an open environment and a destination directory that doesn't exist
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(sourceDir, Set.of())) {
                Path missing = destDir.resolve("does-not-exist");

                // When copied there
                ThrowingCallable result = () -> sut.copyTo(missing, Set.of());

                // Then the native call fails rather than silently creating it
                assertThatThrownBy(result).isInstanceOf(LmdbException.class);
            }
        }
    }

    @Nested
    class ReaderList {

        @Test
        void reportsNoActiveReadersOnAFreshEnvironment(@TempDir Path dir) {
            // Given a freshly opened environment with no read transactions
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                List<String> lines = new ArrayList<>();

                // When the reader lock table is dumped
                sut.listReaders(line -> {
                    lines.add(line);
                    return true;
                });

                // Then a single explanatory line is reported, no header
                assertThat(lines).containsExactly("(no active readers)\n");
            }
        }

        @Test
        void reportsAnActiveReaderWithAHeaderLine(@TempDir Path dir) {
            // Given an environment with one active read-only transaction
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of());
                    LmdbTxn _ = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                List<String> lines = new ArrayList<>();

                // When the reader lock table is dumped
                sut.listReaders(line -> {
                    lines.add(line);
                    return true;
                });

                // Then a header line is followed by one line for the active
                // reader, which reports this process's own pid
                assertThat(lines).hasSize(2);
                assertThat(lines.get(0)).isEqualTo("    pid     thread     txnid\n");
                assertThat(lines.get(1)).contains(String.valueOf(ProcessHandle.current().pid()));
            }
        }

        @Test
        void returningFalseStopsTheDumpEarly(@TempDir Path dir) {
            // Given an environment with an active reader, so more than one line would be reported
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of());
                    LmdbTxn _ = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                AtomicInteger calls = new AtomicInteger();

                // When the handler asks to stop after the first line
                sut.listReaders(line -> {
                    calls.incrementAndGet();
                    return false;
                });

                // Then it is invoked exactly once, not for every line
                assertThat(calls.get()).isEqualTo(1);
            }
        }

        @Test
        void aHandlerThatThrowsDoesNotCrashTheJvm(@TempDir Path dir) {
            // Given a freshly opened environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When the handler throws
                ThrowingCallable result = () -> sut.listReaders(line -> {
                    throw new RuntimeException("boom");
                });

                // Then the exception is contained (an upcall cannot propagate a
                // Java exception into LMDB's C code) rather than crashing the process
                assertThatCode(result).doesNotThrowAnyException();
            }
        }

        @Test
        void rejectsANullHandler(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When listing readers with a null handler
                ThrowingCallable result = () -> sut.listReaders(null);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }
    }

    @Nested
    class Rollback {

        @Test
        void undoesTheLastCommittedTransaction(@TempDir Path dir) {
            // Given two committed write transactions — a first commit is
            // needed so the environment has a valid earlier metapage to roll
            // back to; mdb_env_rollback can't undo an environment's very
            // first commit
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                LmdbDbi dbi;
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "seed".getBytes(), "1".getBytes(), Set.of());
                    txn.commit();
                }
                long txnid;
                try (LmdbTxn txn = sut.beginTxn()) {
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txnid = txn.id();
                    txn.commit();
                }

                // When the second transaction is rolled back immediately afterward
                sut.rollback(txnid);

                // Then its write is undone, while the first transaction's write survives
                try (LmdbTxn txn = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                    assertThat(txn.get(dbi, "k".getBytes())).isNull();
                    assertThat(txn.get(dbi, "seed".getBytes())).isEqualTo("1".getBytes());
                }
            }
        }

        @Test
        void rollingBackTheSameTransactionTwiceFails(@TempDir Path dir) {
            // Given a committed transaction (preceded by an earlier one, so
            // there is a valid earlier metapage to roll back to) already
            // rolled back once — each commit writes a key, since an empty
            // write transaction may not advance LMDB's metapage txnid at all
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                LmdbDbi dbi;
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "seed".getBytes(), "1".getBytes(), Set.of());
                    txn.commit();
                }
                long txnid;
                try (LmdbTxn txn = sut.beginTxn()) {
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txnid = txn.id();
                    txn.commit();
                }
                sut.rollback(txnid);

                // When rolled back again
                ThrowingCallable result = () -> sut.rollback(txnid);

                // Then it fails rather than corrupting the environment further
                assertThatThrownBy(result).isInstanceOf(LmdbException.class);
            }
        }

        @Test
        void rollingBackATxnidThatWasNeverCommittedFails(@TempDir Path dir) {
            // Given a freshly opened environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When rolled back with a txnid that was never committed
                ThrowingCallable result = () -> sut.rollback(999_999L);

                // Then it fails rather than silently doing nothing
                assertThatThrownBy(result).isInstanceOf(LmdbException.class);
            }
        }
    }

    @Nested
    class ReaderCheck {

        @Test
        void reportsNoStaleReadersOnAFreshEnvironment(@TempDir Path dir) {
            // Given a freshly opened environment with no read transactions
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When stale reader slots are checked
                int cleared = sut.checkReaders();

                // Then there is nothing to clear
                assertThat(cleared).isZero();
            }
        }

        @Test
        void doesNotCountALiveReaderAsStale(@TempDir Path dir) {
            // Given an environment with one active (non-stale) read-only transaction
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of());
                    LmdbTxn _ = sut.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
                // When stale reader slots are checked
                int cleared = sut.checkReaders();

                // Then the live reader is left alone
                assertThat(cleared).isZero();
            }
        }
    }

    @Nested
    class IncrementalDump {

        @Test
        void dumpsChangesSinceAnEarlierTransactionToAFile(@TempDir Path dir, @TempDir Path dumpDir) {
            // Given two committed write transactions
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                LmdbDbi dbi;
                long firstTxnid;
                try (LmdbTxn txn = sut.beginTxn()) {
                    dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
                    txn.put(dbi, "seed".getBytes(), "1".getBytes(), Set.of());
                    firstTxnid = txn.id();
                    txn.commit();
                }
                try (LmdbTxn txn = sut.beginTxn()) {
                    txn.put(dbi, "k".getBytes(), "v".getBytes(), Set.of());
                    txn.commit();
                }

                // When an incremental dump since the first transaction is written
                Path dumpFile = dumpDir.resolve("incr.mdb");
                sut.incrementalDumpTo(dumpFile, firstTxnid);

                // Then the dump file is created
                assertThat(dumpFile).exists();

                // And, an LMDB implementation quirk in mdb_env_incr_dump: this
                // environment's own flags now report NOSUBDIR, even though it
                // was never opened with it
                assertThat(sut.flags()).contains(LmdbEnvFlag.NOSUBDIR);
            }
        }

        @Test
        void rejectsANullPath(@TempDir Path dir) {
            // Given an open environment
            try (LmdbEnv sut = LmdbEnv.create().mapSize(LmdbByteSize.ofMB(10)).open(dir, Set.of())) {
                // When dumped to a null path
                ThrowingCallable result = () -> sut.incrementalDumpTo(null, 1L);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }
    }
}
