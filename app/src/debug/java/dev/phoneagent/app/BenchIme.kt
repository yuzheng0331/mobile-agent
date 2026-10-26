package dev.phoneagent.app

import android.inputmethodservice.InputMethodService

/**
 * Debug only, for the cores experiment: an input method with no keyboard. The system schedules
 * the current input method like the app in front (top-app cpuset, all cores); selected with
 * `adb shell ime set`, it tells whether that lifts our process too. It never types anything.
 */
class BenchIme : InputMethodService()
