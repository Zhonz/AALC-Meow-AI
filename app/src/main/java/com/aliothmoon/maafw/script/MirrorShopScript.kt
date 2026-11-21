package com.aliothmoon.maafw.script

/**
 * 镜牢商店：把 AALC 的 tasks/mirror/in_shop.py 搬到手机上
 *
 * 管线 JSON 表达不了这段逻辑（读经费、按行列排序点饰品、按次数刷新、按合成概率决定点几件），
 * 所以引擎跑到 [entry] 就交棒，脚本按 AALC 的同一顺序执行：
 *
 *   技能替换 → 治疗 → 出售 → 购买 → 合成（激进 / 普通 / 体系公式）→ 升级 → 离开
 *
 * 合成与升级会不可逆地改动玩家饰品库存，所以每一项都受 PI 选项控制（默认跟 AALC 一致），
 * 所有坐标都是 AALC 的 1440p 常量按 0.5x 换算到截图空间（1280x720）的。
 */
object MirrorShopScript : ScriptTask {
    override val id: String = "mirror.shop"
    override val entry: String = "MirrorHandleShop"
    override val resumeEntry: String = "MirrorDungeonEntry"
    override val maxRounds: Int = 12

    // ── 参数键（由 ShopScriptBinding 从 PI option 摊平后塞进来） ──
    const val KEY_SYSTEM = "system"
    const val KEY_SELL_SYSTEMS = "sellSystems"
    const val KEY_DO_NOT_HEAL = "doNotHeal"
    const val KEY_DO_NOT_BUY = "doNotBuy"
    const val KEY_DO_NOT_SELL = "doNotSell"
    const val KEY_DO_NOT_ENHANCE = "doNotEnhance"
    const val KEY_FUSE_MODE = "fuseMode"
    const val KEY_SAVE_SYSTEM_GIFTS = "saveOwnSystemGifts"
    const val KEY_ONLY_AGGRESSIVE_FUSE = "onlyAggressiveFuse"
    const val KEY_ONLY_SYSTEM_FUSE = "onlySystemFuse"
    const val KEY_NO_SYSTEM_FUSE = "noSystemFuse"
    const val KEY_AGGRESSIVE_ALSO_ENHANCE = "aggressiveAlsoEnhance"
    const val KEY_AFTER_IV = "afterIv"
    const val KEY_SECOND_SYSTEM = "secondSystem"
    const val KEY_SECOND_BUY = "secondBuy"
    const val KEY_SECOND_PROTECT = "secondProtect"
    const val KEY_SECOND_ENHANCE = "secondEnhance"
    const val KEY_SKILL_REPLACEMENT = "skillReplacement"
    const val KEY_SKILL_PRIORITY = "skillPriority"
    const val KEY_SKILL_MODE = "skillMode"
    const val KEY_MAX_KEYWORD_REFRESH = "maxKeywordRefresh"
    const val KEY_MAX_NORMAL_REFRESH = "maxNormalRefresh"
    const val KEY_BUY_LEVEL_GIFTS = "buyLevelGifts"
    const val KEY_MUST_PURCHASE = "mustPurchase"

    /** 要跳过商店的层，逗号分隔（对应 AALC 的 ignore_shop[5] 五个开关） */
    const val KEY_IGNORE_LAYERS = "ignoreLayers"

    /** 购买策略（AALC 的 shopping_strategy_select）：all / 0..5，见 ShopPlan.shoppingStrategy */
    const val KEY_SHOPPING_STRATEGY = "shoppingStrategy"

    /** 第二体系的时机（AALC 的 second_system_setting）：0 = 合成出四级后，1 = 一直 */
    const val KEY_SECOND_SETTING = "secondSetting"

    /** 出售时是否连 must_be_abandoned 的废品一起清掉（AALC 无开关，默认开） */
    const val KEY_SELL_ABANDONED = "sellAbandoned"

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
    private const val POWER_UP = "mirror/shop/power_up_assets.png"
    private const val POWER_UP_CONFIRM = "mirror/shop/power_up_confirm_assets.png"
    private const val ENHANCE_TAB = "mirror/shop/enhance_gifts_assets.png"
    private const val SORT_BUTTON = "mirror/shop/sort_button_assets.png"
    private const val FUSE_TAB = "mirror/shop/fuse_gift_assets.png"
    private const val FUSE_CONFIRM = "mirror/shop/fuse_gift_confirm_assets.png"
    private const val FUSE_TO_KEYWORD = "mirror/shop/fuse_to_select_keyword_assets.png"
    private const val FUSE_EGO_GIFT = "mirror/shop/fuse_ego_gift_assets.png"
    private const val FUSE_LABEL = "mirror/shop/fuse_label.png"
    private const val FUSE_90 = "mirror/shop/fuse_90%_assets.png"
    private const val FUSION_IV = "mirror/shop/fusion_level_IV_gift_assets.png"
    private const val FUSE_STARLIGHT = "mirror/shop/fuse_use_starlight_assets.png"
    private const val FUSE_STARLIGHT_CONFIRM = "mirror/shop/fuse_use_starlight_confirm_assets.png"
    private const val GIFTS_BLOCK = "mirror/shop/gifts_list_block.png"
    private const val SUPER_SHOP = "mirror/shop/super_shop_assets.png"
    private const val SKILL_COINS = "mirror/shop/skill_replacement_coins.png"
    private const val SKILL_CONFIRM = "mirror/shop/skill_replacement_confirm_assets.png"
    private const val SKILL_NORMAL = "mirror/shop/skill_replacement_assets.png"
    private const val SKILL_SUPER_1 = "mirror/shop/super_shop_skill_replacement_1_assets.png"
    private const val SKILL_SUPER_2 = "mirror/shop/super_shop_skill_replacement_2_assets.png"
    private const val SKILL_SEARCH = "mirror/shop/ID_skill_search_assets.png"
    private const val SKILL_SEARCH_RETURN = "mirror/shop/ID_skill_replace_search_return_assets.png"
    private const val SKILL_PURCHASED_0 = "mirror/shop/ID_skill_replace_0_purchased_assets.png"
    private const val SKILL_PURCHASED_1 = "mirror/shop/ID_skill_replace_1_purchased_assets.png"
    private const val SKILL_PURCHASED_2 = "mirror/shop/ID_skill_replace_2_purchased_assets.png"

