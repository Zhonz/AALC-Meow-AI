package com.aliothmoon.maafw.script

/**
 * 一个"额外体系"（AALC 的第二体系，以及本实现自造的第三体系）
 *
 * 电脑版 AALC 的 second_system_action 是四位开关（refs/AALC/app/__init__.py:168）：
 * `[second_system_fuse_IV, second_system_buy, second_system_choose, second_system_power_up]`。
 * 第三位 choose 在上游代码里没有任何使用点，所以这里只留三位 + 时机开关。
 */
internal data class ExtraSystem(
    val system: String,
    /** second_system_setting == 1：不等本体系四级，一进商店就买/升它 */
    val always: Boolean,
    /** action[0]：本体系四级之后换成它的关键词合成，并在出售/合成里保护它的四级饰品 */
    val fuse: Boolean,
    /** action[1]：购买它的体系饰品 */
    val buy: Boolean,
    /** action[3]：升级它的四级饰品 */
    val enhance: Boolean,
)

/**
 * 镜牢商店脚本的参数表
 *
 * 键名由 [ShopScriptBinding] 从 PI option 摊平后写进运行计划；这里只负责"字符串表 → 强类型"。
 * 默认值一律对齐电脑版 AALC 的 TeamSetting（module/config/config_typing.py），
 * 免得"界面显示关、实际却动了玩家库存"。
 */
