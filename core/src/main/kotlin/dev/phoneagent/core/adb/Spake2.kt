package dev.phoneagent.core.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, byte-for-byte as BoringSSL's spake25519 (crypto/curve25519/spake25519.cc,
 * Apache-2.0), which adbd uses for wireless-debugging pairing. Ours so that the adb client library
 * (Kadb) runs without its GPL-3.0 SPAKE2 dependency. Plain affine arithmetic on BigInteger: one pairing
 * needs four scalar multiplications, and none of it is secret-timing sensitive on the user's own phone.
 *
 * [alice] is the client (adb pair), the server is Bob. [processMessage] gives the 64-byte key both sides share.
 */
class Spake2(
    private val alice: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private var privateKey = BigInteger.ZERO
    private var passwordScalar = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var myMsg: ByteArray? = null

    /** Our message, P + h(password)·M (Alice) or ·N (Bob), where P is a fresh point. */
    fun generateMessage(password: ByteArray): ByteArray {
        check(myMsg == null) { "message already generated" }
        // A random scalar times the cofactor 8, which clears the cofactor of the peer's point later.
        privateKey = reduce(ByteArray(64).also(random::nextBytes)).shiftLeft(3)
        passwordHash = sha512(password)
        // BoringSSL forgot to multiply this by 8 and fixed it by adding multiples of the order until the
        // low three bits are clear: the same point in the prime-order subgroup. Done the same way here.
        var scalar = reduce(passwordHash)
        for (bit in 0..2) if (scalar.testBit(bit)) scalar = scalar.add(L.shiftLeft(bit))
        passwordScalar = scalar
        val mask = mul(passwordScalar, if (alice) M else N)
        return encode(add(mul(privateKey, BASE), mask)).also { myMsg = it }
    }

    /** The shared key from the peer's message, or null when it is not a point on the curve. */
    fun processMessage(theirMsg: ByteArray): ByteArray? {
        val mine = checkNotNull(myMsg) { "generate our message first" }
        if (theirMsg.size != 32) return null
        val masked = decode(theirMsg) ?: return null
        val peersMask = mul(passwordScalar, if (alice) N else M)
        val shared = encode(mul(privateKey, add(masked, negate(peersMask))))
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(data: ByteArray) {
            var n = data.size.toLong()
            repeat(8) { sha.update((n and 0xff).toByte()); n = n shr 8 }
            sha.update(data)
        }
        if (alice) {
            put(myName); put(theirName); put(mine); put(theirMsg)
        } else {
            put(theirName); put(myName); put(theirMsg); put(mine)
        }
        put(shared)
        put(passwordHash)
        return sha.digest()
    }

    internal class Point(val x: BigInteger, val y: BigInteger)

    internal companion object {
        val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
        val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        private val SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE)

        val BASE = Point(
            BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        )

        // BoringSSL's fixed points, from hashing "edwards25519 point generation seed (M)" / "(N)".
        val M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        val N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        fun add(a: Point, b: Point): Point {
            val xx = a.x.multiply(b.x)
            val yy = a.y.multiply(b.y)
            val dxy = D.multiply(xx).multiply(yy).mod(P)
            val x = a.x.multiply(b.y).add(a.y.multiply(b.x)).multiply(BigInteger.ONE.add(dxy).modInverse(P))
            val y = yy.add(xx).multiply(BigInteger.ONE.subtract(dxy).mod(P).modInverse(P))
            return Point(x.mod(P), y.mod(P))
        }

        fun negate(a: Point) = Point(P.subtract(a.x).mod(P), a.y)

        fun mul(k: BigInteger, a: Point): Point {
            var result = IDENTITY
            for (i in k.bitLength() - 1 downTo 0) {
                result = add(result, result)
                if (k.testBit(i)) result = add(result, a)
            }
            return result
        }

        /** 32 bytes: y little-endian, with x's low bit in the top bit. */
        fun encode(a: Point): ByteArray {
            val out = littleEndian(a.y, 32)
            if (a.x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun decode(bytes: ByteArray): Point? {
            val sign = bytes[31].toInt() and 0x80 != 0
            val y = fromLittleEndian(bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
            if (y >= P) return null
            val yy = y.multiply(y).mod(P)
            val u = yy.subtract(BigInteger.ONE).mod(P)
            val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
            val xx = u.multiply(v.modInverse(P)).mod(P)
            var x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
            if (x.multiply(x).mod(P) != xx) x = x.multiply(SQRT_M1).mod(P)
            if (x.multiply(x).mod(P) != xx) return null
            if (x.signum() == 0 && sign) return null
            if (x.testBit(0) != sign) x = P.subtract(x)
            return Point(x, y)
        }

        fun reduce(littleEndianBytes: ByteArray): BigInteger = fromLittleEndian(littleEndianBytes).mod(L)

        fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

        private fun fromLittleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

        private fun littleEndian(n: BigInteger, size: Int): ByteArray {
            val big = n.toByteArray()
            return ByteArray(size) { i -> if (i < big.size) big[big.size - 1 - i] else 0 }
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
