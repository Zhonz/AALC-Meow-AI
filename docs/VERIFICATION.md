# 验证记录 (VERIFICATION.md)

本文件记录 2026-11 这次「跟进 AALC V1.6.2 + MaaFramework v5.16.1」时，实际做过的验证、验证方法与结果。
目的：让后来的人知道**哪些结论有证据**、**哪些还是待真机确认**，避免把「脚本跑通」当成「真机可用」。

## 1. 验证环境

| 项目 | 值 |
| --- | --- |
| 引擎 | MaaFramework v5.16.1（`pip install maafw==5.16.1`，manylinux2014_aarch64 wheel） |
| 绑定 | `maa.resource.Resource` / `maa.tasker.Tasker` / `maa.controller.CustomController` |
| 宿主机 | Android 手机上的 Ubuntu 24.04 (proot, aarch64)，无 Android SDK/NDK，无 adb |
| 语料 | AALC 上游 `assets/images/default/**` 里的 241 张 2560×1440 真机截图，按 0.5x (LANCZOS) 归一化到 1280×720 |

> 上游这些截图是**打过码的**：除目标元素外整屏涂黑。所以它们只能证明「某元素在屏幕上的真实像素位置」，
> 不能用来验证「整屏里只有它能匹配上」。这一点在第 4 节单独说明。

## 2. 真实引擎加载

```python
res = Resource()
res.post_bundle('assets/resource').wait().succeeded   # -> True
tasker = Tasker(); tasker.bind(res, ctrl)
```

引擎能加载 pipeline + 模板 + OCR 模型（det.onnx 3.8M / rec.onnx 20M），说明本次改动后的资源包在 v5.16.1 上
可被正常解析（资源解析失败会直接 `succeeded=False`）。

## 3. 模板与真实像素位置对照

用引擎对每个节点做单节点识别（`post_task(name, {name: {'action': 'DoNothing', 'next': [], 'on_error': []}})`），
喂对应的真机截图，命中框与上游截图里元素的像素位置完全一致：

| 节点 | 模板 (720p 尺寸) | 命中框 (x, y, w, h) | 分数 |
| --- | --- | --- | --- |
| PassSelectDaily | pass/daily_assets.png (23×92) | (260, 212, 23, 92) | 0.997 |
| PassSelectWeekly | pass/weekly_assets.png (21×86) | (261, 357, 21, 86) | 0.997 |
| PassSelectSeasonal | pass/seasonal_assets.png (23×92) | (260, 497, 23, 92) | 0.997 |
| PassClickBattlePass | pass/battle_pass_assets.png (117×25) | (178, 45, 117, 25) | 0.997 |
| PassClaimAllRewards | pass/claim_all_assets.png (80×23) | (795, 577, 80, 23) | 0.997 |
| PassEnterMissions | pass/missions.png (100×24) | (318, 44, 100, 24) | 0.996 |
| MailClaimAll | mail/claim_all.png (170×39) | (873, 526, …) | 0.999 |
| MailClickIcon | home/mail.png (34×25) | (1103, 112, …) | 0.991 |
| MailClose | mail/close.png (57×27) | (749, 539, …) | 0.996 |
| MailConfirmPrize | mail/confirm.png (44×29) | (713, 570, …) | 0.993 |

结论：0.5x 重导链路是对的——模板来自 2560×1440 截图，缩到 720p 后能在同一位置命中，roi 坐标（720p 基准）也随之成立。

## 4. 三个竖排 tab 的互相命中（本次修掉的真问题）

通行证任务页左侧是**同一列竖排**的三枚 tab：

- 日常 y≈212–304
- 周常 y≈357–443
- 赛季 y≈497–589
- 三列纵向间距约 144px，模板高度 92/86px