internal data class ShopPlan(
    val system: String?,
    val sellSystems: List<String>,
    val heal: Boolean,
    val buy: Boolean,
    val sell: Boolean,
    val fuseEnabled: Boolean,
    val fuseAggressive: Boolean,
    val doNotEnhance: Boolean,
    val saveOwnSystemGifts: Boolean,
    val onlyAggressiveFuse: Boolean,
    val onlySystemFuse: Boolean,
    val noSystemFuse: Boolean,
    val alsoEnhance: Boolean,
    val afterIv: AfterIv,
    /** 第二 / 第三体系，按配置顺序排列；门控逻辑对所有体系一致 */
    val extraSystems: List<ExtraSystem>,
    val skillReplacement: Boolean,
    val skillPriority: Int,
    val skillMode: Int,
    val maxKeywordRefresh: Int,
    val maxNormalRefresh: Int,
    val buyLevelGifts: Boolean,
    val mustPurchase: Boolean,
    /** 要跳过商店的层（AALC 的 ignore_shop[0..4] 是五个开关，这里合成一个逗号分隔的表） */
    val ignoreLayers: Set<Int>,

    /**
     * 购买策略（AALC 的 shopping_strategy / shopping_strategy_select）
     *
     * null = 未启用策略（全买）；0..5 见 AALC in_shop.py 的三份名单：
     * 必买只有 0/1/5，三四级只有 1/3/4，体系饰品只有 2/4/5。
     */
    val shoppingStrategy: Int?,

    /** 是否连 must_be_abandoned 的 19 件废品一起卖掉（AALC in_shop.py:858-866） */
    val sellAbandoned: Boolean,

    /** 流血体系下是否跳过白棉花（AALC 的 not_skip_whitegossypium 取反，in_shop.py:236） */
    val skipWhiteGossypium: Boolean,
) {
    /** 合成出四级饰品之后的策略（对应 AALC 的 after_level_IV / after_level_IV_select） */
    enum class AfterIv {
        /** 转非激进：继续用普通合成，不再梭哈 */
        CALM,

        /** 停止合成，改为纯出售 */
        SELL,

        /** 转去追额外体系（第二/第三体系）的四级 */
        SECOND,

        /** 之后几层商店都跳过（AALC 的 ignore_shop[0..4]） */
        SKIP,
    }

    /** 已经合出四级的额外体系（key = 体系名）；用于"追下一个体系"与"不再重复升级" */
    val fuseExtras: List<ExtraSystem> get() = extraSystems.filter { it.fuse }

    companion object {
        fun from(params: Map<String, String>): ShopPlan {
            val system = ScriptParams.text(params, MirrorShopScript.KEY_SYSTEM)?.takeIf { it in MirrorShopScript.SYSTEMS }
            val sellSystems = (params[MirrorShopScript.KEY_SELL_SYSTEMS] ?: "")
                .split(',', ' ', ';')
                .map { it.trim() }
                .filter { it in MirrorShopScript.SYSTEMS }

            val fuseMode = (params[MirrorShopScript.KEY_FUSE_MODE] ?: "off").trim().lowercase()

            return ShopPlan(
                system = system,
                sellSystems = sellSystems,
                heal = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_HEAL),
                buy = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_BUY),
                sell = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_SELL),
                fuseEnabled = fuseMode != "off",
                fuseAggressive = fuseMode == "aggressive",
                doNotEnhance = ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_ENHANCE),
                saveOwnSystemGifts = ScriptParams.flag(params, MirrorShopScript.KEY_SAVE_SYSTEM_GIFTS),
                onlyAggressiveFuse = ScriptParams.flag(params, MirrorShopScript.KEY_ONLY_AGGRESSIVE_FUSE),
                onlySystemFuse = ScriptParams.flag(params, MirrorShopScript.KEY_ONLY_SYSTEM_FUSE),
                noSystemFuse = ScriptParams.flag(params, MirrorShopScript.KEY_NO_SYSTEM_FUSE),
                alsoEnhance = ScriptParams.flag(params, MirrorShopScript.KEY_AGGRESSIVE_ALSO_ENHANCE),
                afterIv = when ((params[MirrorShopScript.KEY_AFTER_IV] ?: "calm").trim().lowercase()) {
                    "sell" -> AfterIv.SELL
                    "second" -> AfterIv.SECOND
                    "skip" -> AfterIv.SKIP
                    else -> AfterIv.CALM
                },
                extraSystems = extras(params, system),
                skillReplacement = ScriptParams.flag(params, MirrorShopScript.KEY_SKILL_REPLACEMENT),
                skillPriority = ScriptParams.int(params, MirrorShopScript.KEY_SKILL_PRIORITY, 0).coerceIn(0, 2),
                skillMode = ScriptParams.int(params, MirrorShopScript.KEY_SKILL_MODE, 0).coerceIn(0, 4),
                maxKeywordRefresh = ScriptParams.int(params, MirrorShopScript.KEY_MAX_KEYWORD_REFRESH, 1).coerceIn(0, 20),
                maxNormalRefresh = ScriptParams.int(params, MirrorShopScript.KEY_MAX_NORMAL_REFRESH, 1).coerceIn(0, 20),
                buyLevelGifts = ScriptParams.flag(params, MirrorShopScript.KEY_BUY_LEVEL_GIFTS, default = true),
                mustPurchase = ScriptParams.flag(params, MirrorShopScript.KEY_MUST_PURCHASE, default = true),
                ignoreLayers = (params[MirrorShopScript.KEY_IGNORE_LAYERS] ?: "")
                    .split(',', ' ', ';', '、')
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 1..99 }
                    .toSet(),
                shoppingStrategy = (params[MirrorShopScript.KEY_SHOPPING_STRATEGY] ?: "all")
                    .trim()
                    .lowercase()
                    .let { if (it == "all" || it.isEmpty()) null else it.toIntOrNull()?.takeIf { v -> v in 0..5 } },
                sellAbandoned = ScriptParams.flag(params, MirrorShopScript.KEY_SELL_ABANDONED, default = true),
                skipWhiteGossypium = ScriptParams.flag(
                    params,
                    MirrorShopScript.KEY_SKIP_WHITE_GOSSYPIUM,
                    default = true,
                ),
            )
        }

        /** 第二 / 第三体系：同一套参数形状，只是键名前缀不同；体系名为空或与本体系重复就跳过 */
        private fun extras(params: Map<String, String>, ownSystem: String?): List<ExtraSystem> {
            val out = mutableListOf<ExtraSystem>()
            for (p in listOf(MirrorShopScript.PREFIX_SECOND, MirrorShopScript.PREFIX_THIRD)) {
                val name = ScriptParams.text(params, p + MirrorShopScript.SUFFIX_SYSTEM)
                    ?.takeIf { it in MirrorShopScript.SYSTEMS && it != ownSystem }
                    ?: continue
                if (out.any { it.system == name }) continue
                val fuse = ScriptParams.flag(params, p + MirrorShopScript.SUFFIX_FUSE)
                val buy = ScriptParams.flag(params, p + MirrorShopScript.SUFFIX_BUY)
                val enhance = ScriptParams.flag(params, p + MirrorShopScript.SUFFIX_ENHANCE)
                // 三项全关就等于没配：留着只会多识别几次
                if (!fuse && !buy && !enhance) continue
                out.add(
                    ExtraSystem(
                        system = name,
                        always = ScriptParams.int(params, p + MirrorShopScript.SUFFIX_SETTING, 0) == 1,
                        fuse = fuse,
                        buy = buy,
                        enhance = enhance,
                    ),
                )
            }
            return out
        }
    }
}
