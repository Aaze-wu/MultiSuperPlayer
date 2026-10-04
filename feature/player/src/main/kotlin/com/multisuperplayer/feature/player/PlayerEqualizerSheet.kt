package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.player.EqualizerBandGain
import com.multisuperplayer.core.player.EqualizerCurve
import com.multisuperplayer.core.player.EqualizerPreset
import com.multisuperplayer.core.player.EqualizerStatus

/**
 * 均衡器面板。
 *
 * ## 为什么这里用连续滑块（本项目第一处）
 *
 * 别处（倍速、睡眠定时、窗口比例）一律用 `FilterChip` 分档，理由是「滑块会让人
 * 以为能取任意值，而实际被吸附到最近的档位上」。均衡器不吃那条理由：这里要调的
 * 就是**连续量**（增益），而且用户是照着耳朵调的——拖动过程中每一帧的声音都要
 * 跟着变，分档反而会让「差一点点」永远调不到。
 *
 * 代价是「拖了但没变」需要靠别的方式避免：拖动期间的每一帧都会真的下给内核
 * （见 `PlayerViewModel.previewEqualizerBand`），所以手感是实的。
 *
 * ## 为什么滑块固定是五根
 *
 * 画的是 [EqualizerCurve.STANDARD_FREQS_HZ] 那五段，不是「设备上报的段数」。
 * 设备段数只有在**开始播放之后**才知道（会话号存在之前读不到），跟着它走的话
 * 面板会在用户眼皮底下从 5 根变成 10 根；而曲线本身是按频率存的（不是按下标），
 * 所以贴到设备上的差异由 [EqualizerCurve.toDeviceGains] 抹平。面板底部那句说明
 * 就是为了回答「为什么只有五根」。
 *
 * ## 三种「不能用」的原因必须分开说
 *
 * - **开关关着**：控件变灰。原因就在旁边，不需要解释。
 * - **还没开始播放**（[EqualizerStatus.Idle]）：控件**照样能调**，调整会存下来、
 *   开始播放后生效。这是可以提前配置的状态，不是错误。
 * - **设备不支持**（[EqualizerStatus.Unsupported]）：控件变灰并明说，否则用户会
 *   一直拖滑块等一个永远不会出现的声音变化。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerEqualizerSheet(
    enabled: Boolean,
    curve: List<EqualizerBandGain>,
    status: EqualizerStatus,
    onToggle: (Boolean) -> Unit,
    onCurveChange: (List<EqualizerBandGain>) -> Unit,
    onPreviewBand: (Int, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            val unsupported = status is EqualizerStatus.Unsupported
            val controlsEnabled = enabled && !unsupported
            val currentPreset = EqualizerPreset.matching(curve)
            // 设备只认 ±6dB 的时候滑块也只能拖到 ±6：区间比设备宽的话，用户看到
            // 数字在变而声音不变（见 `EqualizerCurve.gainRange` 的注释）。
            val range = (status as? EqualizerStatus.Ready)
                ?.let { EqualizerCurve.gainRange(it.capability) }
                ?: (EqualizerCurve.UI_MIN_GAIN_DB..EqualizerCurve.UI_MAX_GAIN_DB)

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.msp_player_equalizer),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val enableLabel = stringResource(R.string.msp_player_eq_enable)
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    // 开关本身没有可见的文字（标题就在左边），但读屏时要能读出
                    // 这个开关管的是什么——不然它只是一个「开/关」。
                    modifier = Modifier.semantics { contentDescription = enableLabel },
                )
            }

            // 状态说明。三种状态各说各的：把「还没开始播放」说成「不支持」
            // 会让人以为这台手机就是不行。
            val note = when {
                unsupported -> stringResource(R.string.msp_player_eq_unsupported_note)
                status is EqualizerStatus.Idle && enabled -> stringResource(R.string.msp_player_eq_idle_note)
                else -> null
            }
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            FlowRow(
                // 换行而不是横向滚动：八个预设全都能看见，没有藏在屏幕外面的选项。
                // （倍速那边用横向滚动是因为它有十几档，摞起来会把面板顶满。）
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EqualizerPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = preset == currentPreset,
                        enabled = controlsEnabled,
                        // 点预设 = 把那条曲线整个存下来（不是「改一个系数」）：
                        // 预设是整条曲线的名字，只改一段会让它变成一条既不是预设
                        // 也不是用户原来那条的第三种东西。
                        onClick = { onCurveChange(preset.curve) },
                        label = { Text(equalizerPresetLabel(preset)) },
                    )
                }
            }

            // 自己拖出来的曲线匹配不上任何预设，此时整排芯片都不选中。不解释一下的话
            // 那看起来像「坏了」；而「自定义」本来就不是一个可以点选的档位
            // （它没有对应的曲线），所以只写一行字，不加芯片。
            if (controlsEnabled && currentPreset == null) {
                Text(
                    text = stringResource(R.string.msp_player_eq_preset_custom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            curve.forEachIndexed { index, band ->
                EqualizerBandRow(
                    band = band,
                    range = range,
                    enabled = controlsEnabled,
                    onPreview = { gainDb -> onPreviewBand(index, gainDb) },
                    onCommit = { gainDb ->
                        onCurveChange(
                            curve.mapIndexed { position, item ->
                                if (position == index) item.copy(gainDb = gainDb) else item
                            },
                        )
                    },
                )
            }

            Text(
                text = stringResource(R.string.msp_player_eq_mapping_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 一根频段滑块：左边频率、右边增益。
 *
 * ## 为什么拖动中的值要存在这里
 *
 * 拖动期间我们只把值下给内核、**不写盘**（见 `PlayerViewModel.previewEqualizerBand`：
 * 一次拖动会产生几十个中间值）。所以传进来的 `band.gainDb` 在整个拖动过程中都不会
 * 变，滑块要是直接绑它，手一松开就会跳回拖动前的那个值——而磁盘上随后存下的
 * 又是新值，屏幕和设置就对不上了。
 *
 * `remember` 的 key 是 `band.gainDb`：选预设、或者松手落盘之后参数变了，这份草稿
 * 就作废，滑块跟着新的曲线走。
 */
