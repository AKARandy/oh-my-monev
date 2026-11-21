package id.ohmymonev.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class SessionTokenStore internal constructor(
    context: Context,
    private val key: () -> SecretKey,
    private val resetKey: (() -> Unit)?,
) {
    constructor(context: Context) : this(context, ::sessionKey, ::deleteSessionKey)
    constructor(context: Context, key: () -> SecretKey) : this(context, key, null)

    private val preferences = context.getSharedPreferences("monev_session", Context.MODE_PRIVATE)

    fun signedOut(): Boolean = preferences.getBoolean("signed_out", false)

    fun read(): String? = runCatching {
        val encoded = preferences.getString("credential", null) ?: return null
        val data = JSONObject(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun save(token: String) {
        val cipher = try {
            initEncryptCipher()
        } catch (e: Exception) {
            if (resetKey != null && isRecoverableKeyError(e)) {
                resetKey.invoke()
                initEncryptCipher()
            } else {
                throw e
            }
        }
        val json = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        if (!preferences.edit().putString("credential", json.toString()).remove("signed_out").commit()) {
            throw IOException("Credential storage failed")
        }
    }

    private fun initEncryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key())
    }

    private fun isRecoverableKeyError(error: Throwable): Boolean =
        error is KeyPermanentlyInvalidatedException ||
            error is UnrecoverableKeyException ||
            error is InvalidKeyException ||
            error is KeyStoreException

    fun clear(signedOut: Boolean = false) {
        if (!preferences.edit().remove("credential").putBoolean("signed_out", signedOut).commit()) {
            throw IOException("Credential removal failed")
        }
    }
}

private fun deleteSessionKey() {
    runCatching {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias("monev-session")) store.deleteEntry("monev-session")
    }
}

private fun sessionKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey("monev-session", null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder("monev-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
    }.generateKey()
}
