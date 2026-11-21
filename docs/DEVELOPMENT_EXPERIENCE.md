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
