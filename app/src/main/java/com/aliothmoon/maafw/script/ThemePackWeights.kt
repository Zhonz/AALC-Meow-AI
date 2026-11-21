package com.aliothmoon.maafw.script

/**
 * 镜牢主题包权重表
 *
 * 数据来自上游 AALC `assets/config/theme_pack_list.example.yaml`（默认权重，不含用户自定义）。
 * 键是主题包名字里会出现的**关键词**（不是完整名字）：OCR 出名字后按子串匹配，取命中的最大值。
 * 四张表对应「普通 / 困难」×「中文 / 英文」，与 AALC 的 theme_pack_list(_hard)(_cn) 一一对应。
 * 数值越大越优先；负数表示「尽量别选」；未知兜底 -5 是游戏更新后新包的权重。
 */
object ThemePackWeights {
    /** AALC 的 preferred_thresholds：达到这个权重就直接选，否则先刷新几次 */
    const val PREFERRED_THRESHOLD = 0

    /** 识别不到任何关键词时的兜底权重 */
    const val UNKNOWN_WEIGHT = -5

    val ZH_NORMAL: Map<String, Int> = mapOf(
        "伏行" to -5,
        "体检" to -5,
        "体险" to -5,
        "信仰" to 0,
        "切琢" to -4,
        "区的奇" to 0,
        "压抑的" to -5,
        "句点" to 0,
        "吞噬的" to 0,
        "地狱鸡" to 1,
        "宅邸" to 0,
        "寒微" to 0,
        "巡礼" to -5,
        "工坊" to 0,
        "开店" to -3,
        "当斩" to 0,
        "当碎" to -1,
        "当贯" to 0,
        "彷徨" to -5,
        "情感压迫" to 0,
        "情感困惑" to 0,
        "情感屈从" to 0,
        "情感懒" to 0,
        "情感评判" to 0,
        "情感饥渴" to 0,
        "斩切" to -5,
        "无作为" to 0,
        "无归属" to -1,
        "无慈悲" to -5,
        "无法去爱" to -5,
        "时间杀人" to -5,
        "未曾面对" to 0,
        "未知" to -5,
        "某个世界" to 0,
        "沉溺者" to -5,
        "沉迷的" to 0,
        "海·边" to 0,
        "海边" to 0,
        "清扫" to -5,
        "湖的" to 0,
        "琢春" to -4,
        "空转" to -5,
        "穿刺者" to 0,
        "粉碎者" to 0,
        "紫罗兰" to -5,
        "经验" to -5,
        "绝望" to -5,
        "绞丝" to -5,
        "自动" to -2,
        "落泪" to 0,
        "落的忧" to 0,
        "落花" to -5,
        "虚张声势" to -5,
        "谋杀" to -5,
        "赌徒" to 0,
        "遗忘" to 0,
        "钉与锤" to 0,
        "骨断" to -5,
        "黄昏" to -5,
    )

    val ZH_HARD: Map<String, Int> = mapOf(
        "保存的" to -3,
        "停滞的" to -5,
        "凄惨的" to -5,
        "凤·皇" to 10,
        "凤皇" to 10,
        "刀与作" to -5,
        "割舍" to -5,
        "动力" to 0,
        "号线" to -5,
        "叹息" to 0,
        "地震" to 0,
        "定义为" to -5,
        "家族" to -4,
        "开园" to -2,
        "循环呼" to -5,
        "心意相" to -5,
        "摇曳" to -5,
        "教材" to -5,
        "无尽的" to -2,
        "无改变" to -5,
        "暴食" to -3,
        "束缚的" to -5,
        "沉于" to 0,
        "沉沦泛" to -5,
        "沉溺的" to -5,
        "渗出的" to 0,
        "漫溢的" to -5,
        "电闪雷鸣" to 0,
        "盛火" to -5,
        "破坏性" to 0,
        "破竹" to -5,
        "纷繁" to -3,
        "终焉" to -5,
        "缭乱的" to 0,
        "自以为" to -5,
        "血海" to -5,
        "观望" to -5,
        "解放的" to -5,
    )

