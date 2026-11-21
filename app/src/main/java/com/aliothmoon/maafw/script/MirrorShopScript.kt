package com.aliothmoon.maafw.script

/**
 * 镜牢商店：把 AALC 的 `tasks/mirror/in_shop.py` 里「进店该干的活」搬到手机上
 *
 * 引擎单靠 pipeline JSON 表达不了这段逻辑——它要读剩余经费、按 (行, 列) 排序点饰品、按次数
 * 刷新、还要在失败时兜底退出。所以管线跑到 [entry] 节点就交棒，脚本按 AALC 的同一顺序执行
 * （治疗 → 出售 → 购买 → 刷新 → 离开），干完回到 [resumeEntry] 让管线继续。
 *
 * 本版**不做**合成 / 升级 / 技能替换：它们会不可逆地改动玩家饰品库存，而这台机器上没有真机
 * 可验证点击位置（见 docs/VERIFICATION.md）。宁可少做，也不做错。
 */
object MirrorShopScript : ScriptTask {
    override val id: String = "mirror.shop"
    override val entry: String = "MirrorHandleShop"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 12

    // ── 参数键（由 RunPlanBuilder 从 PI option 摊平后塞进来） ──
    const val KEY_SYSTEM = "system"
    const val KEY_SELL_SYSTEMS = "sellSystems"
    const val KEY_DO_NOT_HEAL = "doNotHeal"
    const val KEY_DO_NOT_BUY = "doNotBuy"
    const val KEY_DO_NOT_SELL = "doNotSell"
    const val KEY_MAX_KEYWORD_REFRESH = "maxKeywordRefresh"
    const val KEY_MAX_NORMAL_REFRESH = "maxNormalRefresh"
    const val KEY_BUY_LEVEL_GIFTS = "buyLevelGifts"
    const val KEY_MUST_PURCHASE = "mustPurchase"
    const val KEY_AGGRESSIVE = "aggressive"

    // ── 界面元素（相对 assets/resource/image） ──
    private const val SHOP_MARK = "mirror/road_in_mir/shop.png"
    private const val LEGEND = "mirror/road_in_mir/legend_assets.png"
    private const val GIFT_CONFIRM = "mirror/road_in_mir/ego_gift_get_confirm_assets.png"
    private const val COINS = "mirror/shop/shop_coins_assets.png"
    private const val PURCHASE = "mirror/shop/purchase_assets.png"
    private const val LEAVE = "mirror/shop/leave_assets.png"
    private const val LEAVE_CONFIRM = "mirror/shop/leave_shop_confirm_assets.png"
    private const val REFRESH = "mirror/shop/refresh_assets.png"
    private const val REFRESH_KEYWORD = "mirror/shop/refresh_keyword_assets.png"
    private const val REFRESH_KEYWORD_CONFIRM = "mirror/shop/refresh_keyword_confirm_assets.png"
    private const val HEAL_ONE = "mirror/shop/heal_sinner/heal_sinner_assets.png"
    private const val HEAL_ALL = "mirror/shop/heal_sinner/heal_all_sinner_assets.png"
    private const val HEAL_RETURN = "mirror/shop/heal_sinner/heal_sinner_return_assets.png"
    private const val SELL_TAB = "mirror/shop/sell_gift_assets.png"
    private const val SELL_CONFIRM = "mirror/shop/sell_gift_confirm_assets.png"
    private const val SOLD_CONFIRM = "mirror/shop/enhance_and_fuse_and_sell_confirm_assets.png"
    private const val LEVEL_III = "mirror/shop/level_III_to_buy.png"
    private const val LEVEL_IV = "mirror/shop/level_IV_to_buy.png"
    private const val MONEY_BBOX = "mirror/shop/my_money_bbox.png"
    private const val MONEY_BBOX_HEAL = "mirror/shop/my_money_heal_bbox.png"

    private val MUST_PURCHASE = listOf(
        "mirror/shop/must_purchase/carmilla.png",
        "mirror/shop/must_purchase/homeward.png",
        "mirror/shop/must_purchase/lithograph.png",
        "mirror/shop/must_purchase/piece_of_relationship.png",
        "mirror/shop/must_purchase/phlebotomy_pack.png",
    )

    /** AALC 的十体系（与 PI 的 team_system case 同名） */
    private val SYSTEMS = listOf(
        "burn", "bleed", "tremor", "rupture", "poise",
        "sinking", "charge", "slash", "pierce", "blunt",
    )

    private fun keywordIcon(system: String) = "mirror/shop/keyword/keyword_$system.png"