不带 roi 时，三枚模板在同一屏幕上会互相命中：在只贴了「日常任务」的测试画布上，
`PassSelectWeekly` 也能在 (101, 303) 以 ≥0.7 命中；把整条 pipeline 跑起来时引擎直接报
`error handling loop detected [node.name=PassSelectWeekly]`（`PassSelectDaily` 与 `PassSelectWeekly`
互相当成对方的 on_error 回退目标，来回打转）。

修法：三枚 tab 各自加窄带 roi（[237,192,69,132] / [237,335,69,132] / [237,479,69,132]），
每条带子里只可能有一枚 tab。修完用三张真机截图交叉验证：

| 喂入截图 | Daily | Weekly | Seasonal |
| --- | --- | --- | --- |
| 日常截图 | ✅ (260,212) | 不命中 | 不命中 |
| 周常截图 | 不命中 | ✅ (261,357) | 不命中 |
| 赛季截图 | 不命中 | 不命中 | ✅ (260,497) |

## 5. 领取按钮（pass_coin）的 roi

四枚领取节点（日常/周常/赛季/第二页）都用 `pass/pass_coin.png`。这枚 32×50 的小图标在上游只是图标本身，
**没有一张真机截图包含它**（在 241 张语料里最高分只有 0.498），所以它的位置无法从语料确认。
给它加了 `roi [330, 95, 950, 590]`：只收右侧任务列表区，避开左侧 tab 列（x≤306）与顶栏/底栏，
降低「把 tab 当成领取按钮」的概率。贴图验证：coin 贴在 (900,300) 命中，贴在 (250,300)（tab 列）不命中。

## 6. 仍然没被验证到的部分

1. **整条流程在真机上跑通**：本机没有 adb / Shizuku，装不了 APK，也无法把真机画面喂给引擎。
2. **pass_coin 的真实坐标**：语料里没有它出现过的截图（见第 5 节）。
3. **赛季第二页的滑动距离**：来自上游 `dy = -5*174 = -870 @1440` → 720p 的 -435，没有真机回放证据。
4. **本地构建 APK**：需要 Android SDK 37 + NDK r27c（数 GB），`ENVIRONMENT.md` 明确写的是云端构建优先，
   本机不做。

### 6.1 全量模板 × 真机截图矩阵（105 × 241）

把管线里 105 张模板逐张放到 241 张 0.5x 真机截图上扫描（`cv2.matchTemplate`，
`TM_CCOEFF_NORMED`），结论：

- **98 张**在自己的真机截图上能命中（最高分普遍 0.99+），说明重导后的模板与真机像素一致。
- **7 张在任何截图里都没出现过**，真实坐标属于未知量，发布前必须在真机上采一次：

  `battle/gear_right.png`、`luxcavation/exp_enter.png`、`luxcavation/thread_consume.png`、
  `mirror/get_reward_card/gain_cost_and_ego.png`、`mirror/road_in_mir/shop.png`、
  `mirror/claim_reward/weekly_bonuses.png`、`pass/pass_coin.png`

- **确认类按钮之间互相相似**（游戏里本来就用同一套米色按钮）：`mail/confirm.png` 在另外四种
  确认弹窗上能到 0.97–0.99，`mirror/road_to_mir/enter_mirror_confirm.png`（阈值 0.75）在
  「退出镜牢确认」按钮上能到 **0.95**。这不是本次改动引入的问题——上游 AALC 同样靠「流程顺序」
  而不是「像素身份」区分确认按钮。本次复核了触发路径：`MirrorConfirmEnterMirror` 只挂在
  「点击进入镜牢」之后的 next 链上，那时不可能有退出确认弹窗，因此暂不改阈值（改高反而可能
  让真实命中掉到阈值以下，因为语料里没有这张确认弹窗的真机样本）。

因此发布前的最低要求仍然是：在开了 USB 调试/Shizuku 的真机上跑一次 `get_reward`，确认
「日常 → 周常 → 赛季（含第二页） → 战斗通行证 → 邮件」这条路走完且不卡循环。

## 7. 可重复跑的静态校验

