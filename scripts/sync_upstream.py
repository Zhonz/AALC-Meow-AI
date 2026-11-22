#!/usr/bin/env python3
"""检查并同步上游 AALC 更新。

更新内容：
  1. upstream/aalc submodule 指针
  2. UPSTREAM_VERSIONS.json 里的 pinned_commit / pinned_tag
  3. 按 0.5x 重新导出识别模板（截图短边 720p，见 docs/RELEASING.md）

以 GITHUB_OUTPUT 传出 has_changes，供 workflow 决定是否提交与触发构建。
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
AALC_ROOT = PROJECT_ROOT / "upstream" / "aalc"
VERSIONS_FILE = PROJECT_ROOT / "UPSTREAM_VERSIONS.json"
AALC_REPO = "https://github.com/KIYI671/AhabAssistantLimbusCompany"


def log(msg: str) -> None:
    print(f"[sync] {msg}", flush=True)


def sh(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(
        args, cwd=cwd, check=True, capture_output=True, text=True
    ).stdout.strip()


def current_commit() -> str:
    return sh("git", "rev-parse", "HEAD", cwd=AALC_ROOT)


def remote_commit() -> str:
    sh("git", "fetch", "origin", cwd=AALC_ROOT)
    for ref in ("origin/HEAD", "origin/main", "origin/master"):
        try:
            return sh("git", "rev-parse", ref, cwd=AALC_ROOT)
        except subprocess.CalledProcessError:
            continue
    return sh("git", "rev-parse", "origin/main", cwd=AALC_ROOT)


def describe(commit: str) -> str:
    try:
        return sh("git", "describe", "--tags", commit, cwd=AALC_ROOT)
    except subprocess.CalledProcessError:
        return commit[:7]


def regen_templates() -> None:
    """按 0.5x 重新导出模板；失败不应中断同步（宁可让人工介入）"""
    script = PROJECT_ROOT / "scripts" / "regen_templates.py"
    if not script.is_file():
        log("regen_templates.py 不存在，跳过模板重导")
        return
    log("重新导出模板（0.5x）…")
    proc = subprocess.run(
        [sys.executable, str(script), "--apply"],
        cwd=PROJECT_ROOT, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        log(f"警告: 模板重导失败\n{proc.stdout}\n{proc.stderr}")
    else:
        head = [l for l in proc.stdout.splitlines() if "需重导" in l or "尺寸已正确" in l]
        for l in head:
            log(l.strip())


def update_records(new_commit: str) -> None:
    data = json.loads(VERSIONS_FILE.read_text(encoding="utf-8"))
    comp = data["components"]["aalc"]
    old = comp.get("pinned_commit", "")
    comp["pinned_commit"] = new_commit
    comp["pinned_tag"] = describe(new_commit)
    VERSIONS_FILE.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    if old and old != new_commit:
        log(f"UPSTREAM_VERSIONS.json: {old[:7]} -> {new_commit[:7]}")


def set_output(has_changes: bool, summary: str) -> None:
    out = os.environ.get("GITHUB_OUTPUT")
    if not out or not os.path.exists(out):
        return
    with open(out, "a", encoding="utf-8") as f:
        f.write(f"has_changes={'true' if has_changes else 'false'}\n")
        f.write(f"summary={summary}\n")


def pinned_commit() -> str:
    data = json.loads(VERSIONS_FILE.read_text(encoding="utf-8"))
    return data["components"]["aalc"].get("pinned_commit", "")


def remote_head_via_ls_remote() -> str:
    """没有 submodule 时用 ls-remote 查上游 HEAD，不需要克隆"""
    out = sh("git", "ls-remote", AALC_REPO, "HEAD", "refs/heads/main", "refs/heads/master")
    heads = {}
    for line in out.splitlines():
        sha, _, ref = line.partition("\t")
        heads[ref] = sha
    return heads.get("refs/heads/main") or heads.get("refs/heads/master") or heads.get("HEAD", "")


def main() -> int:
    # 本地开发时 upstream/aalc 是指向 refs/AALC 的符号链接，CI 里则是真 submodule。
    # 拿不到真仓库时不要直接失败：用 ls-remote 比一下版本，有更新就提示人工同步。
    if AALC_ROOT.is_symlink() or not (AALC_ROOT / ".git").exists():
        log(f"没有可用的 AALC 仓库（{AALC_ROOT}），改用 ls-remote 比较版本")
        try:
            new = remote_head_via_ls_remote()
        except subprocess.CalledProcessError as exc:
            log(f"警告: ls-remote 失败，跳过本次同步\n{exc.stderr}")
            set_output(False, "ls-remote failed")
            return 0
        old = pinned_commit()
        if new and new == old:
            log(f"上游无更新 ({old[:7]})")
            set_output(False, "no upstream change")
            return 0
        if not new:
            log("警告: 读不到上游 HEAD，跳过本次同步")
            set_output(False, "no upstream head")
            return 0
        log(f"上游有新提交 {old[:7] or '?'} -> {new[:7]}，但当前环境没有 submodule，"
            "请在本地执行 git submodule update --init upstream/aalc 后手动同步")
        set_output(False, f"upstream ahead {new[:7]} (submodule missing)")
        return 0

    old = current_commit()
    new = remote_commit()

    if old == new:
        log(f"上游无更新 ({old[:7]})")
        set_output(False, "no upstream change")
        return 0

    subject = sh("git", "log", "-1", "--format=%s", new, cwd=AALC_ROOT)
    log(f"发现上游更新 {old[:7]} -> {new[:7]}  {subject}")

    sh("git", "checkout", new, cwd=AALC_ROOT)
    update_records(new)
    regen_templates()

    summary = f"AALC {old[:7]}..{new[:7]} {subject}"
    set_output(True, summary)
    log(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