    private val MUST_PURCHASE = listOf(
        "mirror/shop/must_purchase/carmilla.png",
        "mirror/shop/must_purchase/homeward.png",
        "mirror/shop/must_purchase/lithograph.png",
        "mirror/shop/must_purchase/piece_of_relationship.png",
        "mirror/shop/must_purchase/phlebotomy_pack.png",
    )

    /** AALC 的十体系（与 PI 的 team_system case 同名同序） */
    val SYSTEMS = listOf(
        "burn", "bleed", "tremor", "rupture", "poise",
        "sinking", "charge", "slash", "pierce", "blunt",
    )

    /** AALC 的 must_be_abandoned 目录（19 件"留着没用"的低级饰品） */
    private val ABANDONED = listOf(
        "leave0_fragment1", "leave0_fragment2", "leave0_fragment3", "leave0_fragment4",
        "leave0_fragment5", "leave0_fragment6", "leave0_fragment7",
        "leave1_1", "leave1_grand_welcome", "leave1_vestige3",
        "leave2_child_within_a_flask", "leave2_coffee_and_cranes", "leave2_gloden_urn",
        "leave2_oracle", "leave2_vestige2", "leave2_white_gossypium",
        "leave3_blue_zippo_lighter", "leave3_perversion", "leave3_vestige",
    ).map { "mirror/shop/must_be_abandoned/" + it + ".png" }

    /** 各体系的公式合成成品（AALC 的 fusion_result 目录按前缀过滤；slash/pierce/blunt 没有成品图） */
    private val FUSION_RESULTS = mapOf(
        "burn" to listOf("1", "2", "hard_1", "hard_2"),
        "bleed" to listOf("1", "2", "3", "hard_1", "hard_2", "hard_3"),
        "tremor" to listOf("1", "2", "3", "hard_1"),
        "rupture" to listOf("1", "2", "hard_1", "hard_2", "hard_3"),
        "poise" to listOf("1", "2", "3", "4", "hard_1"),
        "sinking" to listOf("1", "2", "3", "hard_1"),
        "charge" to listOf("1", "2", "hard_1"),
    )

    private fun keywordIcon(system: String) = "mirror/shop/keyword/keyword_" + system + ".png"

    /** 商店货架上的体系图标（AALC 用 shop_ 前缀这份找货架） */
    private fun shopGiftIcon(system: String) = "mirror/shop/enhance_gifts/shop_" + system + ".png"

    /** 自己饰品栏里的体系图标（出售、合成列表用这份） */
    private fun listGiftIcon(system: String) = "mirror/shop/enhance_gifts/" + system + ".png"

    private fun bigGiftIcon(system: String) = "mirror/shop/enhance_gifts/big_" + system + ".png"

    private fun levelIvIcon(system: String) = "mirror/shop/level_IV_gifts/" + system + "_level_IV.png"

    private fun fusionResultIcons(system: String): List<String> {
        val suffixes = FUSION_RESULTS[system] ?: return emptyList()
        return suffixes.map { "mirror/shop/fusion_result/" + system + "_" + it + ".png" }
    }

    /** "合成出四级后跳过后续商店"（AALC 的 ignore_shop[0..4]），同一次任务内有效 */
    @Volatile
    private var skipShopsAfterIv: Boolean = false

    /** 激进合成算出来的第一条分界线（普通合成要过滤掉它上面的东西） */
    private var firstLineY: Int? = null

    /** 本局是否已经合出本体系的四级饰品：第二体系的买/升要等它（AALC 的 fuse_IV 门控） */
    @Volatile
    private var fuseIvDone: Boolean = false

    /** 本次商店已做过几次技能替换（AALC in_shop.py:1291-1327 每次加一，330-332 只在少于 3 次时再跑） */
    @Volatile
    private var replacements: Int = 0

    /** 第二体系也合出四级了（AALC fuse_second_IV）：此时不再追第二体系，激进合成收手 */
    @Volatile
    private var fuseSecondIvDone: Boolean = false

    override fun resetSession() {
        skipShopsAfterIv = false
        firstLineY = null
        fuseIvDone = false
        replacements = 0
        fuseSecondIvDone = false
    }

    // ── 入口 ──

    override fun run(host: ScriptHost, params: Map<String, String>) {
        val plan = ShopPlan.from(params)
        firstLineY = null
        host.log(
            "[商店] 接管：体系=" + (plan.system ?: "未配置") +
                " 治疗=" + plan.heal +
                " 购买=" + plan.buy +
                " 出售=" + plan.sell +
                " 合成=" + (if (!plan.fuseEnabled) "关" else if (plan.fuseAggressive) "激进" else "普通") +
                " 升级=" + !plan.doNotEnhance +
                " 技能替换=" + plan.skillReplacement,
        )

        if (skipShopsAfterIv) {
            host.log("[商店] 已合成四级并设置为跳过后续商店，直接离开")
            leave(host)
            return
        }

        val floor = MirrorFloorTracker.current
        if (floor > 0 && floor in plan.ignoreLayers) {
            host.log("[商店] 第 " + floor + " 层配置了跳过商店，直接离开")
            leave(host)
            return
        }

        val anchor = host.find(SHOP_MARK, 0.75) ?: host.find(COINS, 0.8)
        if (anchor == null) {
            host.log("[商店] 没识别到商店界面，交还给管线")
            return
        }

        try {
            if (plan.skillReplacement) skillReplacement(host, plan)
            firstLineY = null
            if (plan.heal) heal(host)
            // AALC 只在"不合成"时才卖：合成模式下低价值饰品是合成材料
            if (!plan.fuseEnabled && plan.sell) sell(host, plan)
            if (plan.buy) buy(host, plan)
            var enhanceAllowed = true
            if (plan.fuseEnabled) enhanceAllowed = fuse(host, plan)
            if (enhanceAllowed && !plan.doNotEnhance) enhance(host, plan)
        } finally {
            // 不管上面哪一步失败，都要把玩家从商店里带出来，否则后面所有识别都对不上
            if (!host.cancelled) leave(host) else host.log("[商店] 已取消，不再自动离开商店")
        }
    }

