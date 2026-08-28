package io.github.dfa1.lmdb;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A validated, non-negative byte size, replacing a naked `long`/`int` at
/// every public API boundary that takes or returns one:
/// [LmdbEnv#mapSize(LmdbByteSize)], [LmdbEnv#pageSize(LmdbByteSize)],
/// [LmdbEnv#maxKeySize()], [LmdbEnvInfo#mapSize()], and [LmdbStat#pageSize()].
///
/// A plain class with a private constructor, not a public record: every value
/// comes from a named factory ([#of(String)], [#ofKB(long)], [#ofMB(long)],
/// [#ofGB(long)], [#ofBytes(long)]) rather than an opaque `new LmdbByteSize(n)`
/// that leaves a reader to guess what unit `n` was even meant to be in.
///
/// Backed by `long` uniformly, even though `mdb_env_set_pagesize`/`mdb_stat`'s
/// `ms_psize` are native `int`s: narrowing to fit that native call happens
/// internally, not by making callers juggle two different types for the same
/// concept depending on which method they're calling.
///
/// `KB`/`MB`/`GB` here mean 1024-based (binary) multiples throughout — the
/// same values a stricter `KiB`/`MiB`/`GiB` naming would give — not the
/// 1000-based SI decimal reading. That matches how these units are used
/// colloquially in most dev tooling, and LMDB's own memory-mapped, page-
/// aligned sizing (pages are themselves powers of two).
public final class LmdbByteSize {

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("\\s*(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB)?\\s*", Pattern.CASE_INSENSITIVE);

    private final long bytes;

    private LmdbByteSize(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("size " + bytes + " must not be negative");
        }
        this.bytes = bytes;
    }

    /// This size, in bytes.
    ///
    /// @return the byte count
    public long bytes() {
        return bytes;
    }

    /// Narrows this size to an `int`, for the native calls (e.g.
    /// [LmdbEnv#pageSize(LmdbByteSize)]) that take one.
    ///
    /// @return this size as an `int`
    /// @throws ArithmeticException if the size exceeds `Integer.MAX_VALUE`
    int toIntBytes() {
        return Math.toIntExact(bytes);
    }

    /// `count` raw bytes — the escape hatch for a value that isn't a round
    /// [#ofKB(long)]/[#ofMB(long)]/[#ofGB(long)] multiple (e.g. wrapping an
    /// already-computed byte count from elsewhere).
    ///
    /// @param count the byte count
    /// @return a size of `count` bytes
    /// @throws IllegalArgumentException if `count` is negative
    public static LmdbByteSize ofBytes(long count) {
        return new LmdbByteSize(count);
    }

    /// Parses a human-readable size such as `"10MB"`, `"512 KB"`, `"1.5GB"`, or
    /// a bare `"1024"` (bytes, the implicit unit with none given). Whitespace
    /// around the number and unit is ignored; the unit is case-insensitive.
    ///
    /// @param text the size to parse
    /// @return the parsed size
    /// @throws IllegalArgumentException if `text` isn't a recognized size, or
    ///                                  parses to a negative size
    /// @throws ArithmeticException      if the value doesn't divide evenly
    ///                                  into a whole number of bytes, or
    ///                                  overflows a `long`
    public static LmdbByteSize of(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = SIZE_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a valid size: '" + text + "'");
        }
        BigDecimal value = new BigDecimal(matcher.group(1));
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(Locale.ROOT);
        long multiplier = switch (unit) {
            case "B" -> 1L;
            case "KB" -> 1024L;
            case "MB" -> 1024L * 1024L;
            case "GB" -> 1024L * 1024L * 1024L;
            default -> throw new AssertionError("unreachable: " + unit + " excluded by SIZE_PATTERN");
        };
        long bytes = value.multiply(BigDecimal.valueOf(multiplier)).setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact();
        return new LmdbByteSize(bytes);
    }

    /// `count` KB (1024 bytes each) — e.g. `LmdbByteSize.ofKB(4)` for a 4 KB
    /// page, clearer than spelling out `LmdbByteSize.ofBytes(4 * 1024)`.
    ///
    /// @param count the count of KB
    /// @return a size of `count * 1024` bytes
    /// @throws IllegalArgumentException if `count` is negative
    /// @throws ArithmeticException      if `count * 1024` overflows a `long`
    public static LmdbByteSize ofKB(long count) {
        return new LmdbByteSize(Math.multiplyExact(count, 1024L));
    }

    /// `count` MB (1024 * 1024 bytes each).
    ///
    /// @param count the count of MB
    /// @return a size of `count * 1024 * 1024` bytes
    /// @throws IllegalArgumentException if `count` is negative
    /// @throws ArithmeticException      if `count * 1024 * 1024` overflows a `long`
    public static LmdbByteSize ofMB(long count) {
        return new LmdbByteSize(Math.multiplyExact(count, 1024L * 1024L));
    }

    /// `count` GB (1024^3 bytes each) — the scale [LmdbEnv#mapSize(LmdbByteSize)]
    /// is typically sized at for a real workload.
    ///
    /// @param count the count of GB
    /// @return a size of `count * 1024 * 1024 * 1024` bytes
    /// @throws IllegalArgumentException if `count` is negative
    /// @throws ArithmeticException      if `count * 1024 * 1024 * 1024` overflows a `long`
    public static LmdbByteSize ofGB(long count) {
        return new LmdbByteSize(Math.multiplyExact(count, 1024L * 1024L * 1024L));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LmdbByteSize other && bytes == other.bytes;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "LmdbByteSize[bytes=" + bytes + "]";
    }
}
