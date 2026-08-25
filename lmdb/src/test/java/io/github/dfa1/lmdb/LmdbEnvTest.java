package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
    }
}
