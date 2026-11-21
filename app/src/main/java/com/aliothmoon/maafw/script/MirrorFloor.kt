package com.aliothmoon.maafw.script

/**
 * 镜牢楼层识别：把 AALC `tasks/mirror/get_floor.py` 搬到手机上。
 *
 * 原理：在地图界面点右上角设置 → 楼层面板展开（认 `to_window_assets` 确认）→
 * 在楼层横条里数「红色的 CLEAR 标记」：通过的层是金色菱形 + 红字 CLEAR，
 * 数到几个就说明已经过了几层，**当前层 = 数量 + 1**。
 *
 * 这一层信息有两个用处：
 *  1. `ignore_shop`：某些层跳过商店（AALC 的 `cfg.ignore_shop[layer-1]`）；
 *  2. 日志：真机出问题时能看出到底在第几层，而不是每个坐标都靠猜。
 *
 * 阈值都是 720p：横条 ROI 来自上游 2560×1440 的 (760,495)-(1860,590) 折半，
 * 红色判定 `r - max(g,b) >= 60 且 r >= 190`（排除黄色、排除不够亮的红），
 * 最小连通面积 300（1440 基准）折半后约 75。
 */
object MirrorFloorTracker {
    /** 最近一次识别到的层数；0 表示未知 */
    @Volatile
    var current: Int = 0

    /** 上次识别的系统时间（毫秒），避免每次寻路都去点设置 */
    @Volatile
    var lastDetectedAt: Long = 0L

    fun reset() {
        current = 0
        lastDetectedAt = 0L
    }
}

object MirrorFloor {
    private const val SETTING = "mirror/road_in_mir/setting_assets.png"
    private const val TO_WINDOW = "mirror/road_in_mir/to_window_assets.png"

    private const val BAND_X = 380
    private const val BAND_Y = 247
    private const val BAND_W = 550
    private const val BAND_H = 48

    private const val MIN_AREA = 75
    private const val MIN_DIST = 40

    private const val RED_DOMINANCE = 60
    private const val RED_MIN = 190

    /** 两次识别之间的最短间隔：面板要点开再关掉，太频繁会拖慢寻路 */
    private const val DETECT_INTERVAL_MS = 90_000L

    /**
     * 识别当前楼层；返回 0 表示这次没识别出来（不覆盖已有的 [MirrorFloorTracker.current]）。
     *
     * 调用前必须确实在地图界面（能认到 setting 才动）。
     */
    fun detect(
        host: ScriptHost,
        force: Boolean = false,
        settingAssets: String = SETTING,
    ): Int {
        val now = System.currentTimeMillis()
        if (!force && MirrorFloorTracker.current > 0 && now - MirrorFloorTracker.lastDetectedAt < DETECT_INTERVAL_MS) {
            return MirrorFloorTracker.current
        }
        if (host.find(settingAssets, 0.75) == null) return 0

        host.clickTemplate(settingAssets, 0.75)
        host.sleep(1_000)
        if (host.find(TO_WINDOW, 0.75) == null) {
            host.log("设置面板没打开，楼层识别跳过")
            host.clickBlank()
            return 0
        }
        val shot = host.screenshot()
        var regions = 0
        if (shot != null) {
            regions = countRedClusters(shot)
        } else {
            host.log("拿不到截图，楼层识别跳过")
        }
        host.clickBlank()
        host.sleep(800)

        MirrorFloorTracker.lastDetectedAt = System.currentTimeMillis()
        if (regions <= 0) return 0
        val floor = regions + 1
        MirrorFloorTracker.current = floor
        return floor
    }

    /** 数横条里红色的 CLEAR 块数量（超过最小面积、且彼此距离够远的算不同块） */
    private fun countRedClusters(shot: ScreenShot): Int {
        val width = BAND_W.coerceAtMost(shot.width - BAND_X)
        val height = BAND_H.coerceAtMost(shot.height - BAND_Y)
        if (width <= 0 || height <= 0) return 0
        val mask = Array(height) { y ->
            BooleanArray(width) { x ->
                val r = shot.red(BAND_X + x, BAND_Y + y)
                val g = shot.green(BAND_X + x, BAND_Y + y)
                val b = shot.blue(BAND_X + x, BAND_Y + y)
                (r - maxOf(g, b)) >= RED_DOMINANCE && r >= RED_MIN
            }
        }

        val centers = ArrayList<Pair<Int, Int>>()
        val visited = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y][x] || visited[y][x]) continue
                val stack = ArrayDeque<Int>()
                stack.addLast(y * width + x)
                visited[y][x] = true
                var area = 0
                var sumX = 0
                var sumY = 0
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    val cy = cur / width
                    val cx = cur % width
                    area++
                    sumX += cx
                    sumY += cy
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val ny = cy + dy
                            val nx = cx + dx
                            if (ny !in 0 until height || nx !in 0 until width) continue
                            if (mask[ny][nx] && !visited[ny][nx]) {
                                visited[ny][nx] = true
                                stack.addLast(ny * width + nx)
                            }
                        }
                    }
                }
                if (area >= MIN_AREA) centers.add(Pair(sumX / area, sumY / area))
            }
        }
        if (centers.isEmpty()) return 0

        // 相邻的红块（同一个 CLEAR 被切开）合并掉，只留一个
        val merged = ArrayList<Pair<Int, Int>>()
        val pool = centers.toMutableList()
        while (pool.isNotEmpty()) {
            val current = pool.removeAt(pool.size - 1)
            val near = pool.filter { Math.hypot((it.first - current.first).toDouble(), (it.second - current.second).toDouble()) <= MIN_DIST }
            pool.removeAll(near)
            merged.add(current)
        }
        return merged.size
    }
}
