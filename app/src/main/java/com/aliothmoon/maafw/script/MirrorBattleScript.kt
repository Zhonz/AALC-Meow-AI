package com.aliothmoon.maafw.script

/**
 * 镜牢战斗：把 AALC `tasks/battle/battle.py` 的战斗循环搬到手机上。
 *
 * 原版在 PC 上按 P（认胜率连线）再按 Enter（右侧齿轮开打），手机端没有键盘，
 * 这里就是「点胜率卡片 → 点右侧齿轮」，两下点击与 P+Enter 等价。
 *
 * 与管线里的 BattleAutoCombat 相比，脚本多管三件事：
 *  1. 进入战斗：普通战斗 `normal_to_battle`、链接战 `chaim_to_battle`、首战清空选择 `select_none`；
 *  2. 第一回合全员防御（PI 的 defense_first_round 开关，之前是个什么都不做的假开关）：
 *     按 `_calculate_skills_position` 算出每个技能格的位置，逐个点防御格，再按住拖一条连线；
 *  3. 全灭/认输与结算：先点认输，再点结算确认，回到探索循环。
 */
object MirrorBattleScript : ScriptTask {
    override val id: String = "mirror.battle"
    override val entry: String = "MirrorHandleBattle"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 40

    /** `defense_first_round`=1 时第 1 回合全员防御 */
    const val KEY_DEFENSE_FIRST_ROUND = "defense_first_round"

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

    private const val ENTER_BATTLE_DELAY = 1_800L
    private const val TURN_DELAY = 800L

    /** 原版 _calculate_skills_position 的三个常数（1440 基准，720p 下直接就是像素） */
    private const val SKILL_SIZE = 161.0
    private const val SKILL_X_OFFSET = 220.0
    private const val SKILL_X_PER_COUNT = 4.5
    private const val SKILL_Y_OFFSET = 250.0
    private const val SKILL_GAP = 145.0
    private const val LINK_Y_OFFSET = 150.0

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val defenseFirstRound = params[KEY_DEFENSE_FIRST_ROUND] == "1"
        var defended = false
        var loop = 0
        // 连续多少次什么都没认到；战斗界面上的东西都认不到时早点交还，别空转 240 次
        var idle = 0

        while (loop < MAX_LOOPS && !host.cancelled) {
            loop++

            // 打完：结算确认
            if (host.clickTemplate(FINISH_CONFIRM, 0.72)) {
                host.log("战斗结束，点结算确认")
                host.sleep(1_000)
                return
            }
            // 全灭/认输
            if (host.clickTemplate(GIVE_UP, 0.75)) {
                host.log("战斗失败，点认输退出")
                host.sleep(1_500)
                return
            }
            // 进入战斗（普通 / 链接战）
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
            // 首战：清掉上次留下的阵容勾选
            if (host.clickTemplate(SELECT_NONE, 0.72)) {
                host.log("清空上次的出战选择")
                host.sleep(600)
                continue
            }

            val gearLeft = host.find(GEAR_LEFT, 0.8)
            val gearRight = host.find(GEAR_RIGHT, 0.72)
            if (gearLeft != null && gearRight != null) {
                if (defenseFirstRound && !defended) {
                    host.log("第一回合全员防御")
                    defenseRound(host, gearLeft, gearRight)
                    defended = true
                    host.sleep(1_200)
                    continue
                }
                // 常规回合：认胜率连线，再点右侧齿轮开打
                host.find(WIN_RATE, 0.7)?.let { host.click(it) }
                host.sleep(250)
                host.click(gearRight)
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

    /**
     * 全员防御：原版 `_defense_this_round`
     *
     * 先按齿轮位置算出技能格（每格宽 145px、起点在左齿轮右上方），逐格点一下选防御，
     * 再从左齿轮按住拖过所有格子到右下角，让它们连锁成一条。
     */
    private fun defenseRound(host: ScriptHost, gearLeft: ScriptMatch, gearRight: ScriptMatch) {
        val left = gearLeft.box.x
        val top = gearLeft.box.y
        val count = (((gearRight.box.x - 100) - (left + 94)) / SKILL_GAP).toInt()
        if (count <= 0) {
            host.log("技能格数量算不出来，跳过防御")
            return
        }
        val slots = (0 until count).map { index ->
            Pair(
                (left + SKILL_X_OFFSET - SKILL_X_PER_COUNT * count + SKILL_SIZE * index).toInt(),
                (top + SKILL_Y_OFFSET).toInt(),
            )
        }
        for (slot in slots) {
            host.click(slot.first, slot.second)
            host.sleep(120)
        }
        val points = ArrayList<Pair<Int, Int>>(slots.size + 2)
        points.add(Pair(left, top))
        points.addAll(slots)
        points.add(Pair((gearRight.box.x + 5 * count).toInt(), (gearRight.box.y + LINK_Y_OFFSET).toInt()))
        host.dragLink(points, segmentMs = 70)
        host.log("防御连线完成，共 " + count + " 人")
    }

    private const val MAX_LOOPS = 240

    /** 连续空转多少次就交还管线 */
    private const val MAX_IDLE = 8
}
