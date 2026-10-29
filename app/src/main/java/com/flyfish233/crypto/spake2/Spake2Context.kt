package com.flyfish233.crypto.spake2

import dev.phoneagent.core.adb.Spake2

// Stands in for the GPL-3.0 spake2-java that Kadb's pairing calls (excluded in app/build.gradle.kts):
// same package, class names and signatures as Kadb's bytecode references, our own SPAKE2 behind them.

enum class Spake2Role { Alice, Bob }

class Spake2Context(role: Spake2Role, myName: ByteArray, theirName: ByteArray) {
    private val spake = Spake2(role == Spake2Role.Alice, myName, theirName)

    fun generateMessage(password: ByteArray): ByteArray = spake.generateMessage(password)

    fun processMessage(theirMsg: ByteArray?): ByteArray? = theirMsg?.let(spake::processMessage)

    fun destroy() = Unit
}
