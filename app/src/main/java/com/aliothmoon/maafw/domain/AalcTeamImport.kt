package com.aliothmoon.maafw.domain

/**
 * 一次 AALC 编队设置的导入结果
 *
 * [unsupported] 只列「文件里有值、但本引擎没有对应管线」的字段；空值/默认值的字段不算，
 * 免得把用户的配置文件整份念一遍
 */
data class AalcTeamImportResult(
    val preset: TeamPreset,
    /** 按文件里的 sinner_order 还原出的出战顺序 */
    val sinners: List<String>,
    /** 成功映射到本引擎选项的字段数（含阵容与顺位） */
    val mappedCount: Int,
    val unsupported: List<String>,
    /** 文件里一个罪人都没有 —— 通常不是编队文件 */
    val emptyRoster: Boolean,
)

/**
 * 导入 AALC（电脑版）导出的编队设置
 *
 * AALC 的「导出队伍设置」是 ruamel 落盘的 YAML（module/config/team_import_export.py），
 * 字段是平铺的 TeamSetting：chosen_sinners / sinner_order 两个 12 元数组决定阵容与顺位，
 * 其余是体系、商店、奖励卡、战斗等开关。这里不引 YAML 依赖，只实现这份文件用到的子集：
 * 顶层键 + 标量 + 块序列/行内序列，嵌套映射（custom_theme_pack_weight）整体跳过
 */
object AalcTeamImport {

    /** AALC 的体系序号 → 本 PI 的 case 名（两边顺序一致，0=烧伤 … 9=打击） */
    private val SYSTEM_CASES = listOf(
        "burn", "bleed", "tremor", "rupture", "poise",
        "sinking", "charge", "slash", "pierce", "blunt",
    )

    /**
     * AALC 的奖励卡优先度序号 → 本 PI 的 case 名
     *
     * 0 星光→饰品… 与 1 星光→钱… 都落在 starlight_first（本 PI 没有「钱在饰品前」的档位），
     * 2 钱→饰品 → cost_first，3 饰品→钱 → ego_first
     */
    private val REWARD_CARD_CASES = mapOf(
        0 to "starlight_first",
        1 to "starlight_cost_first",
        2 to "cost_first",
        3 to "ego_first",
    )

    /** AALC 的 opening_items_select → 本 PI 的三件饰品点击顺序 */
    private val OPENING_ITEM_ORDERS = listOf("123", "132", "213", "231", "312", "321")

    /** 本引擎没有对应管线的 AALC 字段：有值就统计，导入时忽略 */
    private val UNSUPPORTED_KEYS = listOf(
        // 小指良单通（AALC 的 defense_for_solo）：本引擎没有对应的连续防御状态机
        "defense_for_solo", "defense_for_solo_turns",
        // 每层重新编队：管线目前只用一次编队
        "re_formation_each_floor",
        // 固定队伍用途（困难/普通）与开局道具体系：没有对应选项
        "fixed_team_use", "fixed_team_use_select",
        // 编队码自动组队：尚未实现
        "use_team_code", "team_code",
        // 自定义主题包权重（嵌套映射，解析器跳过）
        "use_custom_theme_pack_weight", "custom_theme_pack_weight",
        // 统计字段，导出时已被 AALC 剔除
        "sinners_be_select",
    )

    /** 嵌套映射占位：解析时整块跳过，但要能区分「空值」与「有嵌套内容」 */
    private val NESTED = Any()

