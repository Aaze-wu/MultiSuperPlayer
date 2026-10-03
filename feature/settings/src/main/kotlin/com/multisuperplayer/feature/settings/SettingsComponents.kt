package com.multisuperplayer.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
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

// --------------------------------------------------------------------- 帮助

/**
 * 行标题右边的帮助问号。点开是一个说明对话框。
 *
 * ## 为什么把这个当默认做法，而不是把长句写在副标题里
 *
 * 副标题的位置只有一行（超了就换行、就把行高拉高），于是「说明这个开关是干什么的」
 * 和「解释为什么默认关着」会挤在一起，最后两个都说不完。分开之后：副标题只说
 * **现在是什么状态**（扫一眼就能读），为什么、什么时候需要改放在这里。
 *
 * ## 两个容易写错的地方
 *
 * 1. **点击不会冒泡给整行**。这三个行组件里，大行自己挂着 `clickable`/`selectable`，
 *    而这里是它的子节点，子节点会先消费点击事件，所以点问号不会顺手把开关翻了。
 *    （反过来——把问号放在行的**外面**——就会变成另一个故事了。）
 * 2. **`title` 要的是那一行的名字，不是对话框的抬头**。它会同时作为对话框的标题
 *    和读屏时那句「XXX：查看说明」里的 XXX，所以两处用同一个字符串，不会对不上。
 */
@Composable
internal fun SettingHelpIcon(title: String, text: String) {
    var show by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .clickable { show = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = stringResource(R.string.msp_settings_help_icon_desc, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                // 说明可能很长（有的能占满一屏），所以这里必须能滚：AlertDialog
                // 的内容区不会自己滚，长文会把两个按钮顶出屏幕。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(R.string.msp_settings_got_it))
                }
            },
        )
    }
}

/**
 * 行标题 + 可选的帮助问号。三个行组件共用，省得三处各写一遍 [Row] 的对齐方式。
 *
 * [titleColor] 只由 [SettingActionRow] 用到（禁用时整行变灰）。
 */
@Composable
private fun TitleWithHelp(
    title: String,
    help: String?,
    titleColor: Color = Color.Unspecified,
) {
    if (help == null) {
        Text(text = title, color = titleColor)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, color = titleColor)
        SettingHelpIcon(title = title, text = help)
    }
}

// --------------------------------------------------------------------- 各种行

/**
 * 「当前值 + 点开选择」的一行。
 *
 * [value] 右侧把当前值直接写出来，而不是只画一个箭头：用户扫一眼设置页就能知道
 * 「默认画面比例是裁剪」，不用逐个点进去确认。
 *
 * [help] 非空时在标题右边挂一个问号（见 [SettingHelpIcon]）。
 */
