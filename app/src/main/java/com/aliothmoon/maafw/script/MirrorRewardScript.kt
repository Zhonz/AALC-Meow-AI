package com.aliothmoon.maafw.script

import com.aliothmoon.maafw.script.ScriptHost

/**
 * 镜牢领奖：把 AALC `mirror.py:538-698` 的领奖循环搬到脚本侧。
 *
 * 上游的循环体按「先看特殊界面（等待/战果确认/已领取/领取确认）→ 再判是否失败（没看到 100% 完成度）→
 * 失败就点放弃 → 成功就点领取并把每周加成、通行证经验、脑啡肽这些选项处理掉」的顺序跑，最后回主页退出。
 * 这里保持同样的优先级，只是把「回主界面失败兜底」「retry() 重开」这类平台级动作换成交还管线 / 日志。
 */
object MirrorRewardScript : ScriptTask {
    override val id: String = "mirror.reward"
    override val entry: String = "MirrorHandleReward"
    override val resumeEntry: String = "MirrorRewardHomeDone"
    override val maxRounds: Int = 4

    const val KEY_SAVE_REWARDS = "saveRewards"
    const val KEY_NO_WEEKLY_BONUSES = "noWeeklyBonuses"
    const val KEY_SINGLE_BONUSES = "singleBonuses"
    const val KEY_HARD_MODE = "hardMode"

    private const val HOME = "home/drive.png"
    private const val BATTLE_FINISH = "battle/battle_finish_confirm.png"
    private const val ACQUIRED = "mirror/claim_reward/rewards_acquired_assets.png"
    private const val CLAIM = "mirror/claim_reward/claim_rewards_assets.png"
    private const val CLAIM_CONFIRM = "mirror/claim_reward/claim_rewards_confirm_assets.png"
    private const val CLAIM_FORFEIT = "mirror/claim_reward/claim_forfeit_assets.png"
    private const val COMPLETE_100 = "mirror/claim_reward/complete_mirror_100%_assets.png"
    private const val CLEAR = "mirror/claim_reward/clear_assets.png"
    private const val WEEKLY = "mirror/claim_reward/weekly_bonuses.png"
    private const val USE_ENKEPHALIN = "mirror/claim_reward/use_enkephalin_assets.png"
    private const val COINS_BBOX = "mirror/claim_reward/coins_bbox.png"
    private const val COINS = "mirror/claim_reward/coins.png"
    private const val ANNIVERSARY = "home/close_anniversary_event.png"

    /** 结算页「完成度 100%」文字所在区域（720p） */
    private val COMPLETE_BAND = ScriptRect(276, 300, 260, 150)

    private const val MAX_ROUNDS = 20
    private const val STEP_WAIT = 700L
    private const val CONFIRM_THRESHOLD = 0.75
    /** 保留奖励时点「使用脑啡肽」左边那一格（AALC 300 * set_win_size / 1440，720p 即 150） */
    private const val SAVE_REWARD_X_OFFSET = 150

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val saveRewards = ScriptParams.flag(params, KEY_SAVE_REWARDS)
        val noWeekly = ScriptParams.flag(params, KEY_NO_WEEKLY_BONUSES)
        val singleBonuses = ScriptParams.flag(params, KEY_SINGLE_BONUSES)
        val hardMode = ScriptParams.flag(params, KEY_HARD_MODE)

        val seenSomething = host.find(CLAIM) != null ||
            host.find(COMPLETE_100) != null ||
            host.find(CLEAR) != null ||
            host.find(ACQUIRED) != null
        if (!seenSomething) {
            host.log("[领奖] 没看到结算界面，交还给管线")
            return
        }

