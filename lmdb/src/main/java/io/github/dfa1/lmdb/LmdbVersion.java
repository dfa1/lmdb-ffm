package io.github.dfa1.lmdb;

import java.util.Objects;

/// The version of the linked `liblmdb`, from `mdb_version`.
///
/// A plain class rather than a public record: the only meaningful instance is
/// whatever `mdb_version` actually reports ([Lmdb#version()]), and a public
/// canonical constructor would let a caller fabricate a fake version to feed
/// version-gated logic.
public final class LmdbVersion implements Comparable<LmdbVersion> {

    private final int major;
    private final int minor;
    private final int patch;

    /// Validates the components are non-negative.
    LmdbVersion(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version " + major + "." + minor + "." + patch + " must not be negative");
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /// The major version component.
    ///
    /// @return the major version component
    public int major() {
        return major;
    }

    /// The minor version component.
    ///
    /// @return the minor version component
    public int minor() {
        return minor;
    }

    /// The patch version component.
    ///
    /// @return the patch version component
    public int patch() {
        return patch;
    }

    /// Orders by `major`, then `minor`, then `patch`.
    ///
    /// @param other the version to compare with
    /// @return a negative, zero, or positive value as this version is less than,
    ///         equal to, or greater than `other`
    @Override
    public int compareTo(LmdbVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        if (byMinor != 0) {
            return byMinor;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LmdbVersion other)) {
            return false;
        }
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    /// The `"MAJOR.MINOR.PATCH"` string, matching `mdb_version`'s own rendering.
    ///
    /// @return the version as an `x.y.z` string
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
