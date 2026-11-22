package com.aliothmoon.maafw.script

/**
 * 战后/事件后的「选 EGO 饰品卡」：把 AALC `tasks/mirror/mirror.py:1376-1545 acquire_ego_gift()`
 * 搬过来（原来管线只会点第一张，或者只认流血体系）。
 *
 * 原版流程：
 *
 * 1. 每张卡的识别框以「卡标记」为中心、(-50,-300)-(+450,+350)*scale 的矩形
 * 2. 开了白棉花保护（not_skip_whitegossypium=False）就把框里 OCR 到「白棉花 / white / gossypium」的卡跳过
 * 3. 框里 OCR 到「已持有 / Owned」的卡排到最后（少拿重复饰品）
 * 4. 本体系图标（acquire_ego_gift/{system}.png，阈值 0.85）命中的卡排最前；第二体系/第三体系插到它后面
 * 5. 用 ego_gift_get_bbox 附近的 OCR 判断这次能选几张（"2/2" → 2 张，"1/1" → 1 张，认不出就全选）
 * 6. 依次点卡，再点「选择」按钮
 *
 * 一张卡都留不下（全是白棉花）时走拒绝流程：refuse_gift_assets → refuse_gift_confirm_assets。
 */
object MirrorGiftPickScript : ScriptTask {
    override val id: String = "mirror.giftpick"
    override val entry: String = "MirrorHandleGiftPick"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 6

    /** AALC 这里按 1440 高为基准：720 / 1440 = 0.5 */
    private const val SCALE = 0.5

    private const val CARD = "mirror/road_in_mir/acquire_ego_gift_card.png"
    private const val SELECT = "mirror/road_in_mir/acquire_ego_gift_select_assets.png"
    private const val GET_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"
    private const val REFUSE = "mirror/road_in_mir/refuse_gift_assets.png"
    private const val REFUSE_CONFIRM = "mirror/road_in_mir/refuse_gift_confirm_assets.png"
    private const val COUNT_BBOX = "mirror/road_in_mir/ego_gift_get_bbox.png"

    /** 体系图标：mirror/road_in_mir/acquire_ego_gift/{system}.png */
    private fun systemIcon(system: String) = "mirror/road_in_mir/acquire_ego_gift/" + system + ".png"

    const val KEY_SYSTEM = "system"
    const val KEY_SECOND_SYSTEM = "secondSystem"
    const val KEY_SECOND_SETTING = "secondSetting"
    const val KEY_THIRD_SYSTEM = "thirdSystem"
    const val KEY_THIRD_SETTING = "thirdSetting"
    const val KEY_SKIP_WHITE = "skipWhite"

    private val CARD_LEFT = (-50 * SCALE).toInt()
    private val CARD_TOP = (-300 * SCALE).toInt()
    private val CARD_RIGHT = (450 * SCALE).toInt()
    private val CARD_BOTTOM = (350 * SCALE).toInt()
    private const val BBOX_GROW = (100 * SCALE).toInt()

    private val WHITE = listOf("白棉花", "white", "gossypium")
    private val OWNED = listOf("已持有", "owned")

    private const val MAX_LOOPS = 12
    private const val STEP_WAIT = 800L

    private class Candidate(val x: Int, val y: Int, val priority: Int, val owned: Boolean)

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val system = ScriptParams.text(params, KEY_SYSTEM)?.lowercase() ?: ""
        val second = ScriptParams.text(params, KEY_SECOND_SYSTEM)?.lowercase() ?: ""
        val third = ScriptParams.text(params, KEY_THIRD_SYSTEM)?.lowercase() ?: ""
        val secondSetting = params[KEY_SECOND_SETTING]?.toIntOrNull() ?: 0
        val thirdSetting = params[KEY_THIRD_SETTING]?.toIntOrNull() ?: 0
        val skipWhite = ScriptParams.flag(params, KEY_SKIP_WHITE, default = true)
        // AALC：second_system_setting==0 一直算，==1 等本体系四级合出来（饰品卡这一处 AALC 的 0/1 与商店相反）
        val secondOn = second.isNotEmpty() && (secondSetting == 0 || MirrorShopScript.isFuseIvDone())
        val thirdOn = third.isNotEmpty() && (thirdSetting == 0 || MirrorShopScript.isExtraIvDone(third))
        host.log(
            "[饰品卡] 接管：体系=" + system.ifEmpty { "未知" } +
                (if (secondOn) "，第二体系=" + second else "") +
                (if (thirdOn) "，第三体系=" + third else ""),
        )