@Composable
internal fun SettingChoiceRow(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit,
    help: String? = null,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { TitleWithHelp(title = title, help = help) },
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
    help: String? = null,
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
            TitleWithHelp(
                title = title,
                help = help,
                titleColor = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
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
    help: String? = null,
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
        headlineContent = { TitleWithHelp(title = title, help = help) },
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

// ------------------------------------------------------------- 输入框

/**
 * 跟着存储走、但用户一开始输入就交给他的输入框。
 *
 * `draft == null` 表示「用户还没动过这一栏」，此时显示存储里的值：
 * 设置是异步读盘的，第一帧拿到的往往是空串，所以初值必须能**晚到**；
 * 而一旦绑死到存储上，每敲一个字符都会写盘、回流、把光标和刚敲的字符冲掉
 * （DataStore 的写是异步的，回流顺序没有保证）。
 *
 * [key] 变了就丢掉草稿：那时存储里的值本来就是另一份（换服务商、换模型）。
 * 只有一份存储值时传常量，不传 `null`——见 `AsrSettingsScreen.SOURCE_FIELD_KEY`。
 *
 * [enabled] 为 false 时连输入都不收：用于「有一个长任务正在跑，改这一栏会让用户
 * 以为改动已经作用到那次运行上了」的场合（下载中的下载源、下载中的模型列表）。
 * 这种时候正确的动作是停止，所以整栏禁用比允许编辑、再悄悄忽略更清楚。
 *
 * [help] 非空时在下方提示行末尾挂一个问号。输入框没有「标题行」可用
 * （`label` 是浮在框里的，点它等于聚焦输入框），所以这里把问号放到
 * [supportingText] 那一行上——那一行本来就在解释这个框，位置最对。
 */
@Composable
internal fun DraftTextField(
    key: Any?,
    stored: String,
    onCommit: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    help: String? = null,
) {
    var draft by remember(key) { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = draft ?: stored,
        onValueChange = {
            draft = it
            onCommit(it)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = if (supportingText == null && help == null) {
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (supportingText != null) {
                        // 没有 weight：提示文字该有多长就多长，问号跟着它走，
                        // 而不是被推到框的另一头。
                        Text(supportingText)
                    }
                    if (help != null) SettingHelpIcon(title = label, text = help)
                }
            }
        },
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        trailingIcon = trailing,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// --------------------------------------------------------------- 密钥

/**
 * 密钥的三态。翻译页和识别页共用同一份实现——两处各写一遍的话，一定会有一处
 * 先改：而这类控件的错误后果不是「显示难看」，是**用户以为保存了、其实没有**。
 *
 * 「留空」和「删除」必须分开：一个输入框的空值如果等于「把密钥删掉」，
 * 那么用户只是点进去、什么都没输、退回上一页（或其他任何触发保存的路径），
 * 密钥就没了。所以这里的规则是：
 *
 * - 输入框永远是空的，**不会**把已存的密钥读回来显示（读回来就等于把它明文摊在屏幕上）；
 * - 点「保存」且输入非空 ⇒ 覆盖；
 * - 点「删除」且**只有**这个按钮 ⇒ 删除（带一次确认）。
 *
 * @param resetKey 草稿归属谁。换一家服务商就是换了一份凭证，草稿与刚才那条提示
 *   必须跟着作废——否则框里还留着上一家的密钥（而它会被发给新那家）。这个 key
 *   是「这一栏属于哪一份记录」的标识，不是「值空不空」的判断：后者在第一次载入时
 *   会把用户的输入当成脏数据丢掉。
 * @param clearConfirm 删除确认里的那句话。**必须按用途分别给**：写着「就不能翻译了」
 *   的那段话被搬到识别页上，用户的结论会是「删了不影响识别」。
 */
@Composable
internal fun SecretKeyBlock(
    resetKey: Any?,
    ownerLabel: String,
    stored: Boolean,
    required: Boolean,
    clearConfirm: String,
    onSave: (String, (Boolean) -> Unit) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember(resetKey) { mutableStateOf("") }
    // 提示文案的「说哪一句」就发生在这一层（不经过 `MspText`），所以这里存的是资源 id：
    // 存 `MspText` 的话反而要再引入一个只有在 Compose 里才存在的 `string()` 才能显示。
    var message by remember(resetKey) { mutableStateOf<Int?>(null) }
    var confirmClear by remember(resetKey) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (stored) {
                stringResource(R.string.msp_settings_key_stored, ownerLabel)
            } else {
                stringResource(R.string.msp_settings_key_missing)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!required) {
            Text(
                text = stringResource(R.string.msp_settings_key_not_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                message = null
            },
            label = { Text(stringResource(R.string.msp_settings_api_key_label)) },
            placeholder = { Text(stringResource(R.string.msp_settings_api_key_placeholder)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // 空输入不给点：仓库对空值本来就是「不动」，给一个按下去没反应的按钮
                // 只会让人以为保存成功了。
                onClick = {
                    onSave(input) { saved ->
                        message = if (saved) {
                            R.string.msp_settings_key_saved
                        } else {
                            R.string.msp_settings_key_not_saved
                        }
                        if (saved) input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.msp_settings_save))
            }
            if (stored) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.msp_settings_delete_key))
                }
            }
        }
        message?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.msp_settings_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.msp_settings_delete_key_title)) },
            text = { Text(clearConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                    message = R.string.msp_settings_key_deleted
                }) { Text(stringResource(R.string.msp_settings_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.msp_settings_cancel))
                }
            },
        )
    }
}
