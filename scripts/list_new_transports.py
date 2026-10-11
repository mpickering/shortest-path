#!/usr/bin/env python3
"""List the transport rows added since a base ref, for the debug overlay.

Writes src/main/resources/debug/new_transports.tsv with one line per added row:
origin (empty for teleports), destination and a label. Changed rows count as added.

Usage: scripts/list_new_transports.py [base-ref]   (default: origin/master)
"""
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TRANSPORTS = ROOT / "src/main/resources/transports"
OUTPUT = ROOT / "src/main/resources/debug/new_transports.tsv"


def header(path):
    with open(path, encoding="utf-8") as f:
        return f.readline().lstrip("#").strip().split("\t")


def added_rows(base, path):
    diff = subprocess.run(
        ["git", "diff", "-U0", base, "--", str(path.relative_to(ROOT))],
        cwd=ROOT, check=True, capture_output=True, text=True).stdout
    for line in diff.splitlines():
        if line.startswith("+") and not line.startswith("+++"):
            row = line[1:]
            if row.strip() and not row.startswith("#"):
                yield row.split("\t")


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "origin/master"
    out = []
    for path in sorted(TRANSPORTS.glob("*.tsv")):
        columns = header(path)
        origin_col = columns.index("Origin") if "Origin" in columns else None
        dest_col = columns.index("Destination")
        menu_col = columns.index("menuOption menuTarget objectID") if "menuOption menuTarget objectID" in columns else None
        info_col = columns.index("Display info") if "Display info" in columns else None
        for row in added_rows(base, path):
            def cell(i):
                return row[i].strip() if i is not None and i < len(row) else ""
            label = cell(info_col) or cell(menu_col)
            out.append("\t".join([cell(origin_col), cell(dest_col), f"{path.stem}: {label}"]))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("# Origin\tDestination\tLabel\n" + "".join(r + "\n" for r in out), encoding="utf-8")
    print(f"{len(out)} rows -> {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
