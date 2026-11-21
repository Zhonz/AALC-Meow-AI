"""把 assets/ 打成 pi.zip 并生成 pi-manifest.json

与 build-logic 的 PiAssetsConventionPlugin 同一套白名单（见 pi-profile.yaml 的 include/exclude），
布局也一致：zip 根目录直接是 interface.json、tasks/、resource/…，不能再套一层目录，
否则 PiInstaller.requireInterfaceJson 会认不出这份包。

只依赖标准库，CI 与本机都能跑：
    python scripts/pack_pi.py
    python scripts/pack_pi.py --code 202611211200 --notes "跟到 AALC V1.6.2"
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# pi-profile.yaml 的 include；改那边记得同步这里
INCLUDE = [
    "interface.json",
    "tasks/**/*",
    "resource/**/*",
    "data/**/*",
    "locales/**/*",
    "CONTACT",
    "LICENSE",
    "logo.png",
]

# 与 pi-profile.yaml 的 exclude 一致，再去掉构建/版本控制噪音
EXCLUDE_PARTS = {".git", "node_modules", "__pycache__", ".venv", "tests"}
EXCLUDE_SUFFIX = {".pyc"}


def collect(assets: Path) -> list[Path]:
    files: list[Path] = []
    for pattern in INCLUDE:
        for path in sorted(assets.glob(pattern)):
            if not path.is_file():
                continue
            rel = path.relative_to(assets)
            if any(part in EXCLUDE_PARTS for part in rel.parts):
                continue
            if path.suffix in EXCLUDE_SUFFIX:
                continue
            files.append(rel)
    # 同一个文件可能被多条通配摸到
    return sorted(set(files))


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", default=str(ROOT / "assets"))
    parser.add_argument("--out", default=str(ROOT / "build" / "pi"))
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "Zhonz/AALC-Meow-AI"))
    parser.add_argument("--code", type=int, default=0, help="版本修订号，默认按 YYMMDDHHmm 生成")
    parser.add_argument("--notes", default="")
    parser.add_argument("--version", default="", help="默认取 interface.json 的 version")
    args = parser.parse_args()

    assets = Path(args.assets)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    files = collect(assets)
    if not files:
        print(f"[错误] {assets} 下没有可打包的文件，检查白名单")
        return 1

    code = args.code or int(time.strftime("%y%m%d%H%M"))
    zip_path = out / "pi.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for rel in files:
            archive.write(assets / rel, rel.as_posix())

    interface = json.loads((assets / "interface.json").read_text(encoding="utf-8"))
    version = args.version or str(interface.get("version") or "")
    # 上游 AALC 的 tag 记在 UPSTREAM_VERSIONS.json 里，只作展示
    aalc_tag = ""
    upstream = ROOT / "UPSTREAM_VERSIONS.json"
    if upstream.is_file():
        try:
            aalc_tag = str(
                json.loads(upstream.read_text(encoding="utf-8"))["components"]["aalc"].get("pinned_tag") or ""
            )
        except (KeyError, ValueError):
            aalc_tag = ""

    manifest = {
        "version": version,
        "versionCode": code,
        "url": f"https://github.com/{args.repo}/releases/download/pi-{code}/pi.zip",
        "sha256": sha256_of(zip_path),
        "sizeBytes": zip_path.stat().st_size,
        "aalcTag": aalc_tag,
        "notes": args.notes,
    }
    (out / "pi-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (out / "notes.md").write_text(
        (args.notes or f"资源包 {code}") + "\n\n"
        f"- version: {version}\n"
        f"- files: {len(files)}\n"
        f"- size: {manifest['sizeBytes'] / 1048576:.1f} MB\n",
        encoding="utf-8",
    )

    print(f"[完成] {len(files)} 个文件 -> {zip_path}")
    print(f"        versionCode={code} version={version} sha256={manifest['sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
