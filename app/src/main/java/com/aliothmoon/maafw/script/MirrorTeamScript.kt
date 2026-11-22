package com.aliothmoon.maafw.script

/**
 * 镜牢编队 + 战败重开（AALC `tasks/teams/team_formation.py`、`tasks/mirror/mirror.py:370-401` 编队分支、
 * `mirror.py:1213-1240 re_start`）
 *
 * 接管时机：管线在 `MirrorHandleTeam`（认到 `teams/identify_assets.png`，也就是镜牢编队页）停下来交棒。
 * 本局第一次进来要清队 + 按配置的顺位挨个点罪人格子 + 点出战；之后每次再进编队页先做存活检查
 * ——AALC 在「已经编过队」时不再重编，只在还能打的人不够 5 个时整局重开。
 *
 * 坐标系：素材是 1440 高基准（AALC 的 scale = win/1440），本项目截图 1280x720，所以 SCALE = 0.5。
 * 格子位置与 pipeline 的 `SelectSinnerSlot1..12` 同源：锚点 + (-900, +65) 是第一格，同排每格 +135，
 * 第二排 y 再 +250（合计 +315）。
 */
object MirrorTeamScript : ScriptTask {
    override val id: String = "mirror.team"
    override val entry: String = "MirrorHandleTeam"
    override val resumeEntry: String = "MirrorDungeonEntry"

    /** 一局里进编队页的次数有限：首编、每层开打、战败重开后各一次，6 次够用 */
    override val maxRounds: Int = 6

    /** 出战顺位：逗号分隔的罪人编号 1..12（1 = 李箱 … 12 = 格里高尔），点选顺序就是出场顺序 */
    const val KEY_SINNER_ORDER = "sinnerOrder"

    /** 每层重新编队（AALC `team_setting.re_formation_each_floor`，默认关） */
    const val KEY_REFORM = "reform"

    /**
     * 编队码（AALC `team_setting.team_code`，见 `team_formation.py:283-339`）
     *
     * 目前 `ShopScriptBinding` 还没把这个键写进参数表，需要主线补一行才会传进来；
     * 值非空时本脚本会走 [loadTeamCode] 的探测流程。
     */
    const val KEY_TEAM_CODE = "teamCode"

    private const val SCALE = 0.5

    // ---- 素材（都在 assets/resource/image/ 下） ----
    private const val IDENTIFY = "teams/identify_assets.png"
    private const val CLEAR_CONFIRM = "teams/clear_selection_confirm_assets.png"
    /** 选择队伍确认页（AALC `team_formation.py:290` 的前置条件，进镜牢选队伍时才有） */
    private const val SELECT_TEAM_CONFIRM = "mirror/road_to_mir/select_team_confirm_assets.png"
    /** 选队伍页右上角的「队伍代码」按钮与弹窗里的取消/确认（AALC teams/ 下同源素材） */
    private const val TEAM_CODE = "teams/team_code_assets.png"
    private const val TEAM_CODE_CANCEL = "teams/team_code_cancel_button_assets.png"
    private const val TEAM_CODE_CONFIRM = "teams/team_code_confirm_button_assets.png"
    private const val NONE_SINNER = "teams/none_sinner_assets.png"
    private const val LIVE_12 = "teams/12_sinner_live_assets.png"
    private const val LIVE_11 = "teams/11_sinner_live_assets.png"
    private const val LIVE_10 = "teams/10_sinner_live_assets.png"
    private const val CHAIN_TO_BATTLE = "battle/chaim_to_battle_assets.png"
    private const val NORMAL_TO_BATTLE = "battle/normal_to_battle_assets.png"
    private const val FORFEIT_CONFIRM = "mirror/road_in_mir/towindow&forfeit_confirm_assets.png"
    private const val FORFEIT = "mirror/road_in_mir/forfeit_assets.png"
    private const val GIVE_UP = "battle/give_up_assets.png"
    private const val SETTING = "mirror/road_in_mir/setting_assets.png"

    /** identify_assets.png 的识别区（与管线 TeamFormationStart 一致，720p 截图空间） */
    private val IDENTIFY_ROI = ScriptRect(1122, 129, 158, 98)

    private const val FORMATION_THRESHOLD = 0.75
    private const val BATTLE_THRESHOLD = 0.75
    private const val RESTART_THRESHOLD = 0.8
    private const val LIVE_THRESHOLD = 0.8

