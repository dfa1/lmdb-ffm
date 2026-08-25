package io.github.dfa1.lmdb;

/// The version of the linked `liblmdb`, from `mdb_version`.
///
/// @param major the major version component
/// @param minor the minor version component
/// @param patch the patch version component
public record LmdbVersion(int major, int minor, int patch) implements Comparable<LmdbVersion> {

    /// Validates the components are non-negative.
    public LmdbVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version " + major + "." + minor + "." + patch + " must not be negative");
        }
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

    /// The `"MAJOR.MINOR.PATCH"` string, matching `mdb_version`'s own rendering.
    ///
    /// @return the version as an `x.y.z` string
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