    // ── 技能替换 ──

    private fun skillReplacement(host: ScriptHost, plan: ShopPlan) {
        if (host.find(SUPER_SHOP, 0.8) != null) {
            host.log("[商店] 超级商店：技能替换三处")
            if (host.find(SKILL_PURCHASED_1, 0.8) == null) idSkillReplacement(host, plan, SKILL_SUPER_1)
            if (host.find(SKILL_PURCHASED_2, 0.8) == null) idSkillReplacement(host, plan, SKILL_SUPER_2)
            if (host.find(SKILL_PURCHASED_0, 0.8) == null) searchSkillReplacement(host, plan)
        } else {
            host.log("[商店] 普通商店：技能替换一处")
            if (host.find(SKILL_PURCHASED_0, 0.8) == null) idSkillReplacement(host, plan, SKILL_NORMAL)
        }
    }

    /** 点开一个替换模块，再点它上方的人格头像，然后从三个技能里按配置挑一个 */
    private fun idSkillReplacement(host: ScriptHost, plan: ShopPlan, template: String) {
        val module = host.find(template, 0.8) ?: return
        host.click(module.centerX, module.centerY - 50)
        host.sleep(600)
        replaceSelectedSkill(host, plan)
        host.clickBlank(1)
    }

    /**
     * 超级商店的"按人格搜索"：人格头像在最底一排，横坐标是 AALC 的硬编码表
     *
     * AALC 会先 OCR 判断这个人格在不在优先名单里；脚本拿不到队伍名单，
     * 所以按"点了就换，换不了就换下一个"处理，最后一定点返回退出该界面。
     */
    private fun searchSkillReplacement(host: ScriptHost, plan: ShopPlan) {
        val sinners = when (plan.skillPriority) {
            0 -> 1
            1 -> 3
            2 -> 7
            else -> 12
        }
        for (i in 0 until sinners) {
            if (host.cancelled) return
            if (host.find(SKILL_PURCHASED_0, 0.8) != null) break
            if (!host.clickTemplate(SKILL_SEARCH, 0.8)) return
            host.sleep(500)
            host.click(SINNER_X[i], SINNER_Y)
            host.sleep(500)
            replaceSelectedSkill(host, plan)
        }
        host.clickTemplate(SKILL_SEARCH_RETURN, 0.8)
        host.sleep(300)
    }

    private fun replaceSelectedSkill(host: ScriptHost, plan: ShopPlan) {
        val modes = when (plan.skillMode) {
            3 -> listOf(1, 0)
            4 -> listOf(0, 1)
            else -> listOf(plan.skillMode)
        }
        for (mode in modes) {
            if (mode !in 0..2) return
            val coins = host.findAll(SKILL_COINS, 0.8)
            // AALC：识别不完整时直接放弃，绝不猜选项位置（点了就是不可逆的技能替换）
            if (coins.size != 3) return
            val sorted = coins.sortedBy { it.box.x }
            host.click(sorted[2 - mode])
            host.sleep(500)
            host.clickTemplate(SKILL_CONFIRM, 0.8)
            host.clickTemplate(SKILL_CONFIRM, 0.8)
            replacements++
            host.sleep(400)
        }
    }

    // ── 治疗 ──

    /**
     * 治疗：AALC in_shop.py:962 heal_sinner()
     *
     * 顺序是「先全体治疗，再按剩余经费决定要不要逐个治疗」——
     * 先点 heal_all（只点一次，多点会多花一份经费），再从第 5 圈起用治疗页上的经费栏判钱，
     * 钱 < 100 就停，否则继续点 heal_sinner 逐个治疗，最后点返回。
     */
    private fun heal(host: ScriptHost) {
        host.clickBlank(2)
        if (!host.clickTemplate(HEAL_ONE, 0.8)) {
            host.log("[商店] 没找到治疗入口，跳过治疗")
            return
        }
        host.log("[商店] 打开治疗页")
        host.sleep(700)

        var healed = false
        var loop = 10
        var individual = 0
        while (!host.cancelled && loop > 0) {
            loop--
            // 点过治疗之后才点返回：没治疗就点返回等于白进一次
            if (healed && host.clickTemplate(HEAL_RETURN, 0.8)) {
                host.sleep(700)
                if (host.find(COINS, 0.8) != null) {
                    host.log("[商店] 治疗完成，已回到商店（逐个治疗 " + individual + " 次）")
                    return
                }
                if (host.find(HEAL_ALL, 0.8) != null || host.find(HEAL_ONE, 0.8) != null) continue
                return
            }
            // 全体治疗只点一次：再点会多花一份经费
            if (host.clickTemplate(HEAL_ALL, 0.8)) {
                if (!healed) host.log("[商店] 全体治疗")
                healed = true
                host.sleep(800)
                continue
            }
            // AALC 从第 5 圈起才读治疗页上的经费（正常商店的经费栏位置不同）
            if (loop < 5) {
                val money = readMoney(host, inHeal = true)
                if (money != null && money < 100) {
                    host.log("[商店] 剩余经费 " + money + " < 100，停止治疗")
                    break
                }
            }
            if (host.clickTemplate(HEAL_ONE, 0.8)) {
                healed = true
                individual++
                host.sleep(600)
                continue
            }
            break
        }

        var attempt = 0
        while (attempt < 3) {
            attempt++
            if (host.clickTemplate(HEAL_RETURN, 0.8)) {
                host.sleep(600)
                return
            }
            host.sleep(400)
        }
        host.clickBlank(2)
    }

    // ── 出售 ──

