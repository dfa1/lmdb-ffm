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
        void acceptsZeroForEveryComponent() {
            // When a version made entirely of zeros is wrapped
            LmdbVersion sut = new LmdbVersion(0, 0, 0);

            // Then it is accepted
            assertThat(sut).hasToString("0.0.0");
        }

        @Test
        void rejectsANegativeMajor() {
            // When a negative major is wrapped
            ThrowingCallable result = () -> new LmdbVersion(-1, 0, 0);

            // Then it is rejected
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsANegativeMinor() {
            // When a negative minor is wrapped
            ThrowingCallable result = () -> new LmdbVersion(1, -1, 0);

            // Then it is rejected
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsANegativePatch() {
            // When a negative patch is wrapped
            ThrowingCallable result = () -> new LmdbVersion(1, 0, -1);

            // Then it is rejected
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class EqualityAndHashCode {

        @Test
        void versionsWithTheSameComponentsAreEqual() {
            // Given two versions built from the same components
            LmdbVersion a = new LmdbVersion(1, 0, 1);
            LmdbVersion b = new LmdbVersion(1, 0, 1);

            // Then they are equal and share a hash code
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void versionsDifferingInAnyComponentAreNotEqual() {
            // Then a difference in any single component breaks equality
            assertThat(new LmdbVersion(1, 0, 1)).isNotEqualTo(new LmdbVersion(2, 0, 1));
            assertThat(new LmdbVersion(1, 0, 1)).isNotEqualTo(new LmdbVersion(1, 1, 1));
            assertThat(new LmdbVersion(1, 0, 1)).isNotEqualTo(new LmdbVersion(1, 0, 2));
        }

        @Test
        void isNotEqualToAnUnrelatedType() {
            // Given a version and an unrelated object
            LmdbVersion sut = new LmdbVersion(1, 0, 1);

            // Then it is not equal to it
            assertThat(sut).isNotEqualTo("1.0.1");
        }
    }

    @Nested
    class Accessors {

        @Test
        void exposeTheWrappedComponents() {
            // Given a version
            LmdbVersion sut = new LmdbVersion(1, 2, 3);

            // Then each accessor returns its own component
            assertThat(sut.major()).isEqualTo(1);
            assertThat(sut.minor()).isEqualTo(2);
            assertThat(sut.patch()).isEqualTo(3);
        }
    }
}
