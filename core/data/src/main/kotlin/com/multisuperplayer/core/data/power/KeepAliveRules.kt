package com.multisuperplayer.core.data.power

import java.util.Locale

/**
 * 手机厂商。只用来回答一个问题：**「后台限制」这个设置在哪儿。**
 *
 * 为什么不直接拿 `Build.MANUFACTURER` 字符串去查表：那个值大小写和写法都不统一
 * （`Xiaomi` / `HUAWEI` / `OnePlus` / `samsung` / `realme` / `HONOR`），而
 * `Build.BRAND` 又是另一个值（红米的 `MANUFACTURER` 是 `Xiaomi`、`BRAND` 是 `Redmi`）。
 * 抽成枚举之后，界面层面对的是 9 个确定的值，而不是去猜字符串。
 *
 * [OTHER] 表示**不认识**，不等于「这台机器没有后台限制」：跳页候选列表为空，
 * 界面必须据此换一种说法（见 `KeepAliveSummaries.vendorTitle`）。
 */
enum class DeviceVendor {
    XIAOMI,
    HUAWEI,
    HONOR,

    /** realme 也归这里：它是 OPPO 的系统分支，跳的是同一批页面。 */
    OPPO,
    VIVO,
    MEIZU,
    SAMSUNG,
    ONE_PLUS,
    OTHER,
}

/**
 * 「后台保活」在系统里只有两种状态。
 *
 * 这里刻意**不**加第三种「本机不支持」：`isIgnoringBatteryOptimizations` 是 API 23
 * 就有的，而本应用 `minSdk = 26` ⇒ 每台设备都答得出来。多一个永远为假的分支，
 * 只会让界面多一句永远不会显示的话（和 `PermissionState.UNSUPPORTED` 不是一回事——
 * 那一项在 Android 10 上确实不存在）。
 */
enum class KeepAliveState {
    /** 已在电池优化白名单里：Doze 不再针对它收紧网络与后台任务。 */
    UNRESTRICTED,

    /** 不在白名单里。 */
    RESTRICTED,
}

/**
 * 判定所需的全部事实，全是**从系统读来的原始值**，不含任何推断。
 *
 * 它是数据类而不是三个参数，理由和 `PermissionFacts` 一样：判定函数迟早要多看一个
 * 字段（比如将来区分「用户手动加的白名单」和「厂商预置的」），加参数会改掉所有调用点。
 */
data class KeepAliveFacts(
    val ignoringBatteryOptimizations: Boolean,
    val manufacturer: String,
    val brand: String,
)

/**
 * 保活的两条纯判定：**在不在白名单**、**是哪家厂商**。
 *
 * 抽成纯函数是因为这两条各自都踩过坑（见 [vendorOf]），而坑只在特定机型上出现——
 * 手上没有那台真机的人，只能靠单测钉住。
 */
object KeepAliveRules {

    /** 白名单是系统说了算，这里只是把布尔值说成人话。 */
    fun stateOf(facts: KeepAliveFacts): KeepAliveState =
        if (facts.ignoringBatteryOptimizations) {
            KeepAliveState.UNRESTRICTED
        } else {
            KeepAliveState.RESTRICTED
        }

    /**
     * 这一台设备是哪家。
     *
     * 两个输入都要看，因为**只看一个必错**：
     * - `MANUFACTURER` 一个字段就够认小米（`BRAND` 是 `Redmi` / `POCO` 时它仍是 `Xiaomi`）；
     * - 但 OPPO 系里有反过来的情况：`MANUFACTURER` 写 `OPPO`、`BRAND` 写 `realme` 或
     *   `OnePlus`。只看 `MANUFACTURER` 也还是 OPPO（跳对页），可一旦哪天 `MANUFACTURER`
     *   变成空串而 `BRAND` 有值，只看一个就退化成 [DeviceVendor.OTHER]。
     *
     * 所以两边都拼进去一起匹配，顺序按「越具体的越先判」：
     *
     * 1. `HONOR` 必须在 `HUAWEI` 之前。荣耀独立后的机器 `MANUFACTURER` 是 `HONOR`，
     *    但**独立前**那一批写的仍是 `HUAWEI`，两者该跳的页面不同。
     *    （反方向没有风险：`HONOR` 里不含 `HUAWEI`。）
     * 2. `ONE_PLUS` 在 `OPPO` 之前。一加现在归 OPPO，但它的自启动页还在自己的包里；
     *    把它认成 OPPO 会跳到一个一加没有的页面。
     *
     * 匹配用 `contains` 而不是相等：厂商会往这一栏里塞后缀（`Xiaomi` 有时写成
     * `XiaomiTech`）。代价是可能出现误命中，但这里的词都是专用词
     * （`xiaomi` / `vivo` / `meizu` 都不会出现在别家名字里）。
     */
    fun vendorOf(manufacturer: String, brand: String): DeviceVendor {
        val key = "$manufacturer $brand".lowercase(Locale.ROOT)
        fun matchesAny(vararg needles: String) = needles.any { key.contains(it) }
        return when {
            matchesAny("xiaomi", "redmi", "poco") -> DeviceVendor.XIAOMI
            matchesAny("honor", "hihonor") -> DeviceVendor.HONOR
            matchesAny("huawei") -> DeviceVendor.HUAWEI
            matchesAny("oneplus") -> DeviceVendor.ONE_PLUS
            matchesAny("oppo", "realme") -> DeviceVendor.OPPO
            matchesAny("vivo", "iqoo") -> DeviceVendor.VIVO
            matchesAny("meizu") -> DeviceVendor.MEIZU
            matchesAny("samsung") -> DeviceVendor.SAMSUNG
            else -> DeviceVendor.OTHER
        }
    }
}
