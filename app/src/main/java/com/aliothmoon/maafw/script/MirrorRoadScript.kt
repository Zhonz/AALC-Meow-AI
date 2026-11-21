package com.aliothmoon.maafw.script

/**
 * 镜牢寻路：把 AALC `tasks/mirror/search_road.py` 的 `search_road_default_distance()` 搬到手机上。
 *
 * 原版是「认巴士 → 看巴士右侧上/中/下三个候选节点 → 按节点类型打分 → 点分最高的 → 点进入」，
 * 权重来自 `get_node_weight()`：商店 3、事件 3、普通战斗 2、精英遭遇战 1、集中遭遇战 0、
 * 认不出来 -5（也就是别点）。这里逐条照抄，只是把 find_feature_element 换成引擎的模板匹配。
 *
 * 与管线里的 MirrorClickBusNextNode 系列的区别：那几个是「盲点偏移」，这里是「先认节点再点」，
 * 而且会像原版那样把巴士拖回固定高度再规划，避免地图被拖歪后一直点空。
 */
object MirrorRoadScript : ScriptTask {
    override val id: String = "mirror.road"
    override val entry: String = "MirrorHandleRoad"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 24

    /** 地图右上角 legend，原版用它判断「确实在地图上」 */
    private const val LEGEND = "mirror/road_in_mir/legend_assets.png"
    private const val BUS = "mirror/mybus_default_distance.png"
    private const val ENTER = "mirror/road_in_mir/enter_assets.png"
    private const val SHOP = "mirror/road_in_mir/shop.png"
    private const val EVENT = "mirror/road_in_mir/event.png"
    private const val BATTLE = "mirror/road_in_mir/battle.png"
    private const val RISKY = "mirror/road_in_mir/risky_encounter.png"
    private const val FOCUSED = "mirror/road_in_mir/focused_encounter.png"
    private const val CLEAR_FLOOR = "mirror/road_in_mir/clear_floor.png"

    private const val LEGEND_THRESHOLD = 0.7
    private const val BUS_THRESHOLD = 0.62
    private const val NODE_THRESHOLD = 0.62
    private const val ENTER_THRESHOLD = 0.7

    /** 原版 three_roads：中、下、上（2560×1440 下的偏移，这里已按 720p 折半） */
    private val CANDIDATES = listOf(
        intArrayOf(250, 25),
        intArrayOf(250, 225),
        intArrayOf(250, -200),
    )

    /** 节点周围认图范围（原版 ±125*scale，720p 即 ±62） */
    private const val NODE_BOX = 62

    /** 把巴士拖到这个高度在做路线规划（原版 650*scale） */
    private const val BUS_TARGET_Y = 325
    private const val BUS_TARGET_TOLERANCE = 25
    private const val DRAG_MS = 600

    private const val MAX_ALIGN_TRIES = 4
    private const val MAX_STEPS = 6

    /**
     * 这一次任务里是否已经放弃寻路
     *
     * 地图界面下 `MirrorHandleRoad` 每次都命中，脚本一旦「什么都做不了」（认不到巴士）就直接返回，
     * 管线会立刻再次交棒 —— 不做记号的话会白转满预算（40 次）才判失败。放弃后本次任务不再尝试，
     * 交给管线的盲点节点；任务开始时由 `resetSession()` 复位。
     */
    @Volatile
    private var gaveUp = false

    override fun resetSession() {
        gaveUp = false
    }

    override fun run(host: ScriptHost, params: Map<String, String>) {
        if (gaveUp) return
        if (host.find(LEGEND, LEGEND_THRESHOLD) == null) {
            host.log("不在镜牢地图界面，交还给管线")
            return
        }
        if (host.find(CLEAR_FLOOR, 0.7) != null) {
            host.log("本层已清空，交给管线处理楼层切换")
            return
        }

        // 已经在可进入的节点上：直接点「进入」
        if (host.clickTemplate(ENTER, ENTER_THRESHOLD)) {
            host.log("已站在可进入的节点上，点击进入")
            host.sleep(1200)
            return
        }

        val bus = alignBus(host) ?: run {
            host.log("没找到巴士图标，本次任务不再尝试寻路，交还给管线盲点")
            gaveUp = true
            return
        }

        repeat(MAX_STEPS) { step ->
            if (host.cancelled) return
            val candidates = CANDIDATES.map { offset ->
                val x = bus.centerX + offset[0]
                val y = bus.centerY + offset[1]
                NodeCandidate(x, y, weightAt(host, x, y))
            }.sortedByDescending { it.weight }

            for (candidate in candidates) {
                if (candidate.weight <= -5) continue
                if (!onScreen(host, candidate.x, candidate.y)) continue
                host.log(
                    "第 " + (step + 1) + " 步：点 (" + candidate.x + "," + candidate.y +
                        ")，权重 " + candidate.weight,
                )
                host.click(candidate.x, candidate.y)
                host.sleep(900)
                if (host.clickTemplate(ENTER, ENTER_THRESHOLD)) {
                    host.sleep(1200)
                    return
                }
                // 点空了：点一下空地把选中态清掉，再试下一个
                host.clickBlank(1)
                host.sleep(300)
            }

            // 三个候选都不行：把地图拖回标准位置再认一次
            val aligned = alignBus(host) ?: return
            if (!moved(aligned, bus)) return
        }

        host.log("寻路没有进展，交还给管线的盲点节点")
    }

    private fun moved(before: ScriptMatch, after: ScriptMatch): Boolean =
        kotlin.math.abs(before.centerX - after.centerX) > 8 ||
            kotlin.math.abs(before.centerY - after.centerY) > 8

    /** 原版把巴士拖到固定高度（y≈650*scale、x<150*scale），让右侧节点进画面 */
    private fun alignBus(host: ScriptHost): ScriptMatch? {
        var bus = host.find(BUS, BUS_THRESHOLD) ?: return null
        repeat(MAX_ALIGN_TRIES) {
            if (host.cancelled) return bus
            if (kotlin.math.abs(bus.centerY - BUS_TARGET_Y) <= BUS_TARGET_TOLERANCE) return bus
            val dy = BUS_TARGET_Y - bus.centerY
            host.log("巴士位置 (" + bus.centerX + "," + bus.centerY + ")，拖动 " + dy + " 对齐")
            host.swipe(bus.centerX, bus.centerY, bus.centerX, bus.centerY + dy, DRAG_MS)
            host.sleep(700)
            bus = host.find(BUS, BUS_THRESHOLD) ?: return bus
        }
        return bus
    }

    /** 原版 get_node_weight：商店/事件 3，普通战斗 2，精英 1，集中 0，未知 -5 */
    private fun weightAt(host: ScriptHost, x: Int, y: Int): Int {
        val roi = ScriptRect(x - NODE_BOX, y - NODE_BOX, NODE_BOX * 2, NODE_BOX * 2)
        if (host.find(SHOP, NODE_THRESHOLD, roi) != null) return 3
        if (host.find(EVENT, NODE_THRESHOLD, roi) != null) return 3
        if (host.find(BATTLE, NODE_THRESHOLD, roi) != null) return 2
        if (host.find(RISKY, NODE_THRESHOLD, roi) != null) return 1
        if (host.find(FOCUSED, NODE_THRESHOLD, roi) != null) return 0
        return -5
    }

    private fun onScreen(host: ScriptHost, x: Int, y: Int): Boolean =
        x > 0 && x < host.screenWidth && y > 0 && y < host.screenHeight

    private data class NodeCandidate(val x: Int, val y: Int, val weight: Int)
}
