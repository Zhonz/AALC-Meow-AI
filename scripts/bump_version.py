#!/usr/bin/env python3
"""版本号只递增补丁位（约定：每次更新 +0.0.1）

app 的 versionName 由 git tag 推导（build-logic 的 GitVersion.kt：tag 在 HEAD 上就是 x.y.z，
落后一个提交就自动升补丁位加 -alpha.N），资源清单的版本写死在 assets/interface.json 的
"version" 里。这个脚本把两边对齐：读最新 tag，算出下一个补丁版本，写进 interface.json。

用法:
  python3 scripts/bump_version.py             # 只打印下一个版本
  python3 scripts/bump_version.py --apply     # 写进 assets/interface.json
  python3 scripts/bump_version.py --set v0.6.2
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
INTERFACE = ROOT / "assets" / "interface.json"
TAG_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def latest_tag() -> str:
    try:
        out = subprocess.run(
            ["git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return ""
    return out


def parse(text: str) -> tuple[int, int, int] | None:
    m = TAG_RE.match(text.strip())
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3))


def next_patch(tag: str) -> str:
    parsed = parse(tag)
    if parsed is None:
        return "v0.0.1"
    major, minor, patch = parsed
    return f"v{major}.{minor}.{patch + 1}"


def write_version(version: str) -> None:
    data = json.loads(INTERFACE.read_text(encoding="utf-8"))
    data["version"] = version
    INTERFACE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="版本号 +0.0.1")
    parser.add_argument("--apply", action="store_true", help="写进 assets/interface.json")
    parser.add_argument("--set", dest="explicit", help="直接指定版本，例如 v0.6.1")
    args = parser.parse_args()

    current = latest_tag()
    version = args.explicit or next_patch(current)
    if parse(version) is None:
        print(f"版本号格式不对：{version}（要 vX.Y.Z）", file=sys.stderr)
        return 2

    if args.apply:
        write_version(version)
        print(f"assets/interface.json: {current or '(无 tag)'} -> {version}")
        print(f"接着执行：git tag -a {version} -m '{version}' && git push origin {version}")
    else:
        print(version)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
