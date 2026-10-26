package dev.phoneagent.core

import dev.phoneagent.core.ext.Builtins
import dev.phoneagent.core.pack.Packs
import dev.phoneagent.core.pack.validate
import dev.phoneagent.core.route.route
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Device
import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Outcome
import dev.phoneagent.core.run.Pixels
import dev.phoneagent.core.run.RunObserver
import dev.phoneagent.core.run.Runner
import dev.phoneagent.core.run.StepTrace
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.TextHit
import dev.phoneagent.core.run.TextRecognizer
import dev.phoneagent.core.run.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    var captures = 0
    override fun capture(displayId: Int) = frames[current].also { captures++ }
    override fun launch(packageName: String) = act("launch $packageName")
    var pressFailures = 0
    override fun press(target: Target, long: Boolean): Boolean {
        if (pressFailures > 0) { pressFailures--; actions += "missed"; return false }
        return act(if (long) "long" else "tap")
    }
    override fun back() = act("back")
    override fun nowMs() = now
    override fun awaitSettle(timeoutMs: Long): Boolean {
        now += minOf(300, timeoutMs)
        userActsAt?.let { if (now >= it) { userActsAt = null; current++ } }
        return true
    }

    /** The user changes the screen themselves (e.g. closes a dialog) once the clock passes this. */
    var userActsAt: Long? = null

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
    UiNode(text = "消息", clickable = true, bounds = qqTab), UiNode(text = "联系人", clickable = true),

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

