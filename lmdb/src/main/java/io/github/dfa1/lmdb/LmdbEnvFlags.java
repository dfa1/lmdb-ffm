package io.github.dfa1.lmdb;

/// `mdb_env_open` flags, OR-able bits from `lmdb.h`'s "Environment Flags" group.
/// Kept as named `int` constants rather than a single validated enum: these
/// combine freely (e.g. `NOSUBDIR | NOSYNC`), so no one constant is "the"
/// value passed — see the exception for heterogeneous native passthroughs
/// documented in `CLAUDE.md`.
public final class LmdbEnvFlags {

    /// Use a fixed address for the mmap region (discouraged by upstream LMDB).
    public static final int FIXEDMAP = 0x01;
    /// Treat `path` as a plain file rather than a directory containing `data.mdb`/`lock.mdb`.
    public static final int NOSUBDIR = 0x4000;
    /// Don't flush the OS buffers to disk after each commit — faster, less durable.
    public static final int NOSYNC = 0x10000;
    /// Open the environment read-only.
    public static final int RDONLY = 0x20000;
    /// Flush data but skip the metadata sync after each commit.
    public static final int NOMETASYNC = 0x40000;
    /// Use a writeable memory map, letting LMDB write directly to the mapped pages.
    public static final int WRITEMAP = 0x80000;
    /// With [#WRITEMAP], flush asynchronously via `msync`/`FlushViewOfFile`.
    public static final int MAPASYNC = 0x100000;
    /// Don't bind a reader lock table slot to each thread's thread-local storage.
    public static final int NOTLS = 0x200000;
    /// Don't use file locking — the caller must ensure exclusive access itself.
    public static final int NOLOCK = 0x400000;
    /// Don't do readahead; useful for a database bigger than RAM with random access.
    public static final int NORDAHEAD = 0x800000;
    /// Don't zero-fill newly allocated pages before use.
    public static final int NOMEMINIT = 0x1000000;

    private LmdbEnvFlags() {
        // no instances
    }
}
