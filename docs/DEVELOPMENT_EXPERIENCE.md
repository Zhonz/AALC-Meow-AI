# M9A-Meow 开发经验与 MAA 系移动端移植实战指南

> **文档标识**：`M9A-MEOW-EXP-2026`  
> **项目定位**：基于 [MaaFwApp](https://github.com/Aliothmoon/MaaFwApp) 架构，将 [M9A (重返未来：1999 小助手)](https://github.com/MAA1999/M9A) 桌面自动化完整移植到 Android 平台（免 Root / 后台虚拟屏运行）的工程经验总结。  
> **适用范围**：MAA 系项目（如 MaaEnd、M9A、MAA、MaaDora 等）及非 MAA 系脚本移动端原生化移植。

---

## 目录

1. [架构全景与核心机制](#1-架构全景与核心机制)
2. [关键填坑实战（十宗罪与必修课）](#2-关键填坑实战十宗罪与必修课)
   - [2.1 16KB 内存页 ELF 对齐（Android 15+ 强制标准）](#21-16kb-内存页-elf-对齐android-15-强制标准)
   - [2.2 Release 编译与 ProGuard/R8 性能雪崩](#22-release-编译与-proguardr8-性能雪崩)
   - [2.3 Root 提权崩溃与 Shizuku 双模兼容](#23-root-提权崩溃与-shizuku-双模兼容)
   - [2.4 游戏启动（StartApp）Intent 异常与管道清洗](#24-游戏启动startappintent-异常与管道清洗)
   - [2.5 触控精度与多角度屏幕旋转动态校正（重大突破）](#25-触控精度与多角度屏幕旋转动态校正重大突破)
   - [2.6 识别推理硬件加速（CPU / NNAPI / Vulkan）与控制器重构](#26-识别推理硬件加速cpu--nnapi--vulkan与控制器重构)
   - [2.7 任务失败后补充重试（Retry-After-Completion）机制](#27-任务失败后补充重试retry-after-completion机制)
   - [2.8 国内 GitHub 下载镜像并发竞速引擎](#28-国内-github-下载镜像并发竞速引擎)
   - [2.9 品牌元数据与资产隔离架构](#29-品牌元数据与资产隔离架构)
   - [2.10 纯云端 CI 编译与系统环境零污染规范](#210-纯云端-ci-编译与系统环境零污染规范)
   - [2.11 对象式 recognition 的参数作用域陷阱（静默失效的 roi）](#211-对象式-recognition-的参数作用域陷阱静默失效的-roi)
3. [兄弟项目移植研判：MAAend（终末地）](#3-兄弟项目移植研判maaend终末地)
4. [异构项目破局研判：AALC（边狱巴士）](#4-异构项目破局研判aalc边狱巴士)
5. [结论与标准化移植 Checklist](#5-结论与标准化移植-checklist)

---

## 1. 架构全景与核心机制

在传统模拟器/PC 端，自动化脚本通常依赖 Windows API、Adb 操作或截屏注入。而在 Android 真机上原生运行，必须解决**免 Root 权限**、**前台正常使用与后台自动化隔离**两大核心矛盾。

M9A-Meow 采用的体系架构分为三层：

```
┌─────────────────────────────────────────────────────────────────┐
│                     UI 层 (Android 原生层)                      │
│   Jetpack Compose + Material 3 + Kotlin Coroutines / Flows      │
│   负责配置管理、实时状态展示、触控小窗预览、设置与更新系统      │
└────────────────────────────────┬────────────────────────────────┘
                                 │ Binder IPC
┌────────────────────────────────▼────────────────────────────────┐
│               特权服务层 (Privileged Remote Process)             │
│   通过 Shizuku (adb uid 2000) 或 Root (uid 0) 提权独立启动      │
├─────────────────────────────────────────────────────────────────┤
│ 1. 虚拟显示器 (VirtualDisplay): 独立于物理主屏的独立渲染图层   │
│ 2. 原生输入注入 (InputManager): 无死角的多指手势注入与坐标校准   │
│ 3. 屏幕帧捕获 (NativeCapturer / Surface): 共享内存零拷贝捕获    │
│ 4. MaaFramework Core (libMaaFramework.so / C++ 运行时)          │
│ 5. Python Agent Bundle (Android Bionic 交叉编译的 CPython 3.13) │
└─────────────────────────────────────────────────────────────────┘
                                 │ 驱动游戏
┌────────────────────────────────▼────────────────────────────────┐
│               目标游戏客户端 (如《重返未来：1999》)              │
│   运行在独立虚拟屏上，后台静默渲染与受控，不占用物理前台主屏   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. 关键填坑实战（十宗罪与必修课）

### 2.1 16KB 内存页 ELF 对齐（Android 15+ 强制标准）
- **问题现象**：编译出来的 APK 在 Android 15 或更高版本（以及部分厂商新内核）上安装或运行瞬间闪退，`logcat` 抛出 `ELF alignment error`，指明动态库未按 16KB 边界对齐。
- **根本原因**：Google 从 Android 15 起强制推进 16KB Page Size，所有打包进 APK 的 `.so` 文件（包括 MaaFramework 本身、Chaquopy、Python 动态模块）的 ELF Segment 对齐值 (`p_align`) 必须 $\ge 16384$（`0x4000`）。
- **解决方案**：
  编写针对 ELF 头直接修补的 Python 脚本 [`scripts/realign_elf_16kb.py`](scripts/realign_elf_16kb.py)，在 CI 打包前使用 `llvm-objcopy` 对预编译 `.so` 执行重对齐：
  ```bash
  llvm-objcopy --set-section-alignment .text=16384 --set-section-alignment .data=16384 libxxx.so
  ```
  并在 `build.gradle.kts` 中开启 `packaging.jniLibs.useLegacyPackaging = true`，确保 APK 解压加载时的内存页对齐合规。

---

### 2.2 Release 编译与 ProGuard/R8 性能雪崩
- **问题现象**：Debug 包流畅运行，但 Release 包极其卡顿、甚至掉帧假死，或者 JNI 找不到方法直接 Crash。
- **根本原因**：
  1. R8 Full Mode 优化激进，过度内联或修剪了 JNA / JNI 反射所需的数据类和字段。
  2. 错误的 ProGuard 字典生成配置导致编译器陷入重重混淆死循环，CPU 暴涨。
- **解决方案**：
  - 在 `proguard-rules.pro` 中显式保留所有与 C/C++ 共享的结构体：
    ```proguard
    -keep class com.aliothmoon.maafw.bridge.** { *; }
    -keep class com.aliothmoon.maafw.remote.** { *; }
    -keep class com.sun.jna.** { *; }
    ```
  - 显式关闭过度优化的无效混淆规则，确保 JNI 符号完全直通。

---

### 2.3 Root 提权崩溃与 Shizuku 双模兼容
- **问题现象**：Shizuku 模式可用，但用户一申请 Root 授权立即崩溃。
- **根本原因**：Root 模式使用 `su` 派生服务进程时，环境变量（`CLASSPATH`、`LD_LIBRARY_PATH`）未完全初始化，且 `app_process` 启动特权进程时传入了缺失的权限 Token。
- **解决方案**：
  - 封装统一的 `RemoteAccessState` 与 `PrivilegedServicePort`。
  - 使用与 Shizuku 相同的协议握手机制规范启动参数，彻底消除两种提权模式的生命周期差异。

---

### 2.4 游戏启动（StartApp）Intent 异常与管道清洗
- **问题现象**：启动任务后游戏无法拉起，日志报找不到组件。
- **根本原因**：上游 M9A 面向 PC 模拟器设计，其 pipeline 中硬编码了类似：
  `"package": "com.shenlan.m.reverse1999/com.ssgame.mobile.gamesdk.frame.AppStartUpActivity"`
  在 Android 真机或虚拟屏中，若目标 Activity 被混淆、改名或非 Exported，`startActivity` 会直接抛出 `ActivityNotFoundException` 或权限拒绝。
- **解决方案**：
  在资产同步脚本 [`scripts/prepare_m9a.py`](scripts/prepare_m9a.py) 中加入正则自动清洗：
  ```python
  def fix_m9a_startup_packages():
      bad_spec = "/com.ssgame.mobile.gamesdk.frame.AppStartUpActivity"
      for json_file in M9A_ROOT.rglob("*.json"):
          text = json_file.read_text(encoding="utf-8")
          if bad_spec in text:
              json_file.write_text(text.replace(bad_spec, ""), encoding="utf-8")
  ```
  只保留纯应用包名，由 Android 系统 PackageManager 自动解析默认启动入口。

---

### 2.5 触控精度与多角度屏幕旋转动态校正（重大突破）
- **问题现象**：能够正常识别到游戏内按钮（OCR/模板匹配均成功），但点击时经常**按偏、按空或点在错误坐标**，导致任务失败。
- **深度病因剖析**：
  1. **坐标系与旋转错位**：MaaFramework 是在 16:9 横屏图像空间（如 1280x720）中做图像识别，得出的坐标 `(x, y)` 是基于图像空间的。然而，手机物理屏幕大部分为竖屏（如 1080x2400，宽高比 20:9），当游戏以全屏横屏或虚拟显示器启动时，Display 实际朝向可能为 `Surface.ROTATION_90` 或 `Surface.ROTATION_270`。此时若直接将 `(x, y)` 注入给 Display，Android InputDispatcher 会按其当前的旋转矩阵去映射，导致横竖颠倒、甚至坐标超界！
  2. **Controller 尺寸过期**：主屏模式下手机从竖屏转为横屏时，Native 控制器如果没有重新建立，仍持有初始竖屏尺寸，导致采图与注入双重错位。
- **破局代码实现**：
  在 [`InputControlUtils.java`](file:///Volumes/mac第三磁盘/codes/Projects/M9A-Meow/app/src/main/java/com/aliothmoon/maafw/bridge/InputControlUtils.java) 中实现根据 Display 当前 rotation 进行四向无损转换与 Clamp：
  ```java
  private static float[] transformCoordinates(int x, int y, int displayId) {
      DisplayInfo info = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
      int rotation = info.rotation();
      Size size = info.size(); // 当前逻辑宽高
      int curW = size.width(), curH = size.height();
      
      int naturalW = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) ? curH : curW;
      int naturalH = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) ? curW : curH;
      
      float targetX = x, targetY = y;
      switch (rotation) {
          case Surface.ROTATION_90:
              targetX = y;
              targetY = naturalW - 1 - x;
              break;
          case Surface.ROTATION_180:
              targetX = naturalW - 1 - x;
              targetY = naturalH - 1 - y;
              break;
          case Surface.ROTATION_270:
              targetX = naturalH - 1 - y;
              targetY = x;
              break;
      }
      return new float[] {
          Math.max(0, Math.min(curW - 1, targetX)),
          Math.max(0, Math.min(curH - 1, targetY))
      };
  }
  ```
  并在 [`MaaRunner.kt`](file:///Volumes/mac第三磁盘/codes/Projects/M9A-Meow/app/src/main/java/com/aliothmoon/maafw/remote/MaaRunner.kt) 中引入 `boundResolution` 监听，屏幕方向或尺寸改变立即自动重连 Controller。

---

### 2.6 识别推理硬件加速（CPU / NNAPI / Vulkan）与控制器重构
- **设计思路**：
  手机端 CPU 负载较高，利用 Android NPU (NNAPI) 或 GPU (Vulkan) 进行 ONNX OCR 模型推理，可大幅降低发热并提升识别帧率。
- **实现方案**：
  在 `AppSettings` 中提供选项，构建控制器时动态注入 `inference_device`：
  ```kotlin
  if (payload.inferenceDevice.isNotBlank() && payload.inferenceDevice != "cpu") {
      put("inference_device", payload.inferenceDevice)
  }
  ```
  并在 `MaaRunner` 中将 `inferenceDevice` 纳入控制器缓存判据，切换即刻平滑重建。

---

### 2.7 任务失败后补充重试（Retry-After-Completion）机制
- **设计思路**：
  日常任务运行中，可能偶发因为网络波动、加载变慢而单项任务超时失败。如果每次失败都中断并报废整轮并不理想。
- **机制设计**：
  - 提供 `retryFailedTasks` 开关（用户完全自主选择）。
  - 在主流程循环中记录首次失败的任务集合 `failedTasks`。
  - 在整轮主任务执行完成后（若非用户手动停止），自动对 `failedTasks` 发起二次补跑。
  - 若补跑全部成功，整轮结果自动转正为 `COMPLETED`。

---

### 2.8 国内 GitHub 下载镜像并发竞速引擎
- **设计思路**：
  移动端应用内直接从 GitHub Releases 下载更新包常因网络阻断或限速导致失败。
- **竞速引擎 [`GitHubMirrorRacer.kt`](file:///Volumes/mac第三磁盘/codes/Projects/M9A-Meow/app/src/main/java/com/aliothmoon/maafw/update/GitHubMirrorRacer.kt)**：
  - 维护包含 `ghproxy.com`、`mirror.ghproxy.com`、`ghfast.top`、`github.moeyy.xyz` 等知名镜像前缀池。
  - 收到 GitHub 下载链接后，使用协程并发向各镜像发起轻量 `HEAD` 探活请求。
  - 首个返回有效 HTTP 状态码的镜像即刻获胜，取消剩余任务；若 6 秒内全部失败自动平滑回退官方原链。

---

### 2.9 品牌元数据与资产隔离架构
- **隔离原则**：上游 `upstream/m9a` 作为 git submodule 保持洁净，不直接在上游分支内硬编码当前分支专有配置。
- **双重定制体系**：
  1. 构建脚本 [`scripts/prepare_m9a.py`](scripts/prepare_m9a.py) 在编译期自动覆写 `CONTACT`（替换为指定反馈邮箱 `qiisme1021@icloud.com`）与 `interface.json` 的 Github 仓库地址与介绍。
  2. UI 层 [`SettingsScreen.kt`](file:///Volumes/mac第三磁盘/codes/Projects/M9A-Meow/app/src/main/java/com/aliothmoon/maafw/ui/settings/SettingsScreen.kt) 针对 `AboutCard` 提供兜底展示与跳转，确保即使资源热更新也不会导致关于信息回退。

---

### 2.10 纯云端 CI 编译与系统环境零污染规范
- **环境隔离规范**：
  - 严格遵守**零污染宿主环境**：本地所有临时缓存、Python venv、编译过程文件强制限制在项目内的 `.tmp/` 目录中（设置 `TMPDIR`、`TEMP`、`TMP`）。
  - 本地不强求安装庞大的 Android NDK / SDK，日常代码开发与重构直接依靠 Git 提交。
- **云端全自动流水线**：
  - 在 `.github/workflows/build-apk.yml` 中编排全套环境：Python 依赖构建 -> Agent Bundle 打包 -> Gradle Assemble -> 16KB 页对齐验证 -> 标签自动发布 Release。

---

### 2.11 对象式 recognition 的参数作用域陷阱（静默失效的 roi）

- **现象**：
  管线里写 `"recognition": {"type": "TemplateMatch"}` 的同时把 `roi` / `threshold` / `template` 放在节点顶层，脚本能正常加载、任务也能跑，但这些限定词**完全不生效**——识别退化成全屏找图，误匹配率飙升，且没有任何日志提示。

- **根因**（MaaFramework `source/MaaFramework/Resource/PipelineParser.cpp`）：
  ```cpp
  json::value param_input = input;
  if (auto reco_opt = input.find("recognition"); reco_opt && reco_opt->is_object()) {
      param_input = reco_opt->get("param", *reco_opt);
      reco_type_name = reco_opt->get("type", kDefaultRecognitionFlag);
  }
  ```
  只有**字符串式**（v1，`"recognition": "TemplateMatch"`）才会把整个节点当参数来源；**对象式**（v2）只认 `recognition` 对象本身或其中的 `param` 子树。同一条规则对 `action` 对象同样成立。

- **本项目实测**：AALC 管线迁移时累计踩到 74 处「对象式 recognition + 顶层 roi」与 3 处顶层 `threshold`（`BattleStartTurn` / `BattleChecking` / `BattleFinishCheck`），全部静默失效。

- **三层防线**（现在都已接入）：
  1. [`scripts/validate_pipelines.py`](scripts/validate_pipelines.py) 第 7 项检查：对象式 recognition 下顶层若出现 `roi` / `template` / `threshold` 等识别字段，直接报错。
  2. [`scripts/check_pipeline_schema.py`](scripts/check_pipeline_schema.py)：用官方 `tools/pipeline.schema.json` 做 JSON Schema 校验，并**按引擎语义折叠**识别/动作对象（对象式只折叠对象自身），顶层参数无处可藏。仓库内 `tools/*.schema.json` 与 `UPSTREAM_VERSIONS.json` 的 MaaFramework 版本同源。
  3. `.github/workflows/build-apk.yml` 依次跑白名单校验 → Schema 校验 → 真实引擎加载（`scripts/verify_with_engine.py`），出包前拦住。

- **经验**：升级 MaaFramework 时把官方 `tools/pipeline.schema.json` 一并同步，Schema 校验能免费获得「新版本新增/改名/废弃字段」的告知；但 Schema 管不到作用域语义，仍需第 1 条那种带引擎源码依据的自定义检查。


### 2.12 编队域：把「一支队伍」的选项从任务里搬出来

- **背景**：`mirror` 的 43 个 option 里，体系 / 商店策略 / 奖励卡 / 开局加成 / 两个战斗开关 / 罪人勾选 / 12 位顺位这 17 项语义上是「编队」的属性，却按任务配置——换队要逐项重设，与 AALC 的编队卡差得远。
- **引擎侧不需要新 pipeline**：AALC 的 `tasks/teams/team_formation.py` 也只是「清空选择 → 按 6+6 网格点头像 → OCR 找编队#N」；本项目的 `sinner_selection` / `sinner_order_slot_1..12` / `daily_teams` 已覆盖同样语义，缺的只是 UI 组织方式。
- **边界只写一处**：[`domain/TeamPreset.kt`](app/src/main/java/com/aliothmoon/maafw/domain/TeamPreset.kt) 的 `TeamOptionScope.orderedNames` 是唯一清单——任务面板隐藏（`ui/tasks/Sheets.kt`）、编队页渲染（`ConfigurationResolver.resolveTeamOptions`）、运行期覆盖（`RunPlanBuilder` → `TeamOptionScope.mergedValues`）三处都从它出发。少接一处就是「面板里设了却不生效」。
- **旧配置不改也照原样跑**：`mergedValues` 三档回退——任务没选编队、该编队没配过任何值、配了但键不在编队域——都原样返回任务自己的值。
- **教训**：`teamPresets` 放在 `UserConfiguration` 而不是 `RunConfiguration`（一支编队对所有运行配置通用，与 `globalOptionValues` 同级）；DataStore 的 `ignoreUnknownKeys` + 字段默认值让旧数据无痛升级。编队号上限跟 PI 的 `daily_teams` 选项走（1..20），不要凭游戏内看到的队数拍一个更小的值。

### 2.13 顺位由点选决定 + 直接吃 AALC 的编队 YAML

- **顺位不是「12 个下拉」，是点选顺序**：AALC `tasks/teams/team_formation.py` 里 `sinner_team = TeamSetting.sinner_order` 然后 `for i in 1..12: sinner = sinner_team.index(i)` 依次点头像——**第几个被点，就是第几号位**。本项目有 `sinner_order_slot_1..12` 十二个 option，天然能表达同样的语义：点一下罪人 → 追加到队尾；再点一下 → 移出并把后面的顺位顺延（`TeamOptionScope.toggleSinner`）。
- **顺位槽必须写满**：`applySinners` 把 12 个槽全部写死（未占用写 `none`），并同步 `sinner_selection` 多选。留空的槽会回退到任务里旧的值，多出来的点击会把队伍点乱——这类 bug 在真机上表现为「编队顺序和你点的不一样」。
- **出战/待命**：游戏规则前 6 位出战（`DEPLOY_LIMIT = 6`），第 7 位起标「待命」。宫格固定 2 行 × 6 列，与游戏内 `all_sinners_name` 的顺序、以及 pipeline 里 `SelectSinnerSlot1..12` 的 `target_offset` 一一对应——换宫格顺序等于把点击坐标对错人。
- **导入 AALC 的导出文件**：电脑版「导出队伍设置」是 ruamel 落盘的 YAML（`module/config/team_import_export.py`，`team_setting.model_dump()` 去掉 4 个统计字段），字段平铺。手机端不引 YAML 库，`domain/AalcTeamImport.kt` 只实现这份文件用到的子集：顶层键 + 标量 + 块序列/行内序列，嵌套映射（`custom_theme_pack_weight`）整块跳过；`#` 只有在前面是空白时才算注释（否则 `remark_name: 编队#1` 会被截断）。
- **语义不同就别硬映射**：AALC 的 `shop_strategy` 是「出售/合成/合成四级优先」，本项目是「治疗后离开/直接离开」——同名不同义，硬映射等于给用户一个错的行为，所以归入「已忽略」并在预览里报数量。`reward_cards` 的四档只能压到本项目三档（0/1 → `starlight_first`，2 → `cost_first`，3 → `ego_first`）；`opening_bonus` 的 10 项细分压成「开局加成开/关」。
- **导入前先预览**：解析 → `MaaPromptDialog` 列出出战顺序、待命、套用了几项、忽略了几项，确认后才覆盖**当前选中的那一队**（文件里的 `team_number` 不可信，用户可能想导进别的队）。一个罪人都没解析出来时不允许确认，直接提示「确认是用 AALC 导出的文件」。
- **不做假开关**：AALC 有而本引擎没有管线的设置（弃置体系、不治疗/不购买/不合成/不出售/不升级、第二体系、技能替换、编队码、商店刷新次数、每层重新编队、困难楼层、EGO 饰品观测）不在 UI 里摆开关，只在编队页底部列出并说明「缺的是识别与操作流程」——能点但不生效比没有更糟。

### 2.14 交棒（handoff）：声明式管线中途把执行权交给 Kotlin 脚本

- **为什么必须破例**：AALC 的 `tasks/mirror/in_shop.py` 有 1518 行——读剩余经费（OCR + 数字纠错）、按 `(y // 40, x)` 排序点饰品、按次数刷关键词、钱不够就收手、最后无论如何都要把玩家带出商店。这些是**带状态和阈值的命令式逻辑**，pipeline JSON 表达不了。硬塞成节点只会得到一堆互相抢识别的分支。
- **交棒三件套**：`RuntimeTask.scripts` 携带 `TaskScript(scriptId, entry, resumeEntry, maxRounds, params)`（`runner/RunPlan.kt`），由 `RunPlanBuilder` 在编译任务时从 PI option 摊平（`script/ShopScriptBinding.kt`）。管线跑到 `entry` 节点即「本轮任务成功结束」，App 侧跑脚本，跑完把 `resumeEntry` 重新投给引擎。
- **交棒节点怎么造**：`MirrorHandleShop` 用 `TemplateMatch`（`mirror/shop/shop_coins_assets.png`，thr 0.88，roi `[428,81,346,112]`）+ `action: DoNothing` + `"next": []` + `timeout`。`next` 为空是关键——空 next 让任务**正常成功**返回，MaaRunner 才有机会在中间插一段自己的代码。注意 `mirror/road_in_mir/shop.png` 是地图上的商店节点图标（`search_road.py:119` 用它算节点权重），不是商店界面；判定「人已经在商店里」要用经费栏 `shop_coins_assets.png`（`mirror.py:162` 的注释就是「防止卡死在商店」）。
- **别用「节点存在」判交棒**：`MaaTaskerGetLatestNode` 读的是 `RuntimeCache::latest_nodes_`，**按节点名长期保留，只在 clear() 时清空**。第二次进商店时 id 早就在了，靠存在性判断会一次不落地误判。正确做法是投递前对每个 `script.entry` 取一次 id 快照，`Wait` 成功后再取一次，**id 变了**才算本轮跑到（`MaaRunner.runScriptedTask`）。
- **识别不能走 `MaaTaskerPostRecognition`**：它要求调用方自己给一帧图（`Common/MaaTasker.cpp` 里 image 为空直接返回 `MaaInvalidId`）。改成「探针节点 + 覆盖」：每次识别现造一个 `__MaaScriptProbe` 节点（`pipeline_override` 允许凭空建节点），`DoNothing` + `next: []` 投一次，再顺 task → node → reco 三级详情读 `detail_json`（`remote/MaaScriptHost.kt`）。代价是**一次识别一个任务，约 1.2 秒**，所以脚本里不要写「每帧轮询」，只在决策点识别。
- **坐标是截图空间**：controller 已经设了 `SCREENSHOT_TARGET_SHORT_SIDE = 720`，脚本一律按 1280×720 算；引擎在 `ControllerAgent::handle_click` 里按 `image_raw / image_target` 自动放大回真机像素，脚本不用关心设备分辨率。
- **探针任务要静音**：每次探针都会触发 `Tasker.Task.Starting/Succeeded` 回调，UI 会凭空多出一串无名任务。`MaaRunner` 用 `@Volatile suppressEvents` 在脚本执行期间整段丢弃事件（`MaaRunner.kt` 的 eventSink 第一行）。
- **预算与兜底**：`maxRounds`（默认 16）限制一次任务里被交棒的次数，防止脚本与管线互相踢皮球；脚本自己必须 try/finally——**不管中间哪步失败都要执行「离开商店」**，否则玩家卡在商店界面，后续所有识别都对不上。脚本抛异常按任务失败处理，不让管线带着半截状态继续。
- **参数一律来自 PI**：`shop_do_not_heal` / `shop_do_not_buy` / `shop_do_not_sell` / `shop_sell_systems` / `shop_keyword_refresh` / `shop_normal_refresh` / `shop_buy_level_gifts` / `shop_must_purchase`，缺值回落到 PI 的 `default_case`，与 UI 显示的默认值同源——否则会出现「界面显示开、实际没开」。`shop_strategy = leave` 是全零开关，等同 AALC 的 `ignore_shop`。
- **可验证性**：交棒这条链没有 schema 能校验，只能引擎级验。做法是拿 CustomController 回放合成帧：贴经费栏 → `post_task('MirrorDungeonEntry')` 应走 `MirrorDungeonEntry → MirrorCheckShopCoins → MirrorHandleShop` 且 succeeded；再分别贴「只有地图 legend / 奖励卡确认页 / 卡包选择页」，确认三个处理节点不互相误触。结果记在 [docs/VERIFICATION.md](VERIFICATION.md) 第八节。

---

## 3. 兄弟项目移植研判：MAAend（终末地）

### 3.1 项目技术契合度
| 评估项 | 状态 | 评价与移植考量 |
|:---|:---:|:---|
| **所属体系** | ✅ MAA 系 | 属于 MaaFramework 生态原生项目，遵循标准 `interface.json` + Pipeline 体系 |
| **视觉与识别** | ✅ 完美兼容 | 同样基于 OpenCV / PP-OCR，通用我们的 OCR 模型准备流水线 |
| **移植可行性** | 🟢 **极高 (95%+)** | 几乎可直接套用 M9A-Meow 的全套架构与配置文件 |

### 3.2 移植改造路径
1. **替换上游子模块**：将 `upstream/m9a` 替换为 `MaaEnd/MaaEnd`。
2. **编写 `pi-profile.yaml`**：指定 MaaEnd 的 `interface.json` 路径、包含的资源目录（`resource/`、`pipeline/` 等）。
3. **触控与手势适配重点**：
   - 《明日方舟：终末地》是 3D 动作/即时制游戏，涉及虚拟摇杆移动、视角拖拽、技能连招与双指手势。
   - **技术红利**：我们刚刚在 M9A-Meow 中完善的 `InputControlUtils.java` 坐标旋转校准与多指序列管理（`TouchPointerSequence`），可以直接为终末地的 3D 触控提供高精度保障！

---

## 4. 异构项目破局研判：AALC（边狱巴士）

### 4.1 核心难点与阻碍分析
[AALC (AhabAssistantLimbusCompany)](https://github.com/KIYI671/AhabAssistantLimbusCompany) 是专为 PC Windows 开发的 Python 自动化脚本，它**不是 MAA 系项目**。直接在 Android 上运行面临三大技术壁垒：

```
[AALC 桌面技术栈]                      [Android 移动端鸿沟]
PySide6 (Qt GUI)            ───✖───>  移动端无法原生嵌入 Qt 窗口做悬浮球/虚拟屏
PyAutoGUI / pynput / Win32  ───✖───>  Android 无 Windows 消息系统，无法注入键鼠
RapidOCR (C++ x86_64 库)    ───✖───>  原生 C 扩展在移动端存在严重的交叉编译与架构兼容壁垒
硬编码 1920x1080 桌面窗口   ───✖───>  手机端多比例屏幕适配困难
```

### 4.2 两套技术解决路径评估

#### 方案 A：MaaFramework 化重构（⭐ 强烈推荐，终极形态）
- **核心思想**：不强行搬运 AALC 的桌面代码，而是**提取 AALC 的“业务灵魂”（图片资源、识别逻辑、镜牢路径决策算法），改写为 MaaFramework 规范**。
- **实施步骤**：
  1. 梳理 AALC 的图像资产与匹配逻辑，转为标准的 `tasks/*.json` 和 `pipeline/*.json`。
  2. 镜牢复杂的路径选择、饰品选择等策略逻辑，封装为标准的 Python `CustomAction` / `CustomRecognizer`（就像 M9A 的 `agent/` 一样）。
  3. 直接放入我们的 `MaaFwApp` 架构进行移动端出包。
- **收益**：获得最顶级的移动端体验——无需 Root、后台独立虚拟屏挂机、轻量原生悬浮窗、低功耗。

#### 方案 B：Headless Python 引擎桥接（过渡技术方案）
- **核心思想**：若暂不想重写 AALC 的算法代码，可进行**去桌面化（Headless）抽象**：
  1. 剔除 PySide6 界面代码，UI 全部交给 Android 原生 Jetpack Compose 负责。
  2. 编写抽象控制驱动层：将 `pyautogui.click()` 和截图请求重定向到 Android 特权进程的 `InputManager` 和 `NativeCapturer`。
  3. 将 OCR 替换为 Android 平台友好的轻量库或 MaaFramework 现成的 OCR 模块。
  4. 利用我们的 CPython 3.13 交叉编译环境打包为 Agent Bundle 执行。

---

## 5. 结论与标准化移植 Checklist

开发 M9A-Meow 不仅带来了一个可用的移动端自动化工具，更沉淀出了一套**将任意桌面游戏自动化工具移植到 Android 原生环境的方法论**：

- [ ] **Step 1: 确定架构模式**（MAA 系走标准 Profile 管道；非 MAA 系提炼决策算法为 Agent）。
- [ ] **Step 2: 16KB 页对齐治理**（全部依赖库强制运行 llvm-objcopy 检查）。
- [ ] **Step 3: 管道清理**（清理所有硬编码的目标应用 Activity 路径，改为纯包名启动）。
- [ ] **Step 4: 触控映射校准**（启用 Display 四向旋转坐标转换与 Clamp，避免错位）。
- [ ] **Step 5: 优化体验链路**（配置下载镜像竞速、可选重试机制、推理加速）。
- [ ] **Step 6: 云端 CI 持续交付**（规范环境，推送到 GitHub 即可自动打包发版）。

这份工程经验将成为后续启动 **MaaEnd-Meow** 及攻坚 **AALC-Meow** 最有力的技术指南。

---

## 6. 镜牢商店：把 in_shop.py 的三段式合成搬进脚本

`MirrorShopScript` 从「治疗/购买/出售/刷新」扩到 AALC 的整套商店流程后，最有价值的经验是**别用管线 JSON 表达带状态的策略**：

### 6.1 三段式合成（AALC 的 fuse_gift）

1. **激进合成**：饰品栏里"没用的"全塞进去，直到 `fuse_90%_assets`（0.97）出现、且 `fusion_level_IV_gift_assets` 在场 —— 说明这一手能合出四级。选料靠 `fuse_label.png` 作锚点算 10 个格子：`first_gift = 最下方 label + (48, 68)`，格子步长 95（1440p 的 95 与 190 按 0.5x 折半）。要保护的四级饰品（本体系 / 第二体系 / lunar_memory / 两件 vestige）从候选里按 25px 邻域剔掉。
2. **普通合成**：只塞 `must_be_abandoned` 的 19 件废品 + 勾选要卖的体系饰品，凑三件；不够三件时第一轮仍试一次，还不行就滚一屏列表再试（`gifts_list_block` 往下拖 250px）。
3. **体系公式合成**：找 `fusion_result/<system>_<n>.png` 里本体系的成品图标，位置在分界线（最下方 label 的 y）以上就点；没有成品就随便拿本体系饰品凑。slash/pierce/blunt 没有成品图，只有随机合成那一路。

合成确认里还有两步容易漏：**星光加成**（`fuse_use_starlight_assets` 最多点 5 次，每次把 60%→90%、90%→99%）和 **`enhance_and_fuse_and_sell_confirm_assets`**（合成/出售/升级共用的"确定"按钮）。

### 6.2 四级之后怎么办

AALC 的 `after_level_IV` 是四个分支：转普通合成 / 停止合成只出售 / 追第二体系 / 之后几层商店全跳过。脚本用 `skipShopsAfterIv` 这个 `@Volatile` 字段把"跳过"带出本次交棒 —— 所以 `ScriptTask` 加了 `resetSession()`，由 `MaaRunner` 在每个任务开跑前调用，否则跳过一次商店之后就再也买不了东西。

### 6.3 为什么不像 AALC 那样 OCR 判断"合出的是不是本体系四级"

AALC 用 `find_language_text` 读整屏文字判断合成结果体系。手机上整屏 OCR 一次要 ~1s，而且涂黑过的语料验证不了准确性；脚本改成"能进激进分支 + 四级提示在场"就直接按拿到四级处理。代价是：如果游戏在同一次合成里给了别的体系四级，会误判 —— 这个概率低于漏判的代价，且策略分支可回退。

### 6.4 升级与技能替换

升级要先点排序按钮再点它下面 100px 的选项把列表收拢，然后在 `(659,243)-(1132,513)` 这个扫描区里找本体系图标；点开饰品后靠 `power_up_assets` → `power_up_confirm_assets` 完成，确认框还在说明材料不够。技能替换的"三个技能"用 `skill_replacement_coins` 必须正好三枚来兜底 —— 识别不完整就放弃，绝不猜位置（点了就是不可逆替换）。

> 这一整套仍然只做到**引擎级验证**（真实 v5.16.1 加载资源 + 节点可达 + 三节点互不误触）；点击之后的真实界面变化要等真机反馈，见 docs/VERIFICATION.md 第 8 节。

---

## 7. 镜牢寻路与战斗：把 search_road.py / battle.py 搬进脚本

商店（第 6 节）只解决了「进商店之后」。镜牢真正的主循环是**在地图上选路 → 进节点 → 打架 → 回到地图**，
对应上游两处代码：`tasks/mirror/search_road.py`（893 行）与 `tasks/battle/battle.py`（715 行）。

### 7.1 为什么又开两个脚本，而不是写进管线

管线是「识别到就点」，没有「比较三个候选的权重再挑一个」的能力，也没有「按住拖一条连线」的手势。
AALC 这两个文件里全是这种判断，所以延续第 6 节的交棒机制：

| 交棒节点 | 脚本 | 干什么 |
| :--- | :--- | :--- |
| `MirrorHandleRoad` | `script/MirrorRoadScript.kt` | 认 legend → 对齐巴士 → 三个候选节点打分 → 点最高分 → 点「进入」 |
| `MirrorHandleBattle` | `script/MirrorBattleScript.kt` | 进战斗（普通/链接战）→ 首回合防御 → 每回合胜率连线 + 齿轮 → 结算/认输 |

两个节点都写 `next: []`：任务在交棒点正常结束，MaaRunner 的 `runScriptedTask` 发现该节点
`latest_node_id` 变了，就把执行权交给脚本；脚本跑完从 `resumeEntry`（`MirrorDungeonEntry`）续上管线。
续接点特意选**分发器入口**而不是探索循环：打完架可能停在地图上，也可能停在结算页，只有分发器能兜住。

### 7.2 节点权重：照抄 get_node_weight

```
商店 3 | 事件 3 | 普通战斗 2 | 精英遭遇战 1 | 集中遭遇战 0 | 认不出 -5（别点）
```

候选点是相对**巴士图标中心**的三个固定偏移（上游 `three_roads`，2560×1440 下是 (500,50)/(500,450)/(500,-400)，
720p 折半后 (250,25)/(250,225)/(250,-200)）。每个候选在周围 ±62px（上游 ±125×0.5）里认节点图标，
分高的先试；点完立刻认「进入」按钮，认不到就点一下空地把选中态清掉再试下一个。

### 7.3 战斗：P+Enter 在手机上就是两下点击

电脑版一回合的操作是「按 P 自动连出胜率线 → 按 Enter 开打」。手机没有键盘，等价操作是
**点 `battle/win_rate.png` 那张卡片，再点 `battle/gear_right.png` 齿轮**。管线里的 `BattleAutoCombat`
本来就是这么做的，脚本接手是为了多管三件管线管不了的事：进入战斗的两种按钮、首回合防御手势、结算/认输收尾。

首回合防御对应上游 `_defense_this_round`：

```
技能格数 = (右齿轮.x - 100 - (左齿轮.x + 94)) / 145
第 i 格 x = 左齿轮.x + (220 - 4.5 * 格数) + 161 * i , y = 左齿轮.y + 250
```

算出格子后逐个点一下（选防御），再从左齿轮**按住**依次划过所有格子拖到右下角 —— 这一步是连续手势，
必须 `touchDown → touchMove… → touchUp`，拆成几次 swipe 会被游戏当成多次点击、连携直接断。
为此给 `ScriptHost` 加了 `touchDown/touchMove/touchUp` 与扩展 `dragLink(points)`，
JNA 层补了 `MaaControllerPostTouchMove`。

### 7.4 踩坑：MaaFramework 的 `Or` 不能用内联对象

想用一个节点同时认「普通战斗」和「链接战」两个按钮，最直觉的写法是：

```json
{ "type": "Or", "any_of": [ { "type": "TemplateMatch", "template": "..." } ] }
```

**这个写法会静默失效**：引擎把内联对象当成了「什么都不填」的识别（等价 DirectHit），于是该节点在
任何画面上都命中 —— 实测在纯色画布上 `MirrorHandleBattle` 也返回成功，整条探索循环被它劫持。
正确写法是**引用节点名**：

```json
{ "type": "Or", "any_of": ["MirrorBattleEntryNormal", "MirrorBattleEntryChain"] }
```

再把这两个名字各写成一个普通 TemplateMatch 节点（`next: []`，不接入流程）。改完用「空画布 / 只有 legend
的画布」各跑一遍，确认不命中，才算接对。

### 7.5 仍然是「引擎级验证」，不是真机

本机（Android 手机 proot 环境）没有 Shizuku/ADB，装不上 APK、也拿不到游戏画面，所以这两条脚本能验证到的只有：
交棒节点可达、节点权重分支不误触、模板文件都存在、Kotlin 编译通过。**真实点击后界面有没有变**、
**巴士拖动距离够不够**、**防御连线的落点**，都要等真机反馈再调常数（都集中写在脚本顶部的 `const val` 里）。

## 8. 镜牢主题包：把 select_theme_pack.py 的权重表搬进脚本

### 8.1 为什么不能只是一次点击

旧管线里主题包选择页只有一个节点 `MirrorPickThemePackConfirm`，固定点 `(500,540)` —— 三个卡包摆在哪它都点同一个坐标，等于随机。上游 AALC 是有偏好的：`select_theme_pack.py` 找卡包标记图标，以图标为锚点裁出名字，OCR 后用 `theme_pack_list.yaml` 里的关键词查权重，**选权重最高的那张；最高分还低于优选阈值就先刷新，最多刷 3 次**。

### 8.2 锚点裁剪这件事

不能对整屏做 OCR 再猜哪段是名字。上游的裁法是（720p 换算）：左 140、上 40、右 40、下 260 —— 这些数字来自 1440 宽基准的 210/60/60/390 乘以 2/3。裁歪了 OCR 就是垃圾，于是只剩「按未知兜底」一条路；因此脚本里把 OCR 原文一起打进日志，真机上一眼能看出是没认到包、还是认到包但没认到字。

### 8.3 权重表是生成出来的，不是手抄

`ThemePackWeights.kt` 由一次性脚本读取上游 `assets/config/theme_pack_list.example.yaml` 生成，四张表：中文普通 59 条、中文困难 38 条、英文普通 61 条、英文困难 40 条。查询是**子串包含取最大值**，所以「伏行」这种短关键词能命中带前缀的完整包名。

用户自定义权重（上游 config 里的覆盖）没有做——手机端没有对应设置入口，等有了再合并成第五张表。

### 8.4 交棒接在哪里

`MirrorDungeonEntry` → `MirrorCheckThemePack` → `MirrorHandleThemePackFeature`（next 置空 = 交棒），脚本跑完回 `MirrorDungeonEntry`。第二个入口在 `MirrorInDungeon.next` 里，因为主题包页也可能从地图状态直接弹出。

有 `attempts` 计数：同一任务最多选 4 次，防止脚本认不到页面时和管线来回打转（和寻路脚本的 `gaveUp` 是同一类保险）。

## 9. 镜牢事件：把 EventHandling 的识别补齐

事件界面原来只有五档判断按钮（很好/好/普通/低/很低）+ 几个通用按钮。上游事件种类更多，尤其是「未知事件」「获得 EGO」「优势判定」「判定结果」「执行判定」「跳过」这六类。

补齐后的顺序是：**未知事件 → 获得 EGO → 优势判定 → 判定结果 → 原有五档 → 继续/开始 → 执行判定 → 跳过 → 兜底点击**。越靠前越具体，兜底点击排最后，避免它把还没加载出来的按钮状态截断。

## 10. 只到引擎级验证

这些脚本都没有在真游戏里跑过：本机没有 Shizuku/ADB，装不了 APK，截图也拿不到。能证明的是：引擎（maafw 5.16.1）能加载整包资源并通过 Schema 校验；把模板贴到画布上，`MirrorDungeonEntry` 能被分发到对应的交棒节点（路径见 docs/VERIFICATION.md 第 9、10 节）；Kotlin 侧编译通过（CI 出包成功）。

不能证明的是：OCR 真实读数、点击后界面的真实变化、合成/升级这类不可逆操作的判定准不准。真机上发现问题先改阈值与裁剪常量，别改流程顺序。

## 11. 开局星光与初始饰品：两个必须按 0.5 / 2⁄3 分开算的坐标基准

AALC 的镜牢代码混用了两套基准，移植时**不能一把尺子量到底**：

| 函数 | 上游缩放 | 720p 下的系数 |
| :--- | :--- | :--- |
| `mirror.py:793 enter_mir_with_star` | `scale = set_win_size / 1440` | 0.5 |
| `mirror.py:882 _click_init_gift_row` | `s = set_win_size / 1080` | 2/3 |
| `battle.py` 的技能格 | `scale = set_win_size / 1440` | 0.5 |
| `get_floor.py` 的楼层横带 | 1440 基准 | 0.5 |

星光页（第 11 节 a）：以星光币图标中心为原点，第一格 `(-1800*s, +300*s)`，列距 `400*s`、行距 `480*s`，
二级卡再点左侧「+」`(-80*s, +320*s)`、三级点右侧「++」。本项目把它做成 `script/MirrorStarScript.kt`，
管线只负责把「星光加成页」认出来（`coins_assets.png`）然后 `next: []` 交棒。

初始饰品页（第 11 节 b）：先按体系点分类图标，再按 `start_gift` 的顺序点三件
`select_init_gift/{system}_ego_gift_{1,2,3}.png`；识别不到就退到「以确认按钮为锚点」的坐标兜底：
`x = confirm.x - 340*s`、`y = confirm.y + row_dy[id]*s`，`row_dy = {1:-490, 2:-330, 3:-170}`。
因为这里 `s` 是 2/3，锚点偏移是 `(-227, -327/-220/-113)`，若误用 0.5 会整整偏出一行。

斩击/穿刺/打击三个体系的分类图标在列表下方，上游的做法是：只要还能认到 `slash_gift_1.png` 就向上拖 400，
直到露出 `blunt_gift_1_assets.png`。这条「拖到看见目标为止」的循环必须带 guard，否则列表见底后会空转。

## 12. 商店脚本对齐 in_shop.py 的二轮审计

第一版商店脚本能跑通「治疗 → 出售 → 购买 → 合成 → 升级 → 离开」，但和 in_shop.py 对照后仍有二十多处偏差。这一轮按严重度补齐：

- **阶段顺序与门控**：`after_level_IV_select == 3` 时当店仍要升级一次再跳过后续商店（AALC 是先 `enhance_gifts()` 再 `return`），所以 `fuse()` 返回「是否允许升级」时必须把 SKIP 当作"允许"；第三次激进合成要 `!fuse_IV`，否则合出四级后还会继续梭哈浪费饰品。
- **治疗**：AALC 是先进治疗页点「全体治疗」，从第 5 圈起才用**治疗页**的经费栏判钱（商店页的经费栏位置不同，用错 ROI 会读到 0 然后永远不治疗），钱 < 100 就停，之后才逐个点 `heal_sinner`。
- **经费 OCR**：AALC 读不到钱会把裁剪区域放大一倍重试；脚本层没有图上采样接口，退化成「模板框 → 放大 12×8 → 放大 24×14 + 放宽阈值」三次尝试。
- **技能替换计数**：AALC 一个商店最多 3 次（`replacement < 3` 才在刷新后重跑），否则每次刷新都会再花一次替换。
- **公式合成轮换**：`fuse_system_gifts` 用 `fusion_pos` 在成品图标间轮换，失败就换下一个，全试完才收手；固定点同一个图标会一直等到确认框超时。
- **升级逐个升**：`enhance_gifts` 是一轮把可见列表逐个升完再停，不是升一件就 break。
- **第二体系时序**：`second_system_setting == 0` 表示"本体系合出四级之后"才开始买/升第二体系，所以 `fuseIvDone` 是门控；两个体系都合出四级后（`fuse_second_IV`）激进合成收手。

仍留着的差异（写进 README「还差什么」表）：白棉花的 OCR 跳过、`sort_points` 的坐标网格缓存（现在每轮重新识别，买过的东西从架子上消失所以不会重复点）、`return_assets`（上游素材本身不存在，那一步在 AALC 里是空操作）。
## 13. 观测 EGO 饰品：一张 bbox 图 + 一格网格

AALC 的 `select_observe_ego_gift`（`mirror.py:965`）是少数几个完全靠「bbox 素材 + 固定网格」定位的界面，正好适合搬到脚本里：

- `observe_bbox.png` 是整页范围的非黑区域，只用来限定「体系图标在哪一行」；其中的 `burn.png` / `bleed.png` 两个图标既做识别锚点，也用来算体系之间的间距 `system_distance = bleed.x - burn.x`。
- 体系顺序按 `tasks/__init__.py` 的 `observe_system`：burn/bleed/tremor/rupture/**sinking/poise**/charge/slash/pierce/blunt，注意 sinking 与 poise 在观测页上与商店页顺序互换，`general` 排在第 11 个。
- 格点：`(level.x, level.y + 80*scale)` 是首格，列距 `165*scale`、行距 `160*scale`；目标在 `gift_box_bbox` 下沿之外时，按 `300*scale` 分步向上拖，最后点的 y 要减去累计拖动距离——否则会点到拖动后的错误格子。
- 收尾三步是「选择 → 获得饰品确认 ×5 → 拒绝领取」；两个按钮都是点 bbox 中心（AALC 用 OCR 只做存在性判断，坐标照样取 bbox 中心），所以脚本只需要 `readText` 判存在，不需要文字坐标。
- 素材统一按 720p 缩放：bbox 素材是 1440 高基准，`SCALE = 0.5`；`gift_box` 在 720p 下是 `(78,225,792,624)`。
## 14. 奖励卡、白棉花与清队兜底：三个小改动

- **奖励卡四套顺序**：AALC 的 `reward_card_model` 是 4 个模型（0 星光优先、1 星光-经费、2 经费优先、3 饰品优先），每套都是 5 张牌的顺序表；本项目此前只写了 3 个 case 且漏了 `gain_starlight` / `gain_ego_resource`。现在 `reward_cards` 的 4 个 case 严格对应模型 0~3，每条 `next` 末尾都保留「选第一张」兜底，避免全部不匹配时卡死。
- **流血体系的白棉花**：AALC `in_shop.py:236` 在买体系饰品前后做整屏 OCR，命中「白棉花 / white / gossypium」就点空白取消，不买那一格。脚本层没有整屏 OCR 的默认 ROI，所以显式传 `ScriptRect(0, 0, screenWidth, screenHeight)`；选项 `shop_skip_whitegossypium` 默认开，导入时由 `not_skip_whitegossypium` 取反映射。
- **清队兜底**：AALC `clean_team()` 在清队确认按钮不在时会点 `identify` 下方 600×scale（720p = 300px）把面板收起来。管线里新增 `TeamClearPanelTail` 节点做同一件事，挂在 `TeamFormationStart` 的 `next` 第二位，只尝试一次就转去点选罪人，避免和 `ClearCurrentTeam` 形成死循环。
## 15. 编队页与战败重开：把 first_battle 语义翻成脚本状态

AALC 的编队逻辑有三层语义（`team_formation.py` + `mirror.py:370-401` + `re_start`）：

1. 本局第一次进编队页：清队 → 按顺位点 12 个格子 → 点出战；
2. 之后每次再进编队页：先看还能打几个人——`12/11/10_sinner_live_assets` 任一在场就算够，`none_sinner_assets` 说明队伍被清空（重编即可），否则整屏 OCR 找 `a/b`（分母 5..9、分子 ≥5）判定；
3. 不够 5 人且不是首战：`re_start()` —— 依次点「转窗口并放弃→确认」「放弃」「投降」「设置」，点到确认后整局重开。

移植时的两个关键点：

- **坐标就是管线里那套**：`SelectSinnerSlot1..12` 的 `target_offset` 相对识别框左上角，而 AALC 的 `find_element` 返回中心点；两者换算后点的是同一个像素（中心 + (-900, 65)），所以脚本直接用 `centerX/centerY` 加偏移，不需要为管线再算一遍。
- **first_battle 用两个开关表达**：`formed`（本局按配置编过队）与 `formedOnce`（本局至少编过一次，才有资格做存活检查）。重开成功后两者都清掉，等价于 AALC 的 `first_battle = True`，避免「重开 → 立刻又判定不够 → 再重开」的死循环。

顺位来自编队页的 12 个 slot option（`TeamOptionScope.ORDER_SLOT_NAMES`），映射方式和 `TeamPreset.toggleSinner` 一致：点选顺序即出战顺位。`re_formation_each_floor` 对应 `KEY_REFORM`，打开后每层都重编一次。
## 16. 领奖：把 AALC 的领奖循环搬成脚本

AALC 的领奖（`mirror.py:538-698`）不是「点一下领取就完事」，而是一个优先级很高的状态机：

1. 先看特殊界面：战果确认、已领取、领取确认——按顺序点掉；
2. 判这次有没有打完：没认出 `complete_mirror_100%` 就标记 `failed`，认出 100% 或 `clear` 就清掉；
3. `failed` 时点「放弃领取」，成功时点「领取奖励」——接着处理每周加成（`no_weekly_bonuses` 全点掉、`hard_mirror_single_bonuses` 只留一个）、OCR 读通行证经验、点脑啡肽选项；
4. 回到主页（`home/drive.png`）就结束。

移植时换掉的两处平台级动作：`base/waiting_assets.png` 是整屏截图（2560×1440），拿来做模板匹配等于比整屏，手机端不现实，改成「进循环先固定等 1.5 秒」；`retry()`、`back_init_menu()` 是 PC 端的重开/回主界面兜底，这里换成日志 + 交还管线。

## 17. 战斗里的死局：全员阵亡、状态栏、阵亡认输

`battle.py:262-330` 的三段处理补进了 `MirrorBattleScript`：

- **状态栏被折叠**（`view_status_assets`）：点它右上 `+50/-250`（720p）的位置展开；
- **全员阵亡**（`dead_all`，34×179 的竖条，屏幕上会出现 3 个）：选纵坐标中间那个，再点结算确认；
- **有人阵亡且没开 `fight_to_last_man`**：以 `dead.png` 为中心 OCR ±(50,15)，读到「阵亡 / dead」就认输——点认输、点不到就先点设置再点认输，还不行点空白戳弹窗，最多 6 轮。

`dead_all_confirm_assets.png` 上游是整屏图，和 waiting 一样没法当模板用，这里靠「选完复活选项后界面自己回到战斗 / 结算」来推进，而不是去匹配那张整屏图。