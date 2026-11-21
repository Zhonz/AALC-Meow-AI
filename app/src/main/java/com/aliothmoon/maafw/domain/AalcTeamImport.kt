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
        1 to "starlight_first",
        2 to "cost_first",
        3 to "ego_first",
    )

    /** 本引擎没有对应管线的 AALC 字段：有值就统计，导入时忽略 */
    private val UNSUPPORTED_KEYS = listOf(
        "shop_strategy",
        "system_burn", "system_bleed", "system_tremor", "system_rupture", "system_poise",
        "system_sinking", "system_charge", "system_slash", "system_pierce", "system_blunt",
        "do_not_heal", "do_not_buy", "do_not_fuse", "do_not_sell", "do_not_enhance",
        "only_aggressive_fuse", "do_not_system_fuse", "only_system_fuse",
        "aggressive_also_enhance", "aggressive_save_systems",
        "prioritize_skill_3", "defense_for_solo", "defense_for_solo_turns",
        "re_formation_each_floor", "normal_to_hard_floor", "use_starlight",
        "fixed_team_use", "fixed_team_use_select",
        "shopping_strategy", "shopping_strategy_select",
        "opening_items_system", "opening_items_select",
        "after_level_IV", "after_level_IV_select",
        "second_system", "second_system_select", "second_system_setting", "second_system_action",
        "observe_ego_gift", "observe_ego_gift_selected",
        "skill_replacement", "skill_replacement_select", "skill_replacement_mode",
        "ignore_shop", "max_keyword_refresh", "max_normal_refresh",
        "use_team_code", "team_code", "custom_theme_pack_weight",
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
        }
        if (intList(data["opening_bonus"]).any { it > 0 }) {
            values["choose_opening_bonus"] = OptionValue.SingleCase(YES)
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
