package io.github.dfa1.lmdb;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LmdbByteSizeTest {

    @Nested
    class Factories {

        @Test
        void ofBytesReportsExactlyThatManyBytes() {
            // When a size is built from a raw byte count
            LmdbByteSize sut = LmdbByteSize.ofBytes(513);

            // Then it reports that same count
            assertThat(sut.bytes()).isEqualTo(513L);
        }

        @Test
        void ofKbMultipliesBy1024() {
            assertThat(LmdbByteSize.ofKB(4).bytes()).isEqualTo(4L * 1024);
        }

        @Test
        void ofMbMultipliesBy1024Squared() {
            assertThat(LmdbByteSize.ofMB(10).bytes()).isEqualTo(10L * 1024 * 1024);
        }

        @Test
        void ofGbMultipliesBy1024Cubed() {
            assertThat(LmdbByteSize.ofGB(2).bytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        }

        @Test
        void ofGbOnAnOverflowingCountThrows() {
            // When a GB count large enough to overflow a long is given
            ThrowingCallable result = () -> LmdbByteSize.ofGB(Long.MAX_VALUE);

            // Then it fails rather than silently wrapping around
            assertThatThrownBy(result).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class NegativeSizes {

        @Test
        void ofBytesRejectsANegativeCount() {
            ThrowingCallable result = () -> LmdbByteSize.ofBytes(-1);

            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void ofKbRejectsANegativeCount() {
            ThrowingCallable result = () -> LmdbByteSize.ofKB(-1);

            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void zeroIsAllowed() {
            // Zero bytes is a valid (if degenerate) size, not an error
            assertThat(LmdbByteSize.ofBytes(0).bytes()).isZero();
        }
    }

    @Nested
    class Parsing {

        @ParameterizedTest
        @CsvSource({
            "1024, 1024",
            "1024B, 1024",
            "4KB, 4096",
            "4kb, 4096",
            "4Kb, 4096",
            "10 MB, 10485760",
            "1GB, 1073741824",
            "1.5KB, 1536",
            "0, 0",
            "0B, 0"
        })
        void parsesRecognizedSizes(String text, long expectedBytes) {
            assertThat(LmdbByteSize.of(text).bytes()).isEqualTo(expectedBytes);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "abc", "-5", "-5MB", "5TB", "5 5 MB", "MB"})
        void rejectsUnrecognizedText(String text) {
            ThrowingCallable result = () -> LmdbByteSize.of(text);

            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAFractionThatDoesNotDivideEvenlyIntoBytes() {
            // 1.3 KB = 1331.2 bytes, not a whole number of bytes
            ThrowingCallable result = () -> LmdbByteSize.of("1.3KB");

            assertThatThrownBy(result).isInstanceOf(ArithmeticException.class);
        }

        @Test
        void rejectsNull() {
            ThrowingCallable result = () -> LmdbByteSize.of(null);

            assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class IntNarrowing {

        @Test
        void toIntBytesReturnsTheSameValueWhenItFits() {
            assertThat(LmdbByteSize.ofBytes(4096).toIntBytes()).isEqualTo(4096);
        }

        @Test
        void toIntBytesThrowsWhenTheSizeExceedsIntRange() {
            // Given a size larger than Integer.MAX_VALUE, e.g. a realistic mapSize
            LmdbByteSize sut = LmdbByteSize.ofGB(10);

            // When narrowed to an int
            ThrowingCallable result = sut::toIntBytes;

            // Then it fails rather than silently truncating
            assertThatThrownBy(result).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class EqualityAndHashCode {

        @Test
        void sizesWithTheSameByteCountAreEqual() {
            LmdbByteSize a = LmdbByteSize.ofKB(4);
            LmdbByteSize b = LmdbByteSize.ofBytes(4096);

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void sizesWithDifferentByteCountsAreNotEqual() {
            assertThat(LmdbByteSize.ofBytes(1)).isNotEqualTo(LmdbByteSize.ofBytes(2));
        }

        @Test
        void isNotEqualToAnUnrelatedType() {
            assertThat(LmdbByteSize.ofBytes(1)).isNotEqualTo("not a size");
        }
    }

    @Nested
    class Rendering {

        @Test
        void toStringIncludesTheByteCount() {
            assertThat(LmdbByteSize.ofBytes(4096)).hasToString("LmdbByteSize[bytes=4096]");
        }
    }
}
