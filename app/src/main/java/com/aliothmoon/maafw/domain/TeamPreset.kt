package com.aliothmoon.maafw.domain

import kotlinx.serialization.Serializable

/**
 * 一支编队（对应游戏内「编队#N」）的预设
 *
 * 只承载「编队域」选项：罪人阵容与顺位、队伍体系、商店策略、奖励卡、开局饰品、战斗开关。
 * 运行期由 [com.aliothmoon.maafw.runner.RunPlanBuilder] 把这些值覆盖到「使用该编队」的任务上，
 * 任务选项面板不再逐项展开这些字段（见 [TeamOptionScope]）
 */
@Serializable
data class TeamPreset(
    /** 1..7，与游戏内「编队#N」序号一致 */
    val teamNumber: Int,
    /** 仅本机展示的备注名，不进 pipeline */
    val remark: String = "",
    /** 键为 option 名，值为用户选择；未出现 = 用 PI 默认 */
    val optionValues: Map<String, OptionValue> = emptyMap(),
) {
    companion object {
        const val MIN_TEAM = 1

        /** PI 的 daily_teams 选项给了 1..20；这里跟上，别让 UI 比引擎少选 */
        const val MAX_TEAM = 20
        val numbers: List<Int> = (MIN_TEAM..MAX_TEAM).toList()
    }
}

/**
 * 编队域的边界：哪些 option 归编队页所有
 *
 * 这里多一个名字，任务选项面板就少展开一项；[mergedValues] 是运行期唯一的合并点，
 * 与 UI 的隐藏清单必须来自同一份定义，否则会出现「面板里设了却不生效」
 */
object TeamOptionScope {
    /** 「使用编队」下拉：它本身留在任务面板，是选择器不是编队内容 */
    const val TEAM_SELECT = "daily_teams"

    /** 罪人勾选框（多选） */
    const val SINNERS = "sinner_selection"

    /** 出战顺位 #1..#12 的公共前缀 */
    const val ORDER_SLOT_PREFIX = "sinner_order_slot_"

    private val BASE_NAMES = listOf(
        "team_system",
        "shop_strategy",
        "reward_cards",
        "choose_opening_bonus",
        "opening_items",
        "avoid_skill_3",
        "defense_first_round",
    )

    /** 编队页的字段顺序：先三下拉，再两个战斗开关，最后罪人与顺位 */
    val orderedNames: List<String> = BASE_NAMES + SINNERS + (1..12).map { ORDER_SLOT_PREFIX + it }

    val names: Set<String> = orderedNames.toSet()

    fun isTeamScoped(optionName: String): Boolean = optionName in names

    /** 任务当前选的编队号；未选或不是数字 = null */
    fun selectedTeam(values: Map<String, OptionValue>): Int? =
        (values[TEAM_SELECT] as? OptionValue.SingleCase)?.case?.toIntOrNull()

    /**
     * 任务选项值 + 编队预设 → 实际编译用的值
     *
     * 三档回退：任务没选编队 / 该编队没配过任何值 → 原样返回，保持旧配置行为；
     * 配过 → 编队域字段以编队为准（UI 已不再让用户在任务面板改这些值）
     */
    fun mergedValues(
        presets: List<TeamPreset>,
        values: Map<String, OptionValue>,
    ): Map<String, OptionValue> {
        val team = selectedTeam(values) ?: return values
        val preset = presets.firstOrNull { it.teamNumber == team } ?: return values
        if (preset.optionValues.isEmpty()) return values
        val scoped = preset.optionValues.filterKeys { it in names }
        return if (scoped.isEmpty()) values else values + scoped
    }

    /** 补全 1..20 的编队，顺序固定；越界或重复的脏数据直接丢弃 */
    fun normalize(presets: List<TeamPreset>): List<TeamPreset> = TeamPreset.numbers.map { number ->
        presets.firstOrNull { it.teamNumber == number } ?: TeamPreset(number)
    }
}
