package com.aliothmoon.maafw.script

import com.aliothmoon.maafw.domain.OptionDefinition
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.ProjectDefinition
import com.aliothmoon.maafw.domain.TaskDefinition
import com.aliothmoon.maafw.runner.TaskScript

/**
 * 把 PI option 摊平成脚本参数
 *
 * 这些值决定的是「脚本怎么决策」（刷几次、买不买、合不合、升不升级），不是管线覆盖片段，
 * 所以必须原样送到特权进程的脚本里。读取一律走 [OptionDefinition]，缺值回落到 PI 的
 * default_case —— 与 UI 显示的默认值同源，避免「界面显示开、实际没开」。
 */
object ShopScriptBinding {
    /** 镜牢任务的入口节点；用它认任务比按名字认可靠（PI 里任务名可以改） */
    private const val MIRROR_ENTRY = "MirrorDungeonEntry"

    /** 十个体系；与 PI team_system 的 case 名、AALC 的 all_systems 完全同序同拼写 */
    private val SYSTEMS = setOf(
        "burn", "bleed", "tremor", "rupture", "poise",
        "sinking", "charge", "slash", "pierce", "blunt",
    )

    fun scriptsFor(
        task: TaskDefinition,
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
    ): List<TaskScript> {
        if (task.entry != MIRROR_ENTRY) return emptyList()
        // 任务页的「战斗操作策略」是 AALC 的同名总开关，与编队页两个细项取并集
        val strategy = singleCase(definition, values, "BattleSkillStrategy")
        val defenseFirst =
            flag(definition, values, "defense_first_round", default = false) == "1" ||
                strategy == "defense_first"
        val avoidSkill3 =
            flag(definition, values, "avoid_skill_3", default = false) == "1" ||
                strategy == "avoid_s3"
        return listOf(
            TaskScript(
                scriptId = MirrorShopScript.id,
                entry = MirrorShopScript.entry,
                resumeEntry = MirrorShopScript.resumeEntry,
                maxRounds = MirrorShopScript.maxRounds,
                params = shopParams(definition, values),
            ),
            // 寻路同样只在镜牢里跑：管线认到「MirrorHandleRoad」就交棒，脚本按 AALC 的节点权重挑路
            TaskScript(
                scriptId = MirrorRoadScript.id,
                entry = MirrorRoadScript.entry,
                resumeEntry = MirrorRoadScript.resumeEntry,
                maxRounds = MirrorRoadScript.maxRounds,
                params = mapOf(
                    // 精确楼层优先；没填就退回 AALC 的 floor_3_exit（打完第 3 层走人 = 第 4 层起收工）
                    MirrorRoadScript.KEY_EXIT_FLOOR to inputText(definition, values, "mirror_exit_floor", "floor")
                        .ifEmpty {
                            if (flag(definition, values, "floor_3_exit", default = false)) "4" else ""
                        },
                ),
            ),
            // 主题包：按 AALC 的关键词权重选包（原来只是盲点 500,540）
            TaskScript(
                scriptId = MirrorThemePackScript.id,
                entry = MirrorThemePackScript.entry,
                resumeEntry = MirrorThemePackScript.resumeEntry,
                maxRounds = MirrorThemePackScript.maxRounds,
                params = mapOf(
                    MirrorThemePackScript.KEY_NORMAL_TO_HARD to
                        inputText(definition, values, "mirror_normal_to_hard_floor", "floor"),
                    MirrorThemePackScript.KEY_HARD_MODE to
                        (singleCase(definition, values, "hard_mirror") ?: "normal"),
                    MirrorThemePackScript.KEY_SELECT_EVENT_PACK to
                        flag(definition, values, "select_event_pack", default = false),
                    MirrorThemePackScript.KEY_SKIP_EVENT_PACK to
                        flag(definition, values, "skip_event_pack", default = false),
                ),
            ),
            // 开局星光：管线认到星光加成页（coins_assets.png）就交棒，按 opening_bonus 点卡
            TaskScript(
                scriptId = MirrorStarScript.id,
                entry = MirrorStarScript.entry,
                resumeEntry = MirrorStarScript.resumeEntry,
                maxRounds = MirrorStarScript.maxRounds,
                params = mapOf(
                    MirrorStarScript.KEY_OPENING_BONUS to
                        inputText(definition, values, "mirror_opening_bonus", "bonus"),
                    MirrorStarScript.KEY_USE_STARLIGHT to
                        flag(definition, values, "mirror_use_starlight", default = true),
                ),
            ),
            // 事件：管线认到事件界面（event/skip_assets.png）就交棒，由脚本按 AALC 的按钮优先级处理
            TaskScript(
                scriptId = MirrorEventScript.id,
                entry = MirrorEventScript.entry,
                resumeEntry = MirrorEventScript.resumeEntry,
                maxRounds = MirrorEventScript.maxRounds,
                params = mapOf(
                    MirrorEventScript.KEY_AVOID_UNKNOWN to
                        flag(definition, values, "defense_for_solo", default = false),
                ),
            ),
            // 战斗：管线认到「MirrorHandleBattle」交棒；首回合防御由 defense_first_round 开关决定
            TaskScript(
                scriptId = MirrorBattleScript.id,
                entry = MirrorBattleScript.entry,
                resumeEntry = MirrorBattleScript.resumeEntry,
                maxRounds = MirrorBattleScript.maxRounds,
                params = mapOf(
                    MirrorBattleScript.KEY_DEFENSE_FIRST_ROUND to
                        if (defenseFirst) "1" else "0",
                    MirrorBattleScript.KEY_AVOID_SKILL_3 to
                        if (avoidSkill3) "1" else "0",
                    MirrorBattleScript.KEY_PRIORITIZE_SKILL_3 to
                        flag(definition, values, "prioritize_skill_3", default = false),
                ),
            ),
        )
    }

