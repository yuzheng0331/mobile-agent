package dev.phoneagent.core

import dev.phoneagent.core.pack.Packs
import dev.phoneagent.core.pack.validate
import dev.phoneagent.core.route.route
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Device
import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Outcome
import dev.phoneagent.core.run.Runner
import dev.phoneagent.core.run.StepTrace
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.TextHit
import dev.phoneagent.core.run.TextRecognizer
import dev.phoneagent.core.run.UiNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted phone: each action moves to the next frame; settling advances the clock. */
private class FakeDevice(vararg frames: Frame, private val actionsChangeScreen: Boolean = true) : Device {
    private val frames = frames.toList()
    private var current = 0
    private var now = 0L
    val actions = mutableListOf<String>()

    override fun capture(displayId: Int) = frames[current]
    override fun launch(packageName: String) = act("launch $packageName")
    override fun press(target: Target, long: Boolean) = act(if (long) "long" else "tap")
    override fun back() = act("back")
    override fun nowMs() = now
    override fun awaitSettle(timeoutMs: Long): Boolean {
        now += minOf(300, timeoutMs)
        return true
    }

    private fun act(name: String): Boolean {
        actions += name
        if (actionsChangeScreen && current < frames.lastIndex) current++
        return true
    }
}

private fun row(top: Int, label: String) = UiNode(
    bounds = Box(0, top, 1080, top + 150),
    clickable = true,
    children = listOf(UiNode(bounds = Box(0, top, 1080, top + 150), clickable = true, children = listOf(UiNode(text = label)))),
)

private fun frame(pkg: String, vararg nodes: UiNode) = Frame(pkg, UiNode(children = nodes.toList()))

private val QQ = "com.tencent.mobileqq"
// As on a real QQ with few chats: the list fits one screen (not scrollable), sits inside a same-size
// page RecyclerView, and its first row is the search bar.
private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
// As on real QQ: a chat row's description can contain "消息" ("…3条新消息"); the tab's text is exactly "消息".
private val qqTab = Box(0, 2300, 270, 2400)
private val qqList = frame(QQ, UiNode(desc = "群聊，3条新消息", clickable = true, bounds = Box(0, 374, 1080, 568)),
    UiNode(text = "消息", clickable = true, bounds = qqTab),

    UiNode(className = RECYCLER, bounds = Box(0, 0, 1080, 2400), children = listOf(UiNode(children = listOf(
        UiNode(className = RECYCLER, bounds = Box(0, 0, 1080, 2400),
            children = listOf(row(400, "会话B"), row(212, "搜索").copy(desc = "搜索"), row(250, "会话A"))))))))
// As on real QQ: list rows are a clickable wrapper around a same-size clickable row; message rows
// are not actionable themselves, only the avatar and the (larger) bubble inside are long-clickable.
private fun message(top: Int, text: String) = UiNode(bounds = Box(0, top, 1080, top + 400), children = listOf(
    UiNode(bounds = Box(32, top + 20, 140, top + 240), longClickable = true, clickable = true),
    UiNode(bounds = Box(140, top + 40, 810, top + 400), longClickable = true, children = listOf(UiNode(text = text))),
))
private val qqChat = frame(QQ, UiNode(desc = "聊天设置"),
    UiNode(scrollable = true, bounds = Box(0, 200, 1080, 2000),
        children = listOf(message(300, "旧消息"), message(900, "最新消息"))))
private val qqMenu = frame(QQ, UiNode(desc = "聊天设置"), UiNode(text = "复制", clickable = true))
private val douyin = frame("com.ss.android.ugc.aweme")

private fun pack(steps: String, requires: String = """["$QQ"]""") =
    Packs.parse("""{"schemaVersion":1,"id":"t","requires":$requires,"steps":$steps}""")

class CoreTest {
    private val qqPack = Packs.parse(File("../packs/qq-latest-to-douyin.json").readText())

    @Test
    fun `shipped packs validate`() {
        val packs = File("../packs").listFiles { f -> f.extension == "json" }!!.map { it.name to Packs.parse(it.readText()) }
        packs.forEach { (name, pack) -> assertEquals(emptyList(), validate(pack), name) }
        val ids = packs.map { it.second.id }
        assertEquals(ids.distinct(), ids, "duplicate pack ids")
    }

