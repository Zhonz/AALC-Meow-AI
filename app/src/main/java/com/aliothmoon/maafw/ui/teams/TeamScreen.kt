package com.aliothmoon.maafw.ui.teams

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.AalcTeamImport
import com.aliothmoon.maafw.domain.AalcTeamImportResult
import com.aliothmoon.maafw.domain.OptionCaseState
import com.aliothmoon.maafw.domain.TeamOptionScope
import com.aliothmoon.maafw.domain.TeamPreset
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaEmptyState
import com.aliothmoon.maafw.ui.components.MaaPromptDialog
import com.aliothmoon.maafw.ui.components.MaaSingleChoiceFlow
import com.aliothmoon.maafw.ui.components.maaClickable
import com.aliothmoon.maafw.ui.options.OptionEditorList

/** 罪人头像：case 名 → drawable，取自 AALC 的 assets/app/sinner */
private val SinnerAvatars: Map<String, Int> = mapOf(
    "YiSang" to R.drawable.sinner_yisang,
    "Faust" to R.drawable.sinner_faust,
    "DonQuixote" to R.drawable.sinner_donquixote,
    "Ryoshu" to R.drawable.sinner_ryoshu,
    "Meursault" to R.drawable.sinner_meursault,
    "HongLu" to R.drawable.sinner_honglu,
    "Heathcliff" to R.drawable.sinner_heathcliff,
    "Ishmael" to R.drawable.sinner_ishmael,
    "Rodion" to R.drawable.sinner_rodion,
    "Sinclair" to R.drawable.sinner_sinclair,
    "Outis" to R.drawable.sinner_outis,
    "Gregor" to R.drawable.sinner_gregor,
)

/** 编队页的分区：[TeamOptionScope.BASE_NAMES] 按 AALC 的卡片顺序切成三组 */
private val SECTIONS: List<Pair<Int, List<String>>> = listOf(
    R.string.teams_section_team to listOf("team_system", "shop_strategy", "reward_cards"),
    R.string.teams_section_opening to
        listOf("choose_opening_bonus", "opening_items", "mirror_opening_bonus", "mirror_use_starlight"),
    R.string.teams_section_battle to listOf("avoid_skill_3", "defense_first_round"),
)

