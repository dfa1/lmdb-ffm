package io.github.dfa1.lmdb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LmdbTest {

    @Test
    void reportsTheLinkedLibraryVersion() {
        // Given the liblmdb bundled for this platform, pinned to the LMDB_1.0.1 submodule tag
        // When its version is read
        LmdbVersion sut = Lmdb.version();

        // Then it matches the vendored release
        assertThat(sut).isEqualTo(new LmdbVersion(1, 0, 1));
    }
}
