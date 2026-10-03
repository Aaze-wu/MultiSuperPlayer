package com.multisuperplayer.core.data.power

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeepAliveRules] 的两条判定。
 *
 * 这两条的共同点是**只在特定机型上出错**：手上没有那台真机的人，改一行 `when` 的
 * 顺序也不会看到任何异常。所以每条「不能认成什么」都单独一条用例，而不是只测
 * 「认识的能认出来」——后者对顺序错误完全不敏感（`HONOR` 放在 `HUAWEI` 后面时，
 * `HUAWEI` 那条仍然绿）。
 */
class KeepAliveRulesTest {

    // ------------------------------------------------------------------ 白名单

    @Test
    fun `在白名单里就是不受限`() {
        assertEquals(
            KeepAliveState.UNRESTRICTED,
            KeepAliveRules.stateOf(facts(ignoring = true)),
        )
    }

    @Test
    fun `不在白名单里就是受限`() {
        assertEquals(
            KeepAliveState.RESTRICTED,
            KeepAliveRules.stateOf(facts(ignoring = false)),
        )
    }

    // ------------------------------------------------------------------ 厂商

    @Test
    fun `小米系的三种写法都认成小米`() {
        // 红米和 POCO 的 MANUFACTURER 也是 Xiaomi，但它们的 BRAND 才是线索；
        // 跳的是同一个自启动管理页，所以认成一家是对的。
        listOf(
            "Xiaomi" to "Xiaomi",
            "Xiaomi" to "Redmi",
            "Xiaomi" to "POCO",
            "XiaomiTech" to "Xiaomi", // 厂商会往这一栏塞后缀，所以用 contains 而不是相等
        ).forEach { (manufacturer, brand) ->
            assertEquals(
                "$manufacturer / $brand 应当认成小米",
                DeviceVendor.XIAOMI,
                KeepAliveRules.vendorOf(manufacturer, brand),
            )
        }
    }

    @Test
    fun `华为认成华为`() {
        assertEquals(DeviceVendor.HUAWEI, KeepAliveRules.vendorOf("HUAWEI", "HUAWEI"))
    }

    @Test
    fun `荣耀不能认成华为`() {
        // 这一条是顺序的守卫：`HONOR` 的分支写在 `HUAWEI` 之前。放反了不影响
        // 华为那一条（HONOR 里不含 HUAWEI），只会让荣耀用户跳到一个不存在的页面，
        // 然后落到「应用信息」兜底——**看起来像那台机器没有这个页面**，很难怀疑到顺序。
        assertEquals(DeviceVendor.HONOR, KeepAliveRules.vendorOf("HONOR", "HONOR"))
        assertEquals(DeviceVendor.HONOR, KeepAliveRules.vendorOf("HONOR", "hihonor"))
    }

    @Test
    fun `一加不能认成 OPPO`() {
        // 同上的第二条顺序守卫：一加现在归 OPPO，但自启动页还在自己的包里。
        assertEquals(DeviceVendor.ONE_PLUS, KeepAliveRules.vendorOf("OnePlus", "OnePlus"))
        assertEquals(DeviceVendor.ONE_PLUS, KeepAliveRules.vendorOf("OnePlus", "oneplus"))
    }

    @Test
    fun `realme 归到 OPPO`() {
        // realme 是 OPPO 的系统分支，跳的是同一批页面。
        assertEquals(DeviceVendor.OPPO, KeepAliveRules.vendorOf("realme", "realme"))
        assertEquals(DeviceVendor.OPPO, KeepAliveRules.vendorOf("OPPO", "OPPO"))
        assertEquals(DeviceVendor.OPPO, KeepAliveRules.vendorOf("OPPO", "realme"))
    }

    @Test
    fun `vivo 和 iQOO 都认成 vivo`() {
        assertEquals(DeviceVendor.VIVO, KeepAliveRules.vendorOf("vivo", "vivo"))
        assertEquals(DeviceVendor.VIVO, KeepAliveRules.vendorOf("vivo", "iQOO"))
    }

    @Test
    fun `魅族和三星`() {
        assertEquals(DeviceVendor.MEIZU, KeepAliveRules.vendorOf("Meizu", "Meizu"))
        assertEquals(DeviceVendor.SAMSUNG, KeepAliveRules.vendorOf("samsung", "samsung"))
    }

    @Test
    fun `大小写不影响判定`() {
        // 这一栏在不同 ROM 上写法不统一（`Xiaomi` / `XIAOMI` / `xiaomi` 都见过），
        // 所以判定前统一小写。
        assertEquals(DeviceVendor.XIAOMI, KeepAliveRules.vendorOf("XIAOMI", "XIAOMI"))
        assertEquals(DeviceVendor.XIAOMI, KeepAliveRules.vendorOf("xiaomi", "xiaomi"))
    }

    @Test
    fun `只写 brand 也能认出来`() {
        // 两个字段拼在一起匹配，所以任一有值就够——不依赖 MANUFACTURER 一定非空。
        assertEquals(DeviceVendor.XIAOMI, KeepAliveRules.vendorOf("", "Redmi"))
        assertEquals(DeviceVendor.OPPO, KeepAliveRules.vendorOf("", "realme"))
    }

    @Test
    fun `认不出来是 OTHER 而不是某一家的默认值`() {
        // `OTHER` 必须存在且**不能**是「随便挑一家」：候选列表为空时界面会说
        // 「去应用信息页」，而认错厂商会跳到一个不存在的页面——用户看到的是
        // 「按钮没反应」，查起来离真因很远。
        listOf(
            "Google" to "Pixel",
            "motorola" to "moto",
            "" to "",
        ).forEach { (manufacturer, brand) ->
            assertEquals(
                "$manufacturer / $brand 不该被认成任何已知厂商",
                DeviceVendor.OTHER,
                KeepAliveRules.vendorOf(manufacturer, brand),
            )
        }
    }

    private fun facts(ignoring: Boolean) = KeepAliveFacts(
        ignoringBatteryOptimizations = ignoring,
        manufacturer = "",
        brand = "",
    )
}
