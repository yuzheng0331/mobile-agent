package dev.phoneagent.app

import android.inputmethodservice.InputMethodService

/**
 * An input method with no keyboard that never reads or types text. Android schedules the
 * current input method's process like the app in front; on vivo that lifts our process from
 * cores 0–5 to all eight, and whole-screen OCR runs ~30% faster (docs/ocr-experiments.md 4.8).
 * [AgentService.useFastCores] selects it for OCR runs and gives the user's keyboard back after.
 */
class AgentIme : InputMethodService() {
    override fun onEvaluateInputViewShown() = false
}
