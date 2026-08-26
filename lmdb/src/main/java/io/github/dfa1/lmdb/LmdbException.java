package io.github.dfa1.lmdb;

import java.io.Serial;

/// Thrown when an LMDB native call reports an error.
///
/// Unchecked: an LMDB error on valid use of this API indicates either a
/// corrupt/full database or a programming error (e.g. an invalid handle), not
/// a routinely-recoverable condition. `MDB_NOTFOUND` is the one common
/// exception to "error" — it is not thrown here at all; the read/cursor APIs
/// surface it as a `null` return instead.
public final class LmdbException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /// The raw `mdb_*` return code this exception was built from.
    private final int nativeCode;
    /// The category [#nativeCode] maps to.
    private final LmdbErrorCode code;

    LmdbException(String message, int nativeCode) {
        super(message);
        this.nativeCode = nativeCode;
        this.code = LmdbErrorCode.of(nativeCode);
    }

    /// The category of this error, for programmatic branching.
    ///
    /// @return the LMDB error category
    public LmdbErrorCode code() {
        return code;
    }

    /// The raw `mdb_*` return code this exception was built from — the exact
    /// system `errno` when [#code()] is [LmdbErrorCode#SYSTEM_ERROR].
    ///
    /// @return the native return code
    public int nativeCode() {
        return nativeCode;
    }
}
