package com.qoder.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Account(
    var id: String = UUID.randomUUID().toString(),
    var label: String = "",
    var region: String = "global",          // global | cn
    var kind: String = "device",            // device | pat
    var pat: String = "",                   // kept for PAT accounts so the job token can be re-exchanged
    var token: String = "",
    var refreshToken: String = "",
    var expiresAt: Long = 0,                // epoch ms
    var machineId: String = UUID.randomUUID().toString(),
    var uid: String = "",
    var encInfo: String = "",
    var cosyKey: String = "",
    var needsFields: Boolean = true,
    var enabled: Boolean = true,
    var discovered: MutableList<String> = mutableListOf(),
    var status: String = "unknown",         // active | auth_error | error | unknown
    var lastError: String = "",
    var inTok: Long = 0, var outTok: Long = 0,
    var reqs: Long = 0, var errs: Long = 0,
    var cooldownUntil: Long = 0
) {
    /** Serialises token refresh for this account (refresh tokens rotate — two parallel refreshes kill the account). */
    val authLock = Any()

    fun toJson() = JSONObject().apply {
        put("id", id); put("label", label); put("region", region); put("kind", kind); put("pat", pat)
        put("token", token); put("refreshToken", refreshToken); put("expiresAt", expiresAt)
        put("machineId", machineId); put("uid", uid); put("encInfo", encInfo); put("cosyKey", cosyKey)
        put("needsFields", needsFields); put("enabled", enabled)
        put("discovered", JSONArray(discovered))
        put("status", status); put("lastError", lastError)
        put("inTok", inTok); put("outTok", outTok); put("reqs", reqs); put("errs", errs)
    }

    companion object {
        fun fromJson(o: JSONObject) = Account().apply {
            id = o.optString("id", id); label = o.optString("label", "")
            region = o.optString("region", "global"); kind = o.optString("kind", "device")
            pat = o.optString("pat", "")
            token = o.optString("token", ""); refreshToken = o.optString("refreshToken", "")
            expiresAt = o.optLong("expiresAt", 0); machineId = o.optString("machineId", machineId)
            uid = o.optString("uid", ""); encInfo = o.optString("encInfo", "")
            cosyKey = o.optString("cosyKey", ""); needsFields = o.optBoolean("needsFields", true)
            enabled = o.optBoolean("enabled", true)
            status = o.optString("status", "unknown"); lastError = o.optString("lastError", "")
            inTok = o.optLong("inTok", 0); outTok = o.optLong("outTok", 0)
            reqs = o.optLong("reqs", 0); errs = o.optLong("errs", 0)
            o.optJSONArray("discovered")?.let { for (i in 0 until it.length()) discovered.add(it.getString(i)) }
        }
    }
}

/**
 * Account store. Thread-safe list (requests, logins and the UI touch it concurrently) and atomic,
 * encrypted writes — the old version could corrupt the file on concurrent saves and silently lose
 * every account on next start.
 */
class AccountRepo(ctx: Context) {
    private val file = File(ctx.filesDir, "accounts.enc")
    private val plain = File(ctx.filesDir, "accounts.json")
    private val key: SecretKey? = keystoreKey()
    private val ioLock = Any()
    val accounts: MutableList<Account> = CopyOnWriteArrayList()

    fun load() = synchronized(ioLock) {
        val src = try {
            when {
                key != null && file.exists() -> decrypt(file.readBytes())
                plain.exists() -> plain.readText()
                else -> return@synchronized
            }
        } catch (_: Exception) {
            // try the backup written before the last successful save
            try { File(file.path + ".bak").takeIf { it.exists() && key != null }?.let { decrypt(it.readBytes()) } }
            catch (_: Exception) { null }
        } ?: return@synchronized
        try {
            val arr = JSONArray(src)
            val list = (0 until arr.length()).map { Account.fromJson(arr.getJSONObject(it)) }
            accounts.clear(); accounts.addAll(list)
        } catch (_: Exception) { }
    }

    fun save() = synchronized(ioLock) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        try {
            val target = if (key != null) file else plain
            val bytes = if (key != null) encrypt(arr.toString()) else arr.toString().toByteArray()
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(bytes)
            if (target.exists()) target.copyTo(File(target.path + ".bak"), overwrite = true)
            if (!tmp.renameTo(target)) { target.writeBytes(bytes); tmp.delete() }
        } catch (_: Exception) { }
    }

    fun find(id: String) = accounts.firstOrNull { it.id == id || it.label == id }
    fun totalIn() = accounts.sumOf { it.inTok }
    fun totalOut() = accounts.sumOf { it.outTok }
    fun totalReqs() = accounts.sumOf { it.reqs }

    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("relay-acct", null) as? SecretKey) ?: run {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(KeyGenParameterSpec.Builder("relay-acct", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            kg.generateKey()
        }
    } catch (_: Exception) { null }

    private fun encrypt(text: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key!!)
        return c.iv + c.doFinal(text.toByteArray())
    }

    private fun decrypt(bytes: ByteArray): String {
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key!!, GCMParameterSpec(128, iv))
        return String(c.doFinal(body))
    }
}
