#!/usr/bin/env bash
# Build the LMDB shared library from the vendored submodule using `zig cc`
# and install it into the caller's resources directory so Maven bundles it
# in the native JAR.
#
# LMDB is two C files (mdb.c, midl.c) with no build-system dependency beyond
# a C compiler and pthreads, so we compile them directly with zig cc — no
# autotools, no CMake, fully hermetic. Zig bundles clang + libc for every
# target, enabling cross-compilation without a sysroot or system toolchain.
#
# Usage:
#   ./scripts/build-lmdb.sh <output-resources-dir> <target-classifier>
#
# target-classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "Usage: $0 <output-resources-dir> <target-classifier>" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LMDB_LIB="$PROJECT_DIR/third_party/lmdb/libraries/liblmdb"
mkdir -p "$1"
OUTPUT_RESOURCES="$(cd "$1" && pwd)"
CLASSIFIER="$2"
JOBS="${LMDB_BUILD_JOBS:-$(sysctl -n hw.logicalcpu 2>/dev/null || nproc)}"

# ---------------------------------------------------------------------------
# Map classifier -> (zig target triple, library name)
# ---------------------------------------------------------------------------
case "$CLASSIFIER" in
    osx-aarch64)   ZIG_TARGET="aarch64-macos";     LIB_NAME="liblmdb.dylib" ;;
    osx-x86_64)    ZIG_TARGET="x86_64-macos";      LIB_NAME="liblmdb.dylib" ;;
    linux-x86_64)  ZIG_TARGET="x86_64-linux-gnu";  LIB_NAME="liblmdb.so"    ;;
    linux-aarch64) ZIG_TARGET="aarch64-linux-gnu"; LIB_NAME="liblmdb.so"    ;;
    *) echo "Unsupported classifier: $CLASSIFIER" >&2; exit 1 ;;
esac

DEST_DIR="$OUTPUT_RESOURCES/native/$CLASSIFIER"
mkdir -p "$DEST_DIR"

# Skip if already built (CI cache or repeated local builds)
if [ -f "$DEST_DIR/$LIB_NAME" ]; then
    echo "[build-lmdb] $DEST_DIR/$LIB_NAME already exists, skipping build."
    exit 0
fi

HOST_OS=$(uname -s); HOST_ARCH=$(uname -m)
case "$HOST_OS" in
    Darwin) HOST_OS_NAME="osx" ;;
    Linux) HOST_OS_NAME="linux" ;;
    *) HOST_OS_NAME="unknown" ;;
esac
case "$HOST_ARCH" in
    arm64|aarch64) HOST_ARCH_NAME="aarch64" ;;
    x86_64) HOST_ARCH_NAME="x86_64" ;;
    *) HOST_ARCH_NAME="unknown" ;;
esac
HOST_CLASSIFIER="${HOST_OS_NAME}-${HOST_ARCH_NAME}"
CROSS=""
[ "$CLASSIFIER" != "$HOST_CLASSIFIER" ] && CROSS=" (cross from $HOST_CLASSIFIER)"

echo "[build-lmdb] Building lmdb $CLASSIFIER$CROSS with zig cc (jobs=$JOBS)..."

# liblmdb is exactly these two translation units (see libraries/liblmdb/Makefile).
SRCS="$LMDB_LIB/mdb.c $LMDB_LIB/midl.c"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# mdb.c picks its locking strategy from predefined compiler macros
# (__APPLE__/__linux__/__ANDROID__/...), which zig cc's target sets correctly
# for each classifier, so no platform-specific CFLAGS are needed here beyond
# -pthread (required on every POSIX target LMDB supports).
#
# No -fvisibility=hidden: unlike zstd, lmdb.h has no ZSTDLIB_VISIBLE-style
# annotation on its public mdb_* API, so hiding default visibility would hide
# the public API along with mdb.c/midl.c's few non-static internal symbols.
CFLAGS="-O2 -DNDEBUG -pthread -I$LMDB_LIB -fPIC"

# Strip the symbol/debug tables at link time — an unstripped ELF .so carries
# full debug_info for no runtime benefit.
STRIP_FLAG="-s"
LINK_EXTRA=""
case "$CLASSIFIER" in
    # Full RELRO + immediate binding: GOT is remapped read-only after startup
    # relocation, closing off the classic GOT-overwrite exploit primitive.
    # ELF-only (-z is a GNU ld/lld ELF flag; Mach-O has no equivalent).
    linux-*) LINK_EXTRA="-Wl,-z,relro,-z,now" ;;
esac

ARCH_FLAG=""
case "$CLASSIFIER" in
    *-aarch64) ARCH_FLAG="-mcpu=generic+crc" ;;
esac
CFLAGS="$CFLAGS $ARCH_FLAG"

compile_one() {
    zig cc -target "$ZIG_TARGET" $CFLAGS -c "$1" -o "$WORK/$(basename "$1").o"
}
export -f compile_one
export ZIG_TARGET CFLAGS WORK

printf '%s\n' $SRCS | xargs -P "$JOBS" -I{} bash -c 'compile_one "$@"' _ {}

SONAME_FLAG=""
[ "$LIB_NAME" = "liblmdb.so" ] && SONAME_FLAG="-Wl,-soname,liblmdb.so.0"

zig cc -target "$ZIG_TARGET" -shared -pthread $STRIP_FLAG $SONAME_FLAG $LINK_EXTRA \
    -o "$DEST_DIR/$LIB_NAME" "$WORK"/*.o

echo "[build-lmdb] Installed: $DEST_DIR/$LIB_NAME"
