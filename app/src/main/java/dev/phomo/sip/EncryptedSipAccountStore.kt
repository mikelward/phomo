package dev.phomo.sip

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [SipAccountStore] that encrypts the account at rest with an AES-256-GCM key
 * held in the Android Keystore, so the raw SIP password never touches disk in
 * the clear. The ciphertext and its per-save random IV are stored in the
 * `phomo_account` SharedPreferences file (`phomo_account.xml`), which
 * `res/xml/data_extraction_rules.xml` / `backup_rules.xml` exclude from cloud
 * backup and device transfer — so the credential can never be carried off the
 * device.
 *
 * The key is hardware-backed on devices with a StrongBox / TEE. No per-use user
 * authentication is required: registration happens on-demand at call time and
 * must not block on a biometric prompt (the account is still protected by the
 * Keystore and the device lock screen).
 *
 * The Keystore and Cipher paths cannot run under Robolectric — this class is
 * verified on a device / instrumented test, not by the JVM unit tests. The
 * serialization it wraps ([SipAccountCodec]) is unit-tested separately.
 */
class EncryptedSipAccountStore(context: Context) : SipAccountStore {

    private val appContext = context.applicationContext

    override fun save(account: SipAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(SipAccountCodec.encode(account).toByteArray(Charsets.UTF_8))
        // commit() (synchronous, returns success) rather than apply(): callers run
        // save() off the main thread, and the UI must not report "saved" until the
        // credential is durably on disk. apply() returns before the disk write, so
        // a storage-full or immediately-killed write would be reported as success.
        val committed = prefs().edit()
            .putString(KEY_IV, encodeBase64(cipher.iv))
            .putString(KEY_DATA, encodeBase64(ciphertext))
            .commit()
        if (!committed) throw IOException("Failed to persist the SIP account")
    }

    override fun load(): SipAccount? {
        val ivB64 = prefs().getString(KEY_IV, null) ?: return null
        val dataB64 = prefs().getString(KEY_DATA, null) ?: return null
        return try {
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decodeBase64(ivB64)))
            val plaintext = cipher.doFinal(decodeBase64(dataB64))
            SipAccountCodec.decode(String(plaintext, Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            // Key invalidated (e.g. lock screen removed), tampered ciphertext, etc.
            null
        } catch (_: IllegalArgumentException) {
            // Corrupt Base64 in the stored blob.
            null
        } catch (_: IOException) {
            // Keystore unavailable / unreadable (e.g. KeyStore.load I/O error).
            null
        }
    }

    override fun clear() {
        prefs().edit().clear().apply()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: GeneralSecurityException) {
            // Nothing to delete / keystore unavailable — the prefs are already cleared.
        } catch (_: IOException) {
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
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

    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val PREFS_NAME = "phomo_account"
        const val KEY_ALIAS = "phomo_account_key"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
