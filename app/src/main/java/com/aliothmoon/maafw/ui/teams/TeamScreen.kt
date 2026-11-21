package com.aliothmoon.maafw.ui.teams

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.domain.OptionCaseState
import com.aliothmoon.maafw.domain.OptionEditorState
import com.aliothmoon.maafw.domain.OptionValue
import com.aliothmoon.maafw.domain.TeamOptionScope
import com.aliothmoon.maafw.domain.TeamPreset
import com.aliothmoon.maafw.session.SessionIntent
import com.aliothmoon.maafw.session.SessionUiState
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.ui.components.ITextField
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaEmptyState
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

/**
 * 编队页：对齐 AALC 的「编队卡 + 选项卡」结构
 *
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
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.nav_teams),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        val presets = state.teamPresets
        if (presets.isEmpty()) {
            MaaEmptyState(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.teams_empty_title),
                hint = stringResource(R.string.teams_empty_hint),
                modifier = Modifier.padding(top = MaaDesignTokens.Spacing.xl),
            )
            return@Column
        }

        var editingTeam by rememberSaveable { mutableStateOf(presets.first().teamNumber) }
        val team = presets.firstOrNull { it.teamNumber == editingTeam } ?: presets.first()
        val options = state.teamOptions[team.teamNumber].orEmpty()
        val locked = state.configurationLocked
        val base = remember(options) { options.filter { it.name in BASE_NAMES } }
        val sinners = remember(options) { options.firstOrNull { it.name == TeamOptionScope.SINNERS } }
        val order = remember(options) { options.filter { it.name.startsWith(TeamOptionScope.ORDER_SLOT_PREFIX) } }

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
                    selected = editingTeam,
                    onSelect = { editingTeam = it },
                    enabled = true,
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
                ITextField(
                    value = team.remark,
                    onValueChange = { onIntent(SessionIntent.SetTeamRemark(team.teamNumber, it)) },
                    label = stringResource(R.string.teams_remark_label),
                    placeholder = stringResource(R.string.teams_remark_placeholder),
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (base.isNotEmpty()) {
                OptionEditorList(
                    options = base,
                    locked = locked,
                    onSetOption = { name, value ->
                        onIntent(SessionIntent.SetTeamOption(team.teamNumber, name, value))
                    },
                    carded = true,
                )
            }

            sinners?.let { option ->
                MaaCard(title = option.label) {
                    Text(
                        text = stringResource(R.string.teams_sinners_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SinnerGrid(
                        option = option,
                        locked = locked,
                        onToggle = { case ->
                            val current = option.activeCases.map { it.name }.toSet()
                            val next = if (case.name in current) current - case.name else current + case.name
                            onIntent(
                                SessionIntent.SetTeamOption(
                                    teamNumber = team.teamNumber,
                                    optionName = option.name,
                                    value = OptionValue.MultipleCases(next.toList()),
                                ),
                            )
                        },
                    )
                }
            }

            if (order.isNotEmpty()) {
                MaaCard(
                    title = stringResource(R.string.teams_order_title),
                    collapsible = true,
                    initiallyExpanded = false,
                    summary = stringResource(R.string.teams_order_summary),
                ) {
                    Text(
                        text = stringResource(R.string.teams_order_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OptionEditorList(
                        options = order,
                        locked = locked,
                        onSetOption = { name, value ->
                            onIntent(SessionIntent.SetTeamOption(team.teamNumber, name, value))
                        },
                    )
                }
            }
        }
    }
}

private val BASE_NAMES = setOf(
    "team_system",
    "shop_strategy",
    "reward_cards",
    "choose_opening_bonus",
    "opening_items",
    "avoid_skill_3",
    "defense_first_round",
)

private fun teamChipLabel(preset: TeamPreset): String =
    if (preset.remark.isBlank()) "#${preset.teamNumber}" else "#${preset.teamNumber} ${preset.remark}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SinnerGrid(
    option: OptionEditorState,
    locked: Boolean,
    onToggle: (OptionCaseState) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        option.cases.forEach { case ->
            SinnerTile(case = case, enabled = !locked, onClick = { onToggle(case) })
        }
    }
}

@Composable
private fun SinnerTile(case: OptionCaseState, enabled: Boolean, onClick: () -> Unit) {
    val selected = case.active
    val shape = RoundedCornerShape(MaaDesignTokens.CornerRadius.card)
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .width(SinnerTileWidth)
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
            .padding(vertical = MaaDesignTokens.Spacing.xs, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val avatar = SinnerAvatars[case.name]
        if (avatar != null) {
            Image(
                painter = painterResource(avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(SinnerAvatarSize)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(SinnerAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Text(
            text = case.label.substringBefore(" ("),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private val SinnerTileWidth = 72.dp

/** 头像本体尺寸：比 IconSize 档位大，纯展示用，不参与排版计算 */ 
private val SinnerAvatarSize = 44.dp