    private fun sell(host: ScriptHost, plan: ShopPlan) {
        if (plan.sellSystems.isEmpty() && !plan.sellAbandoned) {
            host.log("[商店] 没配置要出售的体系，跳过出售")
            return
        }
        host.log(
            "[商店] 出售体系：" + plan.sellSystems.joinToString() +
                (if (plan.sellAbandoned) "（含 must_be_abandoned 废品）" else ""),
        )
        host.clickBlank(2)
        if (!host.clickTemplate(SELL_TAB, 0.8)) {
            host.log("[商店] 没找到出售入口，跳过出售")
            return
        }
        host.sleep(800)

        // AALC 的 protect_coordinates：追第二体系时，第二体系的四级饰品一个都不能卖
        val protect = if (plan.secondProtect && plan.secondSystem != null) {
            host.findAll(levelIvIcon(plan.secondSystem), 0.85)
        } else {
            emptyList()
        }
        if (protect.isNotEmpty()) host.log("[商店] 保护第二体系四级饰品 " + protect.size + " 件")

        var sold = 0
        var round = 0
        while (!host.cancelled && round < MAX_SELL_ROUNDS) {
            round++
            // AALC 每轮都重新点开出售入口：卖完一件列表会回到顶部/重绘
            if (round > 1 && !host.clickTemplate(SELL_TAB, 0.8)) break
            val target = plan.sellSystems.asSequence()
                .flatMap { system -> host.findAll(listGiftIcon(system), 0.8).asSequence() }
                .filter { candidate ->
                    protect.none {
                        near(Point(it.box.centerX, it.box.centerY), candidate.box.centerX, candidate.box.centerY, PROTECT_RADIUS)
                    }
                }
                .sortedWith(compareBy<ScriptMatch> { it.box.y / GIFT_ROW_BUCKET }.thenBy { it.box.x })
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
        // 废品单独走一遍：它们不在体系图标里，AALC 是逐个点掉的
        if (plan.sellAbandoned) {
            for (tpl in ABANDONED) {
                if (host.cancelled) break
                val match = host.find(tpl, 0.85) ?: continue
                host.click(match)
                host.sleep(500)
                if (host.clickTemplate(SELL_CONFIRM, 0.8) || host.clickTemplate(SOLD_CONFIRM, 0.8)) {
                    sold++
                    host.sleep(400)
                }
            }
        }
        host.log("[商店] 出售结束：" + sold + " 件")
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
            // AALC 的 shopping_strategy 三处门控：必买 / 三四级 / 体系饰品各看一份名单
            val strategy = plan.shoppingStrategy
            val mustAllowed = strategy == null || strategy in SHOPPING_MUST
            val levelAllowed = strategy == null || strategy in SHOPPING_LEVEL
            val systemAllowed = strategy == null || strategy in SHOPPING_SYSTEM

            if (plan.mustPurchase && mustAllowed) {
                for (commodity in MUST_PURCHASE) {
                    if (host.cancelled) break
                    if (buyOne(host, commodity, 0.85, MUST_PURCHASE_ATTEMPTS)) bought++
                }
            }

            // 三四级只在"激进合成"下买（AALC in_shop.py:182 判的是 fuse_aggressive_switch）
            if (plan.buyLevelGifts && plan.fuseAggressive && levelAllowed) {
                if (buyOne(host, LEVEL_IV, 0.82)) bought++
                if (buyOne(host, LEVEL_III, 0.82)) bought++
            }

            val system = plan.system
            if (system != null && systemAllowed) bought += buySystemGifts(host, system, MAX_SYSTEM_GIFTS)
            // 第二体系要等本体系四级（AALC 的 second_system_setting / fuse_IV 门控）
            val secondReady = plan.secondAlways || fuseIvDone
            if (plan.secondBuy && plan.secondSystem != null && systemAllowed) {
                if (secondReady) {
                    bought += buySystemGifts(host, plan.secondSystem, MAX_SYSTEM_GIFTS)
                } else {
                    host.log("[商店] 第二体系要等本体系合出四级，本轮先不买")
                }
            }

            val money = readMoney(host, inHeal = false)
            if (money == null) {
                host.log("[商店] 读不到剩余经费，结束购买")
                break
            }
            host.log("[商店] 剩余经费 " + money + "，本轮累计已买 " + bought + " 件")

            if (system != null && keywordRefreshUsed < plan.maxKeywordRefresh && money >= KEYWORD_REFRESH_COST) {
                if (refreshKeyword(host, system)) {
                    keywordRefreshUsed++
                    if (plan.skillReplacement && replacements < MAX_SKILL_REPLACEMENTS) {
                        skillReplacement(host, plan)
                    }
                    continue
                }
                break
            }
            if (normalRefreshUsed < plan.maxNormalRefresh && money >= NORMAL_REFRESH_COST) {
                if (refreshNormal(host)) {
                    normalRefreshUsed++
                    if (plan.skillReplacement && replacements < MAX_SKILL_REPLACEMENTS) {
                        skillReplacement(host, plan)
                    }
                    continue
                }
                break
            }
            break
        }
        host.log(
            "[商店] 购买结束：" + bought + " 件，关键词刷新 " + keywordRefreshUsed +
                " 次，普通刷新 " + normalRefreshUsed + " 次",
        )
        host.clickBlank(2)
    }

    private fun buyOne(
        host: ScriptHost,
        template: String,
        threshold: Double,
        attempts: Int = 3,
    ): Boolean {
        val match = host.find(template, threshold) ?: return false
        host.click(match)
        host.sleep(700)
        var attempt = 0
        while (attempt < attempts) {
            attempt++
            if (host.clickTemplate(PURCHASE, 0.85)) {
                host.sleep(700)
                host.clickTemplate(GIFT_CONFIRM, 0.8)
                host.sleep(400)
                return true
            }
            if (host.clickTemplate(GIFT_CONFIRM, 0.8)) {
                host.sleep(400)
                return true
            }
            if (attempt == attempts) {
                host.log("[商店] 购买按钮没出现：" + template)
                host.clickBlank(2)
            } else {
                host.sleep(400)
            }
        }
        return false
    }

