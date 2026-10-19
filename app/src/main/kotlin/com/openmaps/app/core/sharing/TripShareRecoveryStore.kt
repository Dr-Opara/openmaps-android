package com.openmaps.app.core.sharing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Short-lived encrypted recovery for the private trip-share owner capability.
 *
 * The owner token remains private app data and is encrypted with a non-exportable Android Keystore
 * AES key. The record is cleared on stop/arrival/expiry and Android backups are disabled for the app.
 */
interface TripShareRecoveryStore {
    fun load(nowMillis: Long = System.currentTimeMillis()): TripShareSession?
    fun save(session: TripShareSession)
    fun clear()
}

object NoopTripShareRecoveryStore : TripShareRecoveryStore {
    override fun load(nowMillis: Long): TripShareSession? = null
    override fun save(session: TripShareSession) = Unit
    override fun clear() = Unit
}

class EncryptedTripShareRecoveryStore(context: Context) : TripShareRecoveryStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(nowMillis: Long): TripShareSession? {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) error("invalid encrypted trip-share record")
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            val session = TripShareSession(
                sessionId = json.getString("sessionId"),
                ownerToken = json.getString("ownerToken"),
                url = json.getString("url"),
                expiresAtMillis = json.getLong("expiresAtMillis"),
            )
            if (session.expiresAtMillis <= nowMillis) {
                clear()
                null
            } else {
                session
            }
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: TripShareSession) {
        runCatching {
            val plaintext = JSONObject()
                .put("sessionId", session.sessionId)
                .put("ownerToken", session.ownerToken)
                .put("url", session.url)
                .put("expiresAtMillis", session.expiresAtMillis)
                .toString()
                .toByteArray(Charsets.UTF_8)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plaintext)
            val packed = cipher.iv + encrypted
            prefs.edit().putString(
                KEY_SESSION,
                Base64.encodeToString(packed, Base64.NO_WRAP),
            ).apply()
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "openmaps_trip_share_recovery"
        const val KEY_SESSION = "encrypted_session"
        const val KEY_ALIAS = "openmaps_trip_share_owner_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