    // ---- 格子偏移（AALC team_formation.py:66-77 的 1440 基准坐标 × SCALE） ----
    /** first_sinner = identify 中心 + (-1800, +130) * scale */
    private val FIRST_X = (-1800 * SCALE).toInt()
    private val FIRST_Y = (130 * SCALE).toInt()
    /** 同排每格 +270 * scale */
    private val SLOT_STEP_X = (270 * SCALE).toInt()
    /** 第二排再 +500 * scale（y 总偏移 = 65 + 250 = 315） */
    private val SECOND_ROW_Y = (500 * SCALE).toInt()
    /** AALC clean_team():42 的 identify.y + 600 * scale：点面板下方把它收起来 */
    private val PANEL_TAIL_Y = (600 * SCALE).toInt()

    private const val CLEAR_ATTEMPTS = 3
    private const val RESTART_LOOPS = 15
    private const val ACTION_SLEEP = 800L
    private const val PANEL_SLEEP = 500L
    private const val RE_START_SLEEP = 1_500L

    /** 存活检查结论：够打 / 队伍空了要重编 / 人不够要重开整局 */
    private const val ALIVE_ENOUGH = 0
    private const val ALIVE_EMPTY = 1
    private const val ALIVE_LOW = 2

    /** AALC check_team() 的「可战斗人数 / 总人数」，分母 5..9、分子 5..分母 才算够 */
    private val LIVE_COUNT = Regex("""(\d{1,2})\s*/\s*(\d{1,2})""")

    /** 本次会话这一局是否已经按配置编过队（AALC `first_battle` 取反） */
    @Volatile
    private var formed = false

    /** 本次会话是否至少编过一次队：AALC 只在编过队之后才做存活检查（mirror.py:382） */
    @Volatile
    private var formedOnce = false

    override fun resetSession() {
        formed = false
        formedOnce = false
    }

    override fun run(host: ScriptHost, params: Map<String, String>) {
        // AALC mirror.py:372 先认 identify_assets 锚点：认不到就不是编队页，原样交还给管线
        val identify = host.find(IDENTIFY, FORMATION_THRESHOLD, IDENTIFY_ROI)
        if (identify == null) {
            host.log("[编队] 没识别到编队页锚点，交还给管线")
            return
        }
        val order = parseOrder(params[KEY_SINNER_ORDER])
        if (order.isEmpty()) {
            host.log("[编队] 出战顺位为空，交还给管线")
            return
        }
        val reform = ScriptParams.flag(params, KEY_REFORM)

        // AALC mirror.py:1102-1105：启用编队码时先尝试加载；这里先做能力探测，见 loadTeamCode 的说明
        val teamCode = params[KEY_TEAM_CODE]?.trim().orEmpty()
        if (teamCode.isNotEmpty()) loadTeamCode(host, teamCode)

        // AALC mirror.py:382-397：编过队之后每次进编队页先看还能打几个人
        if (formedOnce) {
            when (aliveState(host)) {
                ALIVE_ENOUGH -> host.log("[编队] 存活人数够，不用重开")
                ALIVE_EMPTY -> {
                    // AALC mirror.py:379-381 的队伍被清空分支：重编即可，不用重开整局
                    // （AALC 这里还会点一下 none_sinner，脚本直接走下面的清队 + 点选，结果一样）
                    host.log("[编队] 队伍是空的，重新编队")
                    formed = false
                }
                else -> {
                    // AALC mirror.py:396-397 + re_start()：人不够 5 个就战败重开，交回管线走新的一局
                    host.log("[编队] 存活人数不足，按 AALC re_start 重开本局")
                    val restarted = restart(host)
                    formed = false
                    // AALC 的 re_start 成功后会置 first_battle = True（mirror.py:1238），
                    // 于是新一局的编队页直接重编、不再做存活检查 —— 这里同样清掉 formedOnce
                    if (restarted) formedOnce = false
                    return
                }
            }
        }

        if (formed && !reform) {
            host.log("[编队] 本局已经编过队，直接点出战")
        } else {
            // AALC team_formation():52-77 —— 先清队，再按顺位点格子
            clearTeam(host, identify)
            if (host.cancelled) return
            pickSinners(host, order)
            if (host.cancelled) return
        }

        // AALC mirror.py:399-401：先链战、再普通战斗，点到了就算编完队
        if (clickToBattle(host)) {
            formed = true
            formedOnce = true
            host.sleep(ACTION_SLEEP)
            return
        }
        host.log("[编队] 没找到出战按钮，交还给管线")
    }

