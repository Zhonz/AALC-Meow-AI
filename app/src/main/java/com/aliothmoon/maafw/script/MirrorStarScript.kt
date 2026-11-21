package com.aliothmoon.maafw.script

/**
 * 开局星光加成：把 AALC `tasks/mirror/mirror.py:793-871 enter_mir_with_star()` 搬过来。
 *
 * 原版用「星光币」图标定位整个 5×2 的加成卡网格：
 *
 * - 第一格 = 星光币中心 - (1800*s, -300*s)；列距 400*s，行距 480*s
 * - 期望等级 = opening_bonus[i]（0 不选 / 1 点一次 / 2 再点左侧「+」 / 3 再点右侧「++」）
 * - 星光换钱：use_starlight 为真点「不转换」，否则点「转换成经费」
 * - 最后点「确认」→「进入」（dreaming_star_enter）
 *
 * s = 720/1440 = 0.5，所以这里所有偏移都是上面数值的一半。
 */
object MirrorStarScript : ScriptTask {
    override val id: String = "mirror.star"
    override val entry: String = "MirrorCheckDreamingStar"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 6

    private const val COINS = "mirror/road_to_mir/dreaming_star/coins_assets.png"
    private const val NO_CONVERT = "mirror/road_to_mir/dreaming_star/no_convert_star_to_cost_assets.png"
    private const val CONVERT = "mirror/road_to_mir/dreaming_star/convert_star_to_cost_assets.png"
    private const val CONFIRM = "mirror/road_to_mir/dreaming_star/select_star_confirm_assets.png"
    private const val ENTER = "mirror/road_to_mir/dreaming_star/dreaming_star_enter_assets.png"
    private const val LEVEL_1 = "mirror/road_to_mir/dreaming_star/level_one_bonus_assets.png"
    private const val LEVEL_2 = "mirror/road_to_mir/dreaming_star/level_two_bonus_assets.png"

    /** 初始饰品页的「流血」分类图标：看到它说明已经离开星光页（AALC 用它当退出条件） */
    private const val INIT_GIFT_MARK = "mirror/road_to_mir/bleed_gift_assets.png"

    /** 十张加成卡的目标等级，逗号分隔的 0..3；空 = 不选 */
    const val KEY_OPENING_BONUS = "openingBonus"

    /** 星光是否留着换加成（true 点「不转换」），对应 AALC 的 use_starlight */
    const val KEY_USE_STARLIGHT = "useStarlight"

    private const val SCALE = 0.5
    private const val FIRST_X_OFFSET = -1800 * SCALE
    private const val FIRST_Y_OFFSET = 300 * SCALE
    private const val COLUMN_STEP = 400 * SCALE
    private const val ROW_STEP = 480 * SCALE
    private const val PLUS_OFFSET = 80 * SCALE
    private const val PLUS_Y_OFFSET = 320 * SCALE

    private const val MAX_LOOPS = 30

    /** AALC 的 TeamSetting.opening_bonus 默认值：前四张一级、其余不选 */
    private val DEFAULT_BONUS = listOf(1, 1, 1, 1, 0, 0, 0, 0, 0, 0)

    @Volatile
    private var bonusApplied = false

    override fun resetSession() {
        bonusApplied = false
    }

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val parsed = (params[KEY_OPENING_BONUS] ?: "")
            .split(',', ' ', ';')
            .mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 3) }
        // 没配就按 AALC 的 TeamSetting 默认值（前四张一级）
        val bonuses = if (parsed.isEmpty()) DEFAULT_BONUS else parsed
        val useStarlight = ScriptParams.flag(params, KEY_USE_STARLIGHT, default = true)
        host.log(
            "[星光] 接管：期望等级=" + (bonuses.joinToString(",").ifEmpty { "未配置" }) +
                "，星光" + (if (useStarlight) "留着换加成" else "转换成经费"),
        )

        var loops = 0
        var idle = 0
        while (!host.cancelled && loops < MAX_LOOPS) {
            loops++
            // 已经走到初始饰品页：星光流程结束（AALC 用同一个退出条件）
            if (host.find(INIT_GIFT_MARK, 0.75) != null) {
                host.log("[星光] 已进入初始饰品页，交还给管线")
                return
            }
            // 星光换钱：AALC 每一轮都先处理这一处
            if (host.clickTemplate(if (useStarlight) NO_CONVERT else CONVERT, 0.8)) {
                host.sleep(800)
                idle = 0
                continue
            }
            if (!bonusApplied && applyBonuses(host, bonuses)) {
                bonusApplied = true
                idle = 0
                continue
            }
            if (host.clickTemplate(CONFIRM, 0.8)) {
                host.log("[星光] 已确认加成")
                host.sleep(1_000)
                idle = 0
                continue
            }
            if (host.clickTemplate(ENTER, 0.8)) {
                host.sleep(1_200)
                idle = 0
                continue
            }
            idle++
            if (idle >= 3) {
                host.log("[星光] 连续 3 轮没有可点的按钮，交还给管线")
                return
            }
            host.sleep(600)
        }
        host.log("[星光] 达到循环上限，交还给管线")
    }

    /** 按 5×2 网格点卡；返回是否真的点到了（点不到就交回主循环重识别） */
    private fun applyBonuses(host: ScriptHost, bonuses: List<Int>): Boolean {
        val coins = host.find(COINS, 0.75) ?: return false
        val firstX = coins.centerX + FIRST_X_OFFSET.toInt()
        val firstY = coins.centerY + FIRST_Y_OFFSET.toInt()
        var clicked = 0
        for (i in 0 until 10) {
            if (host.cancelled) return clicked > 0
            val level = bonuses.getOrNull(i) ?: 0
            if (level <= 0) continue
            val x = firstX + (i % 5) * COLUMN_STEP.toInt()
            val y = firstY + (i / 5) * ROW_STEP.toInt()
            host.click(x, y)
            host.sleep(350)
            clicked++
            if (level == 2) {
                host.click(x - PLUS_OFFSET.toInt(), y + PLUS_Y_OFFSET.toInt())
                host.sleep(350)
            } else if (level == 3) {
                host.click(x + PLUS_OFFSET.toInt(), y + PLUS_Y_OFFSET.toInt())
                host.sleep(350)
            }
        }
        host.log("[星光] 已按配置点选 " + clicked + " 张加成卡")
        return clicked > 0
    }
}
