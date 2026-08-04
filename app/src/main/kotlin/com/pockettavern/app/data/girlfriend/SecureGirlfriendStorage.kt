package com.pockettavern.app.data.girlfriend

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小女友本地数据的 AES-256-GCM 加密层。
 * 密钥由 AndroidKeyStore 持久化保存（硬件支持时绑硬件），不随明文暴露。
 * 数据格式：<12字节IV + 密文 + GCM tag>，落盘前 Base64 编码。
 */
object SecureGirlfriendStorage {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "pockettavern_girlfriend_key_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    fun readEncrypted(file: java.io.File): String? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val decoded = Base64.decode(file.readBytes(), Base64.DEFAULT)
            val iv = decoded.copyOfRange(0, IV_LENGTH)
            val ciphertext = decoded.copyOfRange(IV_LENGTH, decoded.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun writeEncrypted(file: java.io.File, plaintext: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
            file.writeBytes(Base64.encode(out, Base64.NO_WRAP))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}