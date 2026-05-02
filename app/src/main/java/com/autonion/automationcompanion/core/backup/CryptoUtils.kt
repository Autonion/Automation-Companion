package com.autonion.automationcompanion.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption/decryption utilities for backup files.
 *
 * File format (when encrypted):
 * ```
 * [4 bytes] Magic header: "ATNB"
 * [1 byte]  Format version
 * [16 bytes] Salt (for PBKDF2)
 * [12 bytes] IV / Nonce (for AES-GCM)
 * [remaining] Encrypted data + GCM auth tag
 * ```
 */
object CryptoUtils {

    private val MAGIC_HEADER = byteArrayOf(0x41, 0x54, 0x4E, 0x42) // "ATNB" = Autonion Backup
    private const val FORMAT_VERSION: Byte = 1

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH = 256
    private const val PBKDF2_ITERATIONS = 120_000
    private const val GCM_TAG_LENGTH = 128

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Encrypts data from [input] and writes the encrypted output to [output].
     * The password is used to derive an AES-256 key via PBKDF2.
     */
    fun encrypt(input: InputStream, output: OutputStream, password: String) {
        val random = SecureRandom()

        // Generate salt and IV
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }

        // Derive key from password
        val key = deriveKey(password, salt)

        // Initialize cipher
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        // Write header
        output.write(MAGIC_HEADER)
        output.write(FORMAT_VERSION.toInt())
        output.write(salt)
        output.write(iv)

        // Encrypt and write data
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            val encrypted = cipher.update(buffer, 0, bytesRead)
            if (encrypted != null) output.write(encrypted)
        }

        // Finalize (includes GCM authentication tag)
        val finalBlock = cipher.doFinal()
        if (finalBlock != null) output.write(finalBlock)

        output.flush()
    }

    /**
     * Decrypts data from [input] and writes the plaintext to [output].
     * Returns true if decryption was successful, false if the password is wrong
     * or the file is corrupted.
     *
     * @throws WrongPasswordException if the password is incorrect
     * @throws InvalidBackupException if the file format is invalid
     */
    fun decrypt(input: InputStream, output: OutputStream, password: String) {
        // Read and validate header
        val header = ByteArray(4)
        if (input.read(header) != 4 || !header.contentEquals(MAGIC_HEADER)) {
            throw InvalidBackupException("Not a valid Autonion backup file")
        }

        val version = input.read()
        if (version != FORMAT_VERSION.toInt()) {
            throw InvalidBackupException("Unsupported backup format version: $version")
        }

        // Read salt and IV
        val salt = ByteArray(SALT_LENGTH)
        if (input.read(salt) != SALT_LENGTH) {
            throw InvalidBackupException("Backup file is corrupted (salt truncated)")
        }

        val iv = ByteArray(IV_LENGTH)
        if (input.read(iv) != IV_LENGTH) {
            throw InvalidBackupException("Backup file is corrupted (IV truncated)")
        }

        // Derive key from password
        val key = deriveKey(password, salt)

        // Initialize cipher
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        // Read all remaining encrypted data (GCM needs it all for auth tag verification)
        val encryptedData = input.readBytes()

        try {
            val decrypted = cipher.doFinal(encryptedData)
            output.write(decrypted)
            output.flush()
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPasswordException("Incorrect password or corrupted backup")
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPasswordException("Incorrect password or corrupted backup")
        }
    }

    /**
     * Checks if the given [input] stream starts with the encrypted backup header.
     * The stream is NOT reset — caller should handle re-opening if needed.
     */
    fun isEncrypted(header: ByteArray): Boolean {
        return header.size >= 4 &&
                header[0] == MAGIC_HEADER[0] &&
                header[1] == MAGIC_HEADER[1] &&
                header[2] == MAGIC_HEADER[2] &&
                header[3] == MAGIC_HEADER[3]
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
}

class WrongPasswordException(message: String) : Exception(message)
class InvalidBackupException(message: String) : Exception(message)
