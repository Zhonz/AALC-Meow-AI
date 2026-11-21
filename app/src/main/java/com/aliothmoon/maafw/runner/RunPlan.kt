package com.aliothmoon.maafw.runner

import com.aliothmoon.maafw.domain.AgentDefinition
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import kotlinx.serialization.json.JsonObject

/** Start 时冻结的执行输入；Runner 不再观察用户配置 */
data class RunPlan(
    val projectName: String,
    val projectVersion: String?,
    val controller: ControllerDefinition,
    val resource: ResourceDefinition,
    val runConfigurationId: RunConfigurationId,
    val tasks: List<RuntimeTask>,
    /** PI 声明的 agent，按声明顺序；Runner 逐个建 client 并拉起 child */
    val agents: List<AgentDefinition> = emptyList(),
    /** 注入 agent 子进程的 `PI_*`，见 [PiAgentEnv]；无 agent 的 PI 用不上 */
    val piEnv: Map<String, String> = emptyMap(),
)

data class RuntimeTask(
    val taskName: String,
    val entry: String,
    /** 有序 patch；Runner 按序传给 MaaFramework，不得提前合并 */
    val pipelineOverrides: List<JsonObject>,
    /** 加载期已物化的展示名；缺省回落 [taskName] */
    val label: String = taskName,
    /** 需要脚本接管的分段；空表示这个任务全程由管线自己跑完 */
    val scripts: List<TaskScript> = emptyList(),
)

/**
 * 一段「交棒」配置：管线跑到 [entry] 节点就算本轮任务成功结束，接着在特权进程里跑
 * [scriptId] 对应的脚本，跑完把 [resumeEntry] 重新投给引擎
 *
 * 存在理由：AALC 的商店逻辑（读经费、按行列排序买、按次数刷新、兜底退出）不是声明式管线
 * 能表达的，但它每一步又都是「识别 + 点击」，所以让管线负责走到现场，脚本负责现场决策。
 */
data class TaskScript(
    /** [com.aliothmoon.maafw.script.ScriptRegistry] 里的 id */
    val scriptId: String,
    /** 交棒节点：管线识别到它就停下来 */
    val entry: String,
    /** 脚本跑完重投的管线入口 */
    val resumeEntry: String,
    /** 一次任务里最多交棒几次，防脚本与管线互相踢皮球 */
    val maxRounds: Int = 16,
    /** 摊平的脚本参数（PI option → 字符串），键见脚本实现里的 KEY_* */
    val params: Map<String, String> = emptyMap(),
)

fun RunPlan.taskLabelMap(): Map<String, String> =
    tasks.associate { it.taskName to it.label.ifBlank { it.taskName } }
