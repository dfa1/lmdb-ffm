#!/usr/bin/env python3
"""Format one JMH JSON result file into a per-class contestants table.

Used by benchmark.yml to report lmdb-java (byte[], MemorySegment, Mapper)
against lmdbjava on the same run. Unlike zstd-java's equivalent script, the
contestant method names differ per benchmark class (ReadBenchmark,
WriteBenchmark), so columns are discovered from the results rather than
hardcoded.

Usage:
  format-results.py <results.json>
"""
import json
import sys

# Windows' default console code page isn't UTF-8, so stdout would otherwise
# mangle the '±' in the tables below (garbles to '?').
sys.stdout.reconfigure(encoding="utf-8")


def load(path):
    with open(path) as f:
        results = json.load(f)

    by_class = {}
    methods_by_class = {}
    for r in results:
        fqn = r["benchmark"]
        cls, method = fqn.rsplit(".", 1)
        cls = cls.rsplit(".", 1)[-1]
        param = r.get("params", {}).get("num", "-")
        by_class.setdefault(cls, {}).setdefault(param, {})[method] = r["primaryMetric"]
        methods = methods_by_class.setdefault(cls, [])
        if method not in methods:
            methods.append(method)
    return by_class, methods_by_class


def fmt(metric):
    if metric is None:
        return "-"
    return f"{metric['score']:.3f} ± {float(metric['scoreError']):.3f} {metric['scoreUnit']}"


def print_table(cls, by_param, methods):
    print(f"#### {cls}")
    print()
    print("| num | " + " | ".join(methods) + " |")
    print("|---|" + "---:|" * len(methods))
    for param in sorted(by_param, key=lambda p: (p == "-", int(p) if p != "-" else 0)):
        row = by_param[param]
        cells = [fmt(row.get(m)) for m in methods]
        label = f"{int(param):,}" if param != "-" else param
        print(f"| {label} | " + " | ".join(cells) + " |")
    print()


def main():
    if len(sys.argv) != 2:
        print("usage: format-results.py <results.json>", file=sys.stderr)
        return 1

    by_class, methods_by_class = load(sys.argv[1])
    for cls in sorted(by_class):
        print_table(cls, by_class[cls], methods_by_class[cls])

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
