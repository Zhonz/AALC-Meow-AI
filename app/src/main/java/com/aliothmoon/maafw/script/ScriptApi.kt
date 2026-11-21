package com.aliothmoon.maafw.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 脚本看到的一小块屏幕区域；坐标系是**截图空间**（默认 1280x720）
 *
 * 引擎在 [com.aliothmoon.maafw.maa.MaaFrameworkLibrary.MaaControllerPostClick] 里会自己把截图
 * 坐标放大回设备物理像素（ControllerAgent.cpp 的 handle_click），所以脚本只按 720p 算即可。
 */
data class ScriptRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
    val right: Int get() = x + w
    val bottom: Int get() = y + h

    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom

    /** 向外扩一圈，给 OCR 留边 */
    fun grow(dx: Int, dy: Int = dx): ScriptRect = ScriptRect(x - dx, y - dy, w + dx * 2, h + dy * 2)

    companion object {
        /** 解析引擎 detail_json 里的 `box`: [x, y, w, h] */
        fun parse(array: JsonArray?): ScriptRect? {
            if (array == null || array.size < 4) return null
            val v = array.map { (it as? JsonPrimitive)?.intOrNull }
            if (v.any { it == null }) return null
            return ScriptRect(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
        }
    }
}

/** 一次识别命中：位置 + 分数 */
data class ScriptMatch(val box: ScriptRect, val score: Double) {
    val centerX: Int get() = box.centerX
    val centerY: Int get() = box.centerY
}

/**
 * 脚本能对设备做的事
 *
 * 实现只有一份：[com.aliothmoon.maafw.remote.MaaScriptHost]（跑在特权进程里，握着引擎 handle）。
 * 抽接口是为了让脚本逻辑能在普通单元测试里对着假 host 跑。
 */
interface ScriptHost {
    /** 截图空间宽度（引擎按 SCREENSHOT_TARGET_SHORT_SIDE=720 归一化过） */
    val screenWidth: Int

    /** 截图空间高度 */
    val screenHeight: Int

    /** 用户点了停止；脚本应在下一个检查点收手 */
    val cancelled: Boolean

    fun log(message: String)

    /** 可被打断的 sleep */
    fun sleep(millis: Long)

    fun find(
        template: String,
        threshold: Double = DEFAULT_THRESHOLD,
        roi: ScriptRect? = null,
    ): ScriptMatch?

    fun findAll(
        template: String,
        threshold: Double = DEFAULT_THRESHOLD,
        roi: ScriptRect? = null,
        limit: Int = MAX_MATCHES,
    ): List<ScriptMatch>

    /** 区域 OCR；返回引擎给出的全部文本行（可能为空） */
    fun readText(roi: ScriptRect, threshold: Double = DEFAULT_OCR_THRESHOLD): List<String>

    fun click(x: Int, y: Int): Boolean

    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int = 400): Boolean

    /** 滚轮；正数向下 */
    fun scroll(dy: Int): Boolean

    /** 按住某点（contact 固定 0，对应单指按下） */
    fun touchDown(x: Int, y: Int): Boolean

    /** 按住状态下滑到某点 */
    fun touchMove(x: Int, y: Int): Boolean

    /** 抬起 */
    fun touchUp(): Boolean

    companion object {
        const val DEFAULT_THRESHOLD = 0.8
        const val DEFAULT_OCR_THRESHOLD = 0.3
        const val MAX_MATCHES = 32
    }
}

/**
 * 点 [box] 的中心
 *
 * 用法上比手算 centerX/centerY 少一次心算，脚本里到处要用
 */
fun ScriptHost.click(box: ScriptRect): Boolean = click(box.centerX, box.centerY)

fun ScriptHost.click(match: ScriptMatch): Boolean = click(match.box)

/** 找到就点，找不到返回 false（AALC 的 click_element 同语义） */
fun ScriptHost.clickTemplate(
    template: String,
    threshold: Double = ScriptHost.DEFAULT_THRESHOLD,
    roi: ScriptRect? = null,
): Boolean {
    val match = find(template, threshold, roi) ?: return false
    return click(match)
}

/**
 * 点「空白处」把弹窗/浮层戳掉
 *
 * AALC 的 mouse_click_blank 就是点屏幕左上角 (1,1)（module/automation/input_handlers/input.py:242），
 * 那里在游戏里永远是背景；这里照抄并加一点抖动，避免连点同一像素被系统当成双击。
 */
fun ScriptHost.clickBlank(times: Int = 1) {
    repeat(times.coerceAtLeast(1)) {
        click(4 + (it % 3) * 3, 4 + (it % 2) * 3)
        sleep(120)
    }
}

/**
 * 一路按住拖过若干点（AALC 的 mouse_drag_link）
 *
 * 战斗里「把技能格连到右侧齿轮」必须是一次连续拖动：分开 swipe 会被游戏当成多次点击，
 * 连携就断了。因此这里用 touchDown → touchMove... → touchUp 拼一条完整手势。
 */
