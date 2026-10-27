package com.qoder.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RelayEngine(val repo: AccountRepo) {
    private val rr = AtomicInteger(0)
    val recent = ArrayDeque<JSONObject>()

    fun usableFor(model: String?): List<Account> {
        val now = System.currentTimeMillis()
        return repo.accounts.filter { a ->
            a.enabled && a.token.isNotEmpty() && a.cooldownUntil < now &&
                (model == null || a.serves(model, this))
        }
    }

    fun candidates(requestedModel: String?, pinnedAccountId: String?): Pair<List<Account>, String> {
        if (pinnedAccountId != null) {
            val a = repo.find(pinnedAccountId)
            if (a != null) return listOf(a) to resolveModel(a, requestedModel ?: "qfmodel")
        }
        val list = usableFor(null)
        val ordered = list.sortedBy { rr.getAndIncrement() % 1000000 }
        val resolved = requestedModel?.let { m ->
            ordered.firstOrNull { it.serves(m, this) }?.let { resolveModel(it, m) } ?: m
        } ?: "qfmodel"
        return ordered.filter { it.serves(resolved, this) || ordered.size == 1 } to resolved
    }

    private fun resolveModel(account: Account, model: String): String {
        val prefix = "qoder-"
        if (model.startsWith(prefix)) {
            val bare = model.removePrefix(prefix)
            account.models.firstOrNull { k -> k.contains(bare, true) }?.let { return it }
            account.discovered.firstOrNull { k -> k.contains(bare, true) }?.let { return it }
        }
        return model
    }

    fun ensureToken(a: Account): Boolean {
        if (a.token.isNotEmpty() && a.expiresAt > System.currentTimeMillis() + 60_000) return true
        val t = QoderClient.deviceRefresh(a) ?: return a.token.isNotEmpty()
        a.token = t.getString("token")
        a.refreshToken = t.optString("refreshToken").ifEmpty { a.refreshToken }
        a.expiresAt = t.getLong("expiresAt")
        a.status = "active"
        repo.save()
        return true
    }

    data class RelayResult(val accountId: String, val model: String)

    fun logEvent(e: JSONObject) {
        synchronized(recent) {
            recent.addFirst(e)
            while (recent.size > 120) recent.removeLast()
        }
    }

    fun buildGatewayPayload(account: Account, modelKey: String, openai: JSONObject, requestId: String): JSONObject {
        val sessionId = openai.optString("relay_session_id")
            .ifEmpty { UUID.randomUUID().toString() }
        val messages = openai.optJSONArray("messages") ?: JSONArray()
        val meta = JSONObject()
            .put("request_id", requestId)
            .put("request_set_id", openai.optString("request_set_id", requestId))
            .put("session_id", sessionId)
            .put("client_type", "cli")
        val out = JSONObject()
            .put("model", modelKey)
            .put("messages", messages)
            .put("stream", if (openai.optBoolean("stream", true)) true else true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("metadata", JSONObject().put("context", meta))
        openai.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { out.put("tools", it) }
        openai.optJSONArray("stop")?.let { out.put("stop", it) }
        if (openai.has("max_tokens")) out.put("max_tokens", openai.getInt("max_tokens"))
        if (openai.has("temperature")) out.put("temperature", openai.getDouble("temperature"))
        val eff = openai.optString("reasoning_effort").ifEmpty { openai.getJSONObjectOrNull("instructions")?.optString("reasoning_effort") ?: "" }
        if (eff.isNotEmpty()) out.put("reasoning_effort", eff)
        return out
    }

    private fun JSONObject.getJSONObjectOrNull(k: String): JSONObject? = opt(k) as? JSONObject
}

fun Account.serves(model: String?, engine: RelayEngine): Boolean {
    if (model == null || model.isEmpty()) return true
    if (models.isEmpty()) return true            // no explicit allow-list
    val bare = model.removePrefix("qoder-")
    return models.any { it.equals(model, true) || it.contains(bare, true) || model.contains(it, true) }
}