    private fun buySystemGifts(host: ScriptHost, system: String, max: Int): Int {
        var bought = 0
        while (!host.cancelled && bought < max) {
            val matches = host.findAll(shopGiftIcon(system), 0.85)
            if (matches.isEmpty()) break
            val target = matches
                .sortedWith(compareBy<ScriptMatch> { it.box.y / GIFT_ROW_BUCKET }.thenBy { it.box.x })
                .first()
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
        if (bought > 0) host.log("[商店] 已买 " + system + " 体系饰品 " + bought + " 件")
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
            host.log("[商店] 关键词刷新页没有本体系图标")
            host.clickBlank(2)
            return false
        }
        host.sleep(600)
        if (!host.clickTemplate(REFRESH_KEYWORD_CONFIRM, 0.8)) {
            host.log("[商店] 关键词刷新确认没出现")
            host.clickBlank(2)
            return false
        }
        var guard = 0
        while (guard < 3) {
            guard++
            host.sleep(600)
            if (host.find(REFRESH_KEYWORD_CONFIRM, 0.8) == null) break
            host.clickTemplate(keywordIcon(system), 0.8)
            host.sleep(500)
            host.clickTemplate(REFRESH_KEYWORD_CONFIRM, 0.8)
        }
        host.sleep(1_500)
        host.log("[商店] 已刷新关键词：" + system)
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

    // ── 合成 ──

    /**
     * AALC 的 fuse_gift() 三段式
     *
     * @return 是否允许继续做升级（激进合成期间默认跳过升级，除非勾了 aggressive_also_enhance）
     */
    private fun fuse(host: ScriptHost, plan: ShopPlan): Boolean {
        if (plan.system == null) {
            host.log("[商店] 没配置体系，跳过合成")
            return true
        }
        var aggressive = plan.fuseAggressive
        var fuseOn = true

        // 合成出四级之后的策略切换；返回"是否仍然是激进模式"
        val onIv: () -> Boolean = {
            host.log("[商店] 合成出四级饰品，按设置切换策略")
            when (plan.afterIv) {
                ShopPlan.AfterIv.SELL -> {
                    aggressive = false
                    fuseOn = false
                }
                ShopPlan.AfterIv.CALM -> aggressive = false
                ShopPlan.AfterIv.SECOND ->
                    aggressive = plan.secondSystem != null && plan.secondProtect && !fuseSecondIvDone
                ShopPlan.AfterIv.SKIP -> {
                    aggressive = false
                    fuseOn = false
                    skipShopsAfterIv = true
                }
            }
            aggressive
        }

        // ① 第一轮激进合成
        if (aggressive && !plan.onlySystemFuse) {
            if (enterFuse(host, plan)) aggressivePass(host, plan, onIv)
            host.clickBlank(3)
        }
        // after_level_IV_select == 3：合成四级后跳过后续商店，但"当店"还要再升一次饰品
        if (!fuseOn) return plan.afterIv == ShopPlan.AfterIv.SKIP

        // ② 普通合成（把废品凑三件）
        if (!plan.onlyAggressiveFuse && !plan.onlySystemFuse) {
            if (enterFuse(host, plan)) normalPass(host, plan, onIv)
            host.clickBlank(3)
        }
        if (!fuseOn) return plan.afterIv == ShopPlan.AfterIv.SKIP

        // ③ 还没合出四级就再激进一次（AALC in_shop.py:946 要 !fuse_IV）
        if (aggressive && !fuseIvDone && !plan.onlySystemFuse) {
            if (enterFuse(host, plan)) aggressivePass(host, plan, onIv)
            host.clickBlank(3)
        }
        if (!fuseOn) return plan.afterIv == ShopPlan.AfterIv.SKIP

        // ④ 体系公式合成
        if (!plan.onlyAggressiveFuse && !plan.noSystemFuse) {
            if (enterFuse(host, plan)) systemPass(host, plan)
            host.clickBlank(3)
        }

        return !aggressive || plan.alsoEnhance
    }

    /** 进入合成页并选好关键词（AALC enter_fuse） */
    private fun enterFuse(host: ScriptHost, plan: ShopPlan): Boolean {
        // AALC in_shop.py:884：本体系四级已合出、要追第二体系时，合成关键词换成第二体系
        val system = if (
            fuseIvDone && !fuseSecondIvDone && plan.afterIv == ShopPlan.AfterIv.SECOND &&
            plan.secondSystem != null && plan.fuseAggressive
        ) {
            plan.secondSystem
        } else {
            plan.system
        } ?: return false
        var guard = 0
        while (!host.cancelled && guard < 15) {
            guard++
            if (host.clickTemplate(keywordIcon(system), 0.8)) {
                host.sleep(300)
                if (host.clickTemplate(FUSE_CONFIRM, 0.8)) {
                    host.sleep(400)
                    return true
                }
            }
            if (host.clickTemplate(FUSE_TO_KEYWORD, 0.8)) continue
            if (host.clickTemplate(FUSE_TAB, 0.8)) continue
            host.clickBlank(1)
            host.sleep(400)
        }
        host.log("[商店] 进不去合成页（没找到合成入口或关键词）")
        return false
    }

    /**
     * 激进合成：把饰品栏里"没用的"全塞进去，凑到合成概率 90% 为止
     *
     * 与 AALC 唯一的差别：不 OCR 判断合出来的四级是不是本体系——能进这个分支就说明
     * fusion_level_IV_gift_assets 在场，直接按"拿到四级"处理，少一次整屏 OCR 的抖动。
     */
    private fun aggressivePass(host: ScriptHost, plan: ShopPlan, onIv: () -> Boolean) {
        val system = plan.system ?: return

        if (host.find(levelIvIcon(system), 0.75) != null) {
            host.log("[商店] 已有本体系四级饰品")
            if (!onIv()) return
        }

        var round = 0
        while (!host.cancelled && round < MAX_FUSE_ROUNDS) {
            round++
            host.clickBlank(1)
            val slots = giftSlots(host) ?: return
            var candidates = slots

            for (protect in protectList(plan)) {
                val p = host.find(protect, 0.7) ?: continue
                candidates = candidates.filterNot { near(it, p.centerX, p.centerY, PROTECT_THRESHOLD) }
            }
            if (plan.saveOwnSystemGifts) {
                candidates = candidates.filterNot { p ->
                    host.find(listGiftIcon(system), 0.8, roiAround(p)) != null
                }
            }
            if (candidates.isEmpty()) {
                host.log("[商店] 饰品栏里都是要保护的，结束激进合成")
                return
            }

            var ready = false
            for (p in candidates) {
                if (host.cancelled) return
                host.click(p.x, p.y)
                host.sleep(320)
                if (host.find(FUSE_90, 0.97) != null) {
                    ready = true
                    break
                }
            }
            if (!ready) {
                host.log("[商店] 凑不到 90% 合成概率，结束激进合成")
                return
            }
            if (host.find(FUSION_IV, 0.9) == null) {
                host.log("[商店] 当前组合合不出四级，结束激进合成")
                return
            }

            if (!confirmFusion(host, plan, onIv)) return
            if (host.find(FUSE_EGO_GIFT, 0.8) != null) {
                host.clickTemplate(FUSE_EGO_GIFT, 0.8)
                host.sleep(800)
            }
        }
    }

    /** 普通合成：只塞"该卖的"和 must_be_abandoned 里的废品，凑三件 */
    private fun normalPass(host: ScriptHost, plan: ShopPlan, onIv: () -> Boolean) {
        var block = true
        var round = 0
        while (!host.cancelled && round < MAX_FUSE_ROUNDS) {
            round++
            host.clickBlank(1)

            val found = mutableListOf<Point>()
            for (tpl in ABANDONED) {
                if (tpl.contains("white_gossypium") && "bleed" in plan.sellSystems) continue
                host.findAll(tpl, 0.8).forEach { found.add(Point(it.centerX, it.centerY)) }
            }
            for (s in plan.sellSystems) {
                host.findAll(listGiftIcon(s), 0.8).forEach { found.add(Point(it.centerX, it.centerY)) }
            }
            var candidates = dedupeSorted(found)
            val line = firstLineY
            if (line != null && plan.fuseAggressive) {
                candidates = candidates.filter { it.y >= line }
            }
            if (plan.secondProtect && plan.secondSystem != null) {
                val p = host.find(levelIvIcon(plan.secondSystem), 0.8)
                if (p != null) {
                    candidates = candidates.filterNot { near(it, p.centerX, p.centerY, PROTECT_THRESHOLD) }
                }
            }

            var fused = false
            if (candidates.size <= 2) {
                if (!block) {
                    host.log("[商店] 可合成的废品不足 3 件，结束普通合成")
                    return
                }
            } else {
                for (i in 0 until 3) {
                    if (host.cancelled) return
                    val p = candidates.getOrNull(i) ?: break
                    host.click(p.x, p.y)
                    host.sleep(750)
                }
                if (confirmFusion(host, plan, onIv)) {
                    fused = true
                    if (host.find(FUSE_EGO_GIFT, 0.8) != null) {
                        host.clickTemplate(FUSE_EGO_GIFT, 0.8)
                        host.sleep(800)
                    }
                }
            }
            if (fused) continue

            if (block) {
                val b = host.find(GIFTS_BLOCK, 0.8)
                if (b != null) {
                    val toY = minOf(b.centerY + GIFT_DRAG, host.screenHeight - 8)
                    host.swipe(b.centerX, b.centerY, b.centerX, toY, 600)
                    block = false
                    host.sleep(600)
                    continue
                }
            }

            host.clickBlank(3)
            return
        }
    }

    /** 体系公式合成：找 fusion_result 里本体系的成品图标，没有成品就随便拿本体系饰品凑 */
    private fun systemPass(host: ScriptHost, plan: ShopPlan) {
        val system = plan.system ?: return
        // AALC in_shop.py:748-763 用 fusion_pos 轮换成品图标：合成失败就换下一个，本轮试完就收手
        val tried = mutableSetOf<String>()
        var round = 0
        while (!host.cancelled && round < MAX_FUSE_ROUNDS) {
            round++
            val block = host.find(GIFTS_BLOCK, 0.8)
            if (block != null) {
                val toY = maxOf(block.centerY - GIFT_DRAG, 8)
                host.swipe(block.centerX, block.centerY, block.centerX, toY, 600)
                host.sleep(600)
            }
            val anchors = host.findAll(FUSE_LABEL, 0.85)
            if (anchors.size < 2) {
                host.clickBlank(3)
                return
            }
            val dividing = anchors.maxOf { it.centerY }

            var clicked = false
            var clickedTpl: String? = null
            for (tpl in fusionResultIcons(system)) {
                if (tpl in tried) continue
                val m = host.find(tpl, 0.8) ?: continue
                if (m.centerY > dividing) continue
                host.click(m)
                clicked = true
                clickedTpl = tpl
                break
            }
            if (!clicked) {
                val all = host.findAll(listGiftIcon(system), 0.8).filter { it.centerY < dividing }
                val first = all.sortedWith(
                    compareBy<ScriptMatch> { it.box.y / GIFT_ROW_BUCKET }.thenBy { it.box.x },
                ).firstOrNull()
                if (first == null) {
                    host.log("[商店] 没找到本体系的合成材料，结束公式合成")
                    host.clickBlank(3)
                    return
                }
                host.click(first)
            }

            var guard = 0
            var done = false
            while (!host.cancelled && guard < 6) {
                guard++
                if (host.clickTemplate(GIFT_CONFIRM, 0.8)) {
                    done = true
                    break
                }
                if (host.clickTemplate(SOLD_CONFIRM, 0.8)) continue
                host.sleep(300)
            }
            if (!done) {
                host.log("[商店] 公式合成没等到确认框，换一件")
                clickedTpl?.let { tried += it }
                if (tried.size >= fusionResultIcons(system).size) {
                    host.log("[商店] 公式合成的成品都试过了，结束")
                    host.clickBlank(3)
                    return
                }
                host.clickBlank(2)
                continue
            }
        }
    }

    /**
     * 合成确认：星光加成 → 确认框 → "获得饰品"那一下
     *
     * @return 是否真的合成成功
     */
    private fun confirmFusion(host: ScriptHost, plan: ShopPlan, onIv: () -> Boolean): Boolean {
        var starlight = STARLIGHT_CHANCES
        var guard = 0
        while (!host.cancelled && guard < 15) {
            guard++
            host.clickBlank(1)

            val confirm = host.find(GIFT_CONFIRM, 0.8)
            if (confirm != null) {
                if (host.find(FUSION_IV, 0.9) != null) {
                    fuseIvDone = true
                    val second = plan.secondSystem
                    if (fuseIvDone && second != null && host.find(levelIvIcon(second), 0.8) != null) {
                        fuseSecondIvDone = true
                        host.log("[商店] 两个体系都合出四级了，激进合成收手")
                    }
                    onIv()
                }
                host.click(confirm)
                host.sleep(500)
                host.clickTemplate(GIFT_CONFIRM, 0.8)
                return true
            }
            if (starlight > 0 && host.clickTemplate(FUSE_STARLIGHT, 0.8)) {
                starlight--
                host.clickTemplate(FUSE_STARLIGHT_CONFIRM, 0.8)
                host.sleep(400)
                continue
            }
            if (host.clickTemplate(SOLD_CONFIRM, 0.8)) {
                host.sleep(500)
                continue
            }
            host.sleep(300)
        }
        host.log("[商店] 合成确认超时")
        return false
    }

    // ── 升级 ──

    private fun enhance(host: ScriptHost, plan: ShopPlan) {
        val system = plan.system
        if (system == null) {
            host.log("[商店] 没配置体系，跳过升级")
            return
        }
        if (!openEnhancePage(host)) return

        val enhanced = mutableListOf<Point>()
        var systemIvDone = false
        var secondIvDone = false
        var bigDone = false
        var stale = 0
        var round = 0

        while (!host.cancelled && round < MAX_ENHANCE_ROUNDS) {
            round++
            var did = false

            if (!systemIvDone) {
                val iv = host.find(levelIvIcon(system), 0.8)
                if (iv != null) {
                    if (!isEnhanced(enhanced, iv.centerX, iv.centerY)) {
                        host.click(iv)
                        if (!powerUp(host)) break
                        enhanced.add(Point(iv.centerX, iv.centerY))
                    }
                    systemIvDone = true
                    continue
                }
            }

            if (plan.secondEnhance && plan.secondSystem != null && !secondIvDone) {
                val iv2 = host.find(levelIvIcon(plan.secondSystem), 0.8)
                if (iv2 != null) {
                    if (!isEnhanced(enhanced, iv2.centerX, iv2.centerY)) {
                        host.click(iv2)
                        if (!powerUp(host)) break
                        enhanced.add(Point(iv2.centerX, iv2.centerY))
                    }
                    secondIvDone = true
                    continue
                }
            }

            if (!bigDone && !systemIvDone) {
                val big = host.find(bigGiftIcon(system), 0.8)
                if (big != null) {
                    host.click(big)
                    if (!powerUp(host)) break
                    enhanced.add(Point(big.centerX, big.centerY))
                    bigDone = true
                    continue
                }
            }

            val gifts = host.findAll(listGiftIcon(system), 0.8)
                .filter { it.box.centerX in ENHANCE_LEFT..ENHANCE_RIGHT && it.box.centerY in ENHANCE_TOP..ENHANCE_BOTTOM }
                .sortedWith(compareBy<ScriptMatch> { it.box.y / GIFT_ROW_BUCKET }.thenBy { it.box.x })
            // AALC in_shop.py:1126-1136 是一轮里把可见列表逐个升完再 break，不是只升一件
            var upgraded = 0
            for (g in gifts) {
                if (isEnhanced(enhanced, g.centerX, g.centerY)) continue
                host.click(g)
                if (!powerUp(host)) break
                enhanced.add(Point(g.centerX, g.centerY))
                upgraded++
            }
            if (upgraded > 0) did = true

            if (!did) {
                stale++
                if (stale > 2) {
                    host.log("[商店] 没有可升级的饰品了")
                    break
                }
            }
            host.sleep(300)
        }
        host.clickBlank(2)
        host.log("[商店] 升级结束：" + enhanced.size + " 件")
    }

    private fun openEnhancePage(host: ScriptHost): Boolean {
        var guard = 0
        while (!host.cancelled && guard < 30) {
            guard++
            val sort = host.find(SORT_BUTTON, 0.8)
            if (sort != null) {
                // AALC：点排序按钮，再点它下面 100px（720p 空间）的选项，把列表按体系收拢
                host.click(sort)
                host.sleep(300)
                host.click(sort.centerX, sort.centerY + SORT_OFFSET_Y)
                host.sleep(500)
                return true
            }
            if (host.clickTemplate(ENHANCE_TAB, 0.8)) {
                host.sleep(900)
                continue
            }
            host.clickBlank(1)
            host.sleep(400)
        }
        host.log("[商店] 没找到升级入口")
        return false
    }

    /** 点一次「强化」并确认；返回 false 表示这件升不动（材料不够） */
    private fun powerUp(host: ScriptHost): Boolean {
        var guard = 0
        while (!host.cancelled && guard < 30) {
            guard++
            host.clickBlank(1)
            if (host.clickTemplate(POWER_UP, 0.85)) {
                host.clickBlank(1)
                host.sleep(500)
                if (!host.clickTemplate(POWER_UP_CONFIRM, 0.85)) return true
                host.sleep(2_500)
                continue
            }
            if (host.find(POWER_UP_CONFIRM, 0.85) != null) return false
            host.sleep(300)
        }
        host.log("[商店] 升级没点到强化按钮")
        return false
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
            if (host.clickTemplate(SOLD_CONFIRM, 0.8)) {
                host.sleep(500)
                continue
            }
            if (round >= MAX_LEAVE_ROUNDS - 3) host.clickBlank(3)
            host.sleep(500)
        }
        host.log("[商店] 退出商店没确认成功，管线会继续尝试")
    }

