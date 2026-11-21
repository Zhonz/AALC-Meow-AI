#!/usr/bin/env python3
"""用 MaaFramework 官方的 pipeline.schema.json 校验本项目管线。

比 validate_pipelines.py 更严格：那个脚本只查"键名白名单 + 跳转目标"，
这里让官方 JSON Schema 检查每个字段的类型 / 枚举 / 取值范围，能在真机之前
拦住 roi 写错、threshold 越界、recognition 拼错这类错误。

引擎解析器对 recognition / action 同时支持两种写法（见
source/MaaFramework/Resource/PipelineParser.cpp:405-408，没有 param 时整个
对象就是 param）：
  * 扁平：{"type": "TemplateMatch", "template": "...", "threshold": 0.8}
  * 嵌套：{"type": "TemplateMatch", "param": {"template": "..."}}
Schema 只描述嵌套式，所以校验前先把扁平式折叠成嵌套式，避免误报。

同时（存在 tools/interface.schema.json 时）校验 assets/interface.json，
MaaPiCli / MaaFwApp 都按这份 schema 读项目清单，字段拼错同样会让资源不完整。

用法:
  python3 scripts/check_pipeline_schema.py
  python3 scripts/check_pipeline_schema.py --schema path/to/pipeline.schema.json
  python3 scripts/check_pipeline_schema.py --interface-schema path/to/interface.schema.json
环境变量 MAA_PIPELINE_SCHEMA / MAA_INTERFACE_SCHEMA 可指定 schema 路径
（CI 里指向 vendored 副本）。
"""
import os
import sys
import glob
import json
import argparse

import jsonschema

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

PIPELINE = 'assets/resource/pipeline'
DEFAULT_SCHEMAS = (
    'tools/pipeline.schema.json',
    'upstream/maa_framework/tools/pipeline.schema.json',
    '../refs/MaaFramework/tools/pipeline.schema.json',
)
CUSTOM_REFS = ('./custom.recognition.schema.json', './custom.action.schema.json')
INTERFACE = 'assets/interface.json'
DEFAULT_INTERFACE_SCHEMAS = (
    'tools/interface.schema.json',
    'upstream/maa_framework/tools/interface.schema.json',
    '../refs/MaaFramework/tools/interface.schema.json',
)


def load_schema(path):
    """读取 schema，并把同目录 custom.*.schema.json 的引用内联进来。

    官方 schema 用相对路径 $ref 指向 custom.*.schema.json，jsonschema 不会
    按文件路径解析相对引用，这里直接内联，避免联网/缺文件。
    """
    base = os.path.dirname(os.path.abspath(path))
    schema = json.load(open(path, encoding='utf-8'))
    custom = {}
    for rel in CUSTOM_REFS:
        sub_path = os.path.join(base, rel)
        custom[rel] = json.load(open(sub_path, encoding='utf-8')) if os.path.exists(sub_path) else {}

    def inline(node):
        if isinstance(node, dict):
            if node.get('$ref') in custom:
                sub = {k: v for k, v in custom[node['$ref']].items()
                       if k not in ('$schema', '$comment')}
                extra = {k: inline(v) for k, v in node.items()
                         if k != '$ref' and not k.startswith('$')}
                if not extra:
                    return sub
                return {'allOf': [sub, extra]}
            return {k: inline(v) for k, v in node.items()}
        if isinstance(node, list):
            return [inline(v) for v in node]
        return node

    return inline(schema)


def normalize_component(node, key):
    """把识别 / 动作对象里的扁平参数折叠进 param，模拟引擎的取值方式。

    引擎（PipelineParser.cpp:405-410）对对象式写法只把 `recognition` 对象本身
    当参数来源，`param` 优先；此时写在节点顶层的 template/roi 不会被读到。
    字符串式写法（v1）才把整个节点当参数来源。所以这里：
      * 对象式：只折叠对象自己的扁平键，顶层键保持原样交给 schema 报错
      * 字符串式：不动节点，schema 的 V1 定义本身允许顶层参数
    """
    val = node.get(key)
    if not isinstance(val, dict):
        return
    params = {k: v for k, v in val.items() if k not in ('type', 'param')}
    if isinstance(val.get('param'), dict):
        params.update(val['param'])
    folded = {'type': val.get('type', 'DirectHit' if key == 'recognition' else 'DoNothing')}
    if params:
        folded['param'] = params
    node[key] = folded


