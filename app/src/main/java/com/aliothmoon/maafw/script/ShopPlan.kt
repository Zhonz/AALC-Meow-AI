package com.aliothmoon.maafw.script

/**
 * 镜牢商店脚本的参数表
 *
 * 键名由 [ShopScriptBinding] 从 PI option 摊平后写进运行计划；这里只负责"字符串表 → 强类型"，
 * 并给每一项兜一个"不折腾玩家库存"的默认值。
 *
 * 对应关系取的是电脑版 AALC 的 TeamSetting（module/config/config_typing.py）里商店相关字段，
 * 语义同名同序，方便直接吃 AALC 导出的 YAML。
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
    val secondSystem: String?,
    val secondBuy: Boolean,
    val secondProtect: Boolean,
    val secondEnhance: Boolean,
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

    /** 第二体系时机：true = 一直买（second_system_setting=1），false = 等本体系四级 */
    val secondAlways: Boolean,

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

        /** 转去追第二体系的四级 */
        SECOND,

        /** 之后几层商店都跳过（AALC 的 ignore_shop[0..4]） */
        SKIP,
    }

    companion object {
        fun from(params: Map<String, String>): ShopPlan {
            val system = ScriptParams.text(params, MirrorShopScript.KEY_SYSTEM)?.takeIf { it in MirrorShopScript.SYSTEMS }
            val sellSystems = (params[MirrorShopScript.KEY_SELL_SYSTEMS] ?: "")
                .split(',', ' ', ';')
                .map { it.trim() }
                .filter { it in MirrorShopScript.SYSTEMS }

            val fuseMode = (params[MirrorShopScript.KEY_FUSE_MODE] ?: "normal").trim().lowercase()
            val second = ScriptParams.text(params, MirrorShopScript.KEY_SECOND_SYSTEM)?.takeIf {
                it in MirrorShopScript.SYSTEMS
            }

            return ShopPlan(
                system = system,
                sellSystems = sellSystems,
                heal = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_HEAL),
                buy = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_BUY),
                sell = !ScriptParams.flag(params, MirrorShopScript.KEY_DO_NOT_SELL, default = true),
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
                secondSystem = second,
                secondBuy = ScriptParams.flag(params, MirrorShopScript.KEY_SECOND_BUY),
                secondProtect = ScriptParams.flag(params, MirrorShopScript.KEY_SECOND_PROTECT),
                secondEnhance = ScriptParams.flag(params, MirrorShopScript.KEY_SECOND_ENHANCE),
                skillReplacement = ScriptParams.flag(params, MirrorShopScript.KEY_SKILL_REPLACEMENT),
                skillPriority = ScriptParams.int(params, MirrorShopScript.KEY_SKILL_PRIORITY, 0).coerceIn(0, 2),
                skillMode = ScriptParams.int(params, MirrorShopScript.KEY_SKILL_MODE, 0).coerceIn(0, 4),
                maxKeywordRefresh = ScriptParams.int(params, MirrorShopScript.KEY_MAX_KEYWORD_REFRESH, 1).coerceIn(0, 20),
                maxNormalRefresh = ScriptParams.int(params, MirrorShopScript.KEY_MAX_NORMAL_REFRESH, 0).coerceIn(0, 20),
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
                secondAlways = ScriptParams.int(params, MirrorShopScript.KEY_SECOND_SETTING, 0) == 1,
                sellAbandoned = ScriptParams.flag(params, MirrorShopScript.KEY_SELL_ABANDONED, default = true),
                skipWhiteGossypium = ScriptParams.flag(
                    params,
                    MirrorShopScript.KEY_SKIP_WHITE_GOSSYPIUM,
                    default = true,
                ),
            )
        }
    }
}
