package dev.phoneagent.core.run

/**
 * The phone as the runner sees it. `AgentService` implements it on Android; tests
 * use a scripted fake. Calls may block, so the runner never runs on the UI thread.
 * `displayId` stays 0 in v1 and is the hook for a later virtual-display mode.
 */
interface Device {
    fun capture(displayId: Int = 0): Frame

    fun launch(packageName: String): Boolean

    fun press(target: Target, long: Boolean): Boolean

    fun back(): Boolean

    /** Blocks until UI events have been quiet for a short window, or [timeoutMs] passes. */
    fun awaitSettle(timeoutMs: Long): Boolean

    fun nowMs(): Long
}
