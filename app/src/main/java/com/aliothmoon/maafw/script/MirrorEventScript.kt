package com.aliothmoon.maafw.script

/**
 * 镜牢事件：把 AALC `tasks/mirror/mirror.py:1241-1374 event_handling()` 的优先级搬到手机上。
 *
 * 原版状态机用 event_chance 计数 + OCR 判断「判定/check」字样，再按一长串优先级逐个尝试按钮。
 * 手机端没有 OCR 之外的信息，但事件界面上的按钮就那么几个，所以照它的**优先级顺序**逐个试探即可：
 *
 * 1. skip（跳过事件，AALC 用 times=6 连点）
 * 2. choices + select_first_option（有选项时选第一个）
 * 3. unknown_event（未知事件：AALC 在 defense_for_solo 时避开，否则点第一个选项）
 * 4. select_to_gain_ego（花代价拿 EGO）
 * 5. advantage_check（有利判定）
 * 6. gain_a_ego_depending_on_result（按结果拿 EGO）
 * 7. perform_the_check_feature（做判定：反复点第一个选项直到出结果）
 * 8. continue / proceed / commence / commence_battle（继续）
 * 9. ego_gift_get_confirm（收下 EGO 饰品）
 *
 * 认不出任何按钮就交还管线 —— 宁可停下来，也不在事件界面乱点（乱点可能直接开战或丢饰品）。
 */
object MirrorEventScript : ScriptTask {
    override val id: String = "mirror.event"
    override val entry: String = "MirrorHandleEvent"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 10

    private const val SKIP = "event/skip_assets.png"
    private const val CHOICES = "event/choices_assets.png"
    private const val SELECT_FIRST = "event/select_first_option_assets.png"
    private const val UNKNOWN = "event/unknown_event.png"
    private const val SELECT_TO_GAIN_EGO = "event/select_to_gain_ego.png"
    private const val ADVANTAGE_CHECK = "event/advantage_check.png"
    private const val GAIN_RESULT = "event/gain_a_ego_depending_on_result.png"
    private const val PERFORM = "event/perform_the_check_feature_assets.png"
    private const val PERFORM_2 = "event/perform_the_check_feature_2_assets.png"
    private const val CONTINUE = "event/continue_assets.png"
    private const val PROCEED = "event/proceed_assets.png"
    private const val COMMENCE = "event/commence_assets.png"
    private const val COMMENCE_BATTLE = "event/commence_battle_assets.png"
    private const val GIFT_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"

    /** AALC 的 defense_for_solo：单通时避开「未知事件」的选项（这里只能做到不点它） */
    const val KEY_AVOID_UNKNOWN = "avoidUnknown"

    private const val MAX_STEPS = 40
    private const val STEP_WAIT = 900L
    private const val CHECK_STEPS = 12

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val avoidUnknown = ScriptParams.flag(params, KEY_AVOID_UNKNOWN)
        var step = 0
        while (!host.cancelled && step < MAX_STEPS) {
            step++
            if (clickAny(host, CONTINUE, PROCEED, COMMENCE, COMMENCE_BATTLE)) continue
            if (host.clickTemplate(SKIP, 0.8)) {
                host.sleep(STEP_WAIT)
                continue
            }
            if (host.find(CHOICES, 0.8) != null || host.find(SELECT_FIRST, 0.75) != null) {
                if (host.clickTemplate(SELECT_FIRST, 0.75)) {
                    host.sleep(STEP_WAIT)
                    continue
                }
            }
            if (host.find(UNKNOWN, 0.8) != null) {
                if (avoidUnknown) {
                    host.log("[事件] 未知事件：按单通设置不选，先空点退出")
                    host.clickBlank(1)
                    host.sleep(STEP_WAIT)
                    continue
                }
                if (host.clickTemplate(SELECT_FIRST, 0.75)) {
                    host.sleep(STEP_WAIT)
                    continue
                }
            }
            if (clickAny(host, SELECT_TO_GAIN_EGO, ADVANTAGE_CHECK, GAIN_RESULT)) continue
            if (host.find(PERFORM, 0.75) != null || host.find(PERFORM_2, 0.8) != null) {
                performCheck(host)
                continue
            }
            if (host.clickTemplate(GIFT_CONFIRM, 0.8)) {
                host.sleep(STEP_WAIT)
                continue
            }
            host.log("[事件] 当前界面没有认得的事件按钮，交还给管线")
            return
        }
    }

    /** 做判定：反复点第一个选项，直到判定按钮消失并出现「继续」类按钮 */
    private fun performCheck(host: ScriptHost) {
        host.log("[事件] 做判定，反复选第一个选项")
        var i = 0
        while (!host.cancelled && i < CHECK_STEPS) {
            i++
            if (host.clickTemplate(CONTINUE, 0.75) || host.clickTemplate(PROCEED, 0.75) ||
                host.clickTemplate(COMMENCE, 0.75) || host.clickTemplate(COMMENCE_BATTLE, 0.75)
            ) {
                host.sleep(STEP_WAIT)
                return
            }
            if (!host.clickTemplate(SELECT_FIRST, 0.75)) {
                host.sleep(600)
                return
            }
            host.sleep(700)
        }
    }

    private fun clickAny(host: ScriptHost, vararg templates: String): Boolean {
        for (t in templates) {
            if (host.clickTemplate(t, 0.75)) {
                host.sleep(STEP_WAIT)
                return true
            }
        }
        return false
    }
}