private fun pack(steps: String, requires: String = """["$QQ"]""", needs: String = "[]") =
    Packs.parse("""{"schemaVersion":1,"id":"t","requires":$requires,"needs":$needs,"steps":$steps}""")

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
    fun `launch presses back toward the start screen while each press changes the screen`() {
        val launchQq = pack("""[{"do":"launch","app":"$QQ","expect":{"anyText":["搜索"]},"timeoutMs":600}]""")
        val resumed = FakeDevice(douyin, qqChat, qqList)
        assertTrue(Runner(resumed).run(launchQq).ok)
        assertEquals(listOf("launch $QQ", "back"), resumed.actions)

        // A browser five pages deep needs five presses.
        val pages = (1..5).map { frame(QQ, UiNode(text = "第 $it 页")) }
        val deep = FakeDevice(douyin, *pages.toTypedArray(), qqList)
        assertTrue(Runner(deep).run(launchQq).ok)
        assertEquals(listOf("launch $QQ") + List(5) { "back" }, deep.actions)

        // A press that changes nothing ends it at once.
        val stuck = FakeDevice(qqChat, actionsChangeScreen = false)
        assertEquals(Outcome.NO_EFFECT, Runner(stuck).run(launchQq).steps.single().outcome)
        assertEquals(listOf("launch $QQ", "back"), stuck.actions)
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
    fun `an image as the latest message stops at copy with the pack's reason`() {
        val imageMenu = frame(QQ, UiNode(desc = "聊天设置"), UiNode(text = "转发", clickable = true))
        val device = FakeDevice(douyin, qqList, qqList, qqChat, imageMenu)
        val result = Runner(device).run(qqPack)
        assertEquals(Outcome.MISSING, result.steps.last().outcome)
        assertEquals(4, result.steps.last().index)
        assertTrue(result.reason!!.contains("不是文字"), result.reason)
    }

    @Test
    fun `qq pack backs out of a group chat that also shows 搜索 and 精华消息`() {
        val groupChat = frame(QQ, UiNode(desc = "聊天设置"), UiNode(desc = "搜索"), UiNode(text = "精华消息", clickable = true))
        val device = FakeDevice(douyin, groupChat, qqList, qqList, qqChat, qqMenu, qqChat, douyin)
        val result = Runner(device).run(qqPack)
        assertTrue(result.ok, result.reason)
        assertEquals(listOf("launch $QQ", "back", "tap", "tap", "long", "tap", "launch com.ss.android.ugc.aweme"), device.actions)
        assertEquals(qqTab, result.steps[1].bounds)
    }

    @Test
    fun `qq pack closes the side drawer, whose page underneath still shows the tabs`() {
        // Real QQ: the drawer slides over the main page; the tabs and list below stay in the tree.
        val drawer = frame(QQ, UiNode(desc = "返回", clickable = true), UiNode(text = "联系人"),
            UiNode(scrollable = true, bounds = Box(0, 900, 1080, 1800), children = listOf(row(950, "相册"))))
        val device = FakeDevice(douyin, drawer, qqList, qqList, qqChat, qqMenu, qqChat, douyin)
        val result = Runner(device).run(qqPack)
        assertTrue(result.ok, result.reason)
        assertEquals("back", device.actions[1])
    }

    @Test
    fun `lastMessage skips full-width system notices`() {
        // Real QQ: a recall notice is a full-width long-clickable row below the last real message.
        val notice = UiNode(bounds = Box(0, 1900, 1080, 2000), children = listOf(
            UiNode(bounds = Box(0, 1920, 1080, 1980), clickable = true, longClickable = true, children = listOf(UiNode(text = "你撤回了一条消息")))))
        val chat = frame(QQ, UiNode(desc = "聊天设置"), UiNode(scrollable = true, bounds = Box(0, 200, 1080, 2100),
            children = listOf(message(300, "旧消息"), message(900, "最新消息"), notice)))
        val hit = Builtins.registry.find(chat, Json.parseToJsonElement("""{"lastMessage":true}""").jsonObject)
        assertEquals(Box(140, 940, 810, 1300), hit!!.bounds)
    }

    @Test
    fun `exact text never falls back to a longer label`() {
        // Real QQ group chat: no 消息 tab, but a toolbar entry 精华消息.
        val chat = frame(QQ, UiNode(desc = "聊天设置"), UiNode(text = "精华消息", clickable = true))
        val tab = pack("""[{"do":"tap","target":{"intent":"消息标签","find":[{"text":"消息","exact":true}]},"timeoutMs":600}]""")
        val device = FakeDevice(chat)
        assertEquals(Outcome.MISSING, Runner(device).run(tab).steps.single().outcome)
        assertEquals(emptyList(), device.actions)
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
    fun `a stuck step asks the user, waits for the screen to change, then tries once more`() {
        val dialog = frame(QQ, UiNode(text = "发现新版本"), UiNode(text = "稍后"))
        val stuckReasons = mutableListOf<String>()
        val device = FakeDevice(dialog, qqMenu, douyin).apply { userActsAt = 3_000 }
        val result = Runner(device, observer = object : RunObserver {
            override fun onStep(trace: StepTrace) = Unit
            override fun onStuck(reason: String) { stuckReasons += reason }
        }).run(pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"timeoutMs":600}]"""))
        assertTrue(result.ok, result.reason)
        assertEquals(1, stuckReasons.size)
        assertEquals(listOf(Outcome.MISSING, Outcome.SUCCESS), result.steps.map { it.outcome })
        assertEquals(listOf("tap"), device.actions) // nothing was tapped while the dialog was up
    }

    @Test
    fun `a stuck step stops when the user does nothing`() {
        val device = FakeDevice(qqChat)
        val result = Runner(device).run(pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"timeoutMs":600}]"""))
        assertEquals(Outcome.MISSING, result.steps.single().outcome)
        assertTrue(device.nowMs() >= 20_000)
    }

    @Test
    fun `listItem picks the list with the most rows, not the pager around it`() {
        // As on Quark's bookmarks page: a scrollable pager holds one page, the page holds the list.
        val list = UiNode(className = "android.widget.ListView", bounds = Box(0, 263, 1080, 2220),
            children = listOf(row(407, "书签1"), row(587, "书签2")))
        val pager = UiNode(scrollable = true, bounds = Box(0, 263, 1080, 2400), children = listOf(UiNode(children = listOf(list))))
        val hit = Builtins.registry.find(frame("com.quark.browser", pager), buildJsonObject { put("listItem", 0) })
        assertEquals(407, hit!!.bounds.top)
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
    fun `retries on the moved target while the previous step's screen still holds`() {
        // A new message arrives after the long-press: the menu is gone and the last bubble is a new one.
        val chatA = frame(QQ, UiNode(desc = "聊天设置"), UiNode(scrollable = true, bounds = Box(0, 200, 1080, 2000),
            children = listOf(message(300, "旧消息"), message(900, "A"))))
        val chatB = frame(QQ, UiNode(desc = "聊天设置"), UiNode(scrollable = true, bounds = Box(0, 200, 1080, 2000),
            children = listOf(message(300, "旧消息"), message(700, "A"), message(1100, "B"))))
        val device = FakeDevice(chatA, chatA, chatB, qqMenu)
        val result = Runner(device).run(pack(
            """[{"do":"back","expect":{"anyText":["聊天设置"]}},
               {"do":"long","target":{"intent":"最后一条","find":[{"listItem":-1}]},"expect":{"anyText":["复制"]},"maxRetry":1,"timeoutMs":600}]""",
        ))
        assertTrue(result.ok, result.reason)
        assertEquals(listOf("back", "long", "long"), device.actions)
        assertEquals(1140, result.steps[1].bounds!!.top) // the new last bubble
    }

    @Test
    fun `a press that misses (target moved) finds the target again`() {
        val device = FakeDevice(qqMenu, douyin).apply { pressFailures = 1 }
        val result = Runner(device).run(
            pack("""[{"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"maxRetry":1,"timeoutMs":600}]"""),
        )
        assertTrue(result.ok, result.reason)
        assertEquals(listOf("missed", "tap"), device.actions)
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
    fun `frame takes its screenshot only when asked, once, and frames get growing ids`() {
        var shots = 0
        val frame = Frame("p", null, shooter = { shots++; Pixels(2, 1, intArrayOf(1, 2)) })
        assertEquals(0, shots)
        assertEquals(2, frame.screenshot!![1, 0])
        frame.screenshot
        assertEquals(1, shots)
        assertTrue(Frame("p", null).id > frame.id)
    }

    @Test
    fun `frame runs OCR once per region, on one screenshot, and only when the run has OCR`() {
        var calls = 0
        var shots = 0
        val reader = object : TextRecognizer {
            override fun recognize(pixels: Pixels, region: Box?) = listOf(TextHit("复制", Box(0, 0, 1, 1), 0.9f)).also { calls++ }
            override fun readLine(pixels: Pixels, region: Box) = null.also { calls++ }
        }
        val frame = Frame("p", null, recognizer = reader, shooter = { shots++; Pixels(1, 1, intArrayOf(0)) })
        frame.ocr(Box(0, 0, 10, 10))
        frame.ocr(Box(0, 0, 10, 10))
        frame.ocr()
        frame.ocrLine(Box(0, 0, 5, 5))
        frame.ocrLine(Box(0, 0, 5, 5)) // a null answer is cached too
        assertEquals(3, calls)
        assertEquals(1, shots)

        val noOcr = Frame("p", null, shooter = { shots++; Pixels(1, 1, intArrayOf(0)) })
        assertEquals(emptyList(), noOcr.ocr())
        assertEquals(1, shots) // no recognizer, no screenshot
    }

    @Test
    fun `ocrText reads only its region, matches like text, and needs ocr declared`() {
        val asked = mutableListOf<Box?>()
        val reader = object : TextRecognizer {
            override fun recognize(pixels: Pixels, region: Box?) = listOf(
                TextHit("精华 消息", Box(10, 1900, 200, 1950), 0.9f),
                TextHit("消息", Box(300, 1900, 400, 1950), 0.9f),
            ).also { asked += region }
            override fun readLine(pixels: Pixels, region: Box) = null
        }
        val frame = Frame("p", null, recognizer = reader, shooter = { Pixels(1000, 2000, IntArray(0)) })
        fun find(spec: String) = Builtins.registry.find(frame, Json.parseToJsonElement(spec).jsonObject)
        assertEquals(Box(300, 1900, 400, 1950), find("""{"ocrText":"消息","region":[0,0.75,1,1]}""")?.bounds)
        assertEquals(Box(0, 1500, 1000, 2000), asked.single()) // fractions of the screenshot, OCR cached per region
        assertEquals(Box(10, 1900, 200, 1950), find("""{"ocrText":"精华消息","region":[0,0.75,1,1]}""")?.bounds)
        assertNull(find("""{"ocrText":"华消","exact":true,"region":[0,0.75,1,1]}"""))
        assertNull(find("""{"ocrText":"转发"}""")?.node)
        assertNull(Builtins.registry.find(Frame("p", null), Json.parseToJsonElement("""{"ocrText":"消息"}""").jsonObject))

        val tap = """[{"do":"tap","target":{"intent":"x","find":[{"ocrText":"复制","region":[0,0.5,1,1]}]}}]"""
        assertEquals(1, validate(pack(tap)).size)
        assertEquals(emptyList(), validate(pack(tap, needs = """["ocr"]""")))
        val badRegion = """[{"do":"tap","target":{"intent":"x","find":[{"ocrText":"复制","region":[0,0.5,1]}]}}]"""
        assertEquals(1, validate(pack(badRegion, needs = """["ocr"]""")).size)
    }

    @Test
    fun `lastBubble finds the bubble beside the lowest avatar and skips notices and voice extras`() {
        val w = 1080
        val screen = IntArray(w * 2400) { 0xFF111111.toInt() }
        fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) screen[y * w + x] = c }
        val avatar = 0xFFE090B0.toInt()
        val bubble = 0xFF303030.toInt()
        fun find() = Builtins.registry.find(Frame("p", null, shooter = { Pixels(w, 2400, screen) }), Json.parseToJsonElement("""{"lastBubble":true}""").jsonObject)?.bounds
        assertNull(find())
        fill(36, 1000, 144, 1108, avatar); fill(170, 1000, 400, 1108, bubble) // received text
        fill(936, 1300, 1044, 1408, avatar); fill(700, 1300, 906, 1408, 0xFF40A060.toInt()) // sent text
        fill(300, 1500, 780, 1540, bubble) // centred system notice: no avatar
        assertEquals(Box(700, 1300, 906, 1408), find())
        fill(36, 1700, 144, 1808, avatar); fill(170, 1700, 372, 1808, bubble) // voice ...
        fill(395, 1740, 410, 1760, 0xFFE04040.toInt()); fill(450, 1730, 576, 1770, bubble) // ... unread dot, 转文字
        assertEquals(Box(170, 1700, 372, 1808), find())
    }

    @Test
    fun `chatRow passes over rows whose name is skipped, and expectations can OCR a region only`() {
        val w = 1080
        val screen = IntArray(w * 2400) { 0xFF191919.toInt() }
        fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) screen[y * w + x] = c }
        fill(0, 240, w, 380, 0xFF2A2A2A.toInt()) // pinned row: lighter background
        listOf(244, 437, 625).forEach { top -> fill(40, top, 172, top + 132, 0xFF3080E0.toInt()) }
        val names = mapOf(244 to "公众号", 437 to "腾讯新闻", 625 to "小王")
        val asked = mutableListOf<Box?>()
        val reader = object : TextRecognizer {
            override fun recognize(pixels: Pixels, region: Box?) = listOf(TextHit("微信(3)", Box(400, 120, 680, 170), 0.9f)).also { asked += region }
            override fun readLine(pixels: Pixels, region: Box) = names[region.top]?.let { TextHit(it, region, 0.9f) }
        }
        val frame = Frame("p", null, recognizer = reader, shooter = { Pixels(w, 2400, screen) })
        fun json(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(Box(40, 625, 1026, 757), Builtins.registry.find(frame, json("""{"chatRow":true,"skip":["公众号","腾讯新闻"]}"""))?.bounds)
        assertEquals(244, Builtins.registry.find(frame, json("""{"chatRow":true}"""))?.bounds?.top)

        assertTrue(Builtins.registry.holds(frame, json("""{"anyText":{"text":["微信"],"region":[0.2,0.035,0.8,0.085]}}""")))
        assertEquals(Box(216, 84, 864, 204), asked.single())
        assertTrue(Builtins.registry.holds(frame, json("""{"gone":{"text":["通讯录"],"region":[0,0.95,1,1]}}""")))
        val bad = """[{"do":"back","expect":{"anyText":{"text":["x"],"region":[0,1]}}}]"""
        assertEquals(1, validate(pack(bad, needs = """["ocr"]""")).size)
    }

    @Test
    fun `with OCR, equal trees still differ when the screenshots do`() {
        val reader = object : TextRecognizer {
            override fun recognize(pixels: Pixels, region: Box?) = emptyList<TextHit>()
            override fun readLine(pixels: Pixels, region: Box) = null
        }
        fun shot(c: Int, menu: Boolean) = Pixels(240, 480, IntArray(240 * 480) { i -> if (menu && i / 240 in 200..300) 0xFFFFFFFF.toInt() else c })
        fun frame(menu: Boolean, ocr: Boolean = true) = Frame("p", UiNode(), recognizer = reader.takeIf { ocr }, shooter = { shot(0xFF111111.toInt(), menu) })
        assertTrue(frame(false).sameScreen(frame(false)))
        assertTrue(!frame(false).sameScreen(frame(true)))
        assertTrue(frame(false, ocr = false).sameScreen(frame(true, ocr = false))) // without OCR the tree decides
    }

    @Test
    fun `the step after a met expectation finds on that same frame, without capturing again`() {
        val device = FakeDevice(qqChat, qqMenu)
        val at = mutableListOf<Int>()
        val result = Runner(device, observer = { at += device.captures }).run(
            pack("""[{"do":"long","target":{"intent":"m","find":[{"lastMessage":true}]},"expect":{"anyText":["复制"]}},
                     {"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]}}]"""),
        )
        assertTrue(result.ok, result.reason)
        assertEquals(at[0], at[1])
    }

    @Test
    fun `a target missing on the screen the previous step confirmed stops at once, without asking the user`() {
        val device = FakeDevice(qqChat, frame(QQ, UiNode(desc = "聊天设置"), UiNode(text = "转发")))
        var stuck = 0
        val result = Runner(device, observer = object : RunObserver {
            override fun onStep(trace: StepTrace) {}
            override fun onStuck(reason: String) { stuck++ }
        }).run(
            pack("""[{"do":"long","target":{"intent":"m","find":[{"lastMessage":true}]},"expect":{"anyText":["转发"]}},
                     {"do":"tap","target":{"intent":"复制","find":[{"text":"复制"}]},"ifMissing":"不是文字"}]"""),
        )
        assertEquals(0, stuck)
        assertEquals(Outcome.MISSING, result.steps.last().outcome)
        assertTrue(device.nowMs() < 6_000, "waited ${device.nowMs()} ms")
    }

    @Test
    fun `an OCR target missing on the frame the previous step confirmed is decided there, with no new screenshot`() {
        var shots = 0
        val reader = object : TextRecognizer {
            override fun recognize(pixels: Pixels, region: Box?) = listOf(TextHit("转发", Box(0, 0, 10, 10), 0.9f), TextHit("多选", Box(20, 0, 30, 10), 0.9f))
            override fun readLine(pixels: Pixels, region: Box) = null
        }
        fun menu() = Frame("com.tencent.mm", UiNode(), recognizer = reader, shooter = { shots++; Pixels(10, 10, IntArray(100)) })
        val device = object : Device {
            var now = 0L
            override fun capture(displayId: Int) = menu()
            override fun launch(packageName: String) = true
            override fun press(target: Target, long: Boolean) = true
            override fun back() = true
            override fun nowMs() = now
            override fun awaitSettle(timeoutMs: Long) = true.also { now += minOf(300, timeoutMs) }
        }
        val result = Runner(device).run(Packs.parse("""{"schemaVersion":1,"id":"t","requires":["com.tencent.mm"],"needs":["ocr"],"steps":[
            {"do":"back","expect":{"anyText":["多选"]}},
            {"do":"tap","target":{"intent":"复制","find":[{"ocrText":"复制"}]},"ifMissing":"不是文字"}]}"""))
        assertEquals(Outcome.MISSING, result.steps.last().outcome)
        assertEquals(1, shots)
        assertEquals(0L, result.steps.last().findMs)
    }
}
