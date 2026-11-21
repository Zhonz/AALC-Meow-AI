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
- **编队页**：新增第 5 个主 tab「编队」，按 AALC 的编队卡把队伍体系 / 商店策略 / 奖励卡 / 开局加成 / 两个战斗开关集中到「每支编队配一次」（1..20 队，可写备注名）；任务里只选「用哪支编队」，运行期由 `TeamOptionScope.mergedValues` 唯一合并，旧配置不改也照原样跑
- **编队像 AALC 那样点选**：罪人是固定的 2 行 × 6 列宫格（AALC 头像），**点选顺序就是出战顺位**——第 1 个点的是 1 号位，前 6 位出战、其余标「待命」，头像上带顺位徽标；点掉再点会排到队尾，也可以一键清空
- **导入 AALC 编队文件**：编队页右上角「导入」直接读电脑版 AALC「导出队伍设置」的 `.yaml`（自带 YAML 子集解析，不引依赖），按 `sinner_order` 还原顺位，并把 AALC 的队伍设置逐项翻成本 PI 的选项——体系、奖励卡、开局加成 10 张卡的等级、星光留不留、商店策略（出售/合成/四级优先）、十个体系的弃置开关、治疗/购买/出售/升级四个「不要做」、只激进/不合成体系/只合成体系/激进也升级/激进保留体系、合成四级后的四种走向、购物策略 0..5、第二体系的体系/时机/购买/升级、刷新次数、技能替换与模式、按层跳过商店、第 N 层切困难；解析前先弹预览（出战顺序、待命、套用了几项、忽略了几项）
- **奖励卡四套顺序**：奖励卡：按 AALC `reward_card_model` 的 0~3 号模型给出四套顺序（星光优先 / 星光-经费 / 经费优先 / 饰品优先，每套都把「第一张」当最后兜底）
- **编队与战败重开交给脚本**：认到镜牢编队页就把执行权交给 `script/MirrorTeamScript.kt`——本局第一次进来清队并按你的顺位点人、点出战；之后每次进编队页先看还能打几个人（12/11/10 存活头像 + OCR `a/b`，≥5 人继续），不够就按 AALC 的 `re_start` 点「放弃并确认」重开整局，重开后重新编队。`每层重新编队` 打开后每层都重编一次
- **镜牢商店交给脚本**：新增「交棒」机制——管线跑到 `MirrorHandleShop` 就把执行权交给特权进程里的 Kotlin 脚本（`script/MirrorShopScript.kt`），按电脑版 AALC 的顺序**技能替换 → 治疗 → 出售 → 购买 → 刷新 → 合成 → 升级 → 离开**，干完回到 `MirrorDungeonEntry` 让管线继续。合成是 AALC 的三段式（激进凑 90% 梭哈四级 / 废品凑三件普通合成 / 按体系配方合成），升级走「强化」按钮，技能替换含超级商店的三处与按人格搜索；选项按 AALC 的语义拆细（共 24 项，见下表），`shop_strategy` 选「直接离开」等于 AALC 的 `ignore_shop`。细节也照抄了：治疗是「先全体、再从第 5 圈起按治疗页经费决定要不要逐个治疗」；经费 OCR 读不出来会换三种裁切重试并套 AALC 的纠错表；买三四级只在激进合成下、第二体系要等本体系四级；合成四级后按 `after_level_IV` 切换（含 `skip` 当店仍升级一次）；公式合成的成品图标失败就轮换下一个；升级一轮把可见列表逐个升完；技能替换一个商店最多 3 次；流血体系买饰品时先整屏 OCR，看到「白棉花 / white / gossypium」就不买那一格；清队确认没出现时会点一下编队锚点下方 300px 收面板，再继续点选
- **镜牢寻路交给脚本**：管线跑到 `MirrorHandleRoad` 交棒给 `script/MirrorRoadScript.kt`——先认地图右上 legend 确认在地图上，把巴士拖回固定高度，再对巴士右侧上/中/下三个候选按 AALC 的节点权重（商店/事件 3、普通战斗 2、精英 1、集中 0、认不出 -5）挑一个点，点完再点「进入」；全都不灵才回落到原来的盲点偏移节点
- **镜牢战斗交给脚本**：管线认到 `MirrorHandleBattle`（普通战斗或链接战按钮）就交棒给 `script/MirrorBattleScript.kt`：进入战斗 → 清空上次出战勾选 → 每回合「点胜率卡片 + 点右侧齿轮」（等价电脑版的 P+Enter）→ 结算/认输收尾。**第一回合全员防御**这个开关以前是假的，现在按 AALC 的 `_calculate_skills_position`（技能格 161px、间距 145px）算格子、逐格点防御再按住拖连线，真的会执行
- **镜牢主题包按权重挑**：认到主题包选择页就交棒给 `script/MirrorThemePackScript.kt`——找出三张卡包的标记图标，按图标裁出名字区做 OCR，用 AALC `theme_pack_list*.yaml` 的关键词权重打分（中英各一份），最高分没到优选阈值就点刷新（最多 3 次），最后选最高分那张；认不出卡包才回落到原来的盲点
- **镜牢事件补齐**：事件界面新增 6 个识别（未知事件、获得 EGO、优势判定、判定结果、执行判定、跳过），按「未知 → 增益 → 判定 → 原有五档 → 继续」的顺序找，不再只认原来那几个「很好/好/普通」按钮；认到事件界面就交棒给 `script/MirrorEventScript.kt`，按 AALC 的按钮优先级（继续类 → 跳过 → 选项 → 未知 → 增益/判定 → 判定子流程 → 饰品确认）循环点到没得点为止
- **开局星光交给脚本**：认到星光加成页（`coins_assets.png`）就交棒给 `script/MirrorStarScript.kt`——按 AALC 的 5×2 网格（星光币中心 -900,+150 起步，列距 200、行距 240）点 10 张加成卡，等级 2 再点左侧「+」、3 点右侧「++」，并按 `use_starlight` 决定「把星光换成经费」还是「留着买加成」，点确认后进镜牢
- **困难难度与按层收工**：卡包页认楼层徽标（红点个数 = 当前层），按「第 N 层起切困难」自动点难度切换；`floor_3_exit` 或「第 N 层收工」到点后点弃权并确认；商店支持「这些层不进店」（对应 AALC 的 `ignore_shop`）
- **观测 EGO 饰品交给脚本**：开了观测并配了想观测的饰品时，游戏进镜牢会先弹「选择观测饰品」页——认到左上体系图标区里的流血图标就交棒给 `script/MirrorObserveScript.kt`，按 AALC 的 grid（首格在等级图标下方 40px，列距 82.5、行距 80，越界按 150px 分步上拖）逐个点选配置的饰品，再点「选择」→确认→「拒绝领取」（AALC 的 select_observe_ego_gift）。饰品列表用 AALC 的 `体系_等级_行_列` 写法（如 `bleed_3_3_7`），编队页可填、导入编队文件时从 `observe_ego_gift_selected` 自动带过来
- **资源热更新**：App 启动时读仓库根的 `pi-manifest.json`，有新版就下载 `pi.zip`、校验 sha256、原子替换旧资源并自动重载——上游换了图/流程不必等下一次发 APK；开关在设置页，失败一律保留旧版。发布链路见 `.github/workflows/publish-pi.yml` 与 [docs/RELEASING.md](docs/RELEASING.md) 第七节