def normalize_pipeline(data):
    for name, node in data.items():
        if name.startswith('$') or not isinstance(node, dict):
            continue
        normalize_component(node, 'recognition')
        normalize_component(node, 'action')
    return data


def find_schema(explicit):
    if explicit:
        return explicit
    env = os.environ.get('MAA_PIPELINE_SCHEMA')
    if env:
        return env
    for p in DEFAULT_SCHEMAS:
        if os.path.exists(p):
            return p
    return None


def iter_overrides(data):
    """遍历 tasks/*.json 里所有 pipeline_override 片段（含嵌套在 option/case 里的）。"""
    if isinstance(data, dict):
        for k, v in data.items():
            if k == 'pipeline_override' and isinstance(v, dict):
                yield v
            else:
                yield from iter_overrides(v)
    elif isinstance(data, list):
        for item in data:
            yield from iter_overrides(item)


def check_task_overrides(validator):
    """校验 assets/tasks 下的 pipeline_override 片段。

    覆盖片段是部分节点（只有要改的字段），仍按节点 schema 校验：拼错字段名
    在这里就会报出来，而不是等到真机跑任务时被引擎当成未知字段忽略。
    """
    out = []
    n = 0
    for f in sorted(glob.glob('assets/tasks/**/*.json', recursive=True)):
        try:
            data = json.load(open(f, encoding='utf-8'))
        except Exception as e:
            out.append(f'{f}: JSON 解析失败 {e}')
            continue
        for block in iter_overrides(data):
            n += 1
            normalize_pipeline(block)
            for err in validator.iter_errors(block):
                path = '/'.join(str(x) for x in err.absolute_path) or '<root>'
                out.append(f'{f}:pipeline_override:{path}: {err.message[:220]}')
    return n, out


def find_interface_schema(explicit):
    if explicit:
        return explicit
    env = os.environ.get('MAA_INTERFACE_SCHEMA')
    if env:
        return env
    for p in DEFAULT_INTERFACE_SCHEMAS:
        if os.path.exists(p):
            return p
    return None


def check_interface(schema_path):
    """校验 assets/interface.json；schema 缺失或文件缺失时跳过（返回 None）。"""
    if not schema_path or not os.path.exists(schema_path) or not os.path.exists(INTERFACE):
        return None
    schema = json.load(open(schema_path, encoding='utf-8'))
    data = json.load(open(INTERFACE, encoding='utf-8'))
    validator = jsonschema.validators.validator_for(schema)(schema)
    out = []
    for err in validator.iter_errors(data):
        path = '/'.join(str(x) for x in err.absolute_path) or '<root>'
        out.append(f'{INTERFACE}:{path}: {err.message[:220]}')
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--schema', help='pipeline.schema.json 路径')
    ap.add_argument('--interface-schema', help='interface.schema.json 路径')
    args = ap.parse_args()

    schema_path = find_schema(args.schema)
    if not schema_path or not os.path.exists(schema_path):
        print('找不到 pipeline.schema.json；用 --schema 或 MAA_PIPELINE_SCHEMA 指定')
        return 2

    validator = jsonschema.Draft202012Validator(load_schema(schema_path))

    files = sorted(glob.glob(f'{PIPELINE}/**/*.json', recursive=True))
    total_nodes = 0
    errors = []

    for f in files:
        try:
            data = json.load(open(f, encoding='utf-8'))
        except Exception as e:
            errors.append(f'{f}: JSON 解析失败 {e}')
            continue
        data = normalize_pipeline(data)
        total_nodes += len([k for k in data if not k.startswith('$')])
        for err in validator.iter_errors(data):
            path = '/'.join(str(x) for x in err.absolute_path) or '<root>'
            errors.append(f'{f}:{path}: {err.message[:220]}')

    override_count, override_errors = check_task_overrides(validator)
    errors.extend(override_errors)

    interface_errors = check_interface(find_interface_schema(args.interface_schema))
    if interface_errors is not None:
        total_nodes += 1
        errors.extend(interface_errors)
        print(f'接口清单: {INTERFACE}（schema: {find_interface_schema(args.interface_schema)}）')

    print(f'校验 {len(files)} 个管线文件 + {override_count} 个覆盖片段 + 1 个接口清单，'
          f'{total_nodes} 个节点')
    print(f'schema: {schema_path}')
    if errors:
        print(f'\n错误 {len(errors)} 条:')
        for e in errors[:80]:
            print('  ' + e)
        if len(errors) > 80:
            print(f'  ... 其余 {len(errors) - 80} 条略')
        return 1
    print('全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
