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

Currently covers environments, transactions, named databases, get/put/delete
and cursors — enough to build on, not (yet) LMDB's full surface. See
[API coverage](#api-coverage) below for exactly what's bound and what isn't,
and [CLAUDE.md](CLAUDE.md) for the design decisions behind what's here.

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

44 of `lmdb.h`'s 65 `mdb_*` functions are bound today:

| Area | Bound |
|---|---|
| Environment | `mdb_env_create`, `mdb_env_open`, `mdb_env_close`, `mdb_env_set_mapsize`, `mdb_env_set_maxdbs`, `mdb_env_set_maxreaders`, `mdb_env_get_maxreaders`, `mdb_env_set_pagesize`, `mdb_env_get_maxkeysize`, `mdb_env_sync`, `mdb_env_set_flags`, `mdb_env_get_flags`, `mdb_env_get_path`, `mdb_env_get_fd`, `mdb_env_stat`, `mdb_env_info`, `mdb_env_copy2`, `mdb_reader_list` |
| Transactions | `mdb_txn_begin`, `mdb_txn_commit`, `mdb_txn_abort`, `mdb_txn_prepare`, `mdb_txn_reset`, `mdb_txn_renew`, `mdb_txn_flags` |
| Databases | `mdb_dbi_open`, `mdb_dbi_close`, `mdb_dbi_flags`, `mdb_drop`, `mdb_set_compare`, `mdb_set_dupsort` |
| Data access | `mdb_get`, `mdb_put`, `mdb_del`, `mdb_stat` |
| Cursors | `mdb_cursor_open`, `mdb_cursor_close`, `mdb_cursor_get`, `mdb_cursor_put`, `mdb_cursor_del`, `mdb_cursor_count`, `mdb_cursor_renew` |
| Misc | `mdb_version`, `mdb_strerror` |

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

Not yet bound (21):

| Function | Category | Purpose |
|---|---|---|
| `mdb_cmp` | Straightforward | Compare two keys using `dbi`'s active key-comparison function (default or custom) |
| `mdb_dcmp` | Straightforward | Same, for the *data* comparison in a `DUPSORT` database |
| `mdb_cursor_is_db` | Straightforward | Whether the cursor's current position is itself a named sub-database record |
| `mdb_reader_check` | Straightforward | Clear stale reader-lock-table slots (e.g. from a crashed process); returns a count |
| `mdb_env_copy` | Straightforward | Superseded — `copyTo`/`mdb_env_copy2` (bound) already covers this plus flags |
| `mdb_env_copyfd` | Straightforward | Backup to an open file descriptor instead of a path (e.g. a pipe/socket) |
| `mdb_env_copyfd2` | Straightforward | Same, with `MDB_CP_COMPACT` support |
| `mdb_env_incr_dump` | Straightforward | Incremental (delta-since-`txnid`) backup to a path |
| `mdb_env_incr_dumpfd` | Straightforward | Same, to an open file descriptor |
| `mdb_env_incr_loadfd` | Straightforward | Load an incremental dump back in |
| `mdb_env_rollback` | Straightforward | Roll the environment back to a historical `txnid` (pairs with incremental dump for recovery) |
| `mdb_set_relctx` | Blocked (upcall) | Opaque context pointer for a relocation callback (`MDB_FIXEDMAP` only — a legacy, rarely-used mode) |
| `mdb_set_relfunc` | Blocked (upcall) | The relocation callback itself, for the same legacy `MDB_FIXEDMAP` mode |
| `mdb_env_set_assert` | Dead on this build | Custom assertion-failure handler/logger — inert here: this project's native builds pass `-DNDEBUG` (see `scripts/build-lmdb.sh`), which compiles out `mdb_env_set_assert`'s store of the callback pointer entirely (`mdb.c`'s own `#ifndef NDEBUG` guard around it), so the callback could never fire. It also always ends in `abort()` even on a debug build, so there'd be no safe way to exercise it in this project's tests either way. |
| `mdb_env_set_checksum` | Blocked (upcall) | Pluggable page-checksum hook (LMDB 1.x addition) |
| `mdb_env_set_encrypt` | Blocked (upcall) | Pluggable page-encryption hook (LMDB 1.x addition) |
| `mdb_modload` | Blocked (upcall) | Dynamically load a shared-library plugin supplying checksum/encryption functions |
| `mdb_modsetup` | Blocked (upcall) | Wire a loaded module's crypto hooks into an environment |
| `mdb_modunload` | Blocked (upcall) | Unload a previously-loaded module |
| `mdb_env_set_userctx` | Skippable | Opaque app-data pointer — a caller would just keep a field on their own wrapper |
| `mdb_env_get_userctx` | Skippable | Getter counterpart to the above |

"Blocked (upcall)" means the C function takes a function pointer LMDB calls
back into — a custom comparator, a relocation/checksum/encryption hook, or a
message callback. Binding one means building a `Linker.upcallStub` (a
native-callable trampoline into a Java `MethodHandle`) — a real, separate
chunk of work, not just another `downcallHandle` like the rest of this
project's bindings.

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
