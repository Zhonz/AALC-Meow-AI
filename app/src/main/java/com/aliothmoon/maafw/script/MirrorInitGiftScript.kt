package com.aliothmoon.maafw.script

/**
 * 开局三件初始 EGO 饰品：把 AALC `tasks/mirror/mirror.py:884-963 select_init_ego_gift()` 搬过来。
 *
 * 原版流程：
 *
 * - 先点掉「获得 EGO 饰品」确认；若已经进入观测页/主题包页就收工
 * - 斩击/穿刺/打击三个体系在列表下方，先把列表向上拖 400（以 slash_gift_1 为锚）直到露出 blunt_gift_1
 * - 点本体系的分类图标（{system}_gift_assets.png），再按 start_gift 的顺序逐个点
 *   select_init_gift/{system}_ego_gift_{1,2,3}.png（阈值 0.8，回落 0.7，再回落锚点坐标）
 * - 锚点兜底：以「确认」按钮为锚，向左 340*s、向上 row_dy（{1:-490, 2:-330, 3:-170}）*s
 * - 最后点确认
 *
 * 注意 AALC 这里用的基准是 1080（`s = set_win_size / 1080`），不是 1440 —— 所以本文件
 * 的 SCALE 是 720/1080 = 2/3，与其他镜牢脚本的 0.5 不同。
 */
object MirrorInitGiftScript : ScriptTask {
    override val id: String = "mirror.initgift"
    override val entry: String = "MirrorHandleInitGift"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 4

    private const val CONFIRM = "mirror/road_to_mir/select_init_ego_gifts_confirm_assets.png"
    private const val GET_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"
    private const val THEME_MARK = "mirror/theme_pack/feature_theme_pack_assets.png"
    private const val OBSERVE_BLEED = "mirror/road_to_mir/observe_ego_gift/bleed.png"
    private const val OBSERVE_BURN = "mirror/road_to_mir/observe_ego_gift/burn.png"
    private const val SLASH_1 = "mirror/road_to_mir/slash_gift_1.png"
    private const val BLUNT_1 = "mirror/road_to_mir/blunt_gift_1_assets.png"

    /** 本体系分类图标：mirror/road_to_mir/{system}_gift_assets.png */
    private fun systemIcon(system: String) = "mirror/road_to_mir/" + system + "_gift_assets.png"

    /** 第 id 个初始饰品：mirror/road_to_mir/select_init_gift/{system}_ego_gift_{id}.png */
    private fun giftIcon(system: String, id: Int) =
        "mirror/road_to_mir/select_init_gift/" + system + "_ego_gift_" + id + ".png"

    /** 队伍体系（AALC 的 team_system / opening_items_system） */
    const val KEY_SYSTEM = "system"

    /** 三件饰品的点击顺序，逗号分隔的 1..3，默认 1,2,3 */
    const val KEY_ORDER = "order"

    /** AALC 这里按 1080 高为基准：720 / 1080 = 2/3 */
    private const val SCALE = 720.0 / 1080.0
    private const val ANCHOR_X_OFFSET = -340 * SCALE
    private const val ROW_1 = -490 * SCALE
    private const val ROW_2 = -330 * SCALE
    private const val ROW_3 = -170 * SCALE
    private const val DRAG_UP = 400

    private const val MAX_LOOPS = 30

    private val SLASH_LIKE = setOf("slash", "pierce", "blunt")

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val system = (params[KEY_SYSTEM] ?: "").trim().lowercase()
        val order = (params[KEY_ORDER] ?: "")
            .split(',', ' ')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id in 1..3 } }
            .ifEmpty { listOf(1, 2, 3) }
        host.log("[初始饰品] 接管：体系=" + system.ifEmpty { "未知" } + "，顺序=" + order.joinToString(","))

        var scrolled = false
        var systemSelected = false
        // 已经点过的第几件：再点一次会取消勾选，所以不能重复点
        val chosen = linkedSetOf<Int>()
        var loops = 0
        while (!host.cancelled && loops < MAX_LOOPS) {
            loops++
            // 拿到饰品后的确认弹窗，先点掉
            if (host.clickTemplate(GET_CONFIRM, 0.75)) {
                host.clickBlank(1)
                continue
            }
            // 已经离开初始饰品页（进入观测页或主题包页）
            if (host.find(THEME_MARK, 0.75) != null ||
                host.find(OBSERVE_BLEED, 0.75) != null ||
                host.find(OBSERVE_BURN, 0.75) != null
            ) {
                host.log("[初始饰品] 已离开初始饰品页，交还给管线")
                return
            }

            // 斩击/穿刺/打击的图标在列表下方：先把列表拖上来
            if (!scrolled && system in SLASH_LIKE) {
                var guard = 0
                while (!host.cancelled && guard++ < 6) {
                    val anchor = host.find(SLASH_1, 0.8) ?: break
                    host.swipe(anchor.centerX, anchor.centerY, anchor.centerX, anchor.centerY - DRAG_UP, 200)
                    host.sleep(500)
                    if (host.find(BLUNT_1, 0.8) != null) {
                        scrolled = true
                        break
                    }
                }
            }

            // 选中本体系分类（点一次就够）
            if (!systemSelected && system.isNotEmpty()) {
                if (host.clickTemplate(systemIcon(system), 0.8)) {
                    systemSelected = true
                    host.sleep(400)
                    continue
                }
            }

            // 按配置顺序点三件饰品，识别失败就退到锚点坐标
            var clickedAny = false
            for (id in order) {
                if (host.cancelled) break
                if (id in chosen) continue
                if (host.clickTemplate(giftIcon(system, id), 0.8)) {
                    chosen += id
                    clickedAny = true
                    host.sleep(350)
                    continue
                }
                if (host.clickTemplate(giftIcon(system, id), 0.7)) {
                    chosen += id
                    clickedAny = true
                    host.sleep(350)
                    continue
                }
                if (clickRowFallback(host, id)) {
                    chosen += id
                    clickedAny = true
                }
            }
            if (clickedAny) {
                host.sleep(400)
                continue
            }

            if (host.clickTemplate(CONFIRM, 0.75)) {
                host.log("[初始饰品] 已确认")
                host.sleep(1_000)
                continue
            }
            host.log("[初始饰品] 没有可点的目标，交还给管线")
            return
        }
        host.log("[初始饰品] 达到循环上限，交还给管线")
    }

    /** 锚点兜底：以确认按钮为锚点，按 AALC 的实测偏移点选第 [id] 行 */
    private fun clickRowFallback(host: ScriptHost, id: Int): Boolean {
        val confirm = host.find(CONFIRM, 0.75) ?: return false
        val dy = when (id) {
            1 -> ROW_1
            2 -> ROW_2
            3 -> ROW_3
            else -> return false
        }
        host.click(
            confirm.centerX + ANCHOR_X_OFFSET.toInt(),
            confirm.centerY + dy.toInt(),
        )
        host.sleep(300)
        return true
    }
}
