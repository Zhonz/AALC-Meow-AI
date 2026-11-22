package com.aliothmoon.maafw.remote

import com.aliothmoon.maafw.maa.MaaFrameworkLibrary
import com.aliothmoon.maafw.maa.MaaStatus
import com.aliothmoon.maafw.script.ScriptHost
import com.aliothmoon.maafw.script.ScriptMatch
import com.aliothmoon.maafw.script.ScriptRect
import com.aliothmoon.maafw.script.ScreenShot
import android.graphics.BitmapFactory
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 脚本层对引擎的落地实现（跑在特权进程 [MaaRunner] 里，握着 tasker / controller 的 native handle）
 *
 * 识别走「探针节点 + 覆盖」：每次识别都现造一个叫 [PROBE_NODE] 的管线节点投递一次
 * （`pipeline_override` 允许凭空建节点，action=DoNothing、next 为空，投完即成功结束），
 * 再顺 task → node → reco 三级详情把 box 与 detail_json 读出来。
 *
 * 为什么不直接 `MaaTaskerPostRecognition`：它要求调用方自己给一帧图（Common/MaaTasker.cpp:154
 * 里 image 为空直接返回 MaaInvalidId），而拿帧再转交 buffer 多一层拷贝且要走 controller 的
 * 截图节流；探针法复用引擎自己的「任务里截图」链路，实测位置与真实像素一一对应。
 */
