package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.data.export.ImportedPlaylist
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.R as ModelR
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「文件里读出来的东西 → 怎么写下去」这一层（[PlaylistImporter] + 它产出的文案）。
 *
 * ## 为什么这一层要单独测
 *
 * 解析层（`export/PlaybackImportTest`）保证的是「文件被读对了」。这一层保证的是
 * **「写下去之后库里是什么样」**，而它的错全都发生在「用户已经点了确定」之后：
 *
 * 1. **重名时先写后问**。用户的文件里有 3 个列表和现有的重名，如果第一遍就把不重的
 *    先写进去，那么「中途取消」留下的是半成品，而且第一遍刚建出来的列表在第二遍里
 *    **又变成重名**，用户会被问一遍已经答过的问题。所以这里钉的是
 *    「还有没决定的就一个字节都不写」。
 * 2. **新建的名字不换**。用户选「新建」却得到两个一模一样的「旅行」——列表页上分不清
 *    哪个是刚导入的、该删哪个。
 * 3. **数字对不上账**。「说要合并 2 条、实际进去 1 条」如果不算成重复，
 *    用户看着结果提示和列表里的条数会以为丢数据了。
 *
 * ## 为什么用假 sink 而不是真 DataStore
 *
 * [PlaylistImportSink] 就是为这件事存在的接口：`core:data` 的单测没有 Robolectric
 * （见 `build.gradle.kts` 的 `unitTests.isReturnDefaultValues`），
 * `DataStore`/`Context` 在这里拿不到。假 sink 顺带把「写了几次」变成可断言的
 * ——「要么全都进去、要么什么都没发生」这条性质只能这样钉住。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」。
 */
class PlaylistImporterTest {

    // ============================================================ 计划：不写

