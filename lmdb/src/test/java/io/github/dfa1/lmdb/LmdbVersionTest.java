package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbVersionTest {

    @Nested
    class Rendering {

        @Test
        void rendersAsAnXYZString() {
            // Given a version
            LmdbVersion sut = new LmdbVersion(1, 0, 1);

            // Then it renders as x.y.z
            assertThat(sut).hasToString("1.0.1");
        }
    }

    @Nested
    class Ordering {

        @Test
        void ordersByMajorThenMinorThenPatch() {
            // Then a later patch/minor/major compares greater
            assertThat(new LmdbVersion(1, 0, 1)).isGreaterThan(new LmdbVersion(1, 0, 0));
            assertThat(new LmdbVersion(1, 1, 0)).isGreaterThan(new LmdbVersion(1, 0, 9));
            assertThat(new LmdbVersion(2, 0, 0)).isGreaterThan(new LmdbVersion(1, 9, 9));
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsANegativeComponent() {
            // When a negative patch is wrapped
            ThrowingCallable result = () -> new LmdbVersion(1, 0, -1);

            // Then it is rejected
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
