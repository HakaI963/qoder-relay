package com.qoder.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Account(
    var id: String = UUID.randomUUID().toString(),
    var label: String = "",
    var region: String = "global",          // global | cn
    var kind: String = "device",            // device | pat
    var token: String = "",
    var refreshToken: String = "",
    var expiresAt: Long = 0,                // epoch ms
    var machineId: String = UUID.randomUUID().toString(),
    var uid: String = "",
    var encInfo: String = "",
    var cosyKey: String = "",
    var needsFields: Boolean = true,
    var enabled: Boolean = true,
    var modelMode: String = "universal",    // universal | per_account
    var models: MutableList<String> = mutableListOf(),   // model keys this account may serve (empty = all discovered)
    var discovered: MutableList<String> = mutableListOf(), // last fetched model list from account
    var universal: Boolean = true,          // participates in universal pool
    var status: String = "unknown",         // active | auth_error | error | unknown
    var lastError: String = "",
    var inTok: Long = 0, var outTok: Long = 0,
    var reqs: Long = 0, var errs: Long = 0, var failovers: Long = 0,
    var cooldownUntil: Long = 0
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("label", label); put("region", region); put("kind", kind)
        put("token", token); put("refreshToken", refreshToken); put("expiresAt", expiresAt)
        put("machineId", machineId); put("uid", uid); put("encInfo", encInfo); put("cosyKey", cosyKey)
        put("needsFields", needsFields); put("enabled", enabled); put("modelMode", modelMode)
        put("models", JSONArray(models)); put("discovered", JSONArray(discovered)); put("universal", universal)
        put("hasCosy", encInfo.isNotEmpty() && cosyKey.isNotEmpty())
        put("status", status); put("lastError", lastError)
        put("inTok", inTok); put("outTok", outTok); put("reqs", reqs); put("errs", errs)
        put("failovers", failovers); put("cooldownUntil", cooldownUntil)
    }
    fun publicJson() = JSONObject().apply {
        put("id", id); put("label", label); put("region", region); put("kind", kind)
        put("enabled", enabled); put("modelMode", modelMode); put("universal", universal)
        put("status", status); put("lastError", lastError); put("hasToken", token.isNotEmpty())
        put("expiresAt", expiresAt); put("inTok", inTok); put("outTok", outTok)
        put("reqs", reqs); put("errs", errs); put("failovers", failovers)
        put("cooldownUntil", cooldownUntil)
        put("models", JSONArray(models))
    }

    companion object {
        fun fromJson(o: JSONObject) = Account().apply {
            id = o.optString("id", id); label = o.optString("label", "")
            region = o.optString("region", "global"); kind = o.optString("kind", "device")
            token = o.optString("token", ""); refreshToken = o.optString("refreshToken", "")
            expiresAt = o.optLong("expiresAt", 0); machineId = o.optString("machineId", machineId)
            uid = o.optString("uid", ""); encInfo = o.optString("encInfo", "")
            cosyKey = o.optString("cosyKey", ""); needsFields = o.optBoolean("needsFields", true)
            enabled = o.optBoolean("enabled", true); modelMode = o.optString("modelMode", "universal")
            universal = o.optBoolean("universal", true); status = o.optString("status", "unknown")
            lastError = o.optString("lastError", "")
            inTok = o.optLong("inTok", 0); outTok = o.optLong("outTok", 0)
            reqs = o.optLong("reqs", 0); errs = o.optLong("errs", 0); failovers = o.optLong("failovers", 0)
            cooldownUntil = o.optLong("cooldownUntil", 0)
            val arr = o.optJSONArray("models")
            if (arr != null) for (i in 0 until arr.length()) models.add(arr.getString(i))
            val disc = o.optJSONArray("discovered")
            if (disc != null) for (i in 0 until disc.length()) discovered.add(disc.getString(i))
        }
    }
}

class AccountRepo(ctx: Context) {
    private val file = File(ctx.filesDir, "accounts.enc")
    private val plain = File(ctx.filesDir, "accounts.json")
    private val key: SecretKey? = keystoreKey()
    val accounts = mutableListOf<Account>()

    fun load() {
        try {
            val src = if (key != null && file.exists()) decrypt(file.readBytes())
                      else if (plain.exists()) plain.readText() else return
            val arr = JSONArray(src)
            accounts.clear()
            for (i in 0 until arr.length()) accounts.add(Account.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) { }
    }

    fun save() {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        try {
            if (key != null) file.writeBytes(encrypt(arr.toString()))
            else plain.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun find(id: String) = accounts.firstOrNull { it.id == id || it.label == id }
    fun totalIn() = accounts.sumOf { it.inTok }
    fun totalOut() = accounts.sumOf { it.outTok }

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
        val iv = c.iv
        return iv + c.doFinal(text.toByteArray())
    }

    private fun decrypt(bytes: ByteArray): String {
        val iv = bytes.copyOfRange(0, 12)
        val body = bytes.copyOfRange(12, bytes.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key!!, GCMParameterSpec(128, iv))
        return String(c.doFinal(body))
    }
}