    @Test
    fun `没有重名时两个列表直接新建`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(list("旅行", "m1"), list("工作", "m2", "m3")),
            PlaylistImportOptions(),
        )

        assertTrue("没有重名就没有要问的", plan.isReady)
        assertEquals(
            "名字原样、条目各归各的",
            listOf(
                PlaylistPlan.Create("旅行", listOf(item("m1"))),
                PlaylistPlan.Create("工作", listOf(item("m2"), item("m3"))),
            ),
            plan.plans,
        )
        assertEquals("plan 是纯计算：它只能读名字表，不能落盘", 0, sink.applyCalls)
    }

    @Test
    fun `重名时先问，不写任何东西`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1"))

        val plan = PlaylistImporter(sink).plan(listOf(list("旅行", "m1")), PlaylistImportOptions())

        assertFalse(plan.isReady)
        assertEquals(listOf("旅行"), plan.pendingNames)
        assertTrue("问清楚之前一个计划都不该有", plan.plans.isEmpty())
        assertEquals("一次调用只读一次名字表", 1, sink.nameIndexReads)
        assertEquals(0, sink.applyCalls)
    }

    @Test
    fun `名字首尾的空白在比对重名之前就被去掉`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1"))

        val plan = PlaylistImporter(sink).plan(listOf(list("  旅行  ", "m1")), PlaylistImportOptions())

        assertEquals(
            "「旅行 」和「旅行」在界面上长得一模一样，当成不重名会让用户拿到两个看不出区别的列表",
            listOf("旅行"),
            plan.pendingNames,
        )
    }

    // ============================================================ 计划：决定

    @Test
    fun `选了合并就并进已有的那一个列表`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-7"))
        val options = PlaylistImportOptions(decisions = mapOf("旅行" to PlaylistImportMergeChoice.MERGE))

        val plan = PlaylistImporter(sink).plan(listOf(list("旅行", "m1", "m2")), options)

        assertEquals(
            "合并要带上**已有那一个**的 id；带错了会并进另一个列表，而界面上一句错都不会报",
            listOf(PlaylistPlan.Append("旅行", listOf(item("m1"), item("m2")), "pl-7")),
            plan.plans,
        )
    }

    @Test
    fun `选了新建就换一个名字再建一份`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1"))
        val options = PlaylistImportOptions(decisions = mapOf("旅行" to PlaylistImportMergeChoice.NEW))

        val plan = PlaylistImporter(sink).plan(listOf(list("旅行", "m1")), options)

        assertEquals(
            "不换名字的话用户会看到两个「旅行」，分不清哪个是刚导入的",
            listOf(PlaylistPlan.Create("旅行 (2)", listOf(item("m1")))),
            plan.plans,
        )
    }

    @Test
    fun `后缀会一直加到第一个没被占用的名字`() = runTest {
        val sink = FakeSink(
            existing = mapOf("旅行" to "pl-1", "旅行 (2)" to "pl-2", "旅行 (3)" to "pl-3"),
        )
        val options = PlaylistImportOptions(decisions = mapOf("旅行" to PlaylistImportMergeChoice.NEW))

        val plan = PlaylistImporter(sink).plan(listOf(list("旅行", "m1")), options)

        assertEquals(
            listOf("旅行 (4)"),
            plan.plans.filterIsInstance<PlaylistPlan.Create>().map { it.name },
        )
    }

    @Test
    fun `本批里刚起的名字也会被后来的列表避开`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1"))
        val options = PlaylistImportOptions(decisions = mapOf("旅行" to PlaylistImportMergeChoice.NEW))

        // 文件里同时还有一个本来就叫「旅行 (2)」的列表：它和**库里**的名字不撞，
        // 但和上一步刚计划出来的那个名字撞。
        val plan = PlaylistImporter(sink).plan(
            listOf(list("旅行", "m1"), list("旅行 (2)", "m2")),
            options,
        )

        assertEquals(
            "只拿库里的名字当占用表是不够的：同一批里前一个「新建」刚占掉「旅行 (2)」，" +
                "后一个必须再往后让一位",
            listOf("旅行 (2)", "旅行 (2) (2)"),
            plan.plans.filterIsInstance<PlaylistPlan.Create>().map { it.name },
        )
    }

    @Test
    fun `勾了全部套用就不再问剩下的重名`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1", "工作" to "pl-2"))
        val options = PlaylistImportOptions(
            decisions = mapOf("旅行" to PlaylistImportMergeChoice.NEW),
            applyToAll = true,
            applyToAllAs = PlaylistImportMergeChoice.MERGE,
        )

        val plan = PlaylistImporter(sink).plan(
            listOf(list("旅行", "m1"), list("工作", "m2")),
            options,
        )

        assertTrue("「全部套用」的意思就是不用再问了", plan.isReady)
        assertEquals(
            "已经答过的那个按答的来，剩下的按套用的来",
            listOf(
                PlaylistPlan.Create("旅行 (2)", listOf(item("m1"))),
                PlaylistPlan.Append("工作", listOf(item("m2")), "pl-2"),
            ),
            plan.plans,
        )
    }

    // ============================================================ 计划：脏数据

    @Test
    fun `文件里同名的两个列表合成一项`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(list("旅行", "m1"), list("旅行", "m2")),
            PlaylistImportOptions(),
        )

        assertEquals(1, plan.plans.size)
        assertEquals(
            "两个同名列表在界面上分不清哪个是刚导入的，合成一项比给出两个「旅行」强",
            listOf(item("m1"), item("m2")),
            (plan.plans.single() as PlaylistPlan.Create).items,
        )
    }

    @Test
    fun `同一个列表里重复的条目只留第一条`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(list("旅行", "m1", "m1", "m2")),
            PlaylistImportOptions(),
        )

        assertEquals(
            listOf(item("m1"), item("m2")),
            (plan.plans.single() as PlaylistPlan.Create).items,
        )
        assertEquals(
            "少了 1 条必须说出来——不然用户会以为自己的文件被读丢了内容",
            1,
            plan.tally.duplicateItems,
        )
    }

    @Test
    fun `条目自己媒体 ID 为空时也计入丢弃`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(ImportedPlaylist(name = "旅行", items = listOf(item(""), item("m1")))),
            PlaylistImportOptions(),
        )

        assertEquals(
            listOf(item("m1")),
            (plan.plans.single() as PlaylistPlan.Create).items,
        )
        assertEquals(
            "没有媒体 ID 就没法去重也没法去媒体库取最新信息，只能丢",
            1,
            plan.tally.itemsWithoutId,
        )
    }

    @Test
    fun `解析层报的丢弃条数会被原样带上来`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(ImportedPlaylist(name = "旅行", items = listOf(item("m1")), itemsWithoutId = 3)),
            PlaylistImportOptions(),
        )

        assertEquals(
            "解析层已经丢掉的条目不会出现在 items 里，它报的数字必须带上去，否则统计会少一截",
            3,
            plan.tally.itemsWithoutId,
        )
    }

    @Test
    fun `一个列表的条目全被丢掉时算它没有可用内容`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(ImportedPlaylist(name = "旅行", items = listOf(item("")))),
            PlaylistImportOptions(),
        )

        assertTrue("一条不剩的列表写下去只会得到一个空列表", plan.plans.isEmpty())
        assertEquals(1, plan.tally.emptyPlaylists)
        assertEquals(1, plan.tally.itemsWithoutId)
        assertFalse(
            "统计非空就不算「文件里什么都没有」——这里要说的恰恰是「有几项没用上」，" +
                "沉默或一句「那个文件是空的」都是错的",
            plan.isEmpty,
        )
    }

    @Test
    fun `名字为空的列表算没有可用内容`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(
            listOf(ImportedPlaylist(name = "   ", items = listOf(item("m1")))),
            PlaylistImportOptions(),
        )

        assertTrue("没有名字的列表建出来用户既看不见也没法删", plan.plans.isEmpty())
        assertEquals(1, plan.tally.emptyPlaylists)
    }

    @Test
    fun `真的空文件才算什么都没得导`() = runTest {
        val sink = FakeSink()

        val plan = PlaylistImporter(sink).plan(emptyList(), PlaylistImportOptions())

        assertTrue("一个列表、一条统计都没有，才是「文件里没有可以导入的播放列表」", plan.isEmpty)
        assertTrue(plan.isReady)
    }

    // ============================================================== 落盘

    @Test
    fun `写完之后把文件里的统计一起报出来`() = runTest {
        val sink = FakeSink()
        val importer = PlaylistImporter(sink)

        val plan = importer.plan(
            listOf(
                ImportedPlaylist(name = "旅行", items = listOf(item("m1"), item("m1")), itemsWithoutId = 2),
                ImportedPlaylist(name = "   ", items = listOf(item("m9"))),
            ),
            PlaylistImportOptions(),
        )
        val outcome = requireNotNull(importer.apply(plan))

        assertEquals(1, outcome.playlistsCreated)
        assertEquals(0, outcome.playlistsMerged)
        assertEquals(1, outcome.itemsAdded)
        assertEquals("空列表被丢掉的个数", 1, outcome.playlistsSkippedEmpty)
        assertEquals("没有媒体 ID 的个数", 2, outcome.itemsSkippedEmpty)
        assertEquals("文件里本来就重复的个数", 1, outcome.itemsSkippedDuplicate)
        assertEquals(0, outcome.playlistsSkippedFull)
        assertEquals(
            "一次写完：中途落盘会留下「导了一半」的中间状态，" +
                "而那种状态在界面上和「导完了」长得一模一样",
            1,
            sink.applyCalls,
        )
    }

    @Test
    fun `合并时已有的条目不算新增，而要单独说成重复`() = runTest {
        val sink = FakeSink(existing = mapOf("旅行" to "pl-1"))
        sink.seed("pl-1", "m1")
        val importer = PlaylistImporter(sink)

        val plan = importer.plan(
            listOf(list("旅行", "m1", "m2")),
            PlaylistImportOptions(decisions = mapOf("旅行" to PlaylistImportMergeChoice.MERGE)),
        )
        val outcome = requireNotNull(importer.apply(plan))

        assertEquals("m1 已经在列表里了，只有 m2 是新增", 1, outcome.itemsAdded)
        assertEquals(0, outcome.playlistsCreated)
        assertEquals(1, outcome.playlistsMerged)
        assertEquals(
            "请求写 2 条、实际进去 1 条，差的这一条必须说成「重复」：" +
                "对话框里明明说要合并 2 条，账对不上用户会以为丢数据了",
            1,
            outcome.itemsSkippedDuplicate,
        )
    }

    @Test
    fun `存储失败时给 null 而不是「导入了 0 个列表」`() = runTest {
        val sink = FakeSink(failing = true)
        val importer = PlaylistImporter(sink)

        val plan = importer.plan(listOf(list("旅行", "m1")), PlaylistImportOptions())

        assertNull(
            "「写失败了」和「文件里没东西」是两件完全不同的事，前者必须说成保存失败",
            importer.apply(plan),
        )
    }

    @Test
    fun `撞上列表数量上限的那几个被单独计数`() = runTest {
        val sink = FakeSink(existing = mapOf("已满" to "pl-1"), cap = 1)
        val importer = PlaylistImporter(sink)

        val plan = importer.plan(listOf(list("甲", "m1"), list("乙", "m2")), PlaylistImportOptions())
        val outcome = requireNotNull(importer.apply(plan))

        assertEquals("库已经满了，一个新列表也建不出来", 0, outcome.playlistsCreated)
        assertEquals(2, outcome.playlistsSkippedFull)
        assertEquals(
            "文案里必须同时有**上限本身**和没进去的个数：只说「有 2 个没导入」，" +
                "用户不知道该删到多少条再试",
            "msp_import_result_nothing_happened(msp_import_note_full(100,2))",
            render(requireNotNull(outcome.message())),
        )
    }

    // ============================================================== 文案

    @Test
    fun `什么都没发生而且没什么可提醒时不给句子`() {
        assertNull(
            "沉默比一句「导入成功」诚实：什么都没发生的时候说成功，用户会去找他其实没得到的东西",
            PlaylistImportOutcome().message(),
        )
    }

    @Test
    fun `新建几个、多少条，分开说`() {
        val text = requireNotNull(PlaylistImportOutcome(playlistsCreated = 2, itemsAdded = 5).message())

        assertEquals(
            "每条数字都对应界面上一个不同的说法：合并了几个和新建了几个，" +
                "用户后续要做的事完全不同",
            "msp_joined(msp_import_result_created(2),「 · 」,msp_import_result_items(5))",
            render(text),
        )
    }

    @Test
    fun `没有写入但有提醒时不能沉默`() {
        val outcome = PlaylistImportOutcome(itemsSkippedEmpty = 12)

        assertEquals(
            "文件里 12 条全没有媒体 ID 时，「已导入 0 条」和沉默都不对，" +
                "得让用户看见那个 12",
            "msp_import_result_nothing_happened(msp_import_note_items_without_id(12))",
            render(requireNotNull(outcome.message())),
        )
    }

    @Test
    fun `有提醒时主句和提醒分开摆`() {
        val outcome = PlaylistImportOutcome(
            playlistsCreated = 1,
            itemsAdded = 2,
            itemsSkippedDuplicate = 3,
        )

        assertEquals(
            "主句说「做了什么」，括号里说「有什么没用上」——合成一句话两边都会变糊",
            "msp_import_result_with_notes(" +
                "msp_joined(msp_import_result_created(1),「 · 」,msp_import_result_items(2))," +
                "msp_import_note_duplicate_items(3))",
            render(requireNotNull(outcome.message())),
        )
    }

    @Test
    fun `读文件和问问题的时候不弹提示`() {
        assertNull(PlaylistImportState.Idle.message())
        assertNull(
            "「正在读文件」不该弹提示：它下一秒就过去了，弹出来只会闪一下",
            PlaylistImportState.Reading.message(),
        )
        assertNull(
            "该问的时候界面上是一个对话框，再弹一条 snackbar 会挡住它",
            PlaylistImportState.Ask("旅行", 3).message(),
        )
    }

    @Test
    fun `认不出的文件把解析层给的句子原样转达`() {
        val text = MspText.Res(R.string.msp_import_error_unrecognized)

        assertEquals(
            "句子是谁给的归谁：状态只负责念出来，换个说法就会出现两处描述同一件事",
            render(text),
            render(requireNotNull(PlaylistImportState.Failed(text).message())),
        )
    }

    @Test
    fun `文件里没东西可导时才说没有可以导入的播放列表`() {
        assertEquals(
            "这句话和「认不出的文件」必须长得不一样，否则用户会去翻一个其实好好的文件",
            "msp_import_result_nothing",
            render(PlaylistImportState.Empty.message()),
        )
    }

    @Test
    fun `写完之后的句子来自结果本身`() {
        val outcome = PlaylistImportOutcome(playlistsCreated = 1, itemsAdded = 1)

        assertEquals(
            render(requireNotNull(outcome.message())),
            render(requireNotNull(PlaylistImportState.Finished(outcome).message())),
        )
    }

}