/**
 * 编队页：对齐 AALC 的「编队卡 + 罪人宫格」结构
 *
 * 罪人点选顺序就是出战顺位（AALC team_formation.py 的语义），前 6 位出战、其余待命。
 * 每支编队在这里配一次，任务页只选「用哪支编队」；运行期由 TeamOptionScope.mergedValues 合并。
 * 编辑中的编队号是页面本地状态，不写进配置——它只是「我正看哪一张卡」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    state: SessionUiState,
    onIntent: (SessionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingImport by remember { mutableStateOf<AalcTeamImportResult?>(null) }
    val presets = state.teamPresets

    var editingTeam by rememberSaveable {
        mutableStateOf(presets.firstOrNull()?.teamNumber ?: TeamPreset.MIN_TEAM)
    }
    val currentTeam = rememberUpdatedState(editingTeam)

    /** 读进内存 → 解析 → 预览；编队文件只有几 KB，直接在主线程读完 */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.teams_import_read_failed, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val data = runCatching { AalcTeamImport.parseYaml(text) }.getOrElse { emptyMap() }
        if (!AalcTeamImport.looksLikeAalcTeam(data)) {
            Toast.makeText(context, R.string.teams_import_not_aalc, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        pendingImport = AalcTeamImport.toPreset(currentTeam.value, data)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_teams),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            actions = {
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(
                        imageVector = Icons.Outlined.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.teams_import),
                        modifier = Modifier.padding(start = MaaDesignTokens.Spacing.xs),
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        if (presets.isEmpty()) {
            MaaEmptyState(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.teams_empty_title),
                hint = stringResource(R.string.teams_empty_hint),
                modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xl),
            )
            return@Column
        }

        val team = presets.firstOrNull { it.teamNumber == editingTeam } ?: presets.first()
        val options = state.teamOptions[team.teamNumber].orEmpty()
        val locked = state.configurationLocked
        val ordered = remember(team) { TeamOptionScope.orderedSinners(team) }
        val sinnerCases = remember(options) {
            options.firstOrNull { it.name == TeamOptionScope.SINNERS }?.cases.orEmpty()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MaaDesignTokens.Spacing.lg,
                    end = MaaDesignTokens.Spacing.lg,
                    top = MaaDesignTokens.Spacing.sm,
                    bottom = MaaDesignTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
        ) {
            MaaCard(title = stringResource(R.string.teams_select_title)) {
                Text(
                    text = stringResource(R.string.teams_select_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MaaSingleChoiceFlow(
                    options = presets.map { it.teamNumber to teamChipLabel(it) },
                    selected = team.teamNumber,
                    onSelect = { editingTeam = it },
                    enabled = true,
                )
                ITextField(
                    value = team.remark,
                    onValueChange = { onIntent(SessionIntent.SetTeamRemark(team.teamNumber, it)) },
                    label = stringResource(R.string.teams_remark_label),
                    placeholder = stringResource(R.string.teams_remark_placeholder),
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            MaaCard(
                title = stringResource(R.string.teams_card_title, team.teamNumber),
                trailing = {
                    TextButton(
                        onClick = { onIntent(SessionIntent.ResetTeam(team.teamNumber)) },
                        enabled = !locked,
                    ) {
                        Text(stringResource(R.string.teams_reset))
                    }
                },
            ) {
                Text(
                    text = stringResource(R.string.teams_sinners_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.teams_sinners_summary,
                        ordered.size,
                        minOf(ordered.size, TeamOptionScope.DEPLOY_LIMIT),
                        maxOf(ordered.size - TeamOptionScope.DEPLOY_LIMIT, 0),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sinnerCases.isNotEmpty()) {
                    SinnerGrid(
                        cases = sinnerCases,
                        ordered = ordered,
                        enabled = !locked,
                        onToggle = { name ->
                            onIntent(
                                SessionIntent.SetTeamSinners(
                                    teamNumber = team.teamNumber,
                                    orderedSinners = if (name in ordered) ordered - name else ordered + name,
                                ),
                            )
                        },
                    )
                }
                if (ordered.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            onIntent(SessionIntent.SetTeamSinners(team.teamNumber, emptyList()))
                        },
                        enabled = !locked,
                    ) {
                        Text(stringResource(R.string.teams_sinners_clear))
                    }
                }
            }

            SECTIONS.forEach { (title, names) ->
                val section = options.filter { it.name in names }
                if (section.isEmpty()) return@forEach
                MaaCard(title = stringResource(title)) {
                    OptionEditorList(
                        options = section,
                        locked = locked,
                        onSetOption = { name, value ->
                            onIntent(SessionIntent.SetTeamOption(team.teamNumber, name, value))
                        },
                    )
                }
            }

            MaaCard(
                title = stringResource(R.string.teams_unsupported_title),
                collapsible = true,
                initiallyExpanded = false,
                summary = stringResource(R.string.teams_unsupported_summary),
            ) {
                Text(
                    text = stringResource(R.string.teams_unsupported_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingImport?.let { result ->
        val labels = state.teamOptions[result.preset.teamNumber]
            .orEmpty()
            .firstOrNull { it.name == TeamOptionScope.SINNERS }
            ?.cases
            .orEmpty()
            .associate { it.name to it.label.substringBefore(" (") }
        MaaPromptDialog(
            title = stringResource(R.string.teams_import_preview_title),
            message = importPreviewMessage(context, result, labels),
            icon = Icons.Outlined.FileOpen,
            onConfirm = {
                if (!result.emptyRoster) {
                    onIntent(SessionIntent.ApplyTeamPreset(result.preset.teamNumber, result.preset))
                    Toast.makeText(context, R.string.teams_import_applied, Toast.LENGTH_SHORT).show()
                }
                pendingImport = null
            },
            confirmText = stringResource(R.string.teams_import_confirm),
            onDismissRequest = { pendingImport = null },
            dismissText = stringResource(R.string.dialog_cancel),
        )
    }
}

/** 导入预览：阵容顺位 + 映射到本引擎的项数 + 被忽略的项数 */
private fun importPreviewMessage(
    context: Context,
    result: AalcTeamImportResult,
    labels: Map<String, String>,
): String {
    if (result.emptyRoster) return context.getString(R.string.teams_import_empty_roster)
    val deploy = result.sinners.take(TeamOptionScope.DEPLOY_LIMIT)
    val standby = result.sinners.drop(TeamOptionScope.DEPLOY_LIMIT)
    val deployText = deploy.mapIndexed { index, sinner ->
        (index + 1).toString() + ". " + (labels[sinner] ?: sinner)
    }.joinToString("  ")
    val builder = StringBuilder()
    builder.append(context.getString(R.string.teams_import_preview_deploy, deployText))
    if (standby.isNotEmpty()) {
        builder.append("\n\n")
        builder.append(
            context.getString(
                R.string.teams_import_preview_standby,
                standby.map { labels[it] ?: it }.joinToString("  "),
            ),
        )
    }
    builder.append("\n\n")
    builder.append(context.getString(R.string.teams_import_preview_mapped, result.mappedCount))
    if (result.unsupported.isNotEmpty()) {
        builder.append("\n")
        builder.append(
            context.getString(R.string.teams_import_preview_ignored, result.unsupported.size),
        )
    }
    return builder.toString()
}

private fun teamChipLabel(preset: TeamPreset): String =
    if (preset.remark.isBlank()) "#" + preset.teamNumber else "#" + preset.teamNumber + " " + preset.remark

/**
 * 罪人宫格：固定 2 行 × 6 列，与游戏编队界面一致
 *
 * 已选的罪人带顺序徽标与出战/待命标注；点选顺序就是顺位，点掉再点会排到队尾
 */
@Composable
private fun SinnerGrid(
    cases: List<OptionCaseState>,
    ordered: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    val rows = cases.chunked(TeamOptionScope.DEPLOY_LIMIT)
    Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
            ) {
                row.forEach { case ->
                    val index = ordered.indexOf(case.name)
                    SinnerTile(
                        label = case.label.substringBefore(" ("),
                        avatar = SinnerAvatars[case.name],
                        order = if (index >= 0) index + 1 else null,
                        deployed = index >= 0 && TeamOptionScope.isDeployed(index),
                        enabled = enabled,
                        onClick = { onToggle(case.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 最后一行不足 6 人时补空位，保持列宽一致
                repeat(TeamOptionScope.DEPLOY_LIMIT - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SinnerTile(
    label: String,
    avatar: Int?,
    order: Int?,
    deployed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = order != null
    val shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
    val borderColor = when {
        selected && deployed -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .maaClickable(enabled = enabled, onClick = onClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.card / 2)),
        ) {
            if (avatar != null) {
                Image(
                    painter = painterResource(avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            if (order != null) {
                Surface(
                    color = if (deployed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = order.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (deployed) R.string.teams_deploy else R.string.teams_standby,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            if (deployed) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
                            },
                        ),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.height(14.dp),
        )
    }
}
