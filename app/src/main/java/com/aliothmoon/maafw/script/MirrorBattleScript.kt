package com.aliothmoon.maafw.script

/**
 * 镜牢战斗：把 AALC `tasks/battle/battle.py` 的战斗循环搬到手机上。
 *
 * 原版在 PC 上按 P（认胜率连线）再按 Enter（右侧齿轮开打），手机端没有键盘，
 * 这里就是「点胜率卡片 → 点右侧齿轮」，两下点击与 P+Enter 等价。
 *
 * 脚本多管四件事：
 *  1. 进入战斗：普通战斗 `normal_to_battle`、链接战 `chaim_to_battle`、首战清空选择 `select_none`；
 *  2. 第一回合全员防御（`defense_first_round`）：`_defense_this_round` 的公式，逐格点防御再拖连线；
 *  3. 避开 / 优先三技能（`avoid_skill_3` / `prioritize_skill_3`）：`_chain_battle` 的公式，
 *     用罪人颜色在技能格上方找「三技能」标记，把对应（或其余）格子切到下排再连线；
 *  4. 全灭/认输与结算：先点认输，再点结算确认，回到探索循环。
 *
 * **坐标基准**：AALC 的常数是 1440 高基准，运行时乘 `set_win_size / 1440`；本机资源按 720p
 * 归一化，所以 [SCALE] = 0.5。原来那版把 161 / 220 / 250 直接当像素用，按 720p 算会偏大 1.5 倍，
 * 现在统一乘 [SCALE]。
 */
object MirrorBattleScript : ScriptTask {
    override val id: String = "mirror.battle"
    override val entry: String = "MirrorHandleBattle"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 40

    const val KEY_DEFENSE_FIRST_ROUND = "defense_first_round"
    const val KEY_AVOID_SKILL_3 = "avoid_skill_3"
    const val KEY_PRIORITIZE_SKILL_3 = "prioritize_skill_3"
    const val KEY_FIGHT_TO_LAST_MAN = "fightToLastMan"

    private const val NORMAL_TO_BATTLE = "battle/normal_to_battle_assets.png"
    private const val CHAIN_TO_BATTLE = "battle/chaim_to_battle_assets.png"
    private const val SELECT_NONE = "battle/select_none_assets.png"
    private const val WIN_RATE = "battle/win_rate.png"
    private const val GEAR_LEFT = "battle/gear_left.png"
    private const val GEAR_RIGHT = "battle/gear_right.png"
    private const val TURN = "battle/turn.png"
    private const val FINISH_CONFIRM = "battle/battle_finish_confirm.png"
    private const val GIVE_UP = "battle/give_up_assets.png"
    private const val IN_MIRROR = "battle/in_mirror.png"
    private const val DEAD_ALL = "battle/dead_all.png"
    private const val DEAD = "battle/dead.png"
    private const val VIEW_STATUS = "battle/view_status_assets.png"
    private const val SETTING = "mirror/road_in_mir/setting_assets.png"

    private const val ENTER_BATTLE_DELAY = 1_800L
    private const val TURN_DELAY = 800L

    /** 720p 相对 AALC 1440 基准的缩放 */
    private const val SCALE = 0.5

    /** _calculate_skills_position 的常数（乘过 SCALE） */
    private const val SKILL_SIZE = 161.0 * SCALE
    private const val SKILL_Y_OFFSET = 250.0 * SCALE
    private const val SKILL_X_OFFSET = 220.0
    private const val SKILL_X_PER_COUNT = 4.5
    private const val ROW_STEP = 145.0 * SCALE
    private const val ROW_SHIFT = 125.0 * SCALE
    private const val TRIANGLE_STEP = 8.0 * SCALE
    private const val LINK_X_OFFSET = 5.0 * SCALE
    private const val LINK_Y_OFFSET = 150.0 * SCALE

    /** find_skill3 的判定：颜色容差、最小像素、聚类合并距离（1440 基准 × SCALE） */
    private const val SKILL3_TOLERANCE = 40
    private const val SKILL3_MIN_PIXELS = 10
    private const val SKILL3_STRIP_PIXELS = 26
    private const val SKILL3_MERGE_DISTANCE = 67.0 * SCALE

