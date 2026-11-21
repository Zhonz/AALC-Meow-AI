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
    /** 1..20，与游戏内「编队#N」序号一致 */
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

    /** 罪人勾选框（多选）：与 12 个顺位槽保持同步，供只读 sinner_selection 的管线使用 */
    const val SINNERS = "sinner_selection"

    /** 顺位槽的公共前缀，槽 N = 第 N 个被点选的罪人 */
    const val ORDER_SLOT_PREFIX = "sinner_order_slot_"

    /** 游戏规则：前 6 位出战，其余待命 */
    const val DEPLOY_LIMIT = 6

    /** 空槽的 case 名（PI 里 action=DoNothing，等于不点这个人） */
    const val NONE_CASE = "none"

    /**
     * 罪人的固定顺序：与游戏编队界面的 2 行 × 6 列一致（第一行李箱…鸿璐，第二行希斯克利夫…格里高尔）
     *
     * 这个顺序同时是 pipeline 里 SelectSinnerSlot1..12 的 target_offset 顺序，
     * 换了它等于把点击坐标对应错人——只在 PI 改版时跟着改
     */
    val SINNER_ORDER: List<String> = listOf(
        "YiSang",
        "Faust",
        "DonQuixote",
        "Ryoshu",
        "Meursault",
        "HongLu",
        "Heathcliff",
        "Ishmael",
        "Rodion",
        "Sinclair",
        "Outis",
        "Gregor",
    )

    /** 编队页的分区顺序：体系与商店 → 开局 → 战斗 */
    val BASE_NAMES = listOf(
        "team_system",
        "shop_strategy",
        "reward_cards",
        "choose_opening_bonus",
        "opening_items",
        "mirror_opening_items_order",
        "mirror_opening_items_system",
        "mirror_observe_gifts",
        "mirror_opening_bonus",
        "mirror_use_starlight",
        "avoid_skill_3",
        "prioritize_skill_3",
        "defense_first_round",
        "re_formation_each_floor",
    )

    val ORDER_SLOT_NAMES: List<String> = (1..SINNER_ORDER.size).map { ORDER_SLOT_PREFIX + it }

    /** 编队页的字段顺序：先基础设置，再罪人勾选与 12 个顺位槽 */
    val orderedNames: List<String> = BASE_NAMES + SINNERS + ORDER_SLOT_NAMES

    val names: Set<String> = orderedNames.toSet()

    fun isTeamScoped(optionName: String): Boolean = optionName in names

    /** 任务当前选的编队号；未选或不是数字 = null */
    fun selectedTeam(values: Map<String, OptionValue>): Int? =
        (values[TEAM_SELECT] as? OptionValue.SingleCase)?.case?.toIntOrNull()

    /** 编队里已选的罪人，按点选顺序（= 出战顺位 1..12） */
    fun orderedSinners(preset: TeamPreset): List<String> = ORDER_SLOT_NAMES.mapNotNull { name ->
        val case = (preset.optionValues[name] as? OptionValue.SingleCase)?.case
        case?.takeIf { it != NONE_CASE && it in SINNER_ORDER }
    }

    /** 第 [index]（0 基）位的身份：0..5 出战，6..11 待命 */
    fun isDeployed(index: Int): Boolean = index < DEPLOY_LIMIT

    /**
     * 点一下罪人头像：未选 → 追加到队尾（顺位就变成「下一个」）；已选 → 移出并把后面的顺位顺延
     *
     * 顺位由点选顺序决定，这也是 AALC 的做法：上游 team_formation.py 按 sinner_order 的 1..12 依次点击
     */
    fun toggleSinner(preset: TeamPreset, sinner: String): TeamPreset {
        val current = orderedSinners(preset)
        val next = if (sinner in current) current - sinner else current + sinner
        return applySinners(preset, next)
    }

    /**
     * 把有序阵容写回 12 个顺位槽
     *
     * 未占用的槽显式写 "none"：留空会回退到任务里旧的顺位值，多出来的点击会把队伍点乱
     */
    fun applySinners(preset: TeamPreset, ordered: List<String>): TeamPreset {
        val trimmed = ordered.filter { it in SINNER_ORDER }.distinct().take(SINNER_ORDER.size)
        val slots = ORDER_SLOT_NAMES.mapIndexed { index, name ->
            name to OptionValue.SingleCase(trimmed.getOrNull(index) ?: NONE_CASE)
        }
        return preset.copy(
            optionValues = preset.optionValues + slots +
                (SINNERS to OptionValue.MultipleCases(trimmed)),
        )
    }

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
        if (scoped.isEmpty()) return values
        val merged = values + scoped
        // 0.4.x 之前存的编队可能只写了前几个顺位槽：补齐成 none，否则会漏用任务里的旧槽值
        if (scoped.keys.none { it in ORDER_SLOT_NAMES }) return merged
        val padding = ORDER_SLOT_NAMES
            .filterNot { it in scoped }
            .associateWith { OptionValue.SingleCase(NONE_CASE) }
        return merged + padding
    }

    /** 补全 1..20 的编队，顺序固定；越界或重复的脏数据直接丢弃，顺位槽补成完整 12 格 */
    fun normalize(presets: List<TeamPreset>): List<TeamPreset> = TeamPreset.numbers.map { number ->
        val preset = presets.firstOrNull { it.teamNumber == number } ?: TeamPreset(number)
        if (preset.optionValues.keys.any { it in ORDER_SLOT_NAMES }) {
            applySinners(preset, orderedSinners(preset))
        } else {
            preset
        }
    }
}
