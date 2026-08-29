package io.github.dfa1.lmdb;

/// `mdb_env_open` flags, OR-able bits from `lmdb.h`'s "Environment Flags"
/// group. Combine several with an `EnumSet.of(...)` (or `EnumSet.noneOf(
/// LmdbEnvFlag.class)`/`Set.of()` for none) — see [LmdbFlag].
public enum LmdbEnvFlag implements LmdbFlag {

    /// Use a fixed address for the mmap region (discouraged by upstream LMDB).
    FIXEDMAP(0x01),
    /// Treat `path` as a plain file rather than a directory containing `data.mdb`/`lock.mdb`.
    NOSUBDIR(0x4000),
    /// Don't flush the OS buffers to disk after each commit — faster, less durable.
    NOSYNC(0x10000),
    /// Open the environment read-only.
    RDONLY(0x20000),
    /// Flush data but skip the metadata sync after each commit.
    NOMETASYNC(0x40000),
    /// Use a writeable memory map, letting LMDB write directly to the mapped pages.
    WRITEMAP(0x80000),
    /// With [#WRITEMAP], flush asynchronously via `msync`/`FlushViewOfFile`.
    MAPASYNC(0x100000),
    /// Don't bind a reader lock table slot to each thread's thread-local
    /// storage. In upstream LMDB this also lets a read-only transaction
    /// migrate between threads, but [LmdbTxn] does not offer that: its
    /// reused `MDB_val` out-param slots live in an `Arena.ofConfined()` tied
    /// to the thread that began the transaction, so a read from any other
    /// thread still throws `WrongThreadException` regardless of this flag —
    /// see [LmdbTxn]'s own class doc and dfa1/lmdb-ffm#10.
    NOTLS(0x200000),
    /// Don't use file locking — the caller must ensure exclusive access itself.
    NOLOCK(0x400000),
    /// Don't do readahead; useful for a database bigger than RAM with random access.
    NORDAHEAD(0x800000),
    /// Don't zero-fill newly allocated pages before use.
    NOMEMINIT(0x1000000);

    private final int bits;

    LmdbEnvFlag(int bits) {
        this.bits = bits;
    }

    @Override
    public int bits() {
        return bits;
    }
}