    /**
     * 存活检查（AALC mirror.py:382-390 的 12/11/10 存活头像 + check_team():272-279 的 OCR）
     *
     * 先看能打 12/11/10 人的头像，有任一就是够；队伍选择被清空（none_sinner）算「空」；
     * 都没有就读全屏 OCR，找形如 `a/b`（b 在 5..9、a 在 5..b）的可战斗人数。
     */
    private fun aliveState(host: ScriptHost): Int {
        if (host.find(LIVE_12, LIVE_THRESHOLD) != null) return ALIVE_ENOUGH
        if (host.find(LIVE_11, LIVE_THRESHOLD) != null) return ALIVE_ENOUGH
        if (host.find(LIVE_10, LIVE_THRESHOLD) != null) return ALIVE_ENOUGH
        if (host.find(NONE_SINNER, FORMATION_THRESHOLD) != null) return ALIVE_EMPTY
        val lines = host.readText(ScriptRect(0, 0, host.screenWidth, host.screenHeight))
        for (line in lines) {
            if (looksEnough(line)) return ALIVE_ENOUGH
        }
        return ALIVE_LOW
    }

    private fun looksEnough(line: String): Boolean {
        for (match in LIVE_COUNT.findAll(line)) {
            val alive = match.groupValues[1].toIntOrNull() ?: continue
            val total = match.groupValues[2].toIntOrNull() ?: continue
            if (total in 5..9 && alive in 5..total) return true
        }
        return false
    }

    /**
     * 清队（AALC team_formation.py:33-47 `clean_team`）
     *
     * 点「清空选择」的确认按钮；没出现就点 identify 下方 300px 把面板收起来，收完再看一眼，
     * 确认按钮还是不在就退出，最多 [CLEAR_ATTEMPTS] 轮。
     */
    private fun clearTeam(host: ScriptHost, anchor: ScriptMatch) {
        var panel = anchor
        for (round in 1..CLEAR_ATTEMPTS) {
            if (host.cancelled) return
            if (host.clickTemplate(CLEAR_CONFIRM, FORMATION_THRESHOLD)) {
                host.log("[编队] 点清队确认（第 " + round + " 轮）")
                host.sleep(ACTION_SLEEP)
                return
            }
            host.log("[编队] 没看到清队确认，点锚点下方收起面板")
            // AALC team_formation.py:41-46：click [identify[0], identify[1] + 600*scale]。
            // AALC 的 identify 匹配坐标是模板框左上角，不是中心，所以这里也用 box.x / box.y 对齐。
            host.click(panel.box.x, panel.box.y + PANEL_TAIL_Y)
            host.sleep(PANEL_SLEEP)
            if (host.find(CLEAR_CONFIRM, FORMATION_THRESHOLD) == null) return
            panel = host.find(IDENTIFY, FORMATION_THRESHOLD, IDENTIFY_ROI) ?: panel
        }
    }

    /**
     * 编队码加载（AALC `team_formation.py:283-339 load_team_code_in_game`）
     *
     * 上游流程是：选择队伍确认页 → 点编队码按钮 → 点「加载编队码」→ 用取消按钮确认输入框出现
     * → `auto.input_text(team_code)` → 点确认 → 再认编队码按钮判断成功 → 失败点取消并用 (100,100) 关窗。
     *
     * 本项目只能做到「探测 + 跳过」，原因有三条，都是硬限制：
     * 1. `ScriptHost` 没有 `input_text` / 键盘注入 / 剪贴板能力（只有 find/click/readText/screenshot），
     *    无法把编队码输进输入框；
     * 2. 上游依赖的 `teams/load_team_code_button_assets.png`、`teams/team_code_cancel_button_assets.png`、
     *    `teams/team_code_confirm_button_assets.png` 在本项目 `assets/resource/image/` 下都不存在，
     *    无法可靠判断输入框是否打开、也无法关闭弹窗；
     * 3. 本脚本的入口是 `MirrorHandleTeam`（镜牢编队页），而 AALC 是在更早的「选择队伍」页
     *    （`mirror.py:1098 select_mirror_team`）加载编队码，那里才有 `select_team_confirm`。
     *
     * 所以按任务约定降级为：先按 AALC 前置条件认 `select_team_confirm`，再用 OCR 找编队码输入框，
     * 输入框不存在就直接 log 跳过；即使存在也因为没有输入能力而跳过，绝不点开弹窗留下半截状态。
     */
    private fun loadTeamCode(host: ScriptHost, teamCode: String) {
        if (host.find(SELECT_TEAM_CONFIRM, FORMATION_THRESHOLD) == null) {
            host.log("[编队] 编队码：" + teamCode + " 已配置，但当前不是选择队伍页（没认出 select_team_confirm），跳过")
            return
        }
        // AALC team_formation.py:303-339：点「队伍代码」→ 等弹窗 → 输入 → 连点确认 → 看到按钮回来算成功
        repeat(3) { attempt ->
            if (host.cancelled) return
            if (!host.clickTemplate(TEAM_CODE, 0.8)) {
                host.log("[编队] 编队码：第 " + (attempt + 1) + " 次没点到「队伍代码」按钮")
                return@repeat
            }
            host.sleep(900)
            if (host.find(TEAM_CODE_CANCEL, 0.75) == null) {
                // 弹窗没出现：点空白关掉再试（AALC 的 mouse_click_blank）
                host.clickBlank(1)
                host.sleep(600)
                return@repeat
            }
            if (!host.inputText(teamCode)) {
                host.log("[编队] 编队码：控制器不支持输入文本，关掉弹窗跳过")
                host.clickTemplate(TEAM_CODE_CANCEL, 0.75)
                host.sleep(600)
                return
            }
            host.sleep(500)
            repeat(3) {
                if (host.clickTemplate(TEAM_CODE_CONFIRM, 0.75)) host.sleep(700)
            }
            host.sleep(700)
            if (host.find(TEAM_CODE, 0.75) != null) {
                host.log("[编队] 编队码加载成功")
                return
            }
            host.clickTemplate(TEAM_CODE_CANCEL, 0.75)
            host.sleep(600)
        }
        // AALC 失败后点左上角关窗
        host.click(100, 100)
        host.log("[编队] 编队码加载失败，已重试 3 次，继续按顺位编队")
    }