        var loops = 0
        while (!host.cancelled && loops < MAX_LOOPS) {
            loops++
            // 已经拿到手：确认弹窗点掉就收工
            if (host.clickTemplate(GET_CONFIRM, 0.75)) {
                host.sleep(STEP_WAIT)
                return
            }
            val cards = host.findAll(CARD, 0.75, limit = 8)
            if (cards.isEmpty()) {
                if (loops >= 4) {
                    host.log("[饰品卡] 没认出饰品卡，交还给管线")
                    return
                }
                host.sleep(400)
                continue
            }
            val candidates = cards.mapNotNull { card ->
                val roi = ScriptRect(
                    card.box.centerX + CARD_LEFT,
                    card.box.centerY + CARD_TOP,
                    (CARD_RIGHT - CARD_LEFT).toInt(),
                    (CARD_BOTTOM - CARD_TOP).toInt(),
                )
                val lines = host.readText(roi)
                if (skipWhite && lines.any { line -> WHITE.any { line.contains(it, ignoreCase = true) } }) {
                    host.log("[饰品卡] 跳过白棉花")
                    return@mapNotNull null
                }
                val owned = lines.any { line -> OWNED.any { line.contains(it, ignoreCase = true) } }
                val priority = when {
                    system.isNotEmpty() && host.find(systemIcon(system), 0.85, roi) != null -> 0
                    secondOn && host.find(systemIcon(second), 0.85, roi) != null -> 1
                    thirdOn && host.find(systemIcon(third), 0.85, roi) != null -> 2
                    else -> 3
                }
                Candidate(card.box.centerX, card.box.centerY, priority, owned)
            }
            if (candidates.isEmpty()) {
                host.log("[饰品卡] 全是白棉花，走拒绝")
                refuse(host)
                return
            }
            val ordered = candidates.sortedWith(compareBy({ it.priority }, { it.owned }))
            val picks = detectPickCount(host).coerceIn(1, ordered.size)
            host.log("[饰品卡] 选 " + picks + " 张，优先级=" + ordered.map { it.priority }.joinToString(","))
            for (i in 0 until picks) {
                if (host.cancelled) return
                host.click(ordered[i].x, ordered[i].y)
                host.sleep(350)
            }
            if (!host.clickTemplate(SELECT, 0.8)) {
                host.log("[饰品卡] 没找到「选择」按钮，交还给管线")
                return
            }
            host.sleep(1200)
            return
        }
    }

    /** 这次能选几张：AALC 在 ego_gift_get_bbox 附近 OCR "2/2" / "1/1"，认不出就全选 */
    private fun detectPickCount(host: ScriptHost): Int {
        val bbox = host.find(COUNT_BBOX, 0.8) ?: return Int.MAX_VALUE
        val roi = bbox.box.grow(BBOX_GROW, BBOX_GROW)
        val text = host.readText(roi, 0.3).joinToString(" ").replace(" ", "")
        if (text.isEmpty()) return Int.MAX_VALUE
        if (text.contains("/2") || text.contains("2/2")) return 2
        if (text.contains("/1")) return 1
        return Int.MAX_VALUE
    }

    private fun refuse(host: ScriptHost) {
        host.clickTemplate(REFUSE, 0.75)
        host.sleep(STEP_WAIT)
        host.clickTemplate(REFUSE_CONFIRM, 0.75)
        host.sleep(STEP_WAIT)
    }
}