### 和电脑版 AALC 还差什么（不做假开关）

镜牢商店这条线已经用脚本补上了 AALC 的整套商店流程：**技能替换 / 治疗 / 按体系出售 / 购买 / 关键词与普通刷新 / 三段式合成 / 升级**。仍然缺的，这里**不摆成看着能点、实际不生效的开关**，而是在编队页底部列出说明，导入 AALC 配置时也明确忽略：

| AALC 有、这里没有 | 为什么 |
| :--- | :--- |
| 弃置体系的坐标数据库（按坐标逐个弃置、部分滚动细节） | 现在按「体系图标 → 点选 → 确认」做，AALC 那份饰品坐标库没有移植；保护第二体系四级、must_be_abandoned 19 件、列表滚动都已实现 |
| YOLO 全图寻路（`search_road_from_road_map`） | 上游靠 `assets/model/best.onnx` 识别全图节点与线路求最短路；手机端只移植了 default distance 那套（认三个候选节点），没有 YOLO 模型与线路图 |
| 编队码自动组队（team_code） | 需要抓取/粘贴编队码的独立流程 |
| 每层重新编队（re_formation_each_floor） | 管线目前只在进镜牢时编一次队，层间重新进编队页还没接 |
| 战斗内的死局处理（全员阵亡的中间选项、连续防御回合数、失败 5 次重开） | 战斗脚本已按胜率/防御/三技能打完整回合，但阵亡确认与重开依赖真实战斗画面校准 |
| 小指良单通（defense_for_solo 的连续防御） | 同上，需要真机回合序列 |

导入时这些字段会被统计成「已忽略 N 项」，其余能映射的项照常生效。

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
