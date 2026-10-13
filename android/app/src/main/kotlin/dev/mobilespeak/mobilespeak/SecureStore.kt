package dev.mobilespeak.mobilespeak

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class IdentityProfile(val id: String, val name: String, val value: JSONObject?)

internal class SecureStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("mobilespeak.secure", Context.MODE_PRIVATE)
    private var blocked = false
    private var data = JSONObject()

    init {
        val saved = preferences.getString("data", null)
        if (saved != null) {
            try {
                data = JSONObject(String(decrypt(Base64.decode(saved, Base64.NO_WRAP)), Charsets.UTF_8))
            } catch (_: Exception) {
                blocked = true
            }
        }
    }

    val readError: String?
        get() = if (blocked) context.localized(R.string.error_secure_store_read) else null

    @Synchronized
    fun bookmarks(): List<Bookmark> {
        check(!blocked) { context.localized(R.string.error_secure_data_read) }
        return data.optJSONArray("bookmarks").objects().map(Bookmark::from)
    }

    @Synchronized
    fun identities(): List<IdentityProfile> {
        check(!blocked) { context.localized(R.string.error_secure_data_read) }
        val array = data.optJSONArray("identities")
        if (array != null) return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IdentityProfile(id, item.optString("name", "Identity").ifBlank { "Identity" }, item.optJSONObject("value"))
        }
        return listOf(IdentityProfile("legacy", "Default", data.optJSONObject("identity")))
    }

    @Synchronized
    fun activeIdentityId(): String {
        val profiles = identities()
        return data.optString("activeIdentityId").takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id.orEmpty()
    }

    @Synchronized
    fun activeIdentityName(): String =
        identities().firstOrNull { it.id == activeIdentityId() }?.name ?: "Default"

    @Synchronized
    fun identity(): JSONObject? {
        check(!blocked) { context.localized(R.string.error_secure_data_read) }
        return identities().firstOrNull { it.id == activeIdentityId() }?.value
    }

    @Synchronized
    fun saveBookmarks(bookmarks: List<Bookmark>) =
        save(JSONObject(data.toString()).put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.json()) } }))

    @Synchronized
    fun saveIdentity(identity: JSONObject) {
        val active = activeIdentityId()
        val profiles = identities().map { profile -> if (profile.id == active) profile.copy(value = identity) else profile }
        saveProfiles(profiles, active)
    }

    @Synchronized
    fun createIdentity(name: String, value: JSONObject? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        saveProfiles(identities() + IdentityProfile(id, name.trim().ifBlank { "Identity" }, value), id)
        return id
    }

    @Synchronized
    fun activateIdentity(id: String) {
        check(identities().any { it.id == id }) { "Identity not found" }
        saveProfiles(identities(), id)
    }

    @Synchronized
    fun renameIdentity(id: String, name: String) {
        val profiles = identities().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        saveProfiles(profiles, activeIdentityId())
    }

    @Synchronized
    fun deleteIdentity(id: String) {
        var profiles = identities().filterNot { it.id == id }
        if (profiles.isEmpty()) profiles = listOf(IdentityProfile(java.util.UUID.randomUUID().toString(), "Default", null))
        val active = if (activeIdentityId() == id) profiles.first().id else activeIdentityId()
        saveProfiles(profiles, active)
    }

    private fun saveProfiles(profiles: List<IdentityProfile>, activeId: String) {
        val array = JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().put("id", profile.id).put("name", profile.name).put("value", profile.value ?: JSONObject.NULL))
            }
        }
        save(JSONObject(data.toString()).put("identities", array).put("activeIdentityId", activeId).also { it.remove("identity") })
    }

    @SuppressLint("UseKtx") // The platform commit() result is required to avoid reporting a failed write as saved.
    private fun save(next: JSONObject) {
        check(!blocked) { context.localized(R.string.error_secure_data_overwrite) }
        val encoded = Base64.encodeToString(encrypt(next.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString("data", encoded).commit()) { context.localized(R.string.error_secure_store_write) }
        data = next
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(value.copyOfRange(IV_SIZE, value.size))
    }

    private companion object {
        const val KEY_ALIAS = "dev.mobilespeak.mobilespeak.secrets"
        const val IV_SIZE = 12
    }
}
