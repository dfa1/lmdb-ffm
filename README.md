# lmdb-java

[![CI](https://github.com/dfa1/lmdb-java/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/lmdb-java/actions/workflows/ci.yml)
![LMDB](https://img.shields.io/badge/LMDB-1.0.1-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-OpenLDAP--2.8-blue.svg)](LICENSE)

**lmdb-java** wraps [LMDB](https://github.com/LMDB/lmdb) (Lightning
Memory-Mapped Database) through the Java **Foreign Function & Memory (FFM)
API** — no JNI, no hand-written C (JDK 25 is the first LTS with stable
`java.lang.foreign`).

The differentiator is the **zero-copy read path**: a lookup hands back a
`MemorySegment` pointing directly into the memory-mapped database file — no
`byte[]` copy — valid for the life of the read transaction.

Currently covers environments, transactions, named databases, get/put/delete
and cursors — enough to build on, not (yet) LMDB's full surface. See
[CLAUDE.md](CLAUDE.md) for what's deliberately left out and why.

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
        txn.get(dbi, "key".getBytes(UTF_8))
                .map(bytes -> new String(bytes, UTF_8))
                .ifPresent(System.out::println); // "value"
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
    for (var e = cursor.get(LmdbCursorOp.FIRST); e.isPresent(); e = cursor.get(LmdbCursorOp.NEXT)) {
        MemorySegment key = e.get().key();   // points straight into the mmap, no copy
        MemorySegment data = e.get().data();
    }
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

## Build from source

Not yet published — build and install locally:

```shell
git clone --recurse-submodules https://github.com/dfa1/lmdb-java.git
cd lmdb-java
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
