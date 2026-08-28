# lmdb-ffm

[![CI](https://github.com/dfa1/lmdb-ffm/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/lmdb-ffm/actions/workflows/ci.yml)
![LMDB](https://img.shields.io/badge/LMDB-1.0.1-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-OpenLDAP--2.8-blue.svg)](LICENSE)

**lmdb-ffm** wraps [LMDB](https://github.com/LMDB/lmdb) (Lightning
Memory-Mapped Database) through the Java **Foreign Function & Memory (FFM)
API** — no JNI, no hand-written C (JDK 25 is the first LTS with stable
`java.lang.foreign`).

The differentiator is the **zero-copy read path**: a lookup hands back a
`MemorySegment` pointing directly into the memory-mapped database file — no
`byte[]` copy — valid for the life of the read transaction.

Currently covers environments, transactions, named databases, get/put/delete,
cursors, custom key/value ordering, reader-lock diagnostics, and two-phase-commit
rollback/incremental backup — enough to build on, not (yet) LMDB's full
surface. See [API coverage](#api-coverage) below for exactly what's bound and
what isn't, and [CLAUDE.md](CLAUDE.md) for the design decisions behind what's
here.

## Quickstart

```java
import io.github.dfa1.lmdb.*;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

try (LmdbEnv env = LmdbEnv.create()
        .mapSize(10L << 20)
        .open(Path.of("/tmp/mydb"), EnumSet.of(LmdbEnvFlag.NOSUBDIR))) {

    LmdbDbi dbi;
    try (LmdbTxn txn = env.beginTxn()) {
        dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
        txn.put(dbi, "key".getBytes(UTF_8), "value".getBytes(UTF_8), Set.of());
        txn.commit();
    }

    try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
        byte[] value = txn.get(dbi, "key".getBytes(UTF_8)); // null if not present
        if (value != null) {
            System.out.println(new String(value, UTF_8)); // "value"
        }
    }
}
```

Flags are `enum`s combined with `EnumSet` (`Set.of()` for none) rather than
OR'd `int` constants — no risk of passing a `LmdbDbiFlag` where an
`LmdbEnvFlag` is expected.

Zero-copy read, cursor iteration:

```java
try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY));
     LmdbCursor cursor = txn.openCursor(dbi)) {
    for (var e = cursor.get(LmdbCursorOp.FIRST); e != null; e = cursor.get(LmdbCursorOp.NEXT)) {
        MemorySegment key = e.key();   // points straight into the mmap, no copy
        MemorySegment data = e.data();
    }
}
```

Zero-copy point lookup with a `Mapper` — no `byte[]`, no `MemorySegment` left
over to manage; the view is only valid inside the callback:

```java
try (LmdbTxn txn = env.beginTxn(EnumSet.of(LmdbEnvFlag.RDONLY))) {
    String value = txn.get(dbi, "key".getBytes(UTF_8), // null if not present
            seg -> new String(seg.toArray(ValueLayout.JAVA_BYTE), UTF_8));
}
```

`get`/`getSegment`/`put`/`delete` (and their `Mapper`-based cousin) all take
`MemorySegment` or direct `ByteBuffer` key/data too, for callers whose bytes
are already off-heap — an mmap slice, a network buffer, an arena allocation —
with no `byte[]` bounce on either side of the call.

Custom key ordering — LMDB calls back into Java for every comparison, via a
`Linker.upcallStub` trampoline, not just another downcall:

```java
try (LmdbTxn txn = env.beginTxn()) {
    LmdbDbi dbi = txn.openDatabase(EnumSet.of(LmdbDbiFlag.CREATE));
    // Must be set before any data is read or written through dbi, and the
    // same comparator must be used every time this database is opened.
    txn.setComparator(dbi, (a, b) -> Long.compare(a.byteSize(), b.byteSize()));
    txn.commit();
}
```

Run with `--enable-native-access=ALL-UNNAMED`.

## Platform support

| OS | x86_64 | aarch64 |
|---|---|---|
| Linux | ✅ | ✅ |
| macOS | ✅ | ✅ |
| Windows | ✅ | ✅ (cross-compiled; not yet run on real ARM64 Windows hardware) |

Every native `liblmdb` is cross-compiled hermetically from the vendored
`third_party/lmdb` submodule with **`zig cc`** — see [CLAUDE.md](CLAUDE.md)
for the build.

## API coverage

51 of `lmdb.h`'s 69 `mdb_*` functions are bound today (this count was
previously, incorrectly, given as 65 — `mdb_txn_id`, `mdb_txn_env`,
`mdb_cursor_dbi` and `mdb_cursor_txn` had been left out of the inventory
entirely, not just miscategorized):

| Area | Bound |
|---|---|
| Environment | `mdb_env_create`, `mdb_env_open`, `mdb_env_close`, `mdb_env_set_mapsize`, `mdb_env_set_maxdbs`, `mdb_env_set_maxreaders`, `mdb_env_get_maxreaders`, `mdb_env_set_pagesize`, `mdb_env_get_maxkeysize`, `mdb_env_sync`, `mdb_env_set_flags`, `mdb_env_get_flags`, `mdb_env_get_path`, `mdb_env_get_fd`, `mdb_env_stat`, `mdb_env_info`, `mdb_env_copy2`, `mdb_reader_list`, `mdb_env_rollback`, `mdb_reader_check`, `mdb_env_incr_dump` |
| Transactions | `mdb_txn_begin`, `mdb_txn_commit`, `mdb_txn_abort`, `mdb_txn_prepare`, `mdb_txn_reset`, `mdb_txn_renew`, `mdb_txn_flags`, `mdb_txn_id` |
| Databases | `mdb_dbi_open`, `mdb_dbi_close`, `mdb_dbi_flags`, `mdb_drop`, `mdb_set_compare`, `mdb_set_dupsort`, `mdb_cmp`, `mdb_dcmp` |
| Data access | `mdb_get`, `mdb_put`, `mdb_del`, `mdb_stat` |
| Cursors | `mdb_cursor_open`, `mdb_cursor_close`, `mdb_cursor_get`, `mdb_cursor_put`, `mdb_cursor_del`, `mdb_cursor_count`, `mdb_cursor_renew`, `mdb_cursor_is_db` |
| Misc | `mdb_version`, `mdb_strerror` |

`mdb_env_rollback` pairs with `mdb_txn_id` (`LmdbTxn#id()`): a two-phase-commit
participant captures a write transaction's ID before committing it, and can
later hand that ID to `LmdbEnv#rollback(long)` to undo exactly that commit if
some other participant fails to follow through — but only immediately after
the commit, with no other operation on the environment in between, and never
for an environment's very first commit (there is no earlier metapage to roll
back to yet). `mdb_env_incr_dump` pairs with it too, for incremental backup:
`LmdbEnv#incrementalDumpTo(Path, long)` dumps everything committed since an
earlier backup's transaction ID — but as a real, LMDB-internal side effect,
it unconditionally sets `MDB_NOSUBDIR` on the environment's own flags, so
`LmdbEnv#flags()` reports it afterward even on an environment never opened
with it.

`mdb_cursor_dbi`/`mdb_cursor_txn` (the database/transaction a cursor was
opened on) and `mdb_txn_env` (a transaction's owning environment) are all
tracked in Java instead of bound as native calls: `LmdbCursor`/`LmdbTxn`
already know these from how they were constructed, so a native round-trip
would only relearn what the object already has — see `LmdbCursor#dbi()`/`#txn()`
and `LmdbTxn#env()`.

`mdb_set_compare`/`mdb_set_dupsort` (`MDB_cmp_func*`) and `mdb_reader_list`
(`MDB_msg_func*`) all take a function pointer LMDB calls back into — a Java
[LmdbComparator](lmdb/src/main/java/io/github/dfa1/lmdb/LmdbComparator.java)
or [LmdbMessageHandler](lmdb/src/main/java/io/github/dfa1/lmdb/LmdbMessageHandler.java)
respectively — through a `Linker.upcallStub` trampoline (see
[#1](https://github.com/dfa1/lmdb-ffm/issues/1)) rather than the plain
`downcallHandle` the rest of this table uses. The two differ in the
trampoline's lifetime: a comparator must keep working for as long as the
`dbi` it was installed on is used, so its stub lives in an `Arena` scoped to
the `LmdbEnv`; `mdb_reader_list` only ever calls back synchronously within
the one call that installs it, so its stub is scoped to that single call.

Not yet bound (18):

| Function | Category | Purpose |
|---|---|---|
| `mdb_env_copy` | Skippable | Superseded — `copyTo`/`mdb_env_copy2` (bound) already covers this plus flags |
| `mdb_env_copyfd` | No clean fd source | Backup to an open OS file descriptor/`HANDLE` instead of a path — Java has no public, non-reflective way to obtain that raw value from a `java.io.FileDescriptor`, and this project avoids internal JDK APIs (see CLAUDE.md) |
| `mdb_env_copyfd2` | No clean fd source | Same, with `MDB_CP_COMPACT` support — same fd problem as `mdb_env_copyfd` |
| `mdb_env_incr_dumpfd` | No clean fd source | Incremental dump to an open file descriptor — same fd problem; `mdb_env_incr_dump` (bound) covers the path-based case |
| `mdb_env_incr_loadfd` | No clean fd source | Reload an incremental dump from an open file descriptor — same fd problem |
| `mdb_txn_env` | Skippable | A transaction's owning environment — already covered by `LmdbTxn#env()`, tracked in Java without a native round-trip |
| `mdb_cursor_dbi` | Skippable | The `dbi` a cursor was opened on — tracked in Java by `LmdbCursor#dbi()`, same reasoning as `mdb_txn_env` |
| `mdb_cursor_txn` | Skippable | The transaction a cursor currently operates within — tracked in Java by `LmdbCursor#txn()`, updated on `renew` |
| `mdb_set_relctx` | Dead upstream | Context pointer for a `MDB_FIXEDMAP` relocation callback — `lmdb.h`'s own doc on `mdb_set_relfunc` says outright "currently the relocation feature is unimplemented and setting this function has no effect," so there is nothing this binding could ever do |
| `mdb_set_relfunc` | Dead upstream | The relocation callback itself — same "unimplemented, no effect" admission from `lmdb.h`, not a gap in this project |
| `mdb_env_set_assert` | Dead on this build | Custom assertion-failure handler/logger — inert here: this project's native builds pass `-DNDEBUG` (see `scripts/build-lmdb.sh`), which compiles out `mdb_env_set_assert`'s store of the callback pointer entirely (`mdb.c`'s own `#ifndef NDEBUG` guard around it), so the callback could never fire. It also always ends in `abort()` even on a debug build, so there'd be no safe way to exercise it in this project's tests either way. |
| `mdb_modload` | Refused on principle | Dynamically loads a shared library from a caller-supplied path (`dlopen`-style) and returns crypto function pointers from it — exactly the risk `NativeLibrary`'s own doc refuses: "loading a caller-supplied native library is arbitrary native code execution in the JVM process." Not a missing feature; deliberately never adding it. |
| `mdb_modsetup` | Refused on principle | Only useful with a module handle from `mdb_modload` — moot without it |
| `mdb_modunload` | Refused on principle | Same — only unloads what `mdb_modload` would have loaded |
| `mdb_env_set_checksum` | Deferred | Pluggable page-checksum hook — unlike `mdb_modload`, this takes a caller-*implemented* function (no `dlopen`), so it's not blocked on principle, but `me_sumfunc` fires on every page read/write in `mdb.c`, not once at open — real hot-path perf work (benchmark-backed, per this project's own bar for `GET`/`CURSOR_GET`) before it can be added responsibly |
| `mdb_env_set_encrypt` | Deferred | Pluggable page-encryption hook — same hot-path concern as `mdb_env_set_checksum`, plus higher stakes: a buggy checksum just reports `MDB_BAD_CHECKSUM`, but a buggy encrypt function silently corrupts page content on disk |
| `mdb_env_set_userctx` | Skippable | Opaque app-data pointer — a caller would just keep a field on their own wrapper |
| `mdb_env_get_userctx` | Skippable | Getter counterpart to the above |

"Dead upstream" means LMDB's own header documents the function as inert —
not a gap here, nothing to add. "Refused on principle" means the function
works but does something (`dlopen` of a caller-supplied path) this project's
native-loading policy explicitly rejects (see `NativeLibrary`'s own doc).
"Deferred" means the function is real, useful, and not blocked by anything
structural — just not yet justified by the benchmarking this project holds
every hot-path binding to.

## Build from source

Not yet published — build and install locally:

```shell
git clone --recurse-submodules https://github.com/dfa1/lmdb-ffm.git
cd lmdb-ffm
./mvnw install
```

Requires JDK 25+ and [Zig](https://ziglang.org) 0.16+ on `PATH`. Then depend
on the `lmdb` artifact plus the `lmdb-native-<classifier>` for your platform
(or `lmdb-platform`, which bundles every classifier currently shipped, for a
zero-choice dependency):

```xml
<dependency>
  <groupId>io.github.dfa1.lmdb</groupId>
  <artifactId>lmdb-platform</artifactId>
  <version>0.1-SNAPSHOT</version>
</dependency>
```

## License

[The OpenLDAP Public License](LICENSE) — the same license LMDB itself ships
under.

---

> **AI-assisted development:** This project uses Claude Code for
> implementation — C header mapping, test generation, docs. Architecture, API
> design, and all decisions are human-driven.
