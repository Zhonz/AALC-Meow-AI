# AALC-Meow-AI

<div align="center">

<img src="logo.png" alt="AALC-Meow Logo" width="128" height="128">

**《边狱公司》（Limbus Company）Android 原生自动化助手**

[![License](https://img.shields.io/badge/license-AGPL%203.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![MaaFramework](https://img.shields.io/badge/MaaFramework-v5.16.1-orange.svg)](https://github.com/MaaXYZ/MaaFramework)
[![AALC Upstream](https://img.shields.io/badge/AALC-V1.6.2-brightgreen.svg)](https://github.com/KIYI671/AhabAssistantLimbusCompany)

</div>

---

## 🧭 这个仓库是怎么来的

一句话：**这是对 [qi-1021/AALC-Meow](https://github.com/qi-1021/AALC-Meow) 的续更版**。原仓库因为维护者精力不足挂出了「无限期搁置」，本仓库在原代码基础上跟随上游更新，并把没做完的部分补上。

| 维度 | 内容 |
| :--- | :--- |
| **代码来源** | 原 Android 移植 [qi-1021/AALC-Meow](https://github.com/qi-1021/AALC-Meow)（AGPL-3.0），保留其全部原始署名与历史 |
| **玩法/资源来源** | [KIYI671/AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany)（AALC）**V1.6.2 / ec8aa17**：任务流程以它的 `tasks/` 为唯一权威，识别模板按它的 `assets/images` 0.5x 重导 |
| **引擎版本** | [MaaXYZ/MaaFramework](https://github.com/MaaXYZ/MaaFramework) **v5.16.1**（原仓库停在 v5.14.0） |
| **Android 基座** | [Aliothmoon/MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) 的架构与 bridge 层 |
| **技术栈** | Kotlin + Jetpack Compose（UI）、KSP、MaaFramework C++ 引擎（JNA 绑定）、CMake/C++ bridge、声明式 Pipeline JSON |
| **谁做的** | 本次更新由 **AI 助手完成**（DeepSeek Harness 上的编码智能体）：读上游 Python 源码 → 重写 Pipeline → 重导模板 → 用真实引擎与真机截图做验证。人工只负责方向与最终验收 |

> 许可证是 **AGPL-3.0**，衍生自 AALC。分发或修改请保留 [LICENSE](LICENSE) 与上述署名，**不要洗掉原作者信息**。

### 本次更新做了什么（相对原仓库）

- **跟版本**：MaaFramework v5.14.0 → **v5.16.1**（C API 兼容，JNA 层无需改动）；AALC 上游核对为最新 **V1.6.2**，462 张官方图片资源全量同步，252 张识别模板按官方链路重新 0.5x 导出
- **补流程**：按上游重建通行证流程（19 节点）——日常 / 周常 / 赛季（含第二页滑动）/ 战斗通行证 / 邮件；领取选项拆成「通行证+邮件 / 只通行证 / 只邮件」三种
- **修问题**：通行证三枚竖排 tab 在同一列、间距仅 144px，不带 roi 时会互相命中导致 `error handling loop detected`，已按真机截图像素位置加窄带 roi
- **加校验**：三层校验进 CI——自写白名单 → 官方 JSON Schema（`tools/`）→ 真实引擎加载；验证方法与残余风险记录在 [docs/VERIFICATION.md](docs/VERIFICATION.md)

---

## 🌟 项目设计与特性

- **📱 彻底摆脱 PC 与重量级 Python 依赖**：将原版 AALC 的 Python/PySide6 体系转译为声明式 MaaFramework Pipeline，在手机端以纯 C++ 引擎毫秒级原生运行。
- **🎮 全平台与全容器兼容**：针对边狱公司在手机端普遍通过 **OurPlay、Kuyo 游戏盒** 等沙箱容器运行的特点，原生提供**前台画面捕获驱动**，无需关心外层包裹容器，画面即控。
- **🔋 超轻量与内存极省**：摒弃 2GB+ 的 OnnxRuntime-Python 内存占用，全套运行时仅占约 100MB 运行内存。
- **🎯 16 KB 分页对齐**：全量底层动态库严格执行 16 KB 页面对齐，完美适配 Android 15+ 现代操作系统。
- **🛡️ 纯免 Root（Shizuku 优先）**：推荐使用 [Shizuku](https://shizuku.rikka.app/) 免 Root 授权，通过系统底层虚拟显示器与注入通道稳定控制。
- **🔄 首期核心闭环**：
  1. **体力全换脑啡肽模块（自动换饼）**：主界面一键全换模块。
  2. **战斗自动胜率连线**：自动 P 键胜率连线与齿轮回合推进，直至战斗胜利结算。
  3. **通行证与邮件奖励**：日常 / 周常 / 赛季任务（含赛季第二页）逐条领取经验，再领战斗通行证奖励与邮箱附件。
- **🔄 资源热更新**：上游换界面图、改流程时不必等 App 发新版。启动时读仓库根目录的 `pi-manifest.json`，
  有更新就下载、校验 SHA-256、解到暂存目录再原子替换；失败保留当前资源，设置页可关。
  发布端是 `.github/workflows/publish-pi.yml`（`assets/` 一改就自动打包发 Release）。

---

## 📱 运行要求

| 项目 | 要求 | 说明 |
| :--- | :--- | :--- |
| **操作系统** | Android 9.0（API 28）及以上 | 推荐 Android 11+，后台虚拟空间兼容性更佳 |
| **提权方案** | [Shizuku](https://shizuku.rikka.app/) 或 Root 权限 | 推荐使用 Shizuku，免 Root 即可直接调用系统底层 Native 控制器 |
| **设备架构** | `arm64-v8a`（主流真机） / `x86_64`（PC 模拟器） | 默认提供单架构轻量化包，安装包体积紧凑 |
| **目标游戏** | 《边狱公司》（Limbus Company）客户端 | 支持原生客户端、OurPlay、Kuyo 及各类游戏空间 |

---

## 📄 开源许可证与致谢

- 本项目基于 [AGPL-3.0 License](LICENSE) 开源。
- 感谢 [KIYI671/AhabAssistantLimbusCompany](https://github.com/KIYI671/AhabAssistantLimbusCompany) 提供的优质边狱巴士自动化思路与图像资源。
- 感谢 [MaaXYZ/MaaFramework](https://github.com/MaaXYZ/MaaFramework) 与 [Aliothmoon/MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) 提供的跨平台自动化框架与 Android 运行架构支持。

---

## 📬 联系方式

- 问题与建议：欢迎提交 [Issues](https://github.com/Zhonz/AALC-Meow-AI/issues)（原仓库的既有问题请留在原仓库，方便追溯）
- 开发者邮箱：[qiisme1021@icloud.com](mailto:qiisme1021@icloud.com)

---

## 🛠 本地开发

```bash
python3 scripts/setup_maa_framework.py     # 从 MaaFramework Release 下载引擎 .so
python3 scripts/validate_pipelines.py      # Pipeline 结构校验
python3 scripts/check_pipeline_schema.py   # 官方 Schema 校验
```

> `upstream/{aalc,m9a,maaend}` 三个目录是上游源码的 submodule，**本仓库不随源码分发**
> （见 [.gitmodules](.gitmodules)）。要重新生成模板或对照上游流程，需要自行
> `git submodule update --init` 或按 `.gitmodules` 里的地址克隆到对应路径。
> 改流程 / 改模板 / 验证方法的完整说明见 [docs/RELEASING.md](docs/RELEASING.md)
> 与 [docs/VERIFICATION.md](docs/VERIFICATION.md)。
