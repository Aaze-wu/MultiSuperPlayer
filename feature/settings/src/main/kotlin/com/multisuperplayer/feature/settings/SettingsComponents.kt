package com.multisuperplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 设置各页共用的小组件。
 *
 * 单独成一个文件，而不是留在某一页里当私有函数：设置现在拆成了「入口页 + 外观 / 播放 /
 * 字幕翻译 / 关于」，这些行必须长得**一模一样**——行高、缩进、图标位、分隔只要有一处不同，
 * 用户从「播放」翻到「外观」就会觉得进错了地方。样式只有一份，改一处就全改。
 *
 * 全部是 `internal`：它们是这个 feature 的内部词汇，不是要对外暴露的 API。
 */

// --------------------------------------------------------------------- 分区

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
internal fun InfoNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --------------------------------------------------------------------- 各种行

/**
 * 「当前值 + 点开选择」的一行。
 *
 * 右侧把当前值直接写出来，而不是只画一个箭头：用户扫一眼设置页就能知道
 * 「默认画面比例是裁剪」，不用逐个点进去确认。
 */
@Composable
internal fun SettingChoiceRow(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * 「点开一页」的一行，用在设置首页的入口上。
 *
 * 和 [SettingChoiceRow] 的区别只有一个：右边**不写当前值**。
 * 入口页的右侧如果也堆一段摘要文字，一屏四行的右侧就会挤成三条长短不一的句子，
 * 反而看不出哪一行是「有值可以选」的。这里的摘要放在副标题里，右侧只留箭头，
 * 「点进去」这件事本身变成唯一的信息。
 *
 * [subtitle] 必须是**当前状态**，不是功能说明：「默认 1.0×・长按 2.0×」比
 * 「调整播放速度」有用得多——后者在四行里等于占位符。
 *
 * [enabled] 只用在「这一项在本机根本不存在」的情况（系统版本太低、系统设置里
 * 没有对应页面）。置灰的同时**不画右侧箭头**：一个灰掉的箭头仍然在说
 * 「点我一下会到别处」，而那一下什么都不会发生。
 */
@Composable
internal fun SettingActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        headlineContent = {
            Text(
                text = title,
                color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            if (enabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    ListItem(
        // 整行可点，不只是那个开关：小屏上点 32dp 的开关很容易失手。
        modifier = Modifier.fillMaxWidth().selectable(
            selected = checked,
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            // onClick = null：整行的 selectable 已经负责切换。
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
    Spacer(Modifier.height(4.dp))
}

// --------------------------------------------------------------------- 选择对话框

/**
 * 单选对话框。
 *
 * 每个选项都用 [selectable] + `Role.RadioButton`：整行是触控目标，读屏能念出
 * 「已选中/未选中」，而不会让点击区域裂成「文字」和「小圆点」两块。
 *
 * 列表套 `verticalScroll` + `heightIn`：倍速有 10 个档位，小屏横屏时
 * 全铺开会把对话框顶出屏幕外，而 `AlertDialog` 的内容区**不会**自己滚。
 *
 * [label] / [description] 是**可组合**的回调而不是 `String` 参数：文案在资源里，
 * 只能在这一层解析；传 `String` 的话调用方就得先拿 `String`，
 * 而那些值恰恰是「哪一条文案」的语义（见 `MspText` 的说明）。
 */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    description: @Composable (T) -> String?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // onClick = null：整行已经接收点击了，再挂一次会点一下触发两次。
                            RadioButton(selected = isSelected, onClick = null)
                            Text(
                                text = label(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        // 说明文字缩进到和标题同一个左边缘，看起来是标题的补充而不是
                        // 一个独立的选项行。为空时整块不画，不留一条空白。
                        description(option)?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp),
                            )
                        }
                    }
                }
            }
        },
        // 只有「取消」：选择本身即生效，再放一个「确定」等于让人确认两次。
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.msp_settings_cancel)) }
        },
    )
}