    // ── 经费 ──

    private fun readMoney(host: ScriptHost, inHeal: Boolean): Int? {
        var anchor = host.find(if (inHeal) MONEY_BBOX_HEAL else MONEY_BBOX, 0.7)
        if (anchor == null) {
            val coins = host.find(COINS, 0.8)
            if (coins != null) anchor = ScriptMatch(coins.box.grow(coins.box.w, 0), 0.0)
        }
        val match = anchor ?: return null
        // AALC in_shop.py:1474 读不到钱会把裁剪区域放大一倍再 OCR。
        // 脚本层没有"对图上采样"的接口，这里退化成「换三种裁切 + 放宽 OCR 阈值」重试，
        // 效果等价于把同一块像素喂三遍识别器。
        val attempts = listOf(
            match.box to ScriptHost.DEFAULT_OCR_THRESHOLD,
            match.box.grow(12, 8) to 0.2,
            match.box.grow(24, 14) to 0.2,
        )
        var last: List<String> = emptyList()
        for ((roi, threshold) in attempts) {
            val lines = host.readText(roi, threshold)
            if (lines.isNotEmpty()) last = lines
            val money = ScriptText.firstNumber(lines)
            if (money != null) return money
        }
        if (last.isNotEmpty()) {
            host.log("[商店] 经费 OCR 读不出数字：" + last.joinToString("|"))
        }
        return null
    }