    val EN_NORMAL: Map<String, Int> = mapOf(
        "Hatred" to -5,
        "Wander" to -5,
        "abyss" to -5,
        "addict" to 0,
        "automated" to -2,
        "bones" to -5,
        "bullet" to 0,
        "campout" to -3,
        "certain" to 0,
        "check" to -5,
        "chick" to 1,
        "cleaved" to 0,
        "compassion" to -5,
        "cravi" to 0,
        "crushed" to -1,
        "crushers" to 0,
        "deg" to 0,
        "degraded" to 0,
        "dev" to 0,
        "devoured" to 0,
        "dicers" to -5,
        "dolen" to 0,
        "dregs" to 0,
        "dusk" to -5,
        "faith" to 0,
        "flood" to -5,
        "flowers" to -5,
        "forgot" to 0,
        "gambl" to 0,
        "judgment" to 0,
        "lake" to 0,
        "miracle" to 0,
        "mne" to -5,
        "mnestic" to -5,
        "nag" to 0,
        "nagel" to 0,
        "nsignif" to 0,
        "outcast" to -1,
        "penetra" to 0,
        "pierced" to 0,
        "repressed" to -5,
        "repression" to 0,
        "s.e.a" to 0,
        "seduct" to 0,
        "shed" to -1,
        "spring" to -4,
        "ssed" to -5,
        "subserv" to 0,
        "sweep" to -5,
        "tearful" to 0,
        "thread" to -5,
        "time" to -5,
        "tread" to -5,
        "treadwheel" to -5,
        "unconf" to 0,
        "unknown" to -5,
        "unloving" to -5,
        "vain" to -5,
        "violet" to -5,
        "warp" to -5,
        "workshop" to 0,
    )

    val EN_HARD: Map<String, Int> = mapOf(
        "Blade" to -5,
        "Text" to -5,
        "Theb" to 10,
        "Unsever" to -5,
        "anarchy" to -3,
        "b·e" to 10,
        "corpses" to -5,
        "creed" to -3,
        "deluge" to -5,
        "dizzying" to 0,
        "ending" to -5,
        "evil" to -5,
        "excessive" to -5,
        "external" to 0,
        "four" to -4,
        "haze" to -5,
        "heartb" to -5,
        "inert" to -5,
        "line" to -5,
        "might" to -5,
        "opening" to -2,
        "pang" to 0,
        "pitiful" to -5,
        "poised" to -5,
        "pride" to -5,
        "procession" to -2,
        "readvent" to -3,
        "sanguine" to 0,
        "season" to -5,
        "seismic" to 0,
        "sigh" to 0,
        "sunk" to -5,
        "supply" to 0,
        "tangling" to -5,
        "thunder" to 0,
        "unbound" to -5,
        "unch" to -5,
        "unchanging" to -5,
        "witnessing" to -5,
        "xte" to 0,
    )

    /** 中英文一起查：OCR 出来是哪种语言都能匹配 */
    fun weightOf(text: String, hard: Boolean): Int {
        val table = if (hard) ZH_HARD + EN_HARD else ZH_NORMAL + EN_NORMAL
        val lowered = text.lowercase()
        var best = UNKNOWN_WEIGHT
        for ((keyword, weight) in table) {
            if (lowered.contains(keyword.lowercase()) && weight > best) best = weight
        }
        return best
    }

    /** 命中的关键词（日志用，真机核对是不是认错了包） */
    fun keywordOf(text: String, hard: Boolean): String? {
        val table = if (hard) ZH_HARD + EN_HARD else ZH_NORMAL + EN_NORMAL
        val lowered = text.lowercase()
        return table.entries
            .filter { lowered.contains(it.key.lowercase()) }
            .maxByOrNull { it.value }
            ?.key
    }
}
