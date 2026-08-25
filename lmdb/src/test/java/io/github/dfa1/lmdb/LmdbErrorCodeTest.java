package io.github.dfa1.lmdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LmdbErrorCodeTest {

    @Test
    void mapsANegativeLmdbCodeToItsNamedCategory() {
        // Given the native MDB_NOTFOUND code
        // When mapped to a category
        LmdbErrorCode sut = LmdbErrorCode.of(-30798);

        // Then it resolves to the matching named constant
        assertThat(sut).isEqualTo(LmdbErrorCode.NOTFOUND);
        assertThat(sut.value()).isEqualTo(-30798);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 22, 12})
    void mapsAnyPositiveCodeToSystemError(int errno) {
        // Given a positive return code (a platform errno, open-ended)
        // When mapped to a category
        LmdbErrorCode sut = LmdbErrorCode.of(errno);

        // Then it resolves to the SYSTEM_ERROR category regardless of the exact errno
        assertThat(sut).isEqualTo(LmdbErrorCode.SYSTEM_ERROR);
    }

    @Test
    void mapsAnUnrecognizedNegativeCodeToUnknown() {
        // Given a negative value outside LMDB's -30799..-30769 range
        // When mapped to a category
        LmdbErrorCode sut = LmdbErrorCode.of(-1);

        // Then it falls back to UNKNOWN rather than throwing
        assertThat(sut).isEqualTo(LmdbErrorCode.UNKNOWN);
    }
}
