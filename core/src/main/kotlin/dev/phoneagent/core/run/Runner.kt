package dev.phoneagent.core.run

import dev.phoneagent.core.ext.Builtins
import dev.phoneagent.core.ext.Registry
import dev.phoneagent.core.pack.Expr
import dev.phoneagent.core.pack.Pack
import dev.phoneagent.core.pack.Site
import dev.phoneagent.core.pack.Step
import dev.phoneagent.core.pack.eval
import dev.phoneagent.core.pack.fill
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val MAX_BACKS = 10
private const val LAUNCH_CHECK_MS = 1_500L
private const val USER_WAIT_MS = 20_000L

/** How a step ended. The `SITE_` ones are `http` steps: see [answer]. */
enum class Outcome {
    SUCCESS, MISSING, WRONG_APP, NO_EFFECT, ACTION_FAILED,
    SITE_AUTH, SITE_DENIED, SITE_BUSY, SITE_DOWN, SITE_UNKNOWN, SITE_CHANGED, SITE_REFUSED,
}

/**
 * One step of the run trajectory: which finder hit, where, and how long each phase
 * took. No screen text, so it can be kept and later replayed by the learning mode.
 */
data class StepTrace(
    val index: Int,
    val action: String,
    val finderIndex: Int?,
    val bounds: Box?,
    val findMs: Long,
    val actMs: Long,
    val settleMs: Long,
    val checkMs: Long,
    val attempts: Int,
    val outcome: Outcome,
)

/** [reason]: why the run stopped, null if it finished. [skipped]: the failures the pack said to skip. */
data class RunResult(val packId: String, val steps: List<StepTrace>, val reason: String?, val skipped: List<String> = emptyList()) {
    val ok: Boolean get() = reason == null
}

/** Hears about a run while it goes, so the UI can draw progress. Teach / skip / stop for debug runs joins in M4. */
fun interface RunObserver {
    fun onStep(trace: StepTrace)

    /** A step failed. The run never waits for anyone: it skips the step or stops, as the pack says. */
    fun onStuck(reason: String) {}
}

/**
 * Runs a validated pack step by step: find (cheapest finder first), act, wait until
 * the UI settles, check the expectation. "Still loading" waits without using up a
 * retry; "no effect" retries the action, but only if the target is still there.
 * `http` steps call the pack's site through [http]; `let`, `when` and `forEach` bind
 * variables, branch and loop on what the site returned.
 */