    private fun giftIcon(system: String) = "mirror/shop/enhance_gifts/shop_$system.png"

    // ── 参数 ──

    private data class ShopPlan(
        val system: String?,
        val sellSystems: List<String>,
        val heal: Boolean,
        val buy: Boolean,
        val sell: Boolean,
        val maxKeywordRefresh: Int,
        val maxNormalRefresh: Int,
        val buyLevelGifts: Boolean,
        val mustPurchase: Boolean,
        val aggressive: Boolean,
    ) {
        companion object {
            fun from(params: Map<String, String>): ShopPlan {
                val system = ScriptParams.text(params, KEY_SYSTEM)?.takeIf { it in SYSTEMS }
                val sell = (params[KEY_SELL_SYSTEMS] ?: "")
                    .split(',', ' ', ';')
                    .map { it.trim() }
                    .filter { it in SYSTEMS }
                return ShopPlan(
                    system = system,
                    sellSystems = sell,
                    heal = !ScriptParams.flag(params, KEY_DO_NOT_HEAL),
                    buy = !ScriptParams.flag(params, KEY_DO_NOT_BUY),
                    sell = !ScriptParams.flag(params, KEY_DO_NOT_SELL, default = true) && sell.isNotEmpty(),
                    maxKeywordRefresh = ScriptParams.int(params, KEY_MAX_KEYWORD_REFRESH, 1).coerceIn(0, 20),
                    maxNormalRefresh = ScriptParams.int(params, KEY_MAX_NORMAL_REFRESH, 0).coerceIn(0, 20),
                    buyLevelGifts = ScriptParams.flag(params, KEY_BUY_LEVEL_GIFTS, default = true),
                    mustPurchase = ScriptParams.flag(params, KEY_MUST_PURCHASE, default = true),
                    aggressive = ScriptParams.flag(params, KEY_AGGRESSIVE, default = true),
                )
            }
        }
    }

    // ── 入口 ──

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val plan = ShopPlan.from(params)
        host.log(
            "[商店] 接管：体系=${plan.system ?: "未配置"} 治疗=${plan.heal} 购买=${plan.buy} " +
                "出售=${plan.sell} 关键词刷新=${plan.maxKeywordRefresh} 普通刷新=${plan.maxNormalRefresh}",
        )

        val anchor = host.find(SHOP_MARK, 0.75) ?: host.find(COINS, 0.8)
        if (anchor == null) {
            host.log("[商店] 没识别到商店界面，交还给管线")
            return
        }