    private fun shopParams(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
    ): Map<String, String> {
        val system = singleCase(definition, values, "team_system")?.takeIf { it in SYSTEMS }
        val sellSystems = multipleCases(definition, values, "shop_sell_systems")
            .filter { it in SYSTEMS }
            .joinToString(",")
        val second = singleCase(definition, values, "shop_second_system")
            ?.takeIf { it in SYSTEMS }
            ?.let { it }
        // 总开关优先：选「直接离开」就一分钱不花，细项一律按住 —— 与电脑版 AALC 的 ignore_shop 同义
        val leaveOnly = singleCase(definition, values, "shop_strategy") == "leave"
        val fuseMode = if (leaveOnly) "off" else singleCase(definition, values, "shop_fuse_mode") ?: "normal"

        return linkedMapOf(
            MirrorShopScript.KEY_SYSTEM to (system ?: ""),
            MirrorShopScript.KEY_SELL_SYSTEMS to sellSystems,
            MirrorShopScript.KEY_DO_NOT_HEAL to
                if (leaveOnly) "1" else flag(definition, values, "shop_do_not_heal", default = false),
            MirrorShopScript.KEY_DO_NOT_BUY to
                if (leaveOnly) "1" else flag(definition, values, "shop_do_not_buy", default = false),
            // 出售会不可逆地清掉玩家饰品，默认关：没配弃置体系就当没开
            MirrorShopScript.KEY_DO_NOT_SELL to
                if (leaveOnly) "1" else flag(definition, values, "shop_do_not_sell", default = true),
            MirrorShopScript.KEY_DO_NOT_ENHANCE to
                if (leaveOnly) "1" else flag(definition, values, "shop_do_not_enhance", default = false),
            MirrorShopScript.KEY_FUSE_MODE to fuseMode,
            MirrorShopScript.KEY_SAVE_SYSTEM_GIFTS to
                if (leaveOnly) "0" else flag(definition, values, "shop_fuse_save_system", default = false),
            MirrorShopScript.KEY_ONLY_AGGRESSIVE_FUSE to
                if (leaveOnly) "1" else flag(definition, values, "shop_fuse_only_aggressive", default = false),
            MirrorShopScript.KEY_ONLY_SYSTEM_FUSE to
                if (leaveOnly) "1" else flag(definition, values, "shop_fuse_only_system", default = false),
            MirrorShopScript.KEY_NO_SYSTEM_FUSE to
                if (leaveOnly) "1" else flag(definition, values, "shop_fuse_no_system", default = false),
            MirrorShopScript.KEY_AGGRESSIVE_ALSO_ENHANCE to
                if (leaveOnly) "0" else flag(definition, values, "shop_aggressive_also_enhance", default = false),
            MirrorShopScript.KEY_AFTER_IV to
                if (leaveOnly) "calm" else (singleCase(definition, values, "shop_after_iv") ?: "calm"),
            MirrorShopScript.KEY_SECOND_SYSTEM to (second ?: ""),
            MirrorShopScript.KEY_SECOND_BUY to
                if (leaveOnly) "0" else flag(definition, values, "shop_second_buy", default = false),
            MirrorShopScript.KEY_SECOND_PROTECT to
                if (leaveOnly) "0" else flag(definition, values, "shop_second_protect", default = false),
            MirrorShopScript.KEY_SECOND_ENHANCE to
                if (leaveOnly) "0" else flag(definition, values, "shop_second_enhance", default = false),
            MirrorShopScript.KEY_SKILL_REPLACEMENT to
                if (leaveOnly) "0" else flag(definition, values, "shop_skill_replacement", default = false),
            MirrorShopScript.KEY_SKILL_PRIORITY to
                number(definition, values, "shop_skill_priority", 0),
            MirrorShopScript.KEY_SKILL_MODE to
                number(definition, values, "shop_skill_mode", 0),
            MirrorShopScript.KEY_MAX_KEYWORD_REFRESH to
                if (leaveOnly) "0" else number(definition, values, "shop_keyword_refresh", 1),
            MirrorShopScript.KEY_MAX_NORMAL_REFRESH to
                if (leaveOnly) "0" else number(definition, values, "shop_normal_refresh", 0),
            MirrorShopScript.KEY_BUY_LEVEL_GIFTS to
                if (leaveOnly) "0" else flag(definition, values, "shop_buy_level_gifts", default = true),
            MirrorShopScript.KEY_MUST_PURCHASE to
                if (leaveOnly) "0" else flag(definition, values, "shop_must_purchase", default = true),
            MirrorShopScript.KEY_IGNORE_LAYERS to
                if (leaveOnly) "" else inputText(definition, values, "shop_ignore_layers", "layers"),
            // 购买策略：all = 不启用策略（全买），与 AALC shopping_strategy 关闭时一致
            MirrorShopScript.KEY_SHOPPING_STRATEGY to
                if (leaveOnly) "all" else (singleCase(definition, values, "shop_shopping_strategy") ?: "all"),
            MirrorShopScript.KEY_SECOND_SETTING to
                if (leaveOnly) "1" else (singleCase(definition, values, "shop_second_setting") ?: "0"),
            MirrorShopScript.KEY_SELL_ABANDONED to
                if (leaveOnly) "0" else flag(definition, values, "shop_sell_abandoned", default = true),
        )
    }

