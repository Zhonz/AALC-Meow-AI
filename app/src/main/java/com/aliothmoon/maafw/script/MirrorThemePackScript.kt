package com.aliothmoon.maafw.script

/**
 * 镜牢主题包选择：把 AALC `tasks/mirror/select_theme_pack.py` 的权重选包搬到手机上。
 *
 * 原版的做法是：找出三个卡包各自的标记图标 → 以图标为锚点裁出「卡包名字」那块 → OCR →
 * 用 `theme_pack_list*.yaml` 里的关键词查权重（取命中的最大值）→ 选权重最大的那张；
 * 权重最高的还低于优选阈值（0）就点刷新，最多刷 3 次，仍不行就选当前最高分的。
 *
 * 这里逐条照抄，权重表在 [ThemePackWeights]（从上游 yaml 生成，中英双份）。
 * 与旧管线里的 `MirrorPickThemePackConfirm`（盲点 500,540）相比，至少是「看名字挑」而不是碰运气。
 */
object MirrorThemePackScript : ScriptTask {
    override val id: String = "mirror.theme"
    override val entry: String = "MirrorHandleThemePackFeature"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 8

    private const val LEGEND = "mirror/road_in_mir/legend_assets.png"
    private const val FEATURES = "mirror/theme_pack/theme_pack_features.png"
    private const val REFRESH = "mirror/theme_pack/refresh_assets.png"
    private const val HARD_MARK = "mirror/theme_pack/hard_assets.png"
    private const val NORMAL_MARK = "mirror/theme_pack/normal_assets.png"

    /** 以标记图标为锚点裁「名字」区域，数值来自 AALC（720p：210/60/60/390 × 2/3） */
    private const val CROP_LEFT = 140
    private const val CROP_TOP = 40
    private const val CROP_RIGHT = 40
    private const val CROP_BOTTOM = 260

    private const val MAX_PACKS = 6
    private const val MAX_REFRESH = 3
    private const val REFRESH_WAIT = 2_200L
    private const val SELECT_WAIT = 2_500L

    /** 旧管线的兜底坐标：实在认不出卡包时点一下中间偏左（原来就是这么点的） */
    private const val FALLBACK_X = 500
    private const val FALLBACK_Y = 540

    @Volatile
    private var attempts = 0

    override fun resetSession() {
        attempts = 0
    }

    override fun run(host: ScriptHost, params: Map<String, String>) {
        if (host.find(LEGEND, 0.7) != null) return
        if (attempts >= MAX_REFRESH + 1) {
            host.log("本任务已经选过 " + attempts + " 次主题包，交还给管线")
            return
        }

        val hard = host.find(HARD_MARK, 0.7) != null &&
            host.find(NORMAL_MARK, 0.7) == null
        var packs = weigh(host, hard)
        if (packs.isEmpty()) {
            host.log("没认出主题包卡包，按旧管线的位置点一下中间偏左")
            host.click(FALLBACK_X, FALLBACK_Y)
            host.sleep(SELECT_WAIT)
            attempts++
            return
        }

        var best = packs.maxByOrNull { it.weight }
        var refresh = 0
        while (refresh < MAX_REFRESH && (best == null || best.weight < ThemePackWeights.PREFERRED_THRESHOLD)) {
            if (host.cancelled) return
            if (!host.clickTemplate(REFRESH, 0.75)) break
            host.log("当前最高权重 " + (best?.weight ?: ThemePackWeights.UNKNOWN_WEIGHT) + "，刷新主题包（第 " + (refresh + 1) + " 次）")
            host.sleep(REFRESH_WAIT)
            refresh++
            packs = weigh(host, hard)
            if (packs.isEmpty()) break
            best = packs.maxByOrNull { it.weight }
        }

        val chosen = best ?: packs.maxByOrNull { it.weight } ?: return
        host.log(
            "选主题包：权重 " + chosen.weight + "（关键词 " + (chosen.keyword ?: "未知") + "），" +
                "OCR = " + chosen.text,
        )
        host.click(chosen.x, chosen.y)
        host.sleep(SELECT_WAIT)
        attempts++
    }

    /** 每个卡包裁一块名字区域做 OCR，按关键词表打分 */
    private fun weigh(host: ScriptHost, hard: Boolean): List<PackCandidate> {
        val marks = host.findAll(FEATURES, 0.7, limit = MAX_PACKS)
        if (marks.isEmpty()) return emptyList()
        return marks.map { mark ->
            val left = (mark.box.x - CROP_LEFT).coerceAtLeast(0)
            val top = (mark.box.y - CROP_TOP).coerceAtLeast(0)
            val right = (mark.box.x + CROP_RIGHT).coerceAtMost(host.screenWidth)
            val bottom = (mark.box.y + CROP_BOTTOM).coerceAtMost(host.screenHeight)
            val roi = ScriptRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
            val text = host.readText(roi).joinToString(" ").trim()
            PackCandidate(
                x = mark.box.centerX,
                y = mark.box.centerY,
                text = text,
                weight = ThemePackWeights.weightOf(text, hard),
                keyword = ThemePackWeights.keywordOf(text, hard),
            )
        }
    }

    private data class PackCandidate(
        val x: Int,
        val y: Int,
        val text: String,
        val weight: Int,
        val keyword: String?,
    )
}