class Runner(
    private val device: Device,
    private val registry: Registry = Builtins.registry,
    private val observer: RunObserver = RunObserver {},
    private val http: Http? = null,
) {
    /**
     * The frame the last expectation held on. Nothing has acted since, so the next step's first
     * find looks at it instead of capturing again: with OCR that saves a whole recognition (~3 s).
     */
    private var settled: Frame? = null

    /** Where the last action landed: frames carry it as [Frame.focus]. */
    private var acted: Box? = null

    /** What the last screen step's expectation confirmed: the next step starts there. */
    private var screen: JsonObject? = null

    /** The last frame the runner perceived; a missing target is judged against the screen it saw. */
    private var lastSeen: Frame? = null

    private val traces = mutableListOf<StepTrace>()
    private val skipped = mutableListOf<String>()

    fun run(pack: Pack): RunResult {
        traces.clear()
        skipped.clear()
        screen = null
        var vars = emptyMap<String, JsonElement>()
        if (pack.site != null) {
            val http = http ?: return RunResult(pack.id, emptyList(), "这个任务包要连网站，但还没有登录网站")
            vars = mapOf("me" to buildJsonObject { put("id", http.userId) })
        }
        val reason = block(pack, pack.steps, vars, "")
        return RunResult(pack.id, traces.toList(), reason, skipped.toList())
    }

    /** Runs [steps] in order with the variables in [outer]; returns why the run stops, or null. */
    private fun block(pack: Pack, steps: List<Step>, outer: Map<String, JsonElement>, at: String): String? {
        var vars = outer
        for ((index, step) in steps.withIndex()) {
            val where = "${at}第 ${index + 1} 步"
            val failure = when {
                step.let != null -> null.also { step.let.forEach { (name, text) -> vars = vars + (name to Expr.parse(text).eval(vars)) } }
                step.cond != null -> if (Expr.parse(step.cond).eval(vars).jsonPrimitive.boolean) block(pack, step.then!!, vars, "$where › ") else null
                step.forEach != null -> forEach(pack, step, vars, where)
                step.action == "http" -> {
                    val (value, failure) = call(pack.site!!, index, step, vars, where)
                    if (value != null && step.save != null) vars = vars + (step.save to value)
                    failure
                }
                else -> screenStep(pack, index, step, vars, where)
            }
            if (failure != null && !skip(step, failure)) return failure
        }
        return null
    }

    /** A failure the pack says to skip is reported at the end instead of stopping the run. */
    private fun skip(step: Step, failure: String) = (step.onStuck == "skip").also { if (it) skipped += failure }

    /** One round per item; `key` drops repeated items, and a failed round is skipped if the `forEach` says so. */
    private fun forEach(pack: Pack, step: Step, vars: Map<String, JsonElement>, where: String): String? {
        val items = Expr.parse(step.forEach!!).eval(vars).jsonArray
        // max guards against a site gone wrong (ten thousand rows would mean ten thousand rounds).
        if (items.size > step.max!!) return "$where：有 ${items.size} 项，超过上限 ${step.max}".also(observer::onStuck)
        val seen = mutableSetOf<JsonElement>()
        for ((round, item) in items.withIndex()) {
            val inner = vars + (step.item!! to item)
            if (step.key != null && !seen.add(Expr.parse(step.key).eval(inner))) continue
            val failure = block(pack, step.body!!, inner, "${where}第 ${round + 1} 轮 › ") ?: continue
            if (!skip(step, failure)) return failure
        }
        return null
    }

    /** Calls the site; returns what it gave, or why the step failed. A write is sent once, never again. */
    private fun call(site: Site, index: Int, step: Step, vars: Map<String, JsonElement>, where: String): Pair<JsonElement?, String?> {
        val op = site.ops.getValue(step.call!!)
        val start = device.nowMs()
        val answer = answer(op, http!!.send(request(site, op, step.with.mapValues { fill(it.value, vars) })))
        val trace = StepTrace(index, "http", null, null, 0, device.nowMs() - start, 0, 0, 1, answer.outcome)
        traces += trace
        observer.onStep(trace)
        if (answer.outcome == Outcome.SUCCESS) return answer.value to null
        return null to reason(where, step, answer.outcome, answer.detail).also(observer::onStuck)
    }

    /** A step on the phone's screen; `{path}` in its finders and expectation is filled from [vars] first. */
    private fun screenStep(pack: Pack, index: Int, step: Step, vars: Map<String, JsonElement>, where: String): String? {
        val filled = step.copy(
            target = step.target?.let { it.copy(find = it.find.map { spec -> fill(spec, vars).jsonObject }) },
            expect = step.expect?.let { fill(it, vars).jsonObject },
            // A typed comment is a variable: `{"text": "{t}"}`.
            with = step.with.mapValues { fill(it.value, vars) },
        )
        var trace = runStep(index, filled, pack.requires, screen)
        // Usually an app dialog (update, ad) is in the way: ask the user to close it, wait for the
        // screen to change, then try the step once more. Nothing is tapped meanwhile.
        // Missing on the screen the previous step confirmed: nothing is in the way, the target
        // just isn't there (e.g. no 复制 for a voice message), so stop now instead of waiting.
        val confirmed = trace.outcome == Outcome.MISSING && screen != null && lastSeen?.let { registry.holds(it, screen!!) } == true
        if (trace.outcome != Outcome.SUCCESS && trace.outcome != Outcome.ACTION_FAILED && !confirmed) {
            traces += trace
            observer.onStep(trace)
            observer.onStuck(reason(where, filled, trace.outcome, null))
            val blocked = capture().freeze()
            if (waitFor(USER_WAIT_MS) { frame -> frame.takeIf { !it.sameScreen(blocked) } } == null) {
                return reason(where, filled, trace.outcome, null)
            }
            device.awaitSettle(filled.timeoutMs)
            trace = runStep(index, filled, pack.requires, screen)
        }
        traces += trace
        observer.onStep(trace)
        // A failed step confirmed nothing, so the next one does not start from its expectation.
        screen = filled.expect.takeIf { trace.outcome == Outcome.SUCCESS }
        if (trace.outcome == Outcome.SUCCESS) return null
        return reason(where, filled, trace.outcome, null)
    }

    private fun runStep(index: Int, step: Step, requires: List<String>, screen: JsonObject?): StepTrace {
        val action = registry.actions.getValue(step.action!!)
        var hit: IndexedValue<Target>? = null
        var attempts = 0
        var findMs = 0L
        var actMs = 0L
        var settleMs = 0L
        var checkMs = 0L
        fun trace(outcome: Outcome) = StepTrace(
            index, step.action, hit?.index, hit?.value?.bounds, findMs, actMs, settleMs, checkMs, attempts, outcome,
        )

        while (true) {
            if (action.needsTarget) {
                val start = device.nowMs()
                // Only ever touch the apps the pack declares; another app in front is waited out, then refused.
                val confirmed = settled
                settled = null
                hit = if (confirmed != null && readsScreenshot(step)) {
                    // The previous expectation read this very screenshot and found the screen ready, so
                    // the answer is final: no second screenshot and OCR (each ~0.4 s + ~3 s).
                    findTarget(step, confirmed)
                } else {
                    waitFor(step.timeoutMs, confirmed) { frame -> if (frame.packageName in requires) findTarget(step, frame) else null }
                }
                findMs += device.nowMs() - start
                if (hit == null) {
                    return trace(if (capture().packageName in requires) Outcome.MISSING else Outcome.WRONG_APP)
                }
            }

            attempts++
            settled = null
            var start = device.nowMs()
            val done = action.perform(device, step, hit?.value)
            if (done) acted = hit?.value?.bounds ?: acted
            actMs += device.nowMs() - start
            // A target that moved or vanished before the action lands (e.g. a new chat message): find it again.
            if (!done && action.needsTarget && attempts <= step.maxRetry) continue
            if (!done) return trace(Outcome.ACTION_FAILED)

            // Nothing to check: don't wait for a screen that may never go quiet (e.g. a video feed).
            val expect = step.expect ?: return trace(Outcome.SUCCESS)

            start = device.nowMs()
            device.awaitSettle(step.timeoutMs)
            settleMs += device.nowMs() - start

            // The launched app is already in front, so a wrong start screen shows quickly: go back sooner.
            // shortcut: 1.5 s may be too short for a slow cold start, raise it if launch backs out of a splash.
            val checkFor = if (step.action == "launch") minOf(step.timeoutMs, LAUNCH_CHECK_MS) else step.timeoutMs
            start = device.nowMs()
            settled = waitFor(checkFor) { frame -> frame.takeIf { registry.holds(it, expect) } }
            checkMs += device.nowMs() - start
            if (settled != null) return trace(Outcome.SUCCESS)

            // A launch can resume the app mid-flow (e.g. inside a chat): press back toward its start screen.
            if (step.action == "launch") {
                start = device.nowMs()
                val back = backToStart(expect, requires, step.timeoutMs)
                actMs += device.nowMs() - start
                return trace(if (back) Outcome.SUCCESS else Outcome.NO_EFFECT)
            }

            // Look again before repeating, so a menu or long-press is never triggered twice. "Still on the
            // same screen" is the previous step's expectation when it has one (the target itself may have
            // moved, e.g. a new message pushed the last bubble up); otherwise the same place and label,
            // since a positional finder (listItem) also hits on a different screen.
            val frame = capture()
            val again = if (action.needsTarget) findTarget(step, frame)?.value else null
            val before = hit?.value
            val sameScreen = screen?.let { registry.holds(frame, it) }
                ?: (again?.bounds == before?.bounds && again?.node?.label == before?.node?.label)
            val stillThere = again != null && sameScreen
            if (attempts > step.maxRetry || !stillThere) return trace(Outcome.NO_EFFECT)
        }
    }

    /**
     * Presses back toward the start screen while a required app is in front and each press still
     * changes the screen (a browser may be several pages deep); at most [MAX_BACKS] times.
     */
    private fun backToStart(expect: JsonObject, requires: List<String>, timeoutMs: Long): Boolean {
        repeat(MAX_BACKS) {
            // The comparison below needs the screen as it was before the press.
            val before = capture().freeze()
            if (before.packageName !in requires || !device.back()) return false
            device.awaitSettle(timeoutMs)
            val after = capture()
            if (registry.holds(after, expect)) return true
            if (after.sameScreen(before)) return false
        }
        return false
    }

    /** Re-perceives after each settle until [probe] answers or [timeoutMs] passes; [first] is tried before capturing. */
    private fun <T : Any> waitFor(timeoutMs: Long, first: Frame? = null, probe: (Frame) -> T?): T? {
        val deadline = device.nowMs() + timeoutMs
        var frame = first
        var seen: Frame? = null
        while (true) {
            val now = frame ?: capture()
            frame = null
            // The same screen gives the same answer: skip it rather than run OCR again (~3 s).
            val unchanged = seen != null && now.packageName == seen.packageName && now.toasts == seen.toasts && now.sameScreen(seen)
            if (!unchanged) {
                seen = now
                lastSeen = now
                probe(now)?.let { return it }
            }
            val left = deadline - device.nowMs()
            if (left <= 0) return null
            device.awaitSettle(left)
        }
    }

    private fun capture() = device.capture().also { it.focus = acted }

    /** Whether every finder of the step reads the screenshot (OCR, layout) rather than the tree. */
    private fun readsScreenshot(step: Step) =
        step.target!!.find.all { spec -> registry.finders[spec.keys.first()]?.needs?.isNotEmpty() == true }

    private fun findTarget(step: Step, frame: Frame): IndexedValue<Target>? =
        step.target!!.find.withIndex().firstNotNullOfOrNull { (i, spec) ->
            registry.find(frame, spec)?.let { IndexedValue(i, it) }
        }

    private fun reason(where: String, step: Step, outcome: Outcome, detail: String?): String {
        val what = step.target?.intent ?: step.call ?: step.action
        val why = when (outcome) {
            Outcome.MISSING -> step.ifMissing ?: "找不到目标"
            Outcome.WRONG_APP -> "前台不是任务包允许操作的 App，已停止"
            Outcome.NO_EFFECT -> "操作后没有出现预期的界面"
            Outcome.ACTION_FAILED -> "操作没有执行成功"
            Outcome.SITE_AUTH -> "网站登录已过期，请重新登录"
            Outcome.SITE_DENIED -> "网站说没有权限"
            Outcome.SITE_BUSY -> "网站说请求太频繁，稍后再试"
            Outcome.SITE_DOWN -> "网站连不上或出错了"
            Outcome.SITE_UNKNOWN -> "发给网站后没收到回复，不知道成没成功，没有重发"
            Outcome.SITE_CHANGED -> "网站接口和任务包对不上，任务包要更新"
            Outcome.SITE_REFUSED -> "网站拒绝了这次请求"
            Outcome.SUCCESS -> "成功"
        }
        return "$where（$what）：$why" + (detail?.let { "：$it" } ?: "")
    }
}
