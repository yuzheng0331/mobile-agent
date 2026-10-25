package dev.phoneagent.core.run

import dev.phoneagent.core.ext.Builtins
import dev.phoneagent.core.ext.Registry
import dev.phoneagent.core.pack.Pack
import dev.phoneagent.core.pack.Step
import kotlinx.serialization.json.JsonObject

private const val MAX_BACKS = 3
private const val LAUNCH_CHECK_MS = 1_500L

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
/** Hears about a run while it goes, so the UI can draw progress. `onStuck` (teach / skip / stop) joins in M4. */
fun interface RunObserver {
    fun onStep(trace: StepTrace)
}

class Runner(
    private val device: Device,
    private val registry: Registry = Builtins.registry,
    private val observer: RunObserver = RunObserver {},
) {

    fun run(pack: Pack): RunResult {
        val traces = mutableListOf<StepTrace>()
        for ((index, step) in pack.steps.withIndex()) {
            val trace = runStep(index, step, pack.requires)
            traces += trace
            observer.onStep(trace)
            if (trace.outcome != Outcome.SUCCESS) return RunResult(pack.id, traces, reason(index, step, trace.outcome))
        }
        return RunResult(pack.id, traces, null)
    }

    private fun runStep(index: Int, step: Step, requires: List<String>): StepTrace {
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
                hit = waitFor(step.timeoutMs) { frame -> if (frame.packageName in requires) findTarget(step, frame) else null }
                findMs += device.nowMs() - start
                if (hit == null) {
                    return trace(if (device.capture().packageName in requires) Outcome.MISSING else Outcome.WRONG_APP)
                }
            }

            attempts++
            var start = device.nowMs()
            val done = action.perform(device, step, hit?.value)
            actMs += device.nowMs() - start
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
            val met = waitFor(checkFor) { frame -> registry.holds(frame, expect).takeIf { it } } != null
            checkMs += device.nowMs() - start
            if (met) return trace(Outcome.SUCCESS)

            // A launch can resume the app mid-flow (e.g. inside a chat): press back toward its start screen.
            if (step.action == "launch") {
                start = device.nowMs()
                val back = backToStart(expect, requires, step.timeoutMs)
                actMs += device.nowMs() - start
                return trace(if (back) Outcome.SUCCESS else Outcome.NO_EFFECT)
            }

            // Look again before repeating, so a menu or long-press is never triggered twice.
            // Same place and same label: a positional finder (listItem) also hits on a different screen.
            val again = if (action.needsTarget) findTarget(step, device.capture())?.value else null
            val before = hit?.value
            val stillThere = again != null && before != null &&
                again.bounds == before.bounds && again.node?.label == before.node?.label
            if (attempts > step.maxRetry || !stillThere) return trace(Outcome.NO_EFFECT)
        }
    }

    /** Up to [MAX_BACKS] presses of back, each only while a required app is in front. */
    private fun backToStart(expect: JsonObject, requires: List<String>, timeoutMs: Long): Boolean {
        repeat(MAX_BACKS) {
            if (device.capture().packageName !in requires || !device.back()) return false
            device.awaitSettle(timeoutMs)
            if (registry.holds(device.capture(), expect)) return true
        }
        return false
    }

    /** Re-perceives after each settle until [probe] answers or [timeoutMs] passes. */
    private fun <T : Any> waitFor(timeoutMs: Long, probe: (Frame) -> T?): T? {
        val deadline = device.nowMs() + timeoutMs
        while (true) {
            probe(device.capture())?.let { return it }
            val left = deadline - device.nowMs()
            if (left <= 0) return null
            device.awaitSettle(left)
        }
    }

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
