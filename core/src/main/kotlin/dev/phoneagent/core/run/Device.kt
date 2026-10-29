package dev.phoneagent.core.run

/** Produces one [Frame] per round: the tree now, the screenshot and OCR when first asked for. */
fun interface Perception {
    fun capture(displayId: Int): Frame
}

/**
 * The phone as the runner sees it. `AgentService` implements it on Android; tests
 * use a scripted fake. Calls may block, so the runner never runs on the UI thread.
 * The device decides which display a run acts on (0, or background mode's virtual display); `capture()`
 * reads that one.
 */
interface Device : Perception {
    override fun capture(displayId: Int): Frame

    fun capture(): Frame = capture(0)

    fun launch(packageName: String): Boolean

    fun press(target: Target, long: Boolean): Boolean

    fun back(): Boolean

    /** Blocks until UI events have been quiet for a short window, or [timeoutMs] passes. */
    fun awaitSettle(timeoutMs: Long): Boolean

    fun nowMs(): Long
}