internal class MaaScriptHost(
    private val lib: MaaFrameworkLibrary,
    private val tasker: Pointer,
    private val controller: Pointer,
    override val screenWidth: Int,
    override val screenHeight: Int,
    private val isCancelled: () -> Boolean,
    private val logger: (String) -> Unit,
) : ScriptHost {

    override val cancelled: Boolean get() = isCancelled()

    override fun log(message: String) = logger(message)

    override fun sleep(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            if (cancelled) return
            val step = minOf(remaining, SLEEP_SLICE_MS)
            Thread.sleep(step)
            remaining -= step
        }
    }

    // ── 识别 ──

    override fun find(template: String, threshold: Double, roi: ScriptRect?): ScriptMatch? =
        findAll(template, threshold, roi, limit = 1).firstOrNull()

    override fun findAll(
        template: String,
        threshold: Double,
        roi: ScriptRect?,
        limit: Int,
    ): List<ScriptMatch> {
        val param = buildJsonObject {
            put("template", template)
            put("threshold", threshold)
            put("order_by", "Score")
            if (roi != null) putRoi(roi)
        }
        val detail = recognize("TemplateMatch", param) ?: return emptyList()
        val buckets = listOf("filtered", "all")
        for (key in buckets) {
            val array = detail[key] as? JsonArray ?: continue
            val matches = array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val box = ScriptRect.parse(item["box"] as? JsonArray) ?: return@mapNotNull null
                val score = item["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                ScriptMatch(box, score)
            }.take(limit.coerceAtLeast(1))
            if (matches.isNotEmpty()) return matches
        }
        return emptyList()
    }

    override fun readText(roi: ScriptRect, threshold: Double): List<String> {
        val param = buildJsonObject {
            putRoi(roi)
            put("threshold", threshold)
        }
        val detail = recognize("Ocr", param) ?: return emptyList()
        val array = (detail["filtered"] as? JsonArray) ?: (detail["all"] as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element ->
            ((element as? JsonObject)?.get("text"))?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 投一次探针任务，返回识别结果的 detail_json
     *
     * 没命中时返回**空对象**而不是 null：模板没匹配与识别失败要区分开，前者 detail 里有
     * `"all":[]`，调用方据此判断「找过但没找到」。
     */
    private fun recognize(type: String, param: JsonObject): JsonObject? {
        val overrides = buildJsonArray {
            add(
                buildJsonObject {
                    put(
                        PROBE_NODE,
                        buildJsonObject {
                            put(
                                "recognition",
                                buildJsonObject {
                                    put("type", type)
                                    put("param", param)
                                },
                            )
                            put("action", "DoNothing")
                            put("next", buildJsonArray { })
                            put("on_error", buildJsonArray { })
                            put("timeout", PROBE_TIMEOUT_MS)
                            put("rate_limit", 0)
                        },
                    )
                },
            )
        }
        val taskId = lib.MaaTaskerPostTask(tasker, PROBE_NODE, overrides.toString())
        if (taskId == INVALID_ID) {
            log("[脚本] 探针任务投递失败（$type）")
            return null
        }
        val status = lib.MaaTaskerWait(tasker, taskId)
        if (status != MaaStatus.SUCCEEDED) {
            log("[脚本] 探针任务未成功（$type，status=$status）")
            return null
        }
        val nodeId = latestNodeId(taskId) ?: return null
        val recoId = nodeRecoId(nodeId) ?: return null
        return recognitionDetail(recoId)
    }

    private fun latestNodeId(taskId: Long): Long? {
        val ids = Memory(NODE_ID_BYTES * NODE_ID_SLOTS)
        val size = Memory(NODE_ID_BYTES)
        size.setLong(0, NODE_ID_SLOTS.toLong())
        val ok = lib.MaaTaskerGetTaskDetail(tasker, taskId, null, ids, size, null)
        if (ok.toInt() == 0) return null
        val count = size.getLong(0)
        if (count <= 0) return null
        return ids.getLong((count - 1) * NODE_ID_BYTES)
    }

    private fun nodeRecoId(nodeId: Long): Long? {
        val recoId = Memory(NODE_ID_BYTES)
        val ok = lib.MaaTaskerGetNodeDetail(tasker, nodeId, null, recoId, null, null)
        if (ok.toInt() == 0) return null
        return recoId.getLong(0).takeIf { it != 0L }
    }

    private fun recognitionDetail(recoId: Long): JsonObject? {
        val hit = Memory(1)
        val box = Memory(RECT_BYTES)
        val buffer = lib.MaaStringBufferCreate() ?: return null
        try {
            val ok = lib.MaaTaskerGetRecognitionDetail(
                tasker, recoId, null, null, hit, box, buffer, null, null,
            )
            if (ok.toInt() == 0) return null
            val text = lib.MaaStringBufferGet(buffer)
            if (text.isNullOrBlank()) return null
            return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        } finally {
            lib.MaaStringBufferDestroy(buffer)
        }
    }

    // ── 操作 ──

    override fun click(x: Int, y: Int): Boolean =
        waitController(lib.MaaControllerPostClick(controller, x, y))

    override fun inputText(text: String): Boolean =
        waitController(lib.MaaControllerPostInputText(controller, text))

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Boolean =
        waitController(lib.MaaControllerPostSwipe(controller, fromX, fromY, toX, toY, durationMs))

    override fun scroll(dy: Int): Boolean =
        waitController(lib.MaaControllerPostScroll(controller, 0, dy))

    override fun screenshot(): ScreenShot? {
        val buffer = lib.MaaImageBufferCreate() ?: return null
        return try {
            if (lib.MaaControllerCachedImage(controller, buffer) == 0.toByte()) {
                log("控制器没有可用截图，交还给管线")
                null
            } else {
                // 统一缩放到脚本坐标系：脚本里所有坐标常量都是 1280×720 的
                lib.MaaImageBufferResize(buffer, screenWidth, screenHeight)
                val data = lib.MaaImageBufferGetEncoded(buffer)
                val size = lib.MaaImageBufferGetEncodedSize(buffer).toInt()
                if (data == null || size <= 0) {
                    null
                } else {
                    val bytes = data.getByteArray(0, size)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, size)
                    if (bitmap == null) {
                        null
                    } else {
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val shot = ScreenShot(bitmap.width, bitmap.height, pixels)
                        bitmap.recycle()
                        shot
                    }
                }
            }
        } catch (t: Throwable) {
            log("截图读取失败: " + t.message)
            null
        } finally {
            lib.MaaImageBufferDestroy(buffer)
        }
    }

    override fun touchDown(x: Int, y: Int): Boolean =
        waitController(lib.MaaControllerPostTouchDown(controller, CONTACT, x, y, PRESSURE))

    override fun touchMove(x: Int, y: Int): Boolean =
        waitController(lib.MaaControllerPostTouchMove(controller, CONTACT, x, y, PRESSURE))

    override fun touchUp(): Boolean =
        waitController(lib.MaaControllerPostTouchUp(controller, CONTACT))

    private fun waitController(id: Long): Boolean {
        if (id == INVALID_ID) return false
        val status = lib.MaaControllerWait(controller, id)
        return status == MaaStatus.SUCCEEDED
    }

    private fun JsonObjectBuilder.putRoi(roi: ScriptRect) {
        putJsonArray("roi") {
            add(roi.x)
            add(roi.y)
            add(roi.w)
            add(roi.h)
        }
    }

    companion object {
        /** 探针节点名；带前缀免得与 PI 里真实节点撞名 */
        const val PROBE_NODE = "__MaaScriptProbe"

        private const val INVALID_ID = 0L
        private const val CONTACT = 0
        private const val PRESSURE = 50
        private const val NODE_ID_BYTES = 8L
        private const val NODE_ID_SLOTS = 4
        private const val RECT_BYTES = 16L
        private const val PROBE_TIMEOUT_MS = 3_000
        private const val SLEEP_SLICE_MS = 200L
    }
}

/**
 * 交棒判定用：某个节点最近一次执行留下的 id；从没跑过返回 0
 *
 * 注意 `RuntimeCache::latest_nodes_` 是按节点名长期保留的（只在 clear() 时清空），
 * 「读到了 id」只说明历史上跑过，不能说明本轮跑过 —— 调用方必须前后比对。
 */
internal fun maaLatestNodeId(lib: MaaFrameworkLibrary, tasker: Pointer, nodeName: String): Long {
    val latest = Memory(8)
    return if (lib.MaaTaskerGetLatestNode(tasker, nodeName, latest).toInt() != 0) latest.getLong(0) else 0L
}