    @Test
    fun `launch presses back toward the start screen at most three times`() {
        val launchQq = pack("""[{"do":"launch","app":"$QQ","expect":{"anyText":["搜索"]},"timeoutMs":600}]""")
        val resumed = FakeDevice(douyin, qqChat, qqList)
        assertTrue(Runner(resumed).run(launchQq).ok)
        assertEquals(listOf("launch $QQ", "back"), resumed.actions)

        val stuck = FakeDevice(qqChat, actionsChangeScreen = false)
        assertEquals(Outcome.NO_EFFECT, Runner(stuck).run(launchQq).steps.single().outcome)
        assertEquals(listOf("launch $QQ", "back", "back", "back"), stuck.actions)
    }

    @Test
    fun `qq pack runs end to end on scripted frames`() {
        val device = FakeDevice(douyin, qqList, qqList, qqChat, qqMenu, qqChat, douyin)
        val seen = mutableListOf<StepTrace>()
        val result = Runner(device, observer = { seen += it }).run(qqPack)
        assertTrue(result.ok, result.reason)
        assertEquals(result.steps, seen)
        assertEquals(listOf("launch $QQ", "tap", "tap", "long", "tap", "launch com.ss.android.ugc.aweme"), device.actions)
        // listItem 0 is the topmost row, -1 the bottom-most message
        assertEquals(qqTab, result.steps[1].bounds) // the tab, not a row mentioning 消息
        assertEquals(250, result.steps[2].bounds!!.top) // row 1: row 0 is the search bar
        assertEquals(Box(140, 940, 810, 1300), result.steps[3].bounds) // the bubble, not the avatar
    }

    @Test
    fun `missing target waits out the timeout then stops with the pack's reason`() {
        val device = FakeDevice(qqChat)
        val result = Runner(device).run(
            pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"ifMissing":"不是文字","timeoutMs":1000}]"""),
        )
        assertEquals(Outcome.MISSING, result.steps.single().outcome)
        assertTrue(result.reason!!.endsWith("不是文字"))
        assertTrue(device.nowMs() >= 1000)
        assertEquals(emptyList(), device.actions)
    }

    @Test
    fun `no retry when the screen changed, even if a positional finder hits again`() {
        fun list(label: String) = frame(QQ, UiNode(scrollable = true, bounds = Box(0, 200, 1080, 2000), children = listOf(row(250, label))))
        val device = FakeDevice(list("会话A"), list("群相册"))
        val result = Runner(device).run(
            pack("""[{"do":"tap","target":{"intent":"第一行","find":[{"listItem":0}]},"expect":{"anyText":["聊天设置"]},"maxRetry":1,"timeoutMs":600}]"""),
        )
        assertEquals(Outcome.NO_EFFECT, result.steps.single().outcome)
        assertEquals(listOf("tap"), device.actions)
    }

    @Test
    fun `no effect retries only up to maxRetry`() {
        val device = FakeDevice(qqMenu, actionsChangeScreen = false)
        val result = Runner(device).run(
            pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"expect":{"gone":["复制"]},"maxRetry":1,"timeoutMs":600}]"""),
        )
        assertEquals(Outcome.NO_EFFECT, result.steps.single().outcome)
        assertEquals(2, result.steps.single().attempts)
        assertEquals(listOf("tap", "tap"), device.actions)
    }

    @Test
    fun `never touches an app the pack does not require`() {
        val device = FakeDevice(frame("com.example.other", UiNode(text = "复制", clickable = true)))
        val result = Runner(device).run(
            pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"timeoutMs":600}]"""),
        )
        assertEquals(Outcome.WRONG_APP, result.steps.single().outcome)
        assertEquals(emptyList(), device.actions)
    }

    @Test
    fun `validator rejects unknown names, bad arguments and stray launches`() {
        val problems = validate(
            pack(
                """[{"do":"swipe"},
                   {"do":"tap","target":{"intent":"x","find":[{"magic":1}]}},
                   {"do":"tap","target":{"intent":"x","find":[{"text":["not","a","string"]}]}},
                   {"do":"back","expect":{"anyText":"not a list"}},
                   {"do":"launch","app":"com.example"}]""",
            ),
        )
        assertEquals(5, problems.size, problems.joinToString("\n"))
    }

    @Test
    fun `router picks the pack an alias names and refuses to guess`() {
        assertEquals("qq-latest-to-douyin", route("帮我复制 QQ 最新消息！", listOf(qqPack))?.id)
        assertNull(route("打开微信", listOf(qqPack)))
        assertNull(route("", listOf(qqPack)))
    }

    @Test
    fun `frame runs OCR once per region`() {
        var calls = 0
        val frame = Frame("p", null, recognizer = { calls++; listOf(TextHit("复制", Box(0, 0, 1, 1), 0.9f)) })
        frame.ocr(Box(0, 0, 10, 10))
        frame.ocr(Box(0, 0, 10, 10))
        frame.ocr()
        assertEquals(2, calls)
    }
}