        try {
            if (plan.heal && !host.cancelled) heal(host)
            if (plan.sell && !host.cancelled) sell(host, plan)
            if (plan.buy && !host.cancelled) buy(host, plan)
        } finally {
            // 不管上面哪一步失败，都要把玩家从商店里带出来，否则后面所有识别都对不上
            if (!host.cancelled) leave(host) else host.log("[商店] 已取消，不再自动离开商店")
        }
    }

    // ── 治疗 ──

    private fun heal(host: ScriptHost) {
        val before = readMoney(host, inHeal = false)
        if (before != null && before < 100) {
            host.log("[商店] 经费 $before 不足 100，跳过治疗")
            return
        }
        host.clickBlank(2)
        if (!host.clickTemplate(HEAL_ONE, 0.8)) {
            host.log("[商店] 没找到治疗入口，跳过治疗")
            return
        }
        host.log("[商店] 打开治疗页")
        host.sleep(700)

        // 「全体治疗」按钮点一次就够：再点一次会多花一份经费
        if (host.clickTemplate(HEAL_ALL, 0.8)) {
            host.log("[商店] 全体治疗")
            host.sleep(900)
        } else {
            host.log("[商店] 没找到全体治疗按钮")
        }

        repeat(3) { attempt ->
            if (host.clickTemplate(HEAL_RETURN, 0.8)) {
                host.sleep(600)
                return
            }
            host.log("[商店] 治疗页没找到返回（第 ${attempt + 1} 次）")
            host.sleep(400)
        }
        host.clickBlank(2)
    }

    // ── 出售 ──

    /**
     * 按「要弃置的体系」卖饰品
     *
     * AALC 的 sell_gifts 还会遍历 must_be_abandoned 坐标表、按 gifts_list_block 滚动一屏，
     * 那些依赖它自己维护的饰品数据库（我们这边没有），这里只做「体系图标 → 点选 → 确认」这一条。
     */
    private fun sell(host: ScriptHost, plan: ShopPlan) {
        if (plan.sellSystems.isEmpty()) {
            host.log("[商店] 没配置要出售的体系，跳过出售")
            return
        }
        host.log("[商店] 出售体系：${plan.sellSystems.joinToString()}")
        host.clickBlank(2)
        if (!host.clickTemplate(SELL_TAB, 0.8)) {
            host.log("[商店] 没找到出售入口，跳过出售")
            return
        }
        host.sleep(800)

        var sold = 0
        var round = 0
        while (!host.cancelled && round < MAX_SELL_ROUNDS) {
            round++
            val target = plan.sellSystems
                .asSequence()
                .flatMap { system -> host.findAll(giftIcon(system), 0.85).asSequence() }
                .sortedWith(compareBy({ it.box.y / GIFT_ROW_BUCKET }, { it.box.x }))
                .firstOrNull()
                ?: break
            host.click(target)
            host.sleep(600)
            if (!host.clickTemplate(SELL_CONFIRM, 0.8) && !host.clickTemplate(SOLD_CONFIRM, 0.8)) {
                host.log("[商店] 出售确认没出现，结束出售")
                host.clickBlank(2)
                break
            }
            sold++
            host.sleep(500)
        }
        host.log("[商店] 出售结束：$sold 件")
        host.clickBlank(2)
    }

    // ── 购买 ──

    private fun buy(host: ScriptHost, plan: ShopPlan) {
        var keywordRefreshUsed = 0
        var normalRefreshUsed = 0
        var round = 0
        var bought = 0
        host.clickBlank(2)

        while (!host.cancelled && round < MAX_BUY_ROUNDS) {
            round++
            if (plan.mustPurchase) {
                for (commodity in MUST_PURCHASE) {
                    if (host.cancelled) break
                    if (buyOne(host, commodity, 0.85, MUST_PURCHASE_ATTEMPTS)) bought++
                }
            }

            if (plan.buyLevelGifts && plan.aggressive) {
                if (buyOne(host, LEVEL_IV, 0.82)) bought++
                if (buyOne(host, LEVEL_III, 0.82)) bought++
            }

            val system = plan.system
            if (system != null) {
                bought += buySystemGifts(host, system, MAX_SYSTEM_GIFTS)
            }

            val money = readMoney(host, inHeal = false)
            if (money == null) {
                host.log("[商店] 读不到剩余经费，结束购买")
                break
            }
            host.log("[商店] 剩余经费 $money，本轮已买 $bought 件")

            if (system != null && keywordRefreshUsed < plan.maxKeywordRefresh && money >= KEYWORD_REFRESH_COST) {
                if (refreshKeyword(host, system)) {
                    keywordRefreshUsed++
                    continue
                }
                break
            }
            if (normalRefreshUsed < plan.maxNormalRefresh && money >= NORMAL_REFRESH_COST) {
                if (refreshNormal(host)) {
                    normalRefreshUsed++
                    continue
                }
                break
            }
            break
        }
        host.log("[商店] 购买结束：$bought 件，关键词刷新 $keywordRefreshUsed 次，普通刷新 $normalRefreshUsed 次")
        host.clickBlank(2)
    }

    /** 点一个商品 → 点购买 → 吃掉「获得饰品」确认；成功返回 true */
    private fun buyOne(
        host: ScriptHost,
        template: String,
        threshold: Double,
        attempts: Int = 3,
    ): Boolean {
        val match = host.find(template, threshold) ?: return false
        host.click(match)
        host.sleep(700)
        repeat(attempts) { attempt ->
            if (host.clickTemplate(PURCHASE, 0.85)) {
                host.sleep(700)
                host.clickTemplate(GIFT_CONFIRM, 0.8)
                host.sleep(400)
                return true
            }
            if (host.clickTemplate(GIFT_CONFIRM, 0.8)) {
                // 已经买下来了，只是确认框还在
                host.sleep(400)
                return true
            }
            if (attempt == attempts - 1) {
                host.log("[商店] 购买按钮没出现：$template")
                host.clickBlank(2)
            } else {
                host.sleep(400)
            }
        }
        return false
    }

    /**
     * 买本体系的饰品
     *
     * AALC 用 sort_points 按 (y // 40, x) 排序后逐个点，并在每次买完后 re_sort_points 重算
     * 剩下的坐标（因为列表会往上收）。我们每轮重新识别一次列表位置，等价且更省心。
     */
    private fun buySystemGifts(host: ScriptHost, system: String, max: Int): Int {
        var bought = 0
        while (!host.cancelled && bought < max) {
            val matches = host.findAll(giftIcon(system), 0.85)
            if (matches.isEmpty()) break
            val target = matches.sortedWith(
                compareBy({ it.box.y / GIFT_ROW_BUCKET }, { it.box.x }),
            ).first()
            host.click(target)
            host.sleep(800)
            if (host.clickTemplate(PURCHASE, 0.85)) {
                host.sleep(600)
                host.clickTemplate(GIFT_CONFIRM, 0.8)
                bought++
                host.sleep(400)
                host.clickBlank(2)
                continue
            }
            host.log("[商店] 体系饰品买不动（经费不足或已售罄）")
            host.clickTemplate(GIFT_CONFIRM, 0.8)
            host.clickBlank(2)
            break
        }
        if (bought > 0) host.log("[商店] 已买 $system 体系饰品 $bought 件")
        return bought
    }

    private fun refreshKeyword(host: ScriptHost, system: String): Boolean {
        host.clickBlank(2)
        if (!host.clickTemplate(REFRESH_KEYWORD, 0.8)) {
            host.log("[商店] 没找到关键词刷新按钮")
            return false
        }
        host.sleep(900)
        if (!host.clickTemplate(keywordIcon(system), 0.8)) {
            host.log("[商店] 关键词刷新页没有 $system 图标")
            host.clickBlank(2)
            return false
        }
        host.sleep(600)
        if (!host.clickTemplate(REFRESH_KEYWORD_CONFIRM, 0.8)) {
            host.log("[商店] 关键词刷新确认没出现")
            host.clickBlank(2)
            return false
        }
        // AALC 会重试三次确认，因为第一次有时吃不到
        repeat(3) {
            host.sleep(600)
            if (host.find(REFRESH_KEYWORD_CONFIRM, 0.8) == null) return@repeat
            host.clickTemplate(keywordIcon(system), 0.8)
            host.sleep(500)
            host.clickTemplate(REFRESH_KEYWORD_CONFIRM, 0.8)
        }
        host.sleep(1_500)
        host.log("[商店] 已刷新关键词：$system")
        return true
    }

    private fun refreshNormal(host: ScriptHost): Boolean {
        host.clickBlank(2)
        if (!host.clickTemplate(REFRESH, 0.8)) {
            host.log("[商店] 没找到刷新按钮")
            return false
        }
        host.sleep(1_500)
        host.log("[商店] 已刷新商店")
        return true
    }

    // ── 离开 ──

    private fun leave(host: ScriptHost) {
        var round = 0
        while (!host.cancelled && round < MAX_LEAVE_ROUNDS) {
            round++
            if (host.find(LEGEND, 0.75) != null) {
                host.log("[商店] 已回到地图")
                return
            }
            if (host.clickTemplate(LEAVE_CONFIRM, 0.8)) {
                host.sleep(800)
                continue
            }
            if (host.clickTemplate(LEAVE, 0.8)) {
                host.sleep(900)
                continue
            }
            if (host.clickTemplate(HEAL_RETURN, 0.8)) {
                host.sleep(600)
                continue
            }
            if (round >= MAX_LEAVE_ROUNDS - 3) host.clickBlank(3)
            host.sleep(500)
        }
        host.log("[商店] 退出商店没确认成功（第 $round 次），管线会继续尝试")
    }

    // ── 经费 ──

    /**
     * 读剩余经费
     *
     * AALC 用 `my_money_bbox.png` 这张「金额区域截图」当定位图，再对这块做 OCR；我们照做，
     * 读不到返回 null（调用方一律当「不刷新」处理，宁可不花也不乱花）。
     */
    private fun readMoney(host: ScriptHost, inHeal: Boolean): Int? {
        val anchor = host.find(if (inHeal) MONEY_BBOX_HEAL else MONEY_BBOX, 0.7)
            ?: host.find(COINS, 0.8)?.let { ScriptMatch(it.box.grow(it.box.w, 0), 0.0) }
            ?: return null
        val roi = anchor.box.grow(12, 8)
        val lines = host.readText(roi)
        val money = ScriptText.firstNumber(lines)
        if (money == null && lines.isNotEmpty()) {
            host.log("[商店] 经费 OCR 读不出数字：${lines.joinToString("|")}")
        }
        return money
    }

    // ── 常量 ──

    private const val MAX_SELL_ROUNDS = 12
    private const val MAX_BUY_ROUNDS = 8
    private const val MAX_SYSTEM_GIFTS = 6
    private const val MUST_PURCHASE_ATTEMPTS = 4
    private const val MAX_LEAVE_ROUNDS = 12
    private const val GIFT_ROW_BUCKET = 40
    private const val KEYWORD_REFRESH_COST = 300
    private const val NORMAL_REFRESH_COST = 200
}