fun ScriptHost.dragLink(points: List<Pair<Int, Int>>, segmentMs: Long = 60) {
    if (points.size < 2) return
    val (sx, sy) = points.first()
    touchDown(sx, sy)
    for (index in 1 until points.size) {
        sleep(segmentMs)
        val (x, y) = points[index]
        touchMove(x, y)
    }
    sleep(segmentMs)
    touchUp()
}

/** 轮询等一个模板出现 */
fun ScriptHost.waitFor(
    template: String,
    timeoutMs: Long = 3_000,
    intervalMs: Long = 300,
    threshold: Double = ScriptHost.DEFAULT_THRESHOLD,
    roi: ScriptRect? = null,
): ScriptMatch? {
    var waited = 0L
    while (waited <= timeoutMs) {
        find(template, threshold, roi)?.let { return it }
        if (cancelled) return null
        sleep(intervalMs)
        waited += intervalMs
    }
    return null
}

/** 轮询若干个模板，谁先出现返回谁（返回命中的模板名与位置） */
fun ScriptHost.waitAny(
    templates: List<String>,
    timeoutMs: Long = 3_000,
    intervalMs: Long = 300,
    threshold: Double = ScriptHost.DEFAULT_THRESHOLD,
): Pair<String, ScriptMatch>? {
    var waited = 0L
    while (waited <= timeoutMs) {
        for (template in templates) {
            find(template, threshold)?.let { return template to it }
        }
        if (cancelled) return null
        sleep(intervalMs)
        waited += intervalMs
    }
    return null
}

/** OCR 多行文本里挑出纯数字（AALC `_extract_money_from_ocr_texts` 的同一套清洗规则） */
object ScriptText {
    /** OCR 常把数字认错；AALC in_shop.py:1441 起的对照表原样搬过来 */
    private val DIGIT_FIXES = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1',
        'Z' to '2',
        'S' to '5',
        'G' to '6',
        'B' to '8',
        'h' to '4',
    )

    fun firstNumber(lines: List<String>): Int? {
        val cleaned = lines.map { it.replace(" ", "").replace(",", "").trim() }.filter { it.isNotEmpty() }
        cleaned.firstOrNull { it.all(Char::isDigit) }?.let { return it.toIntOrNull() }
        for (line in cleaned) {
            val normalized = line.map { DIGIT_FIXES[it] ?: it }.joinToString("")
            if (normalized.isNotEmpty() && normalized.all(Char::isDigit)) {
                return normalized.toIntOrNull()
            }
        }
        return null
    }
}

/**
 * 一段可以「接管」管线执行的脚本
 *
 * 引擎跑到 [entry] 这个节点就算本轮任务成功结束，App 侧随即调 [run]；跑完再把 [resumeEntry]
 * 重新投给引擎，让管线自己继续往下走（见 `MaaRunner` 的交棒循环）。
 */
interface ScriptTask {
    /** 稳定 id，写进运行计划的线格式；改名等于破坏旧计划缓存 */
    val id: String

    /** 交棒节点：管线识别到它就停下来交给脚本 */
    val entry: String

    /** 脚本干完活后重新投递的管线入口 */
    val resumeEntry: String

    /** 一次任务里最多被交棒几次，防止脚本与管线互相踢皮球 */
    val maxRounds: Int get() = MAX_ROUNDS

    /** 每次任务开始时调用：脚本在这里清掉「跨节点共享」的状态（例如合成出四级后跳过后续商店） */
    fun resetSession() {}

    fun run(host: ScriptHost, params: Map<String, String>)

    companion object {
        const val MAX_ROUNDS = 16
    }
}

/** id / 交棒节点 → 脚本实现 */
object ScriptRegistry {
    private val all: List<ScriptTask> =
        listOf(MirrorShopScript, MirrorRoadScript, MirrorBattleScript, MirrorThemePackScript)

    fun byId(id: String): ScriptTask? = all.firstOrNull { it.id == id }

    fun byEntry(entry: String): ScriptTask? = all.firstOrNull { it.entry == entry }

    val ids: List<String> get() = all.map { it.id }
}

/** 参数表的取值助手；缺省一律「关 / 0」 */
object ScriptParams {
    fun flag(params: Map<String, String>, key: String, default: Boolean = false): Boolean {
        val raw = params[key] ?: return default
        return when (raw.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> default
        }
    }

    fun int(params: Map<String, String>, key: String, default: Int): Int =
        params[key]?.trim()?.toIntOrNull() ?: default

    fun text(params: Map<String, String>, key: String): String? =
        params[key]?.trim()?.takeIf { it.isNotEmpty() && it != "none" }

    /** 引擎 detail_json 里的 `best`/`all` 项可能是对象也可能是数组，取分数用 */
    fun score(element: kotlinx.serialization.json.JsonElement?): Double {
        val primitive = element as? JsonPrimitive ?: return 0.0
        return primitive.content.toDoubleOrNull() ?: 0.0
    }

    fun bool(element: kotlinx.serialization.json.JsonElement?): Boolean? =
        (element as? JsonPrimitive)?.booleanOrNull
}