```bash
python scripts/validate_pipelines.py        # 368 节点，0 错 0 警
python scripts/check_pipeline_schema.py     # 12 个管线文件 + 197 个覆盖片段 + 1 个接口清单
python scripts/check_i18n_strings.py        # 中英各 642 条
python scripts/verify_with_engine.py        # 真实引擎加载 pipeline + 模板 + OCR 模型
```

## 8. 镜牢商店交棒（handoff）的引擎级验证

商店逻辑跑在 Kotlin 脚本里（`script/MirrorShopScript.kt`），**没有任何 schema 管得到它**，只能拿
真实引擎 + 回放帧验证。方法：`CustomController` 的 `screencap` 返回一张 1280×720 的合成图，
`tasker.post_task('MirrorDungeonEntry', {})` 后看节点路径。

### 8.1 交棒节点可达

把 `mirror/shop/shop_coins_assets.png` 贴到 (500,100)（落在 `MirrorHandleShop` 的 roi 内）：

```
succeeded = True
节点路径 = ['MirrorDungeonEntry', 'MirrorCheckShopCoins', 'MirrorHandleShop']
```

即：跑进商店 → 识别到经费栏 → 停在交棒节点，且因为 `next` 为空，**任务正常成功结束**
（MaaRunner 就是靠这一点在中间插脚本）。

### 8.2 三个处理节点互不误触

| 喂入画面 | MirrorHandleShop | MirrorHandleRewardCard | MirrorHandleThemePackFeature |
| --- | --- | --- | --- |
| 只有地图（legend） | 不命中 | 不命中 | 不命中 |
| 商店（经费栏） | ✅ (500,100,46,44) | 不命中 | 不命中 |
| 奖励卡确认页 | 不命中 | ✅ (700,500,24,19) | 不命中 |
| 卡包选择页 | 不命中 | 不命中 | ✅ (1140,70,48,48) |

### 8.3 脚本本身仍然没法验证的部分

1. **点击之后的真实界面变化**：回放帧是静态的，脚本点完「治疗」「购买」之后游戏会换页，
   这部分只能真机看。
2. **经费 OCR 的真实读数**：合成图里的数字用的是游戏同类字体渲染，但真机上的数字排版、
   千位分隔符位置未必一致（`ScriptText.firstNumber` 已按 AALC 的对照表纠错，仍属未验证）。
3. **出售 / 合成的不可逆性**：本版只做到「按体系出售」，且默认关闭（`shop_do_not_sell` 默认 Yes）；
   合成 / 升级 / 技能替换这一版没做。
4. **一个任务里多次交棒**：`maxRounds=12` 的循环只在引擎级走过一次交棒，多次往返没有真机证据。

---

## 9. 镜牢寻路与战斗交棒（handoff）的引擎级验证

验证环境同第 1 节：本机 aarch64 + `pip install maafw==5.16.1` + 自写 `CustomController` 回放静态画布。

### 9.1 四个交棒节点都能被认到，且只有对应画面才认到

把模板贴到 1280×720 深色画布上，用 `MirrorDungeonEntry` 起任务，看节点路径：

| 画布内容 | 节点路径 | 结论 |
| :--- | :--- | :--- |
| 只有 legend（地图界面） | `MirrorDungeonEntry → MirrorCheckLegend → MirrorInDungeon → MirrorHandleRoad` | 寻路交棒可达 |
| 普通战斗按钮 | `MirrorDungeonEntry → MirrorHandleBattle` | 战斗交棒可达 |
| 链接战按钮 | `MirrorDungeonEntry → MirrorHandleBattle` | 同上 |
| 商店经费栏 | `MirrorDungeonEntry → MirrorCheckShopCoins → MirrorHandleShop` | 商店交棒没被抢 |
| 纯色画布 | `MirrorDungeonEntry` 后失败 | 不劫持 |

### 9.2 `Or` 内联识别的坑（这次踩到了）

