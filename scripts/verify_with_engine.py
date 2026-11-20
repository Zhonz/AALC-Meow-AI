#!/usr/bin/env python3
"""用真实 MaaFramework 引擎加载 assets，验证 pipeline 能被引擎解析。

为什么需要：MaaFramework 对非法字段（如 recognition_and / timeout_next /
OCR 用 text）不是静默忽略，而是让整个 Resource 加载失败，App 启动即崩。
静态 JSON 校验挡不住所有情况，让引擎自己读一遍最可靠。

需要 MaaFramework 的 MaaPiCli。没有 MaaPiCli 时会回退到官方 Python 绑定
（pip install "maafw==<与 UPSTREAM_VERSIONS.json 一致>"，直接加载 assets/resource，
覆盖 pipeline + 模板 + OCR 模型；不含 interface.json）。两者都没有时跳过并返回 0。

环境变量:
  MAA_CLI   MaaPiCli 可执行文件路径（默认在 PATH 中查找）
  MAA_LIB   动态库目录（默认取 MaaPiCli 同级目录）

用法:
  python3 scripts/verify_with_engine.py
  MAA_CLI=/path/to/MaaPiCli python3 scripts/verify_with_engine.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'assets')

ERR_PATTERNS = [
    re.compile(r'\[ERR\]'),
    re.compile(r'Failed to parse'),
    re.compile(r'failed to parse'),
    re.compile(r'invalid.*(node|param|field)', re.I),
]
# 运行期才会出现的提示（无 controller / 无截图），资源加载阶段不该出现
BENIGN = [
    re.compile(r'No (valid )?controller', re.I),
    re.compile(r'screenshot', re.I),
]


def find_cli():
    env = os.environ.get('MAA_CLI')
    if env and os.path.exists(env):
        return env
    return shutil.which('MaaPiCli') or shutil.which('maa')


def looks_like_errors(text):
    hits = []
    for line in text.splitlines():
        if any(p.search(line) for p in BENIGN):
            continue
        if any(p.search(line) for p in ERR_PATTERNS):
            hits.append(line.strip())
    return hits


def link_assets(lib_dir):
    """MaaPiCli 在可执行文件同级目录找 interface.json / tasks / resource。"""
    made = []
    for name in os.listdir(ASSETS):
        src = os.path.join(ASSETS, name)
        dst = os.path.join(lib_dir, name)
        if os.path.islink(dst) or os.path.exists(dst):
            continue
        try:
            os.symlink(src, dst)
            made.append(dst)
        except OSError:
            pass
    return made


def verify_via_binding():
    """用官方 Python 绑定加载 assets/resource；绑定未安装时返回 None。"""
    try:
        from maa.resource import Resource
    except Exception:
        return None
    print('[回退] 未找到 MaaPiCli，改用官方 Python 绑定 maafw 加载 assets/resource')
    try:
        job = Resource().post_bundle(os.path.join(ASSETS, 'resource')).wait()
    except Exception as e:
        print(f'[失败] 绑定加载异常: {e}')
        return 1
    if job.succeeded:
        print('[通过] 引擎（Python 绑定）成功加载 pipeline + 模板 + 模型')
        return 0
    print(f'[失败] 引擎（Python 绑定）加载失败，status = {job.status}')
    return 1


def main():
    cli = find_cli()
    if not cli:
        via_binding = verify_via_binding()
        if via_binding is not None:
            return via_binding
        print('[跳过] 未找到 MaaPiCli，也未安装 maafw 绑定，跳过引擎级验证')
        print('       从 https://github.com/MaaXYZ/MaaFramework/releases 下载对应平台包并设置 MAA_CLI，')
        print('       或 pip install "maafw==<UPSTREAM_VERSIONS.json 的 pinned_release>" 启用本检查。')
        return 0

    lib_dir = os.environ.get('MAA_LIB') or os.path.dirname(os.path.abspath(cli))
    env = dict(os.environ)
    env['DYLD_LIBRARY_PATH'] = lib_dir + os.pathsep + env.get('DYLD_LIBRARY_PATH', '')

    made = link_assets(lib_dir)
    try:
        with tempfile.TemporaryDirectory() as td:
            p = subprocess.run([cli], input='7\n', env=env, cwd=td,
                               capture_output=True, text=True, timeout=180)
    finally:
        for dst in made:
            try:
                os.unlink(dst)
            except OSError:
                pass

    out = (p.stdout or '') + (p.stderr or '')
    errs = looks_like_errors(out)

    print(f'引擎: {cli}')
    print(f'库目录: {lib_dir}')
    if errs:
        print(f'\n[失败] 引擎解析报错 {len(errs)} 条：')
        for e in errs[:40]:
            print('   ', e)
        return 1
    if '### Current configuration ###' not in out:
        print('[失败] 引擎未能进入配置界面，说明 interface 解析就没通过')
        print(out[-2000:])
        return 1
    print('[通过] 引擎成功加载全部资源（interface + import + pipeline + 模板）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