/**
 * 一条测试用的条目。标题跟着 id 走，断言失败时能从文字里直接看出是哪一条串位了。
 */
private fun item(mediaId: String): PlaylistItem = PlaylistItem(
    mediaId = mediaId,
    uri = "content://media/$mediaId",
    title = mediaId,
    artist = null,
    durationMs = 0L,
    kind = MediaKind.UNKNOWN,
)

/** 一个「文件里的列表」。 */
private fun list(name: String, vararg mediaIds: String): ImportedPlaylist =
    ImportedPlaylist(name = name, items = mediaIds.map(::item))

/**
 * 把 [MspText] 渲染成可读的一行，用来断言「说的是哪句话」。
 *
 * 断言资源 id（而不是解析后的句子）是这一层的正确粒度：改标点不该让测试变红，
 * 但选错了分支一定会。`msp_joined` 是**左折叠**，所以嵌套会出现在这里——
 * 那正是它被当成参数传下去的证据，看着丑，但把「分隔符是译者能改的」这件事钉住了。
 */
private fun render(text: MspText): String = when (text) {
    is MspText.Plain -> "「${text.text}」"
    is MspText.Res -> buildString {
        append(NAME_BY_ID[text.id] ?: "R#${text.id}")
        if (text.args.isNotEmpty()) {
            append('(')
            append(text.args.joinToString(",") { arg -> if (arg is MspText) render(arg) else arg.toString() })
            append(')')
        }
    }
}

