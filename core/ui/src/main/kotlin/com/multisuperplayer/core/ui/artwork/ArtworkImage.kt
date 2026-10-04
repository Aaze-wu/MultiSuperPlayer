package com.multisuperplayer.core.ui.artwork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.multisuperplayer.core.model.ArtworkRequest

/**
 * 一个封面位置：取到了画图，取不到画类型图标。
 *
 * ## 为什么是「图标垫在下面、封面盖在上面」而不是三态分支
 *
 * 直觉写法是读 painter 的状态，成功时画图、否则画图标。但那样要订阅
 * `AsyncImagePainter.state`（Coil 3 里是个 `StateFlow`），还得处理
 * 「加载中」和「失败」两个不同的画法——而**它们本来就该长得一样**：
 * 用户不关心这张图是还没到还是永远不会有，两种情况下都该看到同一个图标。
 *
 * 所以底层永远画图标，[AsyncImage] 叠在上面。Coil 在加载中、失败、
 * model 为 null 时**什么都不画**，图标自然露出来；成功了就用
 * `ContentScale.Crop` 完全盖住它。没有状态可读，也就没有状态可读错。
 *
 * ## 图标为什么不跟着封面一起被 tint
 *
 * 这正是不能简单地给 [AsyncImage] 传 `colorFilter` 的原因：`colorFilter`
 * 会作用到封面本身，一张彩色专辑封面会变成一整块灰色剪影。
 * 分成两个图层之后，`tint` 只作用在图标上。
 *
 * ## 尺寸由调用方给
 *
 * Coil 是从布局约束反推解码尺寸的，所以这里**不能**自己有固定尺寸：
 * 调用方用 `Modifier.size(...)` 给多少，Coil 就按多少解码。
 * 一个 `Modifier` 参数同时管住了「多大」和「在哪儿」。
 */
@Composable
fun ArtworkImage(
    request: ArtworkRequest?,
    fallbackIcon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    fallbackIconSize: Dp = 24.dp,
    fallbackContainer: Color = MaterialTheme.colorScheme.surfaceVariant,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        // clip 在 background 之前：底色和子元素都会被同一个圆角裁掉。
        modifier = modifier.clip(shape).background(fallbackContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = fallbackIcon,
            // contentDescription 只挂在图标上：它在无障碍树里就是「这一行的封面」，
            // 而下面的封面图是纯装饰（同一个东西不该被念两遍）。
            contentDescription = contentDescription,
            tint = fallbackTint,
            modifier = Modifier.size(fallbackIconSize),
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
