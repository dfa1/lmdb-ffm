# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

## [Unreleased]

### Added

- `LmdbByteSize` domain primitive for map/page sizes.
- `module-info.java` for `lmdb-platform` and every native jar.
- `LmdbTxn#put(LmdbDbi, byte[], int, Consumer<MemorySegment>)`, a real
  zero-copy write path for `MDB_RESERVE`.
- `LmdbCursor#get(LmdbCursorOp, byte[], byte[])` (+ `MemorySegment`/
  `ByteBuffer` overloads) for `GET_BOTH`/`GET_BOTH_RANGE`.

### Fixed

- Comparator-installed environments no longer route `mdb_get`/`mdb_cursor_get`
  through the `critical` linker option, which crashed on any upcall.
- `LmdbEnv#close()` no longer frees the native environment while a
  transaction is still open.
- Cursors are guarded against use after their transaction has ended.
- `MemorySegment` key/data from a closed `Arena` is now rejected instead of
  silently copying a stale address.
- Zero-copy read segments (`getSegment`, `get(..., Mapper)`, cursor
  `get`/`getValue`) are now scoped to their owning transaction's lifetime
  instead of staying "alive" — and crash-prone — after it ends.
- `LmdbEnv` configuration (`mapSize`, `maxDatabases`, `maxReaders`,
  `pageSize`) is now rejected after `open()` instead of silently accepted.
- `LmdbWriteFlag.RESERVE` passed to a plain `put` overload is now rejected
  instead of silently discarding the supplied data.
- `GET_BOTH`/`GET_BOTH_RANGE` through a key-only cursor overload is now
  rejected instead of silently searching against a stale reused slot.

### Changed

- Clarified `LmdbTxn`/`LmdbCursor`/`LmdbEnvFlag#NOTLS` docs: both types are
  confined to their creating thread with no runtime enforcement beyond the
  read path's incidental protection, matching LMDB's own contract.

## [0.1] - 2026-08-28

First release. Java FFM bindings for LMDB: environments, transactions, named
databases, get/put/delete, cursors, custom key/value comparators, reader-lock
diagnostics, and two-phase-commit rollback/incremental backup. See the
[README](README.md#api-coverage) for exact `mdb_*` function coverage.
