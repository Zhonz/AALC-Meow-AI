package com.aliothmoon.maafw.maa

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * MaaFramework C API 的 JNA 声明
 *
 * 官方没有 Java binding（`source/binding/` 只有 NodeJS 与 Python），这里按 `include/MaaFramework/` 的
 * 头文件手写，只声明本项目用到的子集。改动前先对照头文件，签名以头文件为准
 *
 * 类型映射：`MaaBool`(uint8) → Byte，`MaaId`/`MaaSize`(int64/uint64) → Long，`MaaStatus`(int32) → Int
 */
interface MaaFrameworkLibrary : Library {

    fun MaaVersion(): String?

    fun MaaGlobalSetOption(key: Int, value: Pointer?, valSize: Long): Byte

    // ── Resource ──

    fun MaaResourceCreate(): Pointer?

    fun MaaResourceDestroy(res: Pointer?)

    fun MaaResourceAddSink(res: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaResourcePostBundle(res: Pointer?, path: String): Long

    fun MaaResourceWait(res: Pointer?, id: Long): Int

    fun MaaResourceLoaded(res: Pointer?): Byte

    fun MaaResourceClear(res: Pointer?): Byte

    // ── Controller ──

    fun MaaAndroidNativeControllerCreate(configJson: String): Pointer?

    fun MaaControllerDestroy(ctrl: Pointer?)

    fun MaaControllerAddSink(ctrl: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaControllerPostConnection(ctrl: Pointer?): Long

    fun MaaControllerWait(ctrl: Pointer?, id: Long): Int

    fun MaaControllerConnected(ctrl: Pointer?): Byte

    fun MaaControllerSetOption(ctrl: Pointer?, key: Int, value: Pointer?, valSize: Long): Byte

    // ── Tasker ──

    fun MaaTaskerCreate(): Pointer?

    fun MaaTaskerDestroy(tasker: Pointer?)

    fun MaaTaskerAddSink(tasker: Pointer?, sink: MaaEventCallback?, transArg: Pointer?): Long

    fun MaaTaskerBindResource(tasker: Pointer?, res: Pointer?): Byte

    fun MaaTaskerBindController(tasker: Pointer?, ctrl: Pointer?): Byte

    fun MaaTaskerInited(tasker: Pointer?): Byte

    fun MaaTaskerPostTask(tasker: Pointer?, entry: String, pipelineOverride: String): Long

    fun MaaTaskerStatus(tasker: Pointer?, id: Long): Int

    fun MaaTaskerWait(tasker: Pointer?, id: Long): Int

    fun MaaTaskerRunning(tasker: Pointer?): Byte

    fun MaaTaskerPostStop(tasker: Pointer?): Long

    fun MaaTaskerStopping(tasker: Pointer?): Byte

    // ── Tasker：脚本驱动（[com.aliothmoon.maafw.script]）用的识别与详情 ──
    // 官方没有 Java binding，以下按 include/MaaFramework/Instance/MaaTasker.h 手写

    /** @param image 上一帧截图；传 null 表示让引擎自己截一张 */
    fun MaaTaskerPostRecognition(tasker: Pointer?, recoType: String, recoParam: String, image: Pointer?): Long

    fun MaaTaskerPostAction(
        tasker: Pointer?,
        actionType: String,
        actionParam: String,
        box: Pointer?,
        recoDetail: String?,
    ): Long

    fun MaaTaskerGetRecognitionDetail(
        tasker: Pointer?,
        recoId: Long,
        nodeName: Pointer?,
        algorithm: Pointer?,
        hit: Pointer?,
        box: Pointer?,
        detailJson: Pointer?,
        raw: Pointer?,
        draws: Pointer?,
    ): Byte

    fun MaaTaskerGetNodeDetail(
        tasker: Pointer?,
        nodeId: Long,
        nodeName: Pointer?,
        recoId: Pointer?,
        actionId: Pointer?,
        completed: Pointer?,
    ): Byte

    /** 一次投递过的任务里最近完成的那个节点；节点不存在返回 0 */
    fun MaaTaskerGetLatestNode(tasker: Pointer?, nodeName: String, latestId: Pointer?): Byte

    /**
     * 一次任务里跑过的节点 id 列表
     *
     * 出参协议特殊：`*node_id_list_size == 0` 时只回填「需要几个」；否则拷 min(n, size) 个并把
     * size 改成实际拷的个数（Common/MaaTasker.cpp）。所以调用方要给一块够大的 Memory，并把
     * size 初始化成它的容量
     */
    fun MaaTaskerGetTaskDetail(
        tasker: Pointer?,
        taskId: Long,
        entry: Pointer?,
        nodeIds: Pointer?,
        nodeIdSize: Pointer?,
        status: Pointer?,
    ): Byte

    // ── Controller：脚本自己点击/滑动 ──

    fun MaaControllerPostClick(ctrl: Pointer?, x: Int, y: Int): Long

    fun MaaControllerPostSwipe(
        ctrl: Pointer?,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        duration: Int,
    ): Long

    fun MaaControllerPostTouchDown(ctrl: Pointer?, contact: Int, x: Int, y: Int, pressure: Int): Long

    /** 按住不放地拖动；AALC 的连线（技能格→齿轮）就靠 down/move/up 三段拼出来 */
    fun MaaControllerPostTouchMove(ctrl: Pointer?, contact: Int, x: Int, y: Int, pressure: Int): Long

    fun MaaControllerPostTouchUp(ctrl: Pointer?, contact: Int): Long

    fun MaaControllerPostScroll(ctrl: Pointer?, dx: Int, dy: Int): Long

    // ── StringBuffer ──
    // MaaAgentClient 的 identifier 走的是 buffer 而不是 char*，用完必须 Destroy

    fun MaaStringBufferCreate(): Pointer?

    fun MaaStringBufferDestroy(handle: Pointer?)

    fun MaaStringBufferGet(handle: Pointer?): String?

    // ── ImageBuffer ──
    // focus 模板的 {image} 占位符取的是 controller 手里那张缓存帧

    fun MaaImageBufferCreate(): Pointer?

    fun MaaImageBufferDestroy(handle: Pointer?)

    fun MaaImageBufferIsEmpty(handle: Pointer?): Byte

    fun MaaImageBufferWidth(handle: Pointer?): Int

    fun MaaImageBufferHeight(handle: Pointer?): Int

    /** 拿的是编码后（PNG）的字节，不是裸位图；长度另取 [MaaImageBufferGetEncodedSize] */
    fun MaaImageBufferGetEncoded(handle: Pointer?): Pointer?

    fun MaaImageBufferGetEncodedSize(handle: Pointer?): Long

    /**
     * 取 controller 的缓存截图
     *
     * 尺寸按 controller 的 screenshot target size 缩放过，与设备物理分辨率未必一致
     */
    fun MaaControllerCachedImage(ctrl: Pointer?, buffer: Pointer?): Byte

    /**
     * 对应 `MaaEventCallback`：`void(void* handle, const char* message, const char* details_json, void* trans_arg)`
     * 由 native 线程回调，实现里不得阻塞，也不得抛异常穿回 native
     */
    fun interface MaaEventCallback : Callback {
        operator fun invoke(handle: Pointer?, message: String?, details: String?, transArg: Pointer?)
    }
}

/** `MaaStatusEnum` */
object MaaStatus {
    const val INVALID = 0
    const val PENDING = 1000
    const val RUNNING = 2000
    const val SUCCEEDED = 3000
    const val FAILED = 4000

    fun isDone(status: Int): Boolean = status == SUCCEEDED || status == FAILED
}

/** `MaaCtrlOptionEnum` */
object MaaCtrlOption {
    const val SCREENSHOT_TARGET_LONG_SIDE = 1
    const val SCREENSHOT_TARGET_SHORT_SIDE = 2
    const val SCREENSHOT_USE_RAW_SIZE = 3
    const val MOUSE_LOCK_FOLLOW = 4
    const val SCREENSHOT_RESIZE_METHOD = 6
}

/** 本项目用到的 `MaaGlobalOptionEnum` */
object MaaGlobalOption {
    const val LOG_DIR = 1
    const val SAVE_DRAW = 2
    const val STDOUT_LEVEL = 4
    const val DEBUG_MODE = 6
    const val SAVE_ON_ERROR = 7
}

/** `MaaLoggingLevelEnum` */
object MaaLoggingLevel {
    const val OFF = 0
    const val ERROR = 2
    const val INFO = 4
    const val DEBUG = 5
}

/** `MaaMsg.h` 里本项目会分派的消息名 */
object MaaMsg {
    const val RESOURCE_LOADING_STARTING = "Resource.Loading.Starting"
    const val RESOURCE_LOADING_SUCCEEDED = "Resource.Loading.Succeeded"
    const val RESOURCE_LOADING_FAILED = "Resource.Loading.Failed"

    const val CONTROLLER_ACTION_STARTING = "Controller.Action.Starting"
    const val CONTROLLER_ACTION_SUCCEEDED = "Controller.Action.Succeeded"
    const val CONTROLLER_ACTION_FAILED = "Controller.Action.Failed"

    const val TASKER_TASK_STARTING = "Tasker.Task.Starting"
    const val TASKER_TASK_SUCCEEDED = "Tasker.Task.Succeeded"
    const val TASKER_TASK_FAILED = "Tasker.Task.Failed"

    const val NODE_PIPELINE_NODE_STARTING = "Node.PipelineNode.Starting"
    const val NODE_PIPELINE_NODE_SUCCEEDED = "Node.PipelineNode.Succeeded"
    const val NODE_PIPELINE_NODE_FAILED = "Node.PipelineNode.Failed"

    const val NODE_ACTION_STARTING = "Node.Action.Starting"
    const val NODE_ACTION_SUCCEEDED = "Node.Action.Succeeded"
    const val NODE_ACTION_FAILED = "Node.Action.Failed"
}