private val NAME_BY_ID: Map<Int, String> = mapOf(
    R.string.msp_import_error_unrecognized to "msp_import_error_unrecognized",
    R.string.msp_import_result_created to "msp_import_result_created",
    R.string.msp_import_result_merged to "msp_import_result_merged",
    R.string.msp_import_result_items to "msp_import_result_items",
    R.string.msp_import_result_with_notes to "msp_import_result_with_notes",
    R.string.msp_import_result_nothing_happened to "msp_import_result_nothing_happened",
    R.string.msp_import_result_nothing to "msp_import_result_nothing",
    R.string.msp_import_note_empty_playlists to "msp_import_note_empty_playlists",
    R.string.msp_import_note_items_without_id to "msp_import_note_items_without_id",
    R.string.msp_import_note_duplicate_items to "msp_import_note_duplicate_items",
    R.string.msp_import_note_full to "msp_import_note_full",
    // 拼接句来自 `core:model`（`MspText.join` 在那里定义），所以要单独把它的 id 认进来。
    // 少了这一条，两个句子里带 `join` 的用例只会打印 `R#2132017368`，看不出选错了哪一句。
    ModelR.string.msp_joined to "msp_joined",
)

/**
 * 内存版 [PlaylistImportSink]。
 *
 * 放在文件末尾的顶层而不嵌在测试类里：它是**状态容器**，每个用例都要新建一个，
 * 嵌进去反而要多写一层限定名。
 */