第一版把两个战斗按钮写成 `any_of` 里的内联 `{"type":"TemplateMatch",...}`，结果该节点在**任何画面上都命中**：
纯色画布探针返回成功，地图画布的路径也被它截断。改成引用节点名（`any_of: ["MirrorBattleEntryNormal",
"MirrorBattleEntryChain"]`，两个名字各写一个普通节点）之后，纯色画布与 legend 画布都不再命中，
两个战斗按钮才命中。复现命令：`python3 /tmp/probe_battle.py`（探针脚本按节点名直接 `post_task`）。

### 9.3 静态核对

- 三个脚本里出现的所有模板名都能在 `assets/resource/image/` 找到（含新增的 `battle/gear_left.png`、
  `mirror/road_in_mir/risky_encounter.png`、`focused_encounter.png`）
- `MirrorHandleRoad` / `MirrorHandleBattle` / `MirrorHandleShop` 三个节点都是 `next: []`，
  `resumeEntry` 指向的 `MirrorDungeonEntry` 存在
- `validate_pipelines.py` 372 节点 0 错 0 警；`check_pipeline_schema.py` 12 管线 + 197 覆盖片段全过

### 9.4 仍然没验证到的

1. 脚本真实点击之后界面有没有变化（本机拿不到游戏画面）
2. 巴士拖动距离、三个候选点的偏移在国服/国际服不同分辨率下是否合适
3. 防御连线的落点与「技能格数」算得对不对（依赖 gear_left/gear_right 同时出现在画面上）
4. 一个任务里连续多次交棒（进地图 → 战斗 → 地图 → 商店）的稳定性，只做了单次交棒

## 10. 主题包与事件的引擎级验证

主题包：把 `mirror/theme_pack/feature_theme_pack_assets.png` 贴到画布 (1160,80)，`post_task('MirrorDungeonEntry')` 的节点路径是 `[MirrorDungeonEntry, MirrorCheckThemePack, MirrorHandleThemePackFeature]`。

第一次接的时候路径停在 `MirrorCheckThemePack` 之后一个失败节点——因为 `MirrorCheckThemePack.next` 只写了 `MirrorInDungeon`，而地图画布不满足 `MirrorInDungeon` 的 legend 识别。把 `MirrorHandleThemePackFeature` 插到 `MirrorCheckThemePack.next` 首位后，路径才通。

事件：`EventHandling` 的 6 个新节点只做了静态校验（`validate_pipelines.py` 378 节点 0 错、Schema 12 管线 + 197 覆盖片段全过）；没有把每个事件页模板都贴到画布上分别验证——事件是「一个界面多种按钮」的结构，单模板贴图只能证明那个模板能被认到，证明不了优先级顺序对不对，等真机反馈。

仍然未验证的四点同第 9.4 节：OCR 读数、点击后界面变化、不可逆操作判定、一个任务里多次交棒。

## 11. 领奖交棒与战斗死局处理（v0.6.6）

### 11.1 领奖链路可达

`tools/` 之外的临时脚本把 `mirror/claim_reward/clear_assets.png` 贴到 (300, 340)、`claim_rewards_assets.png` 贴到 (1030, 575)，
用真实引擎（maafw 5.16.1 + CustomController）跑 `MirrorCheckClaimClear`：

```
claim->handoff succeeded = True path = ['MirrorCheckClaimClear', 'MirrorHandleReward']
home-done succeeded = True path = ['MirrorRewardHomeDone']
```

说明结算页的 `clear_assets` 会走到交棒节点，脚本跑完后 `MirrorRewardHomeDone`（`home/drive.png`）能正常收尾。

### 11.2 仍然只能靠真机确认的部分

- 领取奖励之后的每周加成弹窗坐标、通行证经验的 OCR 读数；
- 全员阵亡时 `dead_all`（34×179 竖条）在真实画面里是否稳定出现 3 个、选中间那个之后的界面变化；
- 阵亡 OCR（`dead.png` 中心 ±(50,15)）在真实战斗画面里的识别率；
- 认输按钮在「设置」打开后的位置。