    /** input 型 option 取某字段的文本；没存过就用 PI 的默认值 */
    private fun inputText(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
        name: String,
        field: String,
    ): String {
        val option = definition.options[name] as? OptionDefinition.Input ?: return ""
        val stored = (values[name] as? OptionValue.Inputs)?.values?.get(field)
        if (!stored.isNullOrBlank()) return stored
        return option.fields.firstOrNull { it.name == field }?.default ?: ""
    }

    /** switch/select 取选中 case；没存过就用 PI 的 default_case */
    private fun singleCase(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
        name: String,
    ): String? {
        val option = definition.options[name] as? OptionDefinition.Choice ?: return null
        return when (val value = values[name]) {
            is OptionValue.SingleCase -> value.case
            is OptionValue.MultipleCases -> value.cases.firstOrNull()
            else -> option.effectiveDefaultCase
        }
    }

    private fun multipleCases(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
        name: String,
    ): List<String> {
        val option = definition.options[name] as? OptionDefinition.Checkbox ?: return emptyList()
        return when (val value = values[name]) {
            is OptionValue.MultipleCases -> value.cases
            is OptionValue.SingleCase -> listOf(value.case)
            else -> option.defaultCases
        }
    }

    /** switch 语义：case "Yes" 为开；缺失时按 [default] */
    private fun flag(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
        name: String,
        default: Boolean,
    ): String {
        val option = definition.options[name] as? OptionDefinition.Choice
            ?: return if (default) "1" else "0"
        val selected = singleCase(definition, values, name) ?: option.effectiveDefaultCase
        val on = when (selected?.lowercase()) {
            "yes", "true", "on", "1" -> true
            "no", "false", "off", "0" -> false
            else -> default
        }
        return if (on) "1" else "0"
    }

    private fun number(
        definition: ProjectDefinition,
        values: Map<String, OptionValue>,
        name: String,
        fallback: Int,
    ): String = singleCase(definition, values, name)?.toIntOrNull()?.toString() ?: fallback.toString()
}
