package dev.phoneagent.core

import dev.phoneagent.core.adb.Spake2
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class Spake2Test {
    private val client = "adb pair client\u0000".toByteArray()
    private val server = "adb pair server\u0000".toByteArray()

    private fun pair(clientPassword: String, serverPassword: String): Pair<ByteArray, ByteArray> {
        val alice = Spake2(alice = true, myName = client, theirName = server)
        val bob = Spake2(alice = false, myName = server, theirName = client)
        val a = alice.generateMessage(clientPassword.toByteArray())
        val b = bob.generateMessage(serverPassword.toByteArray())
        return alice.processMessage(b)!! to bob.processMessage(a)!!
    }

    @Test
    fun `both sides get the same key with the same password, different keys otherwise`() {
        val (a, b) = pair("123456", "123456")
        assertEquals(64, a.size)
        assertContentEquals(a, b)
        val (c, d) = pair("123456", "654321")
        assertFalse(c.contentEquals(d))
    }

    @Test
    fun `M and N are BoringSSL's points, generated from their seeds`() {
        // BoringSSL: x and y of each point, and the generator: sha256(seed), re-hashed until it decodes.
        assertEquals(BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"), Spake2.M.x)
        assertEquals(BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"), Spake2.M.y)
        assertEquals(BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"), Spake2.N.x)
        assertEquals(BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"), Spake2.N.y)
        for ((seed, point) in listOf("M" to Spake2.M, "N" to Spake2.N)) {
            val sha = MessageDigest.getInstance("SHA-256")
            var v = sha.digest("edwards25519 point generation seed ($seed)".toByteArray())
            var found = Spake2.decode(v)
            while (found == null) {
                v = sha.digest(v)
                found = Spake2.decode(v)
            }
            assertContentEquals(Spake2.encode(point), v)
        }
    }

    @Test
    fun `the base point times the order is the identity, and encoding round-trips`() {
        val id = Spake2.mul(Spake2.L, Spake2.BASE)
        assertEquals(BigInteger.ZERO, id.x)
        assertEquals(BigInteger.ONE, id.y)
        val p = Spake2.mul(BigInteger.valueOf(12345), Spake2.BASE)
        val q = Spake2.decode(Spake2.encode(p))!!
        assertEquals(p.x, q.x)
        assertEquals(p.y, q.y)
    }

    @Test
    fun `a peer message that is not a point gives no key`() {
        val alice = Spake2(alice = true, myName = client, theirName = server)
        alice.generateMessage("123456".toByteArray())
        // y = 2 has no x on edwards25519.
        assertNull(alice.processMessage(ByteArray(32).also { it[0] = 2 }))
    }
}
