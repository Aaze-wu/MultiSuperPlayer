package com.multisuperplayer.feature.settings

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.multisuperplayer.core.data.permissions.AppPermissions
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * 权限状态（四个条目的当前情况）与「该申请什么」——**一层转发**。
 *
 * ## 状态与待办都不在这个类里
 *
 * 它们住在 [AppPermissions]（Koin 单例）里，这里只是把它取出来给 Compose 用。
 * 原因不是「少写几行」，而是**这个类会有不止一个实例**：应用根（`MspApp`）拿到的
 * 那个挂在 Activity 上，权限页（`NavHost` 里的 `composable`）拿到的是挂在
 * `NavBackStackEntry` 上的另一个实例——`koinViewModel()` 按
 * `LocalViewModelStoreOwner` 取，两个地方的 owner 本来就不一样。
 *
 * 这个区别造成过一个很难查的 bug：权限页点了「申请」，请求写进了**它自己**那份待办，
 * 而全应用唯一会去调系统框的 launcher 挂在根上、观察的是**另一份**待办——于是按钮
 * 按下去什么都不弹，可「问过系统了」那笔账已经记下了（记账与弹框是两件事，见
 * `AppPermissions.markAsked` 的注释）。状态收进单例之后，谁发起、谁显示、谁弹框
 * 看到的都是同一份。
 *
 * ## 首次启动那次申请也走这里
 *
 * 需求③ 的「启动后延迟一小段再申请」挂在 `MspApp` 上，用的就是这个转发
 * （[requestAtStartup]）。它和权限页共用【问过没有】的记忆，所以「自动申请过一次
 * 之后，权限页显示的就是『已拒绝过，可以再申请』」而不是「还没申请过」。
 *
 * ## 为什么状态只能是「问一次、存一份」
 *
 * 权限的真相在系统里，不在某个可以被观察的数据源里：`checkSelfPermission` 就是一次
 * 读，系统不会在用户去设置里改完之后通知我们。所以由界面在**回到前台**
 * （`PermissionsRoute` 的 `ON_RESUME`）和**系统框返回**（`MspApp` 上那个唯一的
 * launcher 回调里调 [onRequestLaunched]）这两个时刻各问一次。
 */
class PermissionsViewModel(
    private val permissions: AppPermissions,
) : ViewModel() {

    /** 四行的当前状态。 */
    val state: StateFlow<PermissionSnapshot> = permissions.snapshotState

    /**
     * 待弹的系统授权框（`null` = 没有待办）。
     *
     * 权限必须由**界面**去申请（只有 `Activity` 手里有 `ActivityResultRegistry`），
     * 而「什么时候申请、申请哪几条」是数据层的判断。于是有这么一份一次性请求：
     * 界面把它交给 launcher，然后立刻用 [onRequestLaunched] 收回去。
     */
    val pending: StateFlow<List<String>?> = permissions.pending

    /** 重新问一次系统（界面的 `ON_RESUME` 与系统框返回各一次，见类注释）。 */
    fun refresh(activity: Activity?) {
        permissions.refresh(activity)
    }

    /** 用户点了某一行的「申请」。 */
    fun request(kind: PermissionKind) {
        permissions.request(kind)
    }

    /** 应用启动后那一次申请：整个安装只做一次。 */
    fun requestAtStartup() {
        permissions.requestAtStartup()
    }

    /**
     * 权限对话框（或者一次没有框的静默答复）回来了。
     *
     * 收走待办并顺手重算状态——理由见 `AppPermissions.onRequestLaunched`。
     */
    fun onRequestLaunched(activity: Activity?) {
        permissions.onRequestLaunched(activity)
    }

    /** 这一行「去设置」该跳哪个系统页面。 */
    fun settingsIntent(kind: PermissionKind): Intent = permissions.settingsIntent(kind)
}

