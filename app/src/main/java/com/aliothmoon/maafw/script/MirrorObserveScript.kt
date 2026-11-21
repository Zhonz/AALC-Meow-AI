package com.aliothmoon.maafw.script

import com.aliothmoon.maafw.script.ScriptHost.Companion.DEFAULT_THRESHOLD

/**
 * 观测 EGO 饰品选择（AALC `mirror.py:965 select_observe_ego_gift`）
 *
 * 进入镜牢后如果开了"观测"并且配了想要的饰品，游戏会先弹一个"选择观测饰品"页：
 * 顶部是体系图标，下面是该体系的饰品格子。这一页只出现一次，选完会给三件饰品加成。
 *
 * 坐标系：AALC 的 bbox 素材是 1440 高基准，本项目截图是 720p，所以 SCALE = 0.5。
 */
object MirrorObserveScript : ScriptTask {
    override val id: String = "mirror.observe"
    override val entry: String = "MirrorHandleObserve"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 4

    const val KEY_SELECTED = "observeSelected"

    private const val SCALE = 0.5

    private const val BURN = "mirror/road_to_mir/observe_ego_gift/burn.png"
    private const val BLEED = "mirror/road_to_mir/observe_ego_gift/bleed.png"
    private const val LEVEL_I = "mirror/road_to_mir/observe_ego_gift/Level_I.png"
    private const val LEVEL_II = "mirror/road_to_mir/observe_ego_gift/Level_II.png"
    private const val LEVEL_III = "mirror/road_to_mir/observe_ego_gift/Level_III.png"
    private const val LEAVE_CONFIRM = "mirror/shop/leave_shop_confirm_assets.png"
    private const val GIFT_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"

    /** observe_bbox.png 的非黑区域（×0.5）：体系图标所在的横条 */
    private val OBSERVE_ROI = ScriptRect(0, 2, 782, 227)

    /** gift_box_bbox.png 的非黑区域（×0.5）：饰品格子区，用来判断要不要向上拖动 */
    private val GIFT_BOX = ScriptRect(78, 225, 792, 624)

    /** select_gift_bbox.png / reject_gift_bbox.png：底部两个按钮，AALC 也是点这俩的中心 */
    private val SELECT_ROI = ScriptRect(1018, 552, 1176, 594)
    private val REJECT_ROI = ScriptRect(870, 554, 974, 588)

    private const val FIRST_GIFT_DY = 80 * SCALE
    private const val GIFT_COL_STEP = 165 * SCALE
    private const val GIFT_ROW_STEP = 160 * SCALE
    private const val DRAG_STEP = 300 * SCALE
    private val DRAG_INSET = (100 * SCALE).toInt()
    private const val MAX_LOOPS = 40

    /** AALC tasks/__init__.py 的 observe_system：sinking 与 poise 在观测页上互换 */
    private val SYSTEM_INDEX = mapOf(
        "burn" to 0,
        "bleed" to 1,
        "tremor" to 2,
        "rupture" to 3,
        "sinking" to 4,
        "poise" to 5,
        "charge" to 6,
        "slash" to 7,
        "pierce" to 8,
        "blunt" to 9,
        "general" to 10,
    )

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val selected = parse(params[KEY_SELECTED])
        if (selected.isEmpty()) {
            host.log("[观测] 没配置观测饰品，交还给管线")
            return
        }
        val burn = host.find(BURN, DEFAULT_THRESHOLD, OBSERVE_ROI) ?: run {
            host.log("[观测] 没识别到观测页的体系图标，交还给管线")
            return
        }
        val bleed = host.find(BLEED, DEFAULT_THRESHOLD, OBSERVE_ROI) ?: return
        host.log("[观测] 接管：要选 " + selected.size + " 件")

        val distance = bleed.centerX - burn.centerX
        if (distance <= 0) {
            host.log("[观测] 体系图标间距异常（" + distance + "），退出观测页")
            finish(host)
            return
        }

        for (gift in selected) {
            if (host.cancelled) return
            val index = SYSTEM_INDEX[gift.system] ?: continue
            // AALC 先点另一个体系再点回来，重置列表
            host.click(burn)
            host.sleep(400)
            host.click(bleed)
            host.sleep(400)
            host.click(burn.centerX + (distance * index).toInt(), burn.centerY)
            host.sleep(600)
            val level = findLevel(host, gift.level)
            if (level == null) {
                host.log("[观测] 找不到 " + gift.system + " 的 " + gift.level + " 级图标，跳过")
                continue
            }
            selectGift(host, level, gift.row, gift.col)
            host.sleep(400)
        }