    /**
     * 按顺位点罪人格子（AALC team_formation.py:58-77）
     *
     * 锚点取 identify 匹配框的中心，第一格 = 锚点 + (-900, +65)；编号 1..6 在第一排，
     * 7..12 在第二排（y 再 +250），同一排每格 +135。罪人编号与 pipeline 的格子一一对应
     * （SelectSinnerSlotN 的 target_offset 就是这套坐标），点选顺序就是出战顺位。
     */
    private fun pickSinners(host: ScriptHost, order: List<Int>) {
        val anchor = host.find(IDENTIFY, FORMATION_THRESHOLD, IDENTIFY_ROI) ?: run {
            host.log("[编队] 点选前锚点丢了，交还给管线")
            return
        }
        val firstX = anchor.centerX + FIRST_X
        val firstY = anchor.centerY + FIRST_Y
        for (sinner in order) {
            if (host.cancelled) return
            val slot = sinner - 1
            val x = firstX + (slot % 6) * SLOT_STEP_X
            val y = firstY + (slot / 6) * SECOND_ROW_Y
            host.click(x, y)
            host.sleep(ACTION_SLEEP)
        }
        host.log("[编队] 按顺位点了 " + order.size + " 个罪人：" + order.joinToString(","))
    }

    /** AALC mirror.py:399-401：先点「链战」，没有再点「普通战斗」 */
    private fun clickToBattle(host: ScriptHost): Boolean {
        if (host.clickTemplate(CHAIN_TO_BATTLE, BATTLE_THRESHOLD)) {
            host.log("[编队] 点「链战」出战")
            return true
        }
        if (host.clickTemplate(NORMAL_TO_BATTLE, BATTLE_THRESHOLD)) {
            host.log("[编队] 点「普通战斗」出战")
            return true
        }
        return false
    }

    /**
     * 战败重开（AALC mirror.py:1213-1240 `re_start`）
     *
     * 每轮依次试「转窗口并放弃→确认」「放弃」「投降」「设置」，点到就睡一下再看，
     * 都没点到就点空白（AALC 在这里按 ESC 清浮层，手机端没有 ESC），
     * 连续两轮没有任何进展就退出，把控制权还回管线。
     */
    private fun restart(host: ScriptHost): Boolean {
        var idle = 0
        for (round in 1..RESTART_LOOPS) {
            if (host.cancelled) return false
            if (host.clickTemplate(FORFEIT_CONFIRM, RESTART_THRESHOLD)) {
                host.log("[编队] 重开：确认退出本局（第 " + round + " 轮）")
                host.sleep(RE_START_SLEEP)
                return true
            }
            val clicked = host.clickTemplate(FORFEIT, RESTART_THRESHOLD) ||
                host.clickTemplate(GIVE_UP, RESTART_THRESHOLD) ||
                host.clickTemplate(SETTING, RESTART_THRESHOLD)
            if (clicked) {
                idle = 0
                host.sleep(ACTION_SLEEP)
                continue
            }
            host.clickBlank(1)
            host.sleep(ACTION_SLEEP)
            idle++
            if (idle >= 2) {
                host.log("[编队] 重开没进展（连续点空白），交还给管线")
                return false
            }
        }
        host.log("[编队] 重开超过 " + RESTART_LOOPS + " 轮没确认，交还给管线")
        return false
    }

    /** 参数是逗号分隔的罪人编号；容忍空格、中文逗号、分号，越界的直接丢掉 */
    private fun parseOrder(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", "，", ";", " ", "、")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..12 }
            .distinct()
    }
}