    // ── 饰品栏坐标 ──

    /** 合成页的 10 个饰品格子（5 列 x 2 行），以最下方的 fuse_label 为锚点 */
    private fun giftSlots(host: ScriptHost): List<Point>? {
        val anchors = host.findAll(FUSE_LABEL, 0.9)
        if (anchors.isEmpty()) return null
        val last = anchors.sortedBy { it.centerY }.last()
        val firstX = last.centerX + GIFT_OFFSET_X
        val firstY = last.centerY + GIFT_OFFSET_Y
        if (firstLineY == null) firstLineY = firstY + 50
        return (0 until 10).map { i ->
            Point(firstX + GIFT_STEP * (i % 5), firstY + GIFT_STEP * (i / 5))
        }
    }

    private fun protectList(plan: ShopPlan): List<String> {
        val list = mutableListOf(
            "mirror/shop/level_IV_gifts/lunar_memory.png",
            "mirror/shop/must_be_abandoned/leave2_vestige2.png",
            "mirror/shop/must_be_abandoned/leave3_vestige.png",
        )
        val system = plan.system
        if (system != null) list.add(levelIvIcon(system))
        if (plan.secondProtect && plan.secondSystem != null) list.add(levelIvIcon(plan.secondSystem))
        return list
    }

    private fun roiAround(p: Point): ScriptRect =
        ScriptRect(p.x - GIFT_STEP / 2, p.y - GIFT_STEP / 2, GIFT_STEP, GIFT_STEP)

