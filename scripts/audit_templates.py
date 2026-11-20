#!/usr/bin/env python3
"""校验 AALC-Meow 的每张模板能否从官方 upstream/aalc 复现。

官方链路（utils/image_utils.py）:
  load_image -> 整图按 win_size/1440 缩放 -> get_bbox -> 与截图对应区域匹配
本项目目标: 控制器把截图归一化到短边 720p，所以 scale = 720/1440 = 0.5
"""
import os, glob, json, sys
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
sys.path.insert(0, os.path.join(ROOT, 'scripts'))

from regen_templates import EXPLICIT_SOURCE_MAP  # 与重导脚本共用一份源映射

UP = 'upstream/aalc/assets/images/default'
OUR = 'assets/resource/image'
PIPELINE = 'assets/resource/pipeline'
SCALE = 720 / 1440  # 目标短边 720p


def get_bbox(arr, threshold=0):
    """复刻官方 ImageUtils.get_bbox"""
    if arr.ndim == 3 and arr.shape[2] >= 3:
        arr = np.max(arr[:, :, :3], axis=2)
    elif arr.ndim == 3:
        arr = arr[:, :, 0]
    x = np.where(np.max(arr, axis=0) > threshold)[0]
    if len(x) == 0:
        return None
    y = np.where(np.max(arr, axis=1) > threshold)[0]
    if len(y) == 0:
        return None
    return x[0], y[0], x[-1] + 1, y[-1] + 1


def build_official_index():
    idx = {}
    for sub in ('share', 'zh_cn', 'en'):
        for p in glob.glob(f'{UP}/{sub}/**/*.png', recursive=True):
            rel = os.path.relpath(p, f'{UP}/{sub}')
            b = os.path.basename(rel)[:-4]
            for suf in ('_assets', '_bbox'):
                if b.endswith(suf):
                    b = b[: -len(suf)]
                    break
            idx.setdefault(rel, p)
            idx.setdefault(b, p)
    return idx


def used_by_pipeline():
    """扫一遍管线，返回被引用的模板相对路径集合（用于区分失效素材）"""
    used = set()
    for f in glob.glob(f'{PIPELINE}/**/*.json', recursive=True):
        try:
            data = json.load(open(f, encoding='utf-8'))
        except Exception:
            continue
        def walk(node):
            if isinstance(node, dict):
                for k, v in node.items():
                    if k in ('template', 'template_') and isinstance(v, str):
                        used.add(v)
                    elif k in ('template', 'template_') and isinstance(v, list):
                        used.update(x for x in v if isinstance(x, str))
                    else:
                        walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)
        walk(data)
    return used


def official_candidates(rel):
    """按完整相对路径 + 纯文件名 两种方式找官方源"""
    out = []
    if rel in EXPLICIT_SOURCE_MAP and os.path.exists(EXPLICIT_SOURCE_MAP[rel]):
        out.append(EXPLICIT_SOURCE_MAP[rel])
    for sub in ('share', 'zh_cn', 'en'):
        p = f'{UP}/{sub}/{rel}'
        if os.path.exists(p):
            out.append(p)
    b = os.path.basename(rel)[:-4]
    for suf in ('_assets', '_bbox'):
        if b.endswith(suf):
            b = b[: -len(suf)]
            break
    for sub in ('share', 'zh_cn', 'en'):
        for p in glob.glob(f'{UP}/{sub}/**/{b}_assets.png', recursive=True) + \
                  glob.glob(f'{UP}/{sub}/**/{b}.png', recursive=True):
            if p not in out:
                out.append(p)
    return out


def norm_key(rel):
    b = os.path.basename(rel)[:-4]
    for suf in ('_assets', '_bbox'):
        if b.endswith(suf):
            b = b[: -len(suf)]
            break
    return b


def compare(our_path, up_path):
    """返回 (状态, 详情)"""
    try:
        up = np.array(Image.open(up_path).convert('RGBA'))
    except Exception as e:
        return 'UNREADABLE', str(e)
    bb = get_bbox(up)
    if bb is None:
        return 'EMPTY_BBOX', '官方源无有效像素'
    x1, y1, x2, y2 = bb
    # 官方先整体缩放再取 bbox —— 等价于把 bbox 坐标乘 scale
    sx1, sy1, sx2, sy2 = (int(round(x1 * SCALE)), int(round(y1 * SCALE)),
                          int(round(x2 * SCALE)), int(round(y2 * SCALE)))
    sw, sh = sx2 - sx1, sy2 - sy1
    try:
        ours = np.array(Image.open(our_path).convert('RGBA'))
    except Exception as e:
        return 'UNREADABLE', str(e)
    oh, ow = ours.shape[:2]
    dw, dh = abs(ow - sw), abs(oh - sh)
    # 尺寸完全一致或误差<=2px 视为尺寸通过
    size_ok = dw <= 2 and dh <= 2
    detail = f'官方bbox@{SCALE}={sw}x{sh} 我们={ow}x{oh} 差={dw}x{dh}'
    if not size_ok:
        return 'SIZE_MISMATCH', detail
    return 'SIZE_OK', detail


def main():
    ours = sorted(glob.glob(f'{OUR}/**/*.png', recursive=True))
    buckets = {'SIZE_OK': [], 'SIZE_MISMATCH': [], 'NO_OFFICIAL_SOURCE': [],
               'EMPTY_BBOX': [], 'UNREADABLE': []}
    for p in ours:
        rel = os.path.relpath(p, OUR)
        cands = official_candidates(rel)
        if not cands:
            buckets['NO_OFFICIAL_SOURCE'].append((rel, '官方无同名文件'))
            continue
        # 尺寸完全一致的那个优先
        best = None
        for c in cands:
            st, det = compare(p, c)
            if st == 'SIZE_OK':
                best = ('SIZE_OK', det, c)
                break
            if best is None:
                best = (st, det, c)
        assert best is not None and best[0] in buckets, (rel, best)
        buckets[best[0]].append((rel, best[1]))

    total = len(ours)
    print(f"总计 {total} 张模板\n")
    for k in ('SIZE_OK', 'SIZE_MISMATCH', 'NO_OFFICIAL_SOURCE', 'EMPTY_BBOX', 'UNREADABLE'):
        v = buckets[k]
        print(f"{k}: {len(v)}")
    print("\n=== SIZE_MISMATCH（尺寸对不上，疑似裁错或来源不对）===")
    for rel, det in buckets['SIZE_MISMATCH']:
        print(f"  {rel}\n      {det}")
    used = used_by_pipeline()
    print("\n=== NO_OFFICIAL_SOURCE（官方无同名，需逐个判定）===")
    live = [rel for rel, _ in buckets['NO_OFFICIAL_SOURCE'] if rel in used]
    dead = [rel for rel, _ in buckets['NO_OFFICIAL_SOURCE'] if rel not in used]
    print(f"  被管线引用（必须核验）: {len(live)} 张")
    for rel in live:
        print(f"    {rel}")
    print(f"  无管线引用（历史残留，可择机清理）: {len(dead)} 张")
    for rel in dead:
        print(f"    {rel}")
    buckets['USED_BY_PIPELINE'] = sorted(used & {rel for rel, _ in sum(buckets.values(), [])})
    json.dump({k: v for k, v in buckets.items() if k != 'USED_BY_PIPELINE'},
              open('/tmp/template_audit.json', 'w'), ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()