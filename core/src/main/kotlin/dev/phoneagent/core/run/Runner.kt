package dev.phoneagent.core.run

import dev.phoneagent.core.ext.Builtins
import dev.phoneagent.core.ext.Registry
import dev.phoneagent.core.pack.Pack
import dev.phoneagent.core.pack.Step
import kotlinx.serialization.json.JsonObject

private const val MAX_BACKS = 10
private const val LAUNCH_CHECK_MS = 1_500L
private const val USER_WAIT_MS = 20_000L

enum class Outcome { SUCCESS, MISSING, WRONG_APP, NO_EFFECT, ACTION_FAILED }

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

data class RunResult(val packId: String, val steps: List<StepTrace>, val reason: String?) {
    val ok: Boolean get() = reason == null
}

/**
 * Runs a validated pack step by step: find (cheapest finder first), act, wait until
 * the UI settles, check the expectation. "Still loading" waits without using up a
 * retry; "no effect" retries the action, but only if the target is still there.
 */
/** Hears about a run while it goes, so the UI can draw progress. Teach / skip / stop on stuck joins in M4. */
fun interface RunObserver {
    fun onStep(trace: StepTrace)

    /** A step failed and the run waits for the user to clear the screen (usually an app dialog). */
    fun onStuck(reason: String) {}
}

class Runner(
    private val device: Device,
    private val registry: Registry = Builtins.registry,
    private val observer: RunObserver = RunObserver {},
) {
    /**
     * The frame the last expectation held on. Nothing has acted since, so the next step's first
     * find looks at it instead of capturing again: with OCR that saves a whole recognition (~3 s).
     */
    private var settled: Frame? = null

    /** The last frame a find or check looked at. */
    private var lastSeen: Frame? = null

    fun run(pack: Pack): RunResult {
        val traces = mutableListOf<StepTrace>()
        for ((index, step) in pack.steps.withIndex()) {
            val screen = pack.steps.getOrNull(index - 1)?.expect
            var trace = runStep(index, step, pack.requires, screen)
            // Usually an app dialog (update, ad) is in the way: ask the user to close it, wait for
            // the screen to change, then try the step once more. Nothing is tapped meanwhile.
            // Missing on the screen the previous step confirmed: nothing is in the way, the target
            // just isn't there (e.g. no 复制 for a voice message), so stop now instead of waiting.
            val confirmed = trace.outcome == Outcome.MISSING && screen != null && lastSeen?.let { registry.holds(it, screen) } == true
            if (trace.outcome != Outcome.SUCCESS && trace.outcome != Outcome.ACTION_FAILED && !confirmed) {
                traces += trace
                observer.onStep(trace)
                observer.onStuck(reason(index, step, trace.outcome))
                val blocked = device.capture().freeze()
                if (waitFor(USER_WAIT_MS) { frame -> frame.takeIf { !it.sameScreen(blocked) } } == null) {
                    return RunResult(pack.id, traces, reason(index, step, trace.outcome))
                }
                device.awaitSettle(step.timeoutMs)
                trace = runStep(index, step, pack.requires, screen)
            }
            traces += trace
            observer.onStep(trace)
            if (trace.outcome != Outcome.SUCCESS) return RunResult(pack.id, traces, reason(index, step, trace.outcome))
        }
        return RunResult(pack.id, traces, null)
    }

    private fun runStep(index: Int, step: Step, requires: List<String>, screen: JsonObject?): StepTrace {
        val action = registry.actions.getValue(step.action)
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
                    return trace(if (device.capture().packageName in requires) Outcome.MISSING else Outcome.WRONG_APP)
                }
            }

            attempts++
            settled = null
            var start = device.nowMs()
            val done = action.perform(device, step, hit?.value)
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
            val frame = device.capture()
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
            val before = device.capture().freeze()
            if (before.packageName !in requires || !device.back()) return false
            device.awaitSettle(timeoutMs)
            val after = device.capture()
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
            val now = frame ?: device.capture()
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

    /** Whether every finder of the step reads the screenshot (OCR, layout) rather than the tree. */
    private fun readsScreenshot(step: Step) =
        step.target!!.find.all { spec -> registry.finders[spec.keys.first()]?.needs?.isNotEmpty() == true }

    private fun findTarget(step: Step, frame: Frame): IndexedValue<Target>? =
        step.target!!.find.withIndex().firstNotNullOfOrNull { (i, spec) ->
            registry.find(frame, spec)?.let { IndexedValue(i, it) }
        }

    private fun reason(index: Int, step: Step, outcome: Outcome): String {
        val what = step.target?.intent ?: step.action
        val why = when (outcome) {
            Outcome.MISSING -> step.ifMissing ?: "找不到目标"
            Outcome.WRONG_APP -> "前台不是任务包允许操作的 App，已停止"
            Outcome.NO_EFFECT -> "操作后没有出现预期的界面"
            Outcome.ACTION_FAILED -> "操作没有执行成功"
            Outcome.SUCCESS -> "成功"
        }
        return "第 ${index + 1} 步（$what）：$why"
    }
}
