package com.multisuperplayer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.model.Playlist

/**
 * 「加入播放列表」的选择对话框。
 *
 * 没有播放列表时直接把输入框摆出来（[creating] 初始为 false，但**新建**按钮始终在），
 * 而不是先让用户读一句「还没有播放列表」再点一次：多选之后的操作路径已经够长了。
 *
 * 对话框自己不关：选完/新建完由调用方关（它才知道操作有没有成功）。
 */
@Composable
internal fun PlaylistPickerDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (playlistId: String) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msp_library_add_to_playlist)) },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.msp_library_playlist_name_hint)) },
                )
            } else {
                Column(modifier = Modifier.heightIn(max = 360.dp)) {
                    if (playlists.isEmpty()) {
                        Text(stringResource(R.string.msp_library_playlist_empty))
                    } else {
                        LazyColumn {
                            items(items = playlists, key = { it.id }) { playlist ->
                                ListItem(
                                    modifier = Modifier.clickable { onPick(playlist.id) },
                                    headlineContent = { Text(playlist.name) },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.msp_library_item_count,
                                                playlist.size,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { onCreate(name.trim()) },
                    // 空名字建出来的列表在列表页里就是一行空白，用户再也没有办法找回它。
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.msp_library_confirm))
                }
            } else {
                TextButton(onClick = { creating = true }) {
                    Text(stringResource(R.string.msp_library_playlist_new))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.msp_library_cancel))
            }
        },
    )
}