private class FakeSink(
    existing: Map<String, String> = emptyMap(),
    private val failing: Boolean = false,
    private val cap: Int = PlaylistRules.MAX_PLAYLISTS,
) : PlaylistImportSink {

    /** 名字 → id。可变，`apply` 会往里加。 */
    val names: LinkedHashMap<String, String> = LinkedHashMap(existing)

    /** id → 条目。用来模拟「合并时已有条目不算新增」。 */
    private val contents: MutableMap<String, List<PlaylistItem>> = HashMap()

    var nameIndexReads: Int = 0
    var applyCalls: Int = 0

    /** 预置某个已有列表里的条目（合并去重要用它）。 */
    fun seed(playlistId: String, vararg mediaIds: String) {
        contents[playlistId] = mediaIds.map(::item)
    }

    override suspend fun nameIndex(): Map<String, String> {
        nameIndexReads++
        return names
    }

    override suspend fun apply(plans: List<PlaylistPlan>): PlaylistApplyResult {
        applyCalls++
        if (failing) return PlaylistApplyResult.Failed
        var created = 0
        var createdItems = 0
        var appendedItems = 0
        plans.forEach { plan ->
            when (plan) {
                is PlaylistPlan.Create -> {
                    // 上限：超出的那几个**跳过**，和真实 store 一样只体现在返回值里。
                    if (names.size >= cap) return@forEach
                    val id = "pl-created-${names.size}"
                    names[plan.name] = id
                    contents[id] = plan.items
                    created++
                    createdItems += plan.items.size
                }

                is PlaylistPlan.Append -> {
                    val current = contents[plan.playlistId].orEmpty()
                    val merged = PlaylistRules.withAdded(current, plan.items)
                    appendedItems += merged.size - current.size
                    contents[plan.playlistId] = merged
                }
            }
        }
        return PlaylistApplyResult.Applied(created, createdItems, appendedItems)
    }
}
