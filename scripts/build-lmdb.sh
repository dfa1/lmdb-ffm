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
# target-classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64 |
#                     windows-x86_64 | windows-aarch64
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
# nproc isn't guaranteed present on Git Bash (Windows runners' shell for this
# script); NUMBER_OF_PROCESSORS is a standard Windows env var as a last resort.
JOBS="${LMDB_BUILD_JOBS:-$(sysctl -n hw.logicalcpu 2>/dev/null || nproc 2>/dev/null || echo "${NUMBER_OF_PROCESSORS:-4}")}"

# ---------------------------------------------------------------------------
# Map classifier -> (zig target triple, library name)
# ---------------------------------------------------------------------------
case "$CLASSIFIER" in
    osx-aarch64)     ZIG_TARGET="aarch64-macos";       LIB_NAME="liblmdb.dylib" ;;
    osx-x86_64)      ZIG_TARGET="x86_64-macos";        LIB_NAME="liblmdb.dylib" ;;
    linux-x86_64)    ZIG_TARGET="x86_64-linux-gnu";    LIB_NAME="liblmdb.so"    ;;
    linux-aarch64)   ZIG_TARGET="aarch64-linux-gnu";   LIB_NAME="liblmdb.so"    ;;
    windows-x86_64)  ZIG_TARGET="x86_64-windows-gnu";  LIB_NAME="liblmdb.dll"   ;;
    windows-aarch64) ZIG_TARGET="aarch64-windows-gnu"; LIB_NAME="liblmdb.dll"   ;;
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
# Git Bash's uname -s reports MINGW64_NT-.../MSYS_NT-... (never "Windows"), and
# an unmatched case leaves HOST_OS_NAME unset — a hard failure under set -u.
# HOST_CLASSIFIER is cosmetic (the "(cross from ...)" log hint only; the build
# itself is driven entirely by $ZIG_TARGET/$CLASSIFIER from argv), but it still
# needs a value on every host, including ones we don't specifically recognize.
case "$HOST_OS" in
    Darwin) HOST_OS_NAME="osx" ;;
    Linux) HOST_OS_NAME="linux" ;;
    MINGW*|MSYS*|CYGWIN*) HOST_OS_NAME="windows" ;;
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
# (__APPLE__/__linux__/__ANDROID__/_WIN32/...), which zig cc's target sets
# correctly for each classifier: POSIX targets use pthread mutexes/semaphores
# (hence -pthread there), while _WIN32 compiles a separate native Win32
# locking path (CreateMutex/LockFileEx, from threading.h/mdb.c) that never
# touches pthreads, so -pthread is neither needed nor passed for Windows.
#
# No -fvisibility=hidden: unlike zstd, lmdb.h has no ZSTDLIB_VISIBLE-style
# annotation on its public mdb_* API, so hiding default visibility would hide
# the public API along with mdb.c/midl.c's few non-static internal symbols.
PTHREAD_FLAG=""
case "$CLASSIFIER" in
    windows-*) ;;
    *) PTHREAD_FLAG="-pthread" ;;
esac
CFLAGS="-O2 -DNDEBUG $PTHREAD_FLAG -I$LMDB_LIB -fPIC"

# Strip the symbol/debug tables at link time. An unstripped ELF .so carries
# full debug_info for no runtime benefit; PE/COFF keeps its exports in the
# export table (separate from the symbol table) but lld still emits a
# multi-megabyte .pdb and an import .lib next to the .dll — those are deleted
# after the link below rather than suppressed via strip.
STRIP_FLAG="-s"
LINK_EXTRA=""
case "$CLASSIFIER" in
    # Full RELRO + immediate binding: GOT is remapped read-only after startup
    # relocation, closing off the classic GOT-overwrite exploit primitive.
    # ELF-only (-z is a GNU ld/lld ELF flag; Mach-O/PE have no equivalent).
    linux-*) LINK_EXTRA="-Wl,-z,relro,-z,now" ;;
    # PE/COFF exports nothing by default, unlike ELF/Mach-O's automatic
    # export of every non-static global; lmdb.h has no __declspec(dllexport)
    # annotation to opt in per-symbol (verified: 89 mdb_*/related symbols
    # come out exported this way, matching the ELF/Mach-O symbol count), so
    # lld is told to export every global symbol instead. -s is dropped here:
    # it is unneeded (mingw lld already produces no separate strippable debug
    # section) and prior testing showed it interacting poorly with the export
    # table on some lld versions.
    windows-*) LINK_EXTRA="-Wl,--export-all-symbols"; STRIP_FLAG="" ;;
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

zig cc -target "$ZIG_TARGET" -shared $PTHREAD_FLAG $STRIP_FLAG $SONAME_FLAG $LINK_EXTRA \
    -o "$DEST_DIR/$LIB_NAME" "$WORK"/*.o

# lld emits a .pdb (debug database, multiple MB) and a .lib (import library)
# next to a Windows .dll; neither is needed at runtime and both would be
# bundled into the native JAR. Keep only the shared library itself.
find "$DEST_DIR" -type f ! -name "$LIB_NAME" -delete

echo "[build-lmdb] Installed: $DEST_DIR/$LIB_NAME"
