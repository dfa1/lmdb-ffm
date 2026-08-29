package io.github.dfa1.lmdb;

import java.io.Serial;

/// Thrown when [NativeObject#close()] detects that the caller violated this
/// binding's resource-lifetime contract — currently, closing an [LmdbEnv]
/// while one of its [LmdbTxn]s was never committed or aborted.
///
/// Unlike every other `Throwable` [NativeObject#close()] encounters, this one
/// is never swallowed ("destructors must not throw" does not apply here):
/// whatever recovery was possible already happened before this is thrown —
/// see the thrower's own documentation for exactly what that means in a given
/// case — but the caller is still told loudly, since the underlying mistake
/// is a bug in their own resource management that would otherwise go
/// unnoticed, not a routinely-recoverable condition.
public final class LmdbContractException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    LmdbContractException(String message) {
        super(message);
    }
}