    /** 罪孽颜色（AALC tasks/__init__.py 的 sins，BGR；这里换成 RGB） */
    private val SINNER_COLORS: List<IntArray> = listOf(
        intArrayOf(254, 0, 0), // wrath
        intArrayOf(26, 197, 239), // gloom
        intArrayOf(251, 205, 49), // sloth
        intArrayOf(254, 108, 0), // lust
        intArrayOf(1, 75, 213), // pride
        intArrayOf(146, 228, 1), // gluttony
        intArrayOf(150, 1, 222), // envy
    )

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val defenseFirstRound = params[KEY_DEFENSE_FIRST_ROUND] == "1"
        val avoidSkill3 = params[KEY_AVOID_SKILL_3] == "1"
        val prioritizeSkill3 = params[KEY_PRIORITIZE_SKILL_3] == "1" && !avoidSkill3
        val fightToLastMan = params[KEY_FIGHT_TO_LAST_MAN] == "1"
        var defended = false
        var chained = false
        var loop = 0
        var idle = 0

        while (loop < MAX_LOOPS && !host.cancelled) {
            loop++

            if (host.clickTemplate(FINISH_CONFIRM, 0.72)) {
                host.log("战斗结束，点结算确认")
                host.sleep(1_000)
                return
            }
            if (host.clickTemplate(GIVE_UP, 0.75)) {
                host.log("战斗失败，点认输退出")
                host.sleep(1_500)
                return
            }
            if (host.clickTemplate(NORMAL_TO_BATTLE, 0.74)) {
                host.log("点普通战斗进入战斗")
                host.sleep(ENTER_BATTLE_DELAY)
                continue
            }
            if (host.clickTemplate(CHAIN_TO_BATTLE, 0.74)) {
                host.log("点链接战进入战斗")
                host.sleep(ENTER_BATTLE_DELAY)
                continue
            }
            if (host.clickTemplate(SELECT_NONE, 0.72)) {
                host.log("清空上次的出战选择")
                host.sleep(600)
                continue
            }

            // 状态栏被折叠（点一下展开）：AALC 点 view_status 右上 +50/-250 的位置
            val viewStatus = host.find(VIEW_STATUS, 0.72)
            if (viewStatus != null) {
                host.click(viewStatus.box.centerX + 50, viewStatus.box.centerY - 250)
                host.log("点开战斗状态栏")
                host.sleep(600)
                continue
            }
            // 全员阵亡：三个复活选项里选中间那个，再点确认
            val deadAll = host.findAll(DEAD_ALL, 0.75)
            if (deadAll.size >= 2) {
                val middle = deadAll.sortedBy { it.box.centerY }[deadAll.size / 2]
                host.click(middle.box.centerX, middle.box.centerY)
                host.log("全员阵亡，选择中间的复活选项")
                host.sleep(1_500)
                if (host.clickTemplate(FINISH_CONFIRM, 0.72)) {
                    host.sleep(1_000)
                    return
                }
                continue
            }
            // 有人阵亡且不允许战到最后一个：认输退出（AALC battle.py:309-330）
            if (!fightToLastMan) {
                val dead = host.find(DEAD, 0.78)
                if (dead != null) {
                    val band = ScriptRect(
                        dead.box.centerX - 50,
                        dead.box.centerY - 15,
                        100,
                        30,
                    )
                    val texts = host.readText(band, 0.3).map { it.lowercase().replace(" ", "") }
                    if (texts.any { it.contains("阵亡") || it.contains("dead") }) {
                        host.log("有罪人阵亡，认输退出这场战斗")
                        giveUp(host)
                        return
                    }
                }
            }

            val gearLeft = host.find(GEAR_LEFT, 0.8)
            val gearRight = host.find(GEAR_RIGHT, 0.72)
            if (gearLeft != null && gearRight != null) {
                if (defenseFirstRound && !defended) {
                    host.log("第一回合全员防御")
                    if (defenseRound(host, gearLeft, gearRight)) {
                        defended = true
                    } else {
                        host.log("防御连线失败，本回合改用胜率连线")
                        turnByWinRate(host)
                    }
                    host.sleep(1_200)
                    host.clickBlank()
                    continue
                }
                if ((avoidSkill3 || prioritizeSkill3) && !chained) {
                    val mode = if (prioritizeSkill3) "优先" else "避开"
                    if (chainBattle(host, gearLeft, gearRight, prioritizeSkill3)) {
                        host.log(mode + "三技能连线完成")
                        chained = true
                    } else {
                        host.log(mode + "三技能识别失败，先按胜率打一回合")
                    }
                    host.sleep(1_500)
                    host.clickBlank()
                    continue
                }
                turnByWinRate(host)
                host.sleep(TURN_DELAY)
                continue
            }

            if (host.find(IN_MIRROR, 0.7) != null || host.find(TURN, 0.7) != null) {
                idle = 0
                host.sleep(TURN_DELAY)
                continue
            }
            idle++
            if (idle >= MAX_IDLE) {
                host.log("连续 " + idle + " 次没认出战斗界面，交还给管线")
                return
            }
            host.sleep(400)
        }
    }

    /** 阵亡后的认输：点认输 → 不行就点设置 → 还不行点空白戳掉弹窗 */
    private fun giveUp(host: ScriptHost) {
        repeat(6) {
            if (host.cancelled) return
            if (host.clickTemplate(GIVE_UP, 0.75)) {
                host.sleep(1_500)
                return
            }
            if (host.clickTemplate(SETTING, 0.75)) {
                host.sleep(800)
                if (host.clickTemplate(GIVE_UP, 0.75)) {
                    host.sleep(1_500)
                    return
                }
            }
            host.clickBlank()
            host.sleep(600)
        }
        host.log("认输没点出来，交还给管线")
    }

    /** 常规回合：点胜率卡片（P）再点右侧齿轮（Enter） */
    private fun turnByWinRate(host: ScriptHost) {
        host.find(WIN_RATE, 0.7)?.let { host.click(it) }
        host.sleep(250)
        host.find(GEAR_RIGHT, 0.72)?.let { host.click(it) }
    }

    /**
     * 全员防御：原版 `_defense_this_round`
     *
     * gear_1 = 左齿轮 +(100, -35)，gear_2 = 右齿轮 -100；技能格数 = (gear_2.x - gear_1.x) / 145；
     * 每格位置 = 左齿轮 + (220 - 4.5×格数, 250)，步长 161（都已乘 SCALE）。
     */
    private fun defenseRound(host: ScriptHost, gearLeft: ScriptMatch, gearRight: ScriptMatch): Boolean {
        val count = skillCount(gearLeft, gearRight, leftInset = 100.0, topInset = 35.0)
        if (count <= 0) {
            host.log("技能格数量算不出来，跳过防御")
            return false
        }
        val slots = (0 until count).map { index -> normalSlot(gearLeft, count, index) }
        for (slot in slots) {
            host.click(slot.first, slot.second)
            host.sleep(120)
        }
        val points = ArrayList<Pair<Int, Int>>(slots.size + 2)
        points.add(Pair(gearLeft.box.centerX, gearLeft.box.centerY))
        points.addAll(slots)
        points.add(linkEnd(gearRight, count))
        host.dragLink(points, segmentMs = 70)
        host.log("防御连线完成，共 " + count + " 人")
        return true
    }

    /**
     * 避开 / 优先三技能：原版 `_chain_battle`
     *
     * 在技能格上方的细条里，用七个罪孽颜色各找一次「三技能」标记（颜色混合 + 连通域），
     * 命中格子的索引除以行步长就是技能格序号；避开模式把这些格子切到下排，
     * 优先模式把其余格子切到下排，然后一次拖线到底。
     */
    private fun chainBattle(
        host: ScriptHost,
        gearLeft: ScriptMatch,
        gearRight: ScriptMatch,
        prioritize: Boolean,
    ): Boolean {
        val count = skillCount(gearLeft, gearRight, leftInset = 94.0, topInset = 37.0)
        if (count <= 0) return false
        val shot = host.screenshot() ?: return false

        // AALC battle.py:610-620：人数 >= 10 时 bbox 左边再往右让 50*scale，否则第一格会切到齿轮
        val leftInset = 94 + if (count >= 10) 50 else 0
        val left = (gearLeft.box.x + leftInset * SCALE).toInt()
        val back = (gearLeft.box.y - 37 * SCALE).toInt()
        val right = (gearRight.box.x - 100 * SCALE).toInt()
        val top = (back - 15 * SCALE).toInt()
        val width = (right - left).coerceAtLeast(1)
        val height = (back - top).coerceAtLeast(1)

        val skill3 = HashSet<Int>()
        for (color in SINNER_COLORS) {
            for (centerX in findSkill3Centers(shot, left, top, width, height, color)) {
                val index = Math.round(centerX / ROW_STEP).toInt()
                if (index in 1..count) skill3.add(index)
            }
        }
        val valid = (1..count).toSet()
        val lowerRow = if (prioritize) valid - skill3 else skill3

        val points = ArrayList<Pair<Int, Int>>(count + 2)
        points.add(Pair(gearLeft.box.centerX, gearLeft.box.centerY))
        val anchorY = gearLeft.box.y - ROW_SHIFT
        for (index in 0 until count) {
            var x = gearLeft.box.x + (SKILL_X_OFFSET - SKILL_X_PER_COUNT * count) * SCALE + SKILL_SIZE * index
            var y = anchorY + SKILL_Y_OFFSET
            val slotNumber = index
            val center = count / 2
            if (slotNumber <= center) {
                x += (center - slotNumber) * TRIANGLE_STEP
            } else {
                x -= (slotNumber - center) * TRIANGLE_STEP
            }
            if ((slotNumber + 1) in lowerRow) {
                y += ROW_SHIFT
                if (slotNumber <= center) {
                    x -= (center - slotNumber) * TRIANGLE_STEP
                } else {
                    x += (slotNumber - center) * TRIANGLE_STEP
                }
            }
            points.add(Pair(x.toInt(), y.toInt()))
        }
        points.add(linkEnd(gearRight, count))
        host.dragLink(points, segmentMs = 70)
        host.log("三技能格子: " + skill3.sorted() + "，切下排: " + lowerRow.sorted())
        host.sleep(400)
        host.find(GEAR_RIGHT, 0.72)?.let { host.click(it) }
        return true
    }

    private fun skillCount(gearLeft: ScriptMatch, gearRight: ScriptMatch, leftInset: Double, topInset: Double): Int {
        val x1 = gearLeft.box.x + leftInset * SCALE
        val x2 = gearRight.box.x - 100 * SCALE
        return ((x2 - x1) / ROW_STEP).toInt()
    }

    private fun normalSlot(gearLeft: ScriptMatch, count: Int, index: Int): Pair<Int, Int> = Pair(
        (gearLeft.box.x + (SKILL_X_OFFSET - SKILL_X_PER_COUNT * count) * SCALE + SKILL_SIZE * index).toInt(),
        (gearLeft.box.y + SKILL_Y_OFFSET).toInt(),
    )

    private fun linkEnd(gearRight: ScriptMatch, count: Int): Pair<Int, Int> = Pair(
        (gearRight.box.x + LINK_X_OFFSET * count).toInt(),
        (gearRight.box.centerY + LINK_Y_OFFSET).toInt(),
    )

    /**
     * 原版 `utils/utils.py:find_skill3` 的 Kotlin 版：背景中位数与罪孽色混合成目标色，
     * 在容差内取掩码，做一次腐蚀 + 三次膨胀，再找连通域；区域够大、且顶部细条里同色像素够多，
     * 才认为是三技能标记。返回连通域中心的 x（相对裁剪框）。
     */
    private fun findSkill3Centers(
        shot: ScreenShot,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        color: IntArray,
    ): List<Double> {
        if (width <= 6 || height <= 4) return emptyList()
        val median = medianColor(shot, left, top, width, height)
        val target = intArrayOf(
            (median[0] * 0.45 + color[0] * 0.55).toInt(),
            (median[1] * 0.45 + color[1] * 0.55).toInt(),
            (median[2] * 0.45 + color[2] * 0.55).toInt(),
        )
        val mask = Array(height) { y ->
            BooleanArray(width) { x ->
                val px = left + x
                val py = top + y
                val r = shot.red(px, py)
                val g = shot.green(px, py)
                val b = shot.blue(px, py)
                kotlin.math.abs(r - target[0]) <= SKILL3_TOLERANCE &&
                    kotlin.math.abs(g - target[1]) <= SKILL3_TOLERANCE &&
                    kotlin.math.abs(b - target[2]) <= SKILL3_TOLERANCE
            }
        }
        val eroded = erode(mask, width, height)
        val dilated = dilate(dilate(dilate(eroded, width, height), width, height), width, height)

        val centers = ArrayList<Double>()
        val visited = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!dilated[y][x] || visited[y][x]) continue
                // 洪泛一块，边算面积与中心，边统计顶部细条里的像素数
                val stack = ArrayDeque<Int>()
                stack.addLast(y * width + x)
                visited[y][x] = true
                var area = 0
                var sumX = 0.0
                var sumY = 0.0
                var strip = 0
                while (stack.isNotEmpty()) {
                    val cur = stack.removeLast()
                    val cy = cur / width
                    val cx = cur % width
                    area++
                    sumX += cx
                    sumY += cy
                    if (cy < (13 * SCALE).toInt() + 1) strip++
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val ny = cy + dy
                            val nx = cx + dx
                            if (ny !in 0 until height || nx !in 0 until width) continue
                            if (dilated[ny][nx] && !visited[ny][nx]) {
                                visited[ny][nx] = true
                                stack.addLast(ny * width + nx)
                            }
                        }
                    }
                }
                if (area >= SKILL3_MIN_PIXELS && strip >= SKILL3_STRIP_PIXELS) {
                    centers.add(sumX / area)
                }
            }
        }
        if (centers.size <= 1) return centers
        // 合并相邻中心（AALC 用 67 像素的欧氏距离）
        val merged = ArrayList<Double>()
        val pool = centers.toMutableList()
        while (pool.isNotEmpty()) {
            val current = pool.removeAt(pool.size - 1)
            val group = pool.filter { kotlin.math.abs(it - current) <= SKILL3_MERGE_DISTANCE }
            pool.removeAll(group)
            merged.add((group + current).average())
        }
        return merged
    }

    private fun medianColor(shot: ScreenShot, left: Int, top: Int, width: Int, height: Int): IntArray {
        val step = maxOf(1, width / 32)
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        var y = top
        while (y < top + height) {
            var x = left
            while (x < left + width) {
                rs.add(shot.red(x, y))
                gs.add(shot.green(x, y))
                bs.add(shot.blue(x, y))
                x += step
            }
            y += maxOf(1, height / 6)
        }
        fun median(list: ArrayList<Int>): Int {
            if (list.isEmpty()) return 0
            list.sort()
            return list[list.size / 2]
        }
        return intArrayOf(median(rs), median(gs), median(bs))
    }

    private fun erode(mask: Array<BooleanArray>, width: Int, height: Int): Array<BooleanArray> {
        val out = Array(height) { BooleanArray(width) }
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var all = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (!mask[y + dy][x + dx]) {
                            all = false
                            break
                        }
                    }
                    if (!all) break
                }
                out[y][x] = all
            }
        }
        return out
    }

    private fun dilate(mask: Array<BooleanArray>, width: Int, height: Int): Array<BooleanArray> {
        val out = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y][x]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = y + dy
                        val nx = x + dx
                        if (ny in 0 until height && nx in 0 until width) out[ny][nx] = true
                    }
                }
            }
        }
        return out
    }

    private const val MAX_LOOPS = 240
    private const val MAX_IDLE = 8
}
