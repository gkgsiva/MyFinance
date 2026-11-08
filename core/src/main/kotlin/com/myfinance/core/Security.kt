package com.myfinance.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** PIN storage for the app lock: only a salted PBKDF2 hash is kept, never the PIN. */
object PinHasher {
    private const val ITERATIONS = 120_000

    fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    /** Returns "iterations:saltHex:hashHex". */
    fun hash(pin: String, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "$ITERATIONS:${salt.hex()}:${derive(pin.toCharArray(), salt, ITERATIONS, 256).hex()}"
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 3) return false
        val iter = parts[0].toIntOrNull() ?: return false
        val salt = parts[1].unhex() ?: return false
        val expected = parts[2].unhex() ?: return false
        return MessageDigest.isEqual(derive(pin.toCharArray(), salt, iter, expected.size * 8), expected)
    }

    /** Lockout after repeated wrong PINs: 5 tries free, then 30s doubling up to 1 hour. */
    fun lockoutMillis(failedAttempts: Int): Long =
        if (failedAttempts < 5) 0 else minOf(30_000L shl minOf(failedAttempts - 5, 7), 3_600_000L)
}

class WrongPasswordException : Exception("Wrong password, or the backup file is damaged")
class NotABackupException : Exception("This is not a MyFinance backup file")

/**
 * Encrypted backups: the named files are gzipped and sealed with AES-256-GCM using a key derived from the
 * user's backup password (PBKDF2-HMAC-SHA256, random salt). Without the password the file is unreadable,
 * so it is safe to keep in Google Drive, email or a pen drive.
 *
 * Layout: "MFBK" | version(1) | iterations(int) | salt(16) | iv(12) | ciphertext+tag
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000
    const val MIN_PASSWORD = 8

    fun encrypt(files: Map<String, ByteArray>, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD) { "Use a password of at least $MIN_PASSWORD characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = SecretKeySpec(derive(password, salt, iterations, 256), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { it.write(MAGIC); it.writeByte(VERSION.toInt()); it.writeInt(iterations); it.write(salt); it.write(iv) }
        }.toByteArray()
        cipher.updateAAD(header)
        return header + cipher.doFinal(pack(files))
    }

    fun decrypt(data: ByteArray, password: CharArray): Map<String, ByteArray> {
        if (data.size < 4 + 1 + 4 + 16 + 12 + 16 || !data.copyOfRange(0, 4).contentEquals(MAGIC)) throw NotABackupException()
        val input = DataInputStream(ByteArrayInputStream(data))
        input.skipBytes(4)
        if (input.readByte() != VERSION) throw NotABackupException()
        val iterations = input.readInt()
        if (iterations !in 10_000..10_000_000) throw NotABackupException()
        val salt = ByteArray(16).also(input::readFully)
        val iv = ByteArray(12).also(input::readFully)
        val headerLen = 4 + 1 + 4 + 16 + 12
        val key = SecretKeySpec(derive(password, salt, iterations, 256), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        cipher.updateAAD(data, 0, headerLen)
        val plain = try { cipher.doFinal(data, headerLen, data.size - headerLen) } catch (e: AEADBadTagException) { throw WrongPasswordException() }
        return unpack(plain)
    }

    private fun pack(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { b ->
        DataOutputStream(GZIPOutputStream(b)).use { out ->
            out.writeInt(files.size)
            for ((name, bytes) in files) { out.writeUTF(name); out.writeInt(bytes.size); out.write(bytes) }
        }
    }.toByteArray()

    private fun unpack(bytes: ByteArray): Map<String, ByteArray> {
        DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
            val n = input.readInt()
            val out = LinkedHashMap<String, ByteArray>()
            repeat(n) {
                val name = input.readUTF()
                val len = input.readInt()
                require(len in 0..(512 * 1024 * 1024)) { "Corrupt backup" }
                out[name] = ByteArray(len).also(input::readFully)
            }
            return out
        }
    }
}

internal fun derive(password: CharArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password, salt, iterations, bits)).encoded

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun String.unhex(): ByteArray? =
    if (length % 2 != 0) null else runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
