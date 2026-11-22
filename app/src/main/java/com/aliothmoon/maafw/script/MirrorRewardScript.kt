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

    /**
     * 奖励卡偏好（AALC `reward_card.py:44 get_reward_card(model=...)` 的 model 0..3）。
     *
     * 四套顺序见 [REWARD_CARD_MODELS]；`ShopScriptBinding` 目前没传这个键，所以默认 0。
     */
    const val KEY_REWARD_CARD_MODEL = "rewardCardModel"

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


    // ---- 奖励卡（AALC reward_card.py:8-37 的四套顺序 + :44-108 状态机） ----
    private const val CARD_DIR = "mirror/get_reward_card/"
    private const val CARD_STARLIGHT = CARD_DIR + "gain_starlight.png"
    private const val CARD_EGO = CARD_DIR + "gain_ego.png"
    private const val CARD_COST = CARD_DIR + "gain_cost.png"
    private const val CARD_COST_AND_EGO = CARD_DIR + "gain_cost_and_ego.png"
    private const val CARD_EGO_RESOURCE = CARD_DIR + "gain_ego_resource.png"
    private const val CARD_CONFIRM = CARD_DIR + "get_reward_card_confirm_assets.png"
    private const val CARD_CONTINUE = CARD_DIR + "continue_choosing_assets.png"
    private const val LEGEND = "mirror/road_in_mir/legend_assets.png"
    private const val EGO_GIFT_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"
    private const val ACQUIRE_EGO_GIFT_CARD = "mirror/road_in_mir/acquire_ego_gift_card.png"

    /**
     * AALC `reward_card.py:8-37 reward_card_model` 的四套逐张优先级：
     * 0 = 星光/EGO/经费/经费+EGO/EGO 资源；1 = 星光/经费/EGO/…；
     * 2 = 经费/EGO/…/星光；3 = EGO/经费/…/星光。
     * 认不到当前卡就顺延下一张，整轮都认不到就交给管线（保持「认不到就降级」）。
     */
    private val REWARD_CARD_MODELS: List<List<String>> = listOf(
        listOf(CARD_STARLIGHT, CARD_EGO, CARD_COST, CARD_COST_AND_EGO, CARD_EGO_RESOURCE),
        listOf(CARD_STARLIGHT, CARD_COST, CARD_EGO, CARD_COST_AND_EGO, CARD_EGO_RESOURCE),
        listOf(CARD_COST, CARD_EGO, CARD_COST_AND_EGO, CARD_EGO_RESOURCE, CARD_STARLIGHT),
        listOf(CARD_EGO, CARD_COST, CARD_COST_AND_EGO, CARD_EGO_RESOURCE, CARD_STARLIGHT),
    )

    /** AALC reward_card.py:45 的 loop_count = 30 */
    private const val CARD_LOOPS = 30

    /** AALC reward_card.py:47/67/79 的三种状态：选卡、已点领取、等页面切换 */
    private const val CARD_STATE_SELECT = 0
    private const val CARD_STATE_CLAIM = 1
    private const val CARD_STATE_WAIT = 2
    /** AALC reward_card.py:39 的 _CLAIM_RETRY_DELAY = 5.0 秒 */
    private const val CARD_CLAIM_RETRY_MS = 5_000L
    /** 奖励卡界面优先用保守阈值；循环过半 / 过九成后逐级降到 0.70 / 0.62（AALC 是切 normal/aggressive 识别模型） */
    private const val CARD_THRESHOLD = 0.78
    private const val CARD_THRESHOLD_LOOSE = 0.70
    private const val CARD_THRESHOLD_AGGRESSIVE = 0.62

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

        // AALC reward_card.py:44-108：如果交棒时正好停在奖励卡界面，先按四套模型选卡并领走。
        // 正常管线会在 MirrorHandleRewardCard 里处理（DailyTasks 的 reward_cards 四档就是 model 0..3），
        // 这里作为脚本侧兜底；入口不是奖励卡页时下面的 isRewardCardScreen 会直接返回 false。
        val cardModel = params[KEY_REWARD_CARD_MODEL]?.toIntOrNull()?.coerceIn(0, REWARD_CARD_MODELS.size - 1) ?: 0
        if (isRewardCardScreen(host)) {
            if (getRewardCard(host, cardModel)) return
            host.log("[领奖] 奖励卡循环没结果，交还给管线")
            return
        }

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

    /** 奖励卡界面探测：确认按钮 / 继续选择 / 任一奖励卡模板命中即视为在奖励卡页 */
    private fun isRewardCardScreen(host: ScriptHost): Boolean {
        if (host.find(CARD_CONFIRM, 0.75) != null) return true
        if (host.find(CARD_CONTINUE, 0.75) != null) return true
        for (card in listOf(CARD_STARLIGHT, CARD_EGO, CARD_COST, CARD_COST_AND_EGO, CARD_EGO_RESOURCE)) {
            if (host.find(card, CARD_THRESHOLD_LOOSE) != null) return true
        }
        return false
    }

    /**
     * 奖励卡状态机（AALC `reward_card.py:44-108 get_reward_card`）
     *
     * 与上游逐段对应：
     * - :55-62 先认 legend / EGO 确认 / 饰品选择，命中就说明奖励卡流程已经结束；
     * - :63-65 点「继续选择」后把状态重置回 select_reward；
     * - :67-77 claim_reward：认到确认按钮就点，5 秒内切到 wait_result；
     * - :79-89 wait_result：确认按钮还在且过了 5 秒才重点，避免连点；
     * - :91-99 select_reward：按 [REWARD_CARD_MODELS] 当前这套顺序逐张点，命中即进 claim_reward；
     * - :100-109 每轮 retry 后 loop_count-1，<20 切 normal、<10 切 aggressive、<0 报错退出。
     *   本项目没有识别模型档位，用降低匹配阈值模拟同样的「越来越激进」降级。
     */
    private fun getRewardCard(host: ScriptHost, model: Int): Boolean {
        var loopCount = CARD_LOOPS
        var claimRetryAt = 0L
        var state = CARD_STATE_SELECT
        var threshold = CARD_THRESHOLD
        val order = REWARD_CARD_MODELS[model]
        while (loopCount-- > 0 && !host.cancelled) {
            // :54 mouse_to_blank()：手机端点一下空白，避免鼠标悬停高亮影响模板匹配
            host.clickBlank(1)
            if (host.find(LEGEND, 0.75) != null) return true
            if (host.clickTemplate(EGO_GIFT_CONFIRM, 0.75)) {
                host.log("[领奖] 奖励卡领取后识别到 EGO 确认，流程结束")
                return true
            }
            if (host.find(ACQUIRE_EGO_GIFT_CARD, 0.75) != null) {
                host.log("[领奖] 奖励卡领取后进入饰品选择，交回管线")
                return true
            }
            if (host.clickTemplate(CARD_CONTINUE, 0.75)) {
                state = CARD_STATE_SELECT
                continue
            }

            if (state == CARD_STATE_CLAIM) {
                val confirm = host.find(CARD_CONFIRM, 0.75)
                if (confirm != null) {
                    host.click(confirm)
                    claimRetryAt = System.currentTimeMillis() + CARD_CLAIM_RETRY_MS
                    state = CARD_STATE_WAIT
                    host.log("[领奖] 已点奖励卡确认，等待页面切换")
                    continue
                }
            } else if (state == CARD_STATE_WAIT) {
                val confirm = host.find(CARD_CONFIRM, 0.75)
                if (confirm != null && System.currentTimeMillis() >= claimRetryAt) {
                    host.click(confirm)
                    claimRetryAt = System.currentTimeMillis() + CARD_CLAIM_RETRY_MS
                    host.log("[领奖] 奖励卡确认按钮还在，重新点击")
                    continue
                }
            }

            if (state == CARD_STATE_SELECT) {
                var picked = false
                for (card in order) {
                    if (host.clickTemplate(card, threshold)) {
                        picked = true
                        break
                    }
                }
                if (picked) {
                    state = CARD_STATE_CLAIM
                    continue
                }
            }

            if (loopCount < 20) threshold = CARD_THRESHOLD_LOOSE
            if (loopCount < 10) threshold = CARD_THRESHOLD_AGGRESSIVE
            host.sleep(400)
        }
        host.log("[领奖] 奖励卡循环 " + CARD_LOOPS + " 次仍没结果")
        return false
    }
}