        var failed: Boolean? = null
        var rounds = MAX_ROUNDS
        while (rounds-- > 0 && !host.cancelled) {
            // 进循环先等界面稳住，再决定「是不是没打完」（AALC 靠 waiting 图整屏匹配，这里用固定等待代替）
            if (rounds == MAX_ROUNDS - 1) host.sleep(1500)
            if (failed == null && host.find(COMPLETE_100) == null) failed = true
            if (host.find(COMPLETE_100) != null || host.find(CLEAR) != null) {
                failed = false
                host.log("[领奖] 完成度 100%，正常领奖")
            }
            if (host.find(HOME) != null) {
                host.log("[领奖] 已回到主页，领奖结束")
                return
            }
            if (host.clickTemplate(BATTLE_FINISH)) {
                host.sleep(STEP_WAIT)
                continue
            }
            if (host.clickTemplate(ACQUIRED)) {
                host.sleep(STEP_WAIT)
                continue
            }
            if (host.clickTemplate(CLAIM_CONFIRM, CONFIRM_THRESHOLD)) {
                host.sleep(STEP_WAIT)
                continue
            }
            if (failed == true) {
                host.clickBlank(1)
                host.sleep(500)
                // 只 OCR「完成度」那一小块（AALC 也是把 OCR 限制在 complete_mirror 模板的 bbox 里）
                val texts = host.readText(COMPLETE_BAND, 0.3)
                if (texts.any { it.contains("100") }) {
                    host.log("[领奖] 之前判错，完成度其实是 100%")
                    failed = false
                    continue
                }
                if (host.clickTemplate(CLAIM)) {
                    host.sleep(1000)
                }
                if (host.clickTemplate(CLAIM_FORFEIT, 0.75)) {
                    host.sleep(STEP_WAIT)
                    continue
                }
            } else {
                if (hardMode && saveRewards) {
                    if (host.clickTemplate(CLAIM)) {
                        host.sleep(1000)
                    }
                    val pos = host.find(USE_ENKEPHALIN)
                    if (pos != null) {
                        host.click(pos.box.centerX - SAVE_REWARD_X_OFFSET, pos.box.centerY)
                        host.log("[领奖] 保留奖励：点掉脑啡肽选项")
                        host.sleep(1000)
                    }
                    continue
                }
                if (host.clickTemplate(CLAIM)) {
                    host.sleep(1000)
                    if (noWeekly) clickAllWeekly(host)
                    if (singleBonuses) keepOneBonus(host)
                    readPassCoins(host)
                    if (host.clickTemplate(USE_ENKEPHALIN)) {
                        host.sleep(1000)
                    }
                    continue
                }
            }
            if (host.clickTemplate(USE_ENKEPHALIN, CONFIRM_THRESHOLD)) {
                host.sleep(1000)
                continue
            }
            if (host.clickTemplate(ANNIVERSARY)) {
                host.sleep(STEP_WAIT)
                continue
            }
            host.clickBlank(1)
            host.sleep(STEP_WAIT)
        }
        if (failed == true) host.log("[领奖] 这次没拿到 100% 完成度（可能提前收工）")
        else host.log("[领奖] 领奖循环结束")
    }

    /** AALC no_weekly_bonuses：把每周加成全部点掉（从最后一个开始点，避免坐标漂移） */
    private fun clickAllWeekly(host: ScriptHost) {
        val all = host.findAll(WEEKLY)
        if (all.isEmpty()) return
        for (match in all.sortedByDescending { it.box.centerX }) {
            host.click(match.box.centerX, match.box.centerY)
            host.sleep(400)
        }
        host.log("[领奖] 点掉 " + all.size + " 个每周加成")
    }

    /** AALC hard_mirror_single_bonuses：只留一个加成，其余点掉 */
    private fun keepOneBonus(host: ScriptHost) {
        val all = host.findAll(WEEKLY).sortedBy { it.box.centerX }
        if (all.size <= 1) return
        for (match in all.drop(1)) {
            host.click(match.box.centerX, match.box.centerY)
            host.sleep(400)
        }
        host.log("[领奖] 单次领取：点掉 " + (all.size - 1) + " 个加成")
    }

    /** AALC pass_coins：OCR 读「x N」里的通行证经验，只做日志 */
    private fun readPassCoins(host: ScriptHost) {
        repeat(5) {
            val band = host.find(COINS_BBOX)?.box ?: host.find(COINS)?.box?.grow(50, 20)
            if (band != null) {
                val texts = host.readText(band, 0.3)
                val joined = texts.joinToString("").lowercase()
                if (joined.contains("x")) {
                    val value = joined.substringAfterLast("x").trim().toIntOrNull()
                    if (value != null) {
                        host.log("[领奖] 本次镜牢领取 " + value + " 个通行证经验")
                        return
                    }
                }
            }
            host.sleep(300)
        }
        host.log("[领奖] 没读到通行证经验数量（可能 UI 变了）")
    }
}