    /** 顶层键 + 标量 + 序列的 YAML 子集解析；解析不了的行直接跳过，不抛异常 */
    fun parseYaml(text: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val lines = text.removePrefix("﻿").lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index++
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            // 缩进行是上一个键的嵌套内容，由下面收集列表时消费
            if (line.firstOrNull()?.isWhitespace() == true) continue
            val colon = keyColon(line) ?: continue
            val key = line.take(colon).trim()
            if (key.isEmpty()) continue
            val raw = stripComment(line.substring(colon + 1)).trim()
            if (raw.isNotEmpty()) {
                result[key] = parseScalar(raw)
                continue
            }
            val items = ArrayList<Any?>()
            var sawNested = false
            while (index < lines.size) {
                val next = lines[index]
                if (next.isBlank() || next.trimStart().startsWith("#")) {
                    index++
                    continue
                }
                val trimmed = next.trim()
                val isListItem = trimmed == "-" || trimmed.startsWith("- ")
                if (isListItem) {
                    items += parseScalar(trimmed.removePrefix("-").trim())
                    index++
                    continue
                }
                if (next.firstOrNull()?.isWhitespace() == true) {
                    sawNested = true
                    index++
                    continue
                }
                break
            }
            result[key] = when {
                items.isNotEmpty() -> items
                sawNested -> NESTED
                else -> null
            }
        }
        return result
    }

    /** 像是 AALC 的编队文件：至少带一个编队字段 */
    fun looksLikeAalcTeam(data: Map<String, Any?>): Boolean =
        data.containsKey("chosen_sinners") ||
            data.containsKey("sinner_order") ||
            data.containsKey("team_system") ||
            data.containsKey("remark_name")

    /** AALC 的 TeamSetting（已解析成 Map）→ 本项目的编队预设 */
    fun toPreset(teamNumber: Int, data: Map<String, Any?>): AalcTeamImportResult {
        val orders = intList(data["sinner_order"])
        val chosen = intList(data["chosen_sinners"])
        val ordered = LinkedHashSet<String>()
        // sinner_order[i] = 第 i 个罪人的顺位；按 1..12 反查即得到点选顺序
        for (rank in 1..TeamOptionScope.SINNER_ORDER.size) {
            val sinnerIndex = orders.indexOfFirst { it == rank }
            if (sinnerIndex in TeamOptionScope.SINNER_ORDER.indices) {
                ordered += TeamOptionScope.SINNER_ORDER[sinnerIndex]
            }
        }
        if (ordered.isEmpty()) {
            chosen.forEachIndexed { sinnerIndex, flag ->
                if (flag != 0 && sinnerIndex in TeamOptionScope.SINNER_ORDER.indices) {
                    ordered += TeamOptionScope.SINNER_ORDER[sinnerIndex]
                }
            }
        }

        val values = LinkedHashMap<String, OptionValue>()
        int(data["team_system"])?.let { system ->
            if (system in SYSTEM_CASES.indices) {
                values["team_system"] = OptionValue.SingleCase(SYSTEM_CASES[system])
            }
        }
        if (bool(data["reward_cards"]) == true) {
            val selected = int(data["reward_cards_select"]) ?: 3
            values["reward_cards"] = OptionValue.SingleCase(REWARD_CARD_CASES[selected] ?: "ego_first")
        }
        bool(data["opening_items"])?.let {
            values["opening_items"] = OptionValue.SingleCase(if (it) YES else NO)
            if (it) {
                int(data["opening_items_system"])?.let { index ->
                    SYSTEM_CASES.getOrNull(index)?.let {
                        values["mirror_opening_items_system"] = OptionValue.SingleCase(it)
                    }
                }
                int(data["opening_items_select"])?.let { index ->
                    OPENING_ITEM_ORDERS.getOrNull(index)?.let {
                        values["mirror_opening_items_order"] = OptionValue.SingleCase(it)
                    }
                }
            }
        }
        val openingBonus = intList(data["opening_bonus"])
        if (openingBonus.any { it > 0 }) {
            values["choose_opening_bonus"] = OptionValue.SingleCase(YES)
            // AALC 的 0/1/2/3（不选/一级/二级/三级）与本 PI 的输入框一一对应
            values["mirror_opening_bonus"] =
                OptionValue.Inputs(mapOf("bonus" to openingBonus.joinToString(",")))
        }
        // 观测 EGO 饰品：AALC 的列表项就是 bleed_3_3_7 这种 id，直接拼成脚本要的逗号串
        val observe = stringList(data["observe_ego_gift_selected"])
        if (bool(data["observe_ego_gift"]) == true && observe.isNotEmpty()) {
            values["mirror_observe_gifts"] =
                OptionValue.Inputs(mapOf("gifts" to observe.joinToString(",")))
        }
        bool(data["use_starlight"])?.let {
            // AALC：True = 不换钱，把星光留给加成卡（点 no_convert_star_to_cost）
            values["mirror_use_starlight"] = OptionValue.SingleCase(if (it) YES else NO)
        }

        // 商店：AALC 的 shop_strategy 0=只出售 1=普通合成 2=四级优先
        val fuseMode = when {
            bool(data["do_not_fuse"]) == true -> "off"
            int(data["shop_strategy"]) == 0 -> "off"
            int(data["shop_strategy"]) == 1 -> "normal"
            int(data["shop_strategy"]) == 2 -> "aggressive"
            else -> null
        }
        fuseMode?.let { values["shop_fuse_mode"] = OptionValue.SingleCase(it) }

        val sellSystems = SYSTEM_CASES.filter { bool(data["system_" + it]) == true }
        if (sellSystems.isNotEmpty()) {
            values["shop_sell_systems"] = OptionValue.MultipleCases(sellSystems)
        }
        mapOf(
            "do_not_heal" to "shop_do_not_heal",
            "do_not_buy" to "shop_do_not_buy",
            "do_not_sell" to "shop_do_not_sell",
            "do_not_enhance" to "shop_do_not_enhance",
            "only_aggressive_fuse" to "shop_fuse_only_aggressive",
            "do_not_system_fuse" to "shop_fuse_no_system",
            "only_system_fuse" to "shop_fuse_only_system",
            "aggressive_also_enhance" to "shop_aggressive_also_enhance",
            "aggressive_save_systems" to "shop_fuse_save_system",
            "skill_replacement" to "shop_skill_replacement",
            "prioritize_skill_3" to "prioritize_skill_3",
        ).forEach { (from, to) ->
            bool(data[from])?.let { values[to] = OptionValue.SingleCase(if (it) YES else NO) }
        }

        // AALC 的 not_skip_whitegossypium 语义相反：开=不跳过白棉花
        bool(data["not_skip_whitegossypium"])?.let { notSkip ->
            values["shop_skip_whitegossypium"] = OptionValue.SingleCase(if (notSkip) NO else YES)
        }

        if (bool(data["after_level_IV"]) == true) {
            val modes = listOf("sell", "calm", "second", "skip")
            int(data["after_level_IV_select"])?.let { index ->
                modes.getOrNull(index)?.let { values["shop_after_iv"] = OptionValue.SingleCase(it) }
            }
        }
        if (bool(data["shopping_strategy"]) == true) {
            int(data["shopping_strategy_select"])?.let { index ->
                if (index in 0..5) {
                    values["shop_shopping_strategy"] = OptionValue.SingleCase(index.toString())
                }
            }
        }
        if (bool(data["second_system"]) == true) {
            int(data["second_system_select"])?.let { index ->
                SYSTEM_CASES.getOrNull(index)?.let {
                    values["shop_second_system"] = OptionValue.SingleCase(it)
                }
            }
            int(data["second_system_setting"])?.let {
                values["shop_second_setting"] = OptionValue.SingleCase(if (it == 1) "1" else "0")
            }
            // second_system_action = [合成四级, 购买, 选择, 升级]
            val actions = intList(data["second_system_action"])
            actions.getOrNull(1)?.let {
                values["shop_second_buy"] = OptionValue.SingleCase(if (it != 0) YES else NO)
            }
            actions.getOrNull(3)?.let {
                values["shop_second_enhance"] = OptionValue.SingleCase(if (it != 0) YES else NO)
            }
        }
        int(data["max_keyword_refresh"])?.let {
            if (it in 0..3) values["shop_keyword_refresh"] = OptionValue.SingleCase(it.toString())
        }
        int(data["max_normal_refresh"])?.let {
            if (it in 0..2) values["shop_normal_refresh"] = OptionValue.SingleCase(it.toString())
        }
        int(data["skill_replacement_mode"])?.let {
            if (it in 0..4) values["shop_skill_mode"] = OptionValue.SingleCase(it.toString())
        }
        int(data["skill_replacement_select"])?.let {
            values["shop_skill_priority"] = OptionValue.SingleCase(minOf(it, 2).toString())
        }

        val ignoreLayers = intList(data["ignore_shop"])
            .mapIndexedNotNull { index, flag -> if (flag != 0) index + 1 else null }
        if (ignoreLayers.isNotEmpty()) {
            values["shop_ignore_layers"] =
                OptionValue.Inputs(mapOf("layers" to ignoreLayers.joinToString(" ")))
        }
        int(data["normal_to_hard_floor"])?.let {
            if (it > 0) {
                values["mirror_normal_to_hard_floor"] =
                    OptionValue.Inputs(mapOf("floor" to it.toString()))
            }
        }
        bool(data["avoid_skill_3"])?.let {
            values["avoid_skill_3"] = OptionValue.SingleCase(if (it) YES else NO)
        }
        bool(data["defense_first_round"])?.let {
            values["defense_first_round"] = OptionValue.SingleCase(if (it) YES else NO)
        }

        val remark = (data["remark_name"] as? String).orEmpty().trim()
        val preset = TeamOptionScope.applySinners(
            preset = TeamPreset(teamNumber = teamNumber, remark = remark, optionValues = values.toMap()),
            ordered = ordered.toList(),
        )
        return AalcTeamImportResult(
            preset = preset,
            sinners = ordered.toList(),
            mappedCount = values.size + (if (ordered.isEmpty()) 0 else 2),
            unsupported = UNSUPPORTED_KEYS.filter { isSet(data[it]) },
            emptyRoster = ordered.isEmpty(),
        )
    }

    private const val YES = "Yes"
    private const val NO = "No"

    /** 「字段有值」判定：开关为 true、数字非 0、字符串非空、列表里有非默认项 */
    private fun isSet(value: Any?): Boolean = when (value) {
        null, NESTED -> false
        is Boolean -> value
        is Int -> value != 0
        is Double -> value != 0.0
        is String -> value.isNotBlank()
        is List<*> -> value.any { item ->
            when (item) {
                null -> false
                is Boolean -> item
                is Number -> item.toDouble() != 0.0
                is String -> item.isNotBlank()
                else -> true
            }
        }
        else -> true
    }

    private fun int(value: Any?): Int? = when (value) {
        is Int -> value
        is Double -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun bool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun intList(value: Any?): List<Int> = when (value) {
        is List<*> -> value.mapNotNull { int(it) }
        is Int -> listOf(value)
        else -> emptyList()
    }

    /** 第一个不在引号里的冒号 */
    private fun keyColon(line: String): Int? {
        var quote = '\u0000'
        for (index in line.indices) {
            val char = line[index]
            if (quote != '\u0000') {
                if (char == quote) quote = '\u0000'
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                ':' -> return index
            }
        }
        return null
    }

    /** 去掉行尾注释：只在引号外、且 # 前面是空白时才算注释 */
    private fun stripComment(text: String): String {
        var quote = '\u0000'
        for (index in text.indices) {
            val char = text[index]
            if (quote != '\u0000') {
                if (char == quote) quote = '\u0000'
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                '#' -> if (index == 0 || text[index - 1].isWhitespace()) return text.take(index)
            }
        }
        return text
    }

    private fun parseScalar(raw: String): Any? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length >= 2 && text.first() == text.last() && (text.first() == '\'' || text.first() == '"')) {
            val body = text.substring(1, text.length - 1)
            return if (text.first() == '\'') body.replace("''", "'") else unescapeDouble(body)
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            val inner = text.substring(1, text.length - 1).trim()
            if (inner.isEmpty()) return emptyList<Any?>()
            return splitFlow(inner).map { parseScalar(it) }
        }
        when (text.lowercase()) {
            "true" -> return true
            "false" -> return false
            "null", "~" -> return null
        }
        text.toIntOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return it }
        return text
    }

    private fun unescapeDouble(body: String): String {
        val builder = StringBuilder(body.length)
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (char == '\\' && index + 1 < body.length) {
                when (val next = body[index + 1]) {
                    'n' -> builder.append('\n')
                    't' -> builder.append('\t')
                    else -> builder.append(next)
                }
                index += 2
                continue
            }
            builder.append(char)
            index++
        }
        return builder.toString()
    }

    private fun splitFlow(text: String): List<String> {
        val parts = ArrayList<String>()
        val builder = StringBuilder()
        var quote = '\u0000'
        for (char in text) {
            if (quote != '\u0000') {
                builder.append(char)
                if (char == quote) quote = '\u0000'
                continue
            }
            when (char) {
                '\'', '"' -> {
                    quote = char
                    builder.append(char)
                }
                ',' -> {
                    parts += builder.toString()
                    builder.clear()
                }
                else -> builder.append(char)
            }
        }
        parts += builder.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