@Composable
private fun EqualizerBandRow(
    band: EqualizerBandGain,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(band.gainDb) { mutableStateOf<Float?>(null) }
    // 设备范围可能比存下来的值窄（换过设备、或者旧版本存过 ±12），夹一下：
    // 超出 `valueRange` 的值在滑块上是画不出来的。
    val shown = (draft ?: band.gainDb).coerceIn(range.start, range.endInclusive)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val frequency = EqualizerCurve.formatFreq(band.centerFreqHz)
        Text(
            text = frequency,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(FREQUENCY_LABEL_WIDTH),
        )
        Slider(
            value = shown,
            onValueChange = { value ->
                draft = value
                onPreview(value)
            },
            // 松手才写盘：拖动的每一帧都写一次 DataStore 是拿数据库当记事本用，
            // 而且真正要想记住的只有最后那一个值。
            onValueChangeFinished = { draft?.let(onCommit) },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = frequency },
        )
        Text(
            text = EqualizerCurve.formatGain(shown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(GAIN_LABEL_WIDTH),
        )
    }
}

/**
 * 控制条芯片上的字。
 *
 * 「关着」写功能名（「均衡器」），「开着」写现在的档位（「低音增强」/「自定义」）——
 * 和睡眠定时芯片同一套读法：用户下次看这一行时要回答的问题是「现在声音是什么样」，
 * 而不是「有没有这个功能」。
 */
@Composable
internal fun equalizerChipLabel(enabled: Boolean, curve: List<EqualizerBandGain>): String =
    if (!enabled) {
        stringResource(R.string.msp_player_equalizer)
    } else {
        EqualizerPreset.matching(curve)?.let { equalizerPresetLabel(it) }
            ?: stringResource(R.string.msp_player_eq_preset_custom)
    }

/** 预设名。名字归我们自己的三种语言管（不用系统预设的另一个好处）。 */
@Composable
internal fun equalizerPresetLabel(preset: EqualizerPreset): String = stringResource(
    when (preset) {
        EqualizerPreset.FLAT -> R.string.msp_player_eq_preset_flat
        EqualizerPreset.BASS -> R.string.msp_player_eq_preset_bass
        EqualizerPreset.TREBLE -> R.string.msp_player_eq_preset_treble
        EqualizerPreset.VOCAL -> R.string.msp_player_eq_preset_vocal
        EqualizerPreset.ROCK -> R.string.msp_player_eq_preset_rock
        EqualizerPreset.POP -> R.string.msp_player_eq_preset_pop
        EqualizerPreset.JAZZ -> R.string.msp_player_eq_preset_jazz
        EqualizerPreset.CLASSICAL -> R.string.msp_player_eq_preset_classical
    },
)

/**
 * 频率标签的宽度。
 *
 * 固定宽度是为了让五根滑块左右对齐：宽度跟着文字走的话，「14 kHz」那一行的滑块
 * 比上面几根窄一截，看起来像布局坏了。`3.6 kHz` 是这个宽度里最长的那个。
 */
private val FREQUENCY_LABEL_WIDTH = 64.dp

/** 增益标签的宽度。同上，数字右对齐之后五行的「dB」才会落在同一列。 */
private val GAIN_LABEL_WIDTH = 60.dp
