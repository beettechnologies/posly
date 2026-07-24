package com.beettechnologies.posly.auth

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP (RFC 6238) service using HMAC-SHA1.
 * Secrets are stored as Base32-encoded strings (Google Authenticator compatible).
 */
object MfaService {

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val TIME_STEP_SECONDS = 30L
    private const val CODE_DIGITS = 6
    private val secureRandom = SecureRandom()

    fun generateSecret(): String {
        val bytes = ByteArray(20)
        secureRandom.nextBytes(bytes)
        return encodeBase32(bytes)
    }

    fun generateOtpAuthUri(secret: String, username: String, issuer: String = "Posly"): String =
        "otpauth://totp/$issuer:$username?secret=$secret&issuer=$issuer&algorithm=SHA1&digits=$CODE_DIGITS&period=$TIME_STEP_SECONDS"

    fun verifyCode(secret: String, code: String, windowSize: Int = 1): Boolean {
        val normalised = code.trim()
        if (normalised.length != CODE_DIGITS || !normalised.all { it.isDigit() }) return false
        val secretBytes = decodeBase32(secret)
        val counter = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS
        for (delta in -windowSize..windowSize) {
            if (generateHotp(secretBytes, counter + delta) == normalised) return true
        }
        return false
    }

    private fun generateHotp(secret: ByteArray, counter: Long): String {
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = (hash.last().toInt() and 0x0F)
        val truncated = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val otp = truncated % 10.0.pow(CODE_DIGITS).toInt()
        return otp.toString().padStart(CODE_DIGITS, '0')
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    fun decodeBase32(input: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (ch in input.uppercase()) {
            val index = BASE32_ALPHABET.indexOf(ch)
            if (index < 0) continue
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return bytes.toByteArray()
    }
}
