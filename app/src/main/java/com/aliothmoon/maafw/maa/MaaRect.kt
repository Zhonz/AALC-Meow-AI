package com.aliothmoon.maafw.maa

import com.sun.jna.Structure

/**
 * `MaaRect`（见 include/MaaFramework/MaaDef.h）
 *
 * 只作 C 侧出参/入参用，脚本层不直接接触——[com.aliothmoon.maafw.script.ScriptRect] 才是脚本看到的形状
 */
@Structure.FieldOrder("x", "y", "w", "h")
class MaaRect : Structure() {
    @JvmField
    var x: Int = 0

    @JvmField
    var y: Int = 0

    @JvmField
    var w: Int = 0

    @JvmField
    var h: Int = 0

    fun toScriptRect(): com.aliothmoon.maafw.script.ScriptRect =
        com.aliothmoon.maafw.script.ScriptRect(x, y, w, h)
}