        finish(host)
    }

    private fun findLevel(host: ScriptHost, level: Int): ScriptMatch? {
        val template = when (level) {
            1 -> LEVEL_I
            2 -> LEVEL_II
            else -> LEVEL_III
        }
        host.find(template, 0.85)?.let { return it }
        var drags = 0
        while (!host.cancelled && drags < 5) {
            drags++
            val fromY = GIFT_BOX.y + GIFT_BOX.h - DRAG_INSET
            val dragX = GIFT_BOX.x + GIFT_BOX.w - DRAG_INSET
            host.swipe(dragX, fromY, dragX, fromY - GIFT_BOX.h / 2, 1500)
            host.sleep(1500)
            host.find(template, 0.85)?.let { return it }
        }
        return null
    }

    /**
     * 点格子：首格在等级图标下方 80*scale，列距 165、行距 160；
     * 目标在格子区下方时先按 300*scale 往上拖，再点（坐标要减掉拖动距离）。
     */
    private fun selectGift(host: ScriptHost, level: ScriptMatch, row: Int, col: Int) {
        val x = level.centerX + ((col - 1) * GIFT_COL_STEP).toInt()
        val y = level.centerY + FIRST_GIFT_DY.toInt() + ((row - 1) * GIFT_ROW_STEP).toInt()
        val bottom = GIFT_BOX.y + GIFT_BOX.h
        if (y < bottom) {
            host.click(x, y)
            return
        }
        var remaining = y - bottom
        val fromY = GIFT_BOX.y + GIFT_BOX.h - DRAG_INSET
        val dragX = GIFT_BOX.x + GIFT_BOX.w - DRAG_INSET
        var total = 0
        while (remaining > 0 && !host.cancelled) {
            val step = minOf(DRAG_STEP, remaining.toDouble()).toInt()
            host.swipe(dragX, fromY, dragX, fromY - step, 1500)
            host.sleep(1200)
            total += step
            remaining -= step
        }
        host.click(x, y - total)
    }

    /** 选完点"选择"→确认，再处理"拒绝领取"分支（AALC 末尾三段） */
    private fun finish(host: ScriptHost) {
        var chosen = false
        for (i in 0 until 5) {
            if (host.cancelled) return
            val lines = host.readText(SELECT_ROI)
            if (lines.any { it.contains("选择") || it.contains("select", ignoreCase = true) }) {
                host.click(SELECT_ROI.centerX, SELECT_ROI.centerY)
                host.sleep(1000)
                chosen = true
                if (host.clickTemplate(LEAVE_CONFIRM, 0.8)) break
            }
        }
        if (!chosen) host.click(SELECT_ROI.centerX, SELECT_ROI.centerY)
        for (i in 0 until 5) {
            if (host.cancelled) return
            if (host.clickTemplate(GIFT_CONFIRM, 0.8)) host.sleep(600)
        }
        for (i in 0 until 3) {
            val lines = host.readText(REJECT_ROI)
            if (lines.any { it.contains("拒绝") || it.contains("reject", ignoreCase = true) }) {
                host.click(REJECT_ROI.centerX, REJECT_ROI.centerY)
                host.sleep(1000)
                if (host.clickTemplate(LEAVE_CONFIRM, 0.8)) return
            }
        }
    }

    private data class Demand(val system: String, val level: Int, val row: Int, val col: Int)

    private fun parse(raw: String?): List<Demand> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", " ", ";", "，").mapNotNull { token ->
            val parts = token.trim().split("_")
            if (parts.size < 4) return@mapNotNull null
            val system = parts[0].lowercase()
            if (system !in SYSTEM_INDEX) return@mapNotNull null
            val level = parts[1].toIntOrNull() ?: return@mapNotNull null
            val row = parts[2].toIntOrNull() ?: return@mapNotNull null
            val col = parts[3].toIntOrNull() ?: return@mapNotNull null
            if (level !in 1..3 || row < 1 || col < 1) return@mapNotNull null
            Demand(system, level, row, col)
        }
    }
}
