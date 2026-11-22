package com.gemini4studio.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("gemini4_secure_prefs", Context.MODE_PRIVATE)
    private val keyAlias = "gemini4_api_key_aes"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun saveApiKey(apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove("enc_key").remove("enc_iv").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("enc_key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("enc_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun loadApiKey(): String {
        val enc = prefs.getString("enc_key", null) ?: return ""
        val iv = prefs.getString("enc_iv", null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
