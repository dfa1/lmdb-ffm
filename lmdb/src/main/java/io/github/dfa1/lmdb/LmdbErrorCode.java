package io.github.dfa1.lmdb;

/// Categories of LMDB result code, exposed by [LmdbException#code()] so callers
/// can branch on the kind of failure (e.g. distinguish [#MAP_FULL] from
/// [#NOTFOUND]) instead of matching on message text.
///
/// LMDB's own negative codes (`MDB_KEYEXIST`..`MDB_ADDR_BUSY`, in the range
/// `BerkeleyDB uses -30800 to -30999, we'll go under them`) are enumerated
/// here by name. A positive return value is a system `errno`
/// (`ENOENT`, `EACCES`, `EINVAL`, `ENOMEM`, ...) — platform-dependent and not
/// enumerated — and maps to [#SYSTEM_ERROR]; use [LmdbException#nativeCode()]
/// for the raw value in that case. `MDB_NOTFOUND` itself is not thrown by the
/// read/cursor APIs — they surface it as a `null` return — but is still
/// enumerated for completeness and for APIs that pass a raw code through
/// (e.g. [LmdbTxn#drop]'s underlying `mdb_drop` never returns it, but other
/// paths may).
public enum LmdbErrorCode {

    /// Not from a recognized LMDB or system error code.
    UNKNOWN(Integer.MIN_VALUE),
    /// A positive system `errno`; see [LmdbException#nativeCode()] for the value.
    SYSTEM_ERROR(1),
    /// key/data pair already exists.
    KEY_EXIST(-30799),
    /// key/data pair not found (EOF).
    NOTFOUND(-30798),
    /// Requested page not found — this usually indicates corruption.
    PAGE_NOTFOUND(-30797),
    /// Located page was the wrong type.
    CORRUPTED(-30796),
    /// Update of meta page failed or environment had a fatal error.
    PANIC(-30795),
    /// Environment version mismatch.
    VERSION_MISMATCH(-30794),
    /// File is not an LMDB file.
    INVALID(-30793),
    /// Environment map size limit reached.
    MAP_FULL(-30792),
    /// Environment `maxdbs` limit reached.
    DBS_FULL(-30791),
    /// Environment `maxreaders` limit reached.
    READERS_FULL(-30790),
    /// Thread-local storage keys full — too many environments open.
    TLS_FULL(-30789),
    /// Transaction has too many dirty pages.
    TXN_FULL(-30788),
    /// Cursor stack too deep — internal error.
    CURSOR_FULL(-30787),
    /// Page has no more space — internal error.
    PAGE_FULL(-30786),
    /// Database contents grew beyond the environment's mapsize.
    MAP_RESIZED(-30785),
    /// Operation and database incompatible, or the DB type changed.
    INCOMPATIBLE(-30784),
    /// Invalid reuse of reader locktable slot.
    BAD_RSLOT(-30783),
    /// Transaction must abort, has a child, or is invalid.
    BAD_TXN(-30782),
    /// Unsupported size of key/DB name/data, or wrong `DUPFIXED` size.
    BAD_VALSIZE(-30781),
    /// The specified DBI handle was closed or is not valid.
    BAD_DBI(-30780),
    /// Unexpected problem — internal error.
    PROBLEM(-30779),
    /// Page checksum did not validate.
    BAD_CHECKSUM(-30778),
    /// Encryption/decryption of a page failed.
    CRYPTO_FAIL(-30777),
    /// Environment encryption/checksum settings changed.
    ENV_ENCRYPTION(-30776),
    /// Transaction is pending, cannot commit/abort/etc.
    TXN_PENDING(-30775),
    /// [LmdbEnv#rollback(long)] can't roll back the given transaction — a
    /// rollback was already done, there is no other valid metapage to roll
    /// back to, or another transaction has already been committed over it.
    CANT_ROLLBACK(-30774),
    /// Database handles are being used by other transactions.
    DBIS_BUSY(-30773),
    /// Data write of `mdb_env_copy` was short.
    SHORT_WRITE(-30772),
    /// Environment is already open in another process with different flags.
    ENV_BUSY(-30771),
    /// Environment is read-only.
    IS_READONLY(-30770),
    /// Requested map address is unavailable.
    ADDR_BUSY(-30769);

    private final int value;

    LmdbErrorCode(int value) {
        this.value = value;
    }

    /// The native code for this category, when it has exactly one; `1` for
    /// [#SYSTEM_ERROR] (a placeholder) or [Integer#MIN_VALUE] for [#UNKNOWN].
    /// Not exposed publicly: callers branch on the category itself
    /// (`code() == LmdbErrorCode.NOTFOUND`), and [LmdbException#nativeCode()]
    /// is the public way to get the real raw code, including the actual
    /// `errno` behind [#SYSTEM_ERROR] that this placeholder is not.
    int value() {
        return value;
    }

    /// Maps a native `mdb_*` return code to its category.
    ///
    /// @param code the native return code (never `MDB_SUCCESS`)
    /// @return the matching category, [#SYSTEM_ERROR] for any positive `errno`,
    ///         or [#UNKNOWN] if it is a negative value outside LMDB's range
    static LmdbErrorCode of(int code) {
        if (code > 0) {
            return SYSTEM_ERROR;
        }
        for (LmdbErrorCode c : values()) {
            if (c.value == code) {
                return c;
            }
        }
        return UNKNOWN;
    }
}