    private fun near(p: Point, x: Int, y: Int, threshold: Int): Boolean =
        kotlin.math.abs(p.x - x) <= threshold && kotlin.math.abs(p.y - y) <= threshold

    private fun dedupeSorted(points: List<Point>): List<Point> {
        val sorted = points.sortedWith(compareBy<Point> { it.y }.thenBy { it.x })
        val out = mutableListOf<Point>()
        for (p in sorted) {
            if (out.none { near(it, p.x, p.y, GIFT_DEDUPE) }) out.add(p)
        }
        return out
    }

    private fun isEnhanced(list: List<Point>, x: Int, y: Int): Boolean = list.any { near(it, x, y, 5) }

    private data class Point(val x: Int, val y: Int)

    // ── 常量（AALC 的 1440p 常量 x0.5） ──

    private val SINNER_X = listOf(99, 176, 255, 331, 409, 484, 560, 638, 714, 790, 868, 944)
    private const val SINNER_Y = 649

    private const val GIFT_STEP = 95
    private const val GIFT_OFFSET_X = 48
    private const val GIFT_OFFSET_Y = 68
    private const val GIFT_DEDUPE = 20
    private const val PROTECT_THRESHOLD = 25
    private const val GIFT_DRAG = 250
    private const val SORT_OFFSET_Y = 100
    private const val STARLIGHT_CHANCES = 5

    /** AALC in_shop.py:330-332：一个商店里最多做 3 次技能替换 */
    private const val MAX_SKILL_REPLACEMENTS = 3
    private const val MAX_FUSE_ROUNDS = 8
    private const val MAX_ENHANCE_ROUNDS = 30
    private const val MAX_SELL_ROUNDS = 20
    private const val MAX_BUY_ROUNDS = 8
    private const val MAX_SYSTEM_GIFTS = 6
    /** AALC buy_chance = 10（重点商品最多 5 件，所以每次必买都重试 10 次） */
    private const val MUST_PURCHASE_ATTEMPTS = 10
    private const val MAX_LEAVE_ROUNDS = 12

    /** 同一行的判定桶高：AALC 是 40（1440 基准），720p 下折半 */
    private const val GIFT_ROW_BUCKET = 20

    /** 出售时保护第二体系四级饰品的半径（AALC protect_coordinates threshold 50 → 25） */
    private const val PROTECT_RADIUS = 25

    /** shopping_strategy_select 的三份名单（AALC in_shop.py:152/184/218） */
    private val SHOPPING_MUST = setOf(0, 1, 5)
    private val SHOPPING_LEVEL = setOf(1, 3, 4)
    private val SHOPPING_SYSTEM = setOf(2, 4, 5)
    private const val KEYWORD_REFRESH_COST = 300
    private const val NORMAL_REFRESH_COST = 200

    // 升级扫描区域（AALC 的相对坐标 0.5151/0.3380/0.8844/0.7130 换成 1280x720 绝对像素）
    private const val ENHANCE_LEFT = 659
    private const val ENHANCE_TOP = 243
    private const val ENHANCE_RIGHT = 1132
    private const val ENHANCE_BOTTOM = 513
}
