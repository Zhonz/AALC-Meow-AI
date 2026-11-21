# tools/ 说明

这里存放 **MaaFramework 官方的 JSON Schema 快照**，供
[`scripts/check_pipeline_schema.py`](../scripts/check_pipeline_schema.py) 在本地和 CI 离线校验资源。

| 文件 | 来源 |
| --- | --- |
| `pipeline.schema.json` | `MaaXYZ/MaaFramework` 的 `tools/pipeline.schema.json` |
| `interface.schema.json` | 同仓库 `tools/interface.schema.json` |
| `custom.recognition.schema.json` | 同仓库 `tools/custom.recognition.schema.json`（被上面两个 schema 以相对路径 `$ref` 引用） |
| `custom.action.schema.json` | 同仓库 `tools/custom.action.schema.json` |

## 为什么要快照

1. **离线可用**：CI 与本地都不必依赖上游仓库是否可访问；
2. **版本对齐**：schema 版本必须与 `UPSTREAM_VERSIONS.json` 里 `maa_framework.pinned_release` 一致，
   否则会拿旧 schema 判新字段（误报）或用新 schema 判旧引擎（漏报）。

## 更新方式

升级 MaaFramework 时同步这四个文件：

```bash
TAG=v5.16.1  # 与 UPSTREAM_VERSIONS.json 的 pinned_release 相同
for f in pipeline.schema.json interface.schema.json custom.recognition.schema.json custom.action.schema.json; do
  curl -fsSL -o "tools/$f" "https://raw.githubusercontent.com/MaaXYZ/MaaFramework/$TAG/tools/$f"
done
python3 scripts/check_pipeline_schema.py
```

国内网络可把 `https://raw.githubusercontent.com/` 换成 `https://gh-proxy.com/https://raw.githubusercontent.com/`。

> 注意：schema 只能检查**字段名与取值**，检查不了「参数写在 recognition 对象外会被引擎忽略」这类作用域语义
> （见 [开发经验 2.11](../docs/DEVELOPMENT_EXPERIENCE.md)），那一层由 `scripts/validate_pipelines.py` 兜底。
