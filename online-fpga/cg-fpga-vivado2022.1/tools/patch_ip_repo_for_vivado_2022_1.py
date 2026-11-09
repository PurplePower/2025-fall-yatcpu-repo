#!/usr/bin/env python3
"""
Patch a Vivado 2022.2-packaged IP repository so it can be used by Vivado 2022.1.

Vivado 2022.2 added two keys to the JSON .xci instance files:
  * ip_inst.cell_name
  * <param_value>.enabled
Vivado 2022.1 validates .xci against a schema with additionalProperties=false
and rejects both keys with [IP_Flow 19-8166].

Usage:
    python patch_ip_repo_for_vivado_2022_1.py <ip_repo_dir> [--dry-run]

After patching, delete the generated output products and regenerate:
    project_1.gen/sources_1/bd/<bd>/ip/<bd>_<ip>_0_0
    project_1.cache/ip
Then in Vivado: Reset Output Products -> Generate Output Products.
"""
import argparse
import re
import sys
from pathlib import Path

CELL_NAME_RE = re.compile(r'^[ \t]*"cell_name"\s*:\s*"[^"]*"[ \t]*(?:,[ \t]*)?\r?\n', re.M)
ENABLED_RE = re.compile(r',[ \t]*"enabled"[ \t]*:[ \t]*(?:true|false)')


def patch_file(path: Path, dry_run: bool) -> bool:
    text = path.read_text(encoding="utf-8")
    original = text
    text = CELL_NAME_RE.sub("", text)
    text = ENABLED_RE.sub("", text)
    if text == original:
        return False
    if not dry_run:
        path.write_text(text, encoding="utf-8", newline="")
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ip_repo", nargs="?", default="ip_repo")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    root = Path(args.ip_repo).resolve()
    if not root.is_dir():
        print(f"not a directory: {root}")
        return 2

    files = sorted(root.rglob("*.xci"))
    changed = [f for f in files if patch_file(f, args.dry_run)]
    print(f"{'would patch' if args.dry_run else 'patched'} {len(changed)} / {len(files)} .xci files")
    for f in changed:
        print(f"  {f.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
