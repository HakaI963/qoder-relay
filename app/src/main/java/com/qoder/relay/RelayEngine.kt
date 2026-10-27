package com.qoder.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RelayEngine(val repo: AccountRepo) {
    private val rr = AtomicInteger(0)
    val recent = ArrayDeque<JSONObject>()
    /** key -> display name + reasoning flag, filled from signed model listings. */
    val modelInfo = ConcurrentHashMap<String, Pair<String, Boolean>>()

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
        var resolved = requestedModel?.let { m ->
            ordered.firstOrNull { it.serves(m, this) }?.let { resolveModel(it, m) } ?: m
        } ?: "qfmodel"
        if (resolved !in catalog()) resolved = "qfmodel"
        val cands = ordered.filter { it.serves(resolved, this) }.ifEmpty { ordered }
        return cands to resolved
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

    fun catalog(): Set<String> {
        val s = HashSet(listOf("qfmodel", "dfmodel"))
        repo.accounts.forEach { s.addAll(it.discovered); s.addAll(it.models) }
        s.addAll(modelInfo.keys)
        return s
    }

    fun ensureToken(a: Account): Boolean {
        if (a.token.isNotEmpty() && a.expiresAt > System.currentTimeMillis() + 60_000) return true
        val t = QoderClient.deviceRefresh(a) ?: return a.token.isNotEmpty()
        a.token = t.getString("token")
        a.refreshToken = t.optString("refreshToken").ifEmpty { a.refreshToken }
        a.expiresAt = t.getLong("expiresAt")
        a.needsFields = true
        a.status = "active"
        repo.save()
        return true
    }

    fun logEvent(e: JSONObject) {
        synchronized(recent) {
            recent.addFirst(e)
            while (recent.size > 120) recent.removeLast()
        }
    }

    private fun modelConfig(key: String): JSONObject {
        val info = modelInfo[key]
        val display = info?.first ?: key
        val reasoning = info?.second ?: (key !in setOf("auto", "fast", "efficient") && !key.startsWith("qwork"))
        return JSONObject()
            .put("key", key)
            .put("display_name", display)
            .put("model", "")
            .put("format", "openai")
            .put("is_vl", true)
            .put("is_reasoning", reasoning)
            .put("api_key", "")
            .put("url", "")
            .put("source", "system")
            .put("max_input_tokens", 180000)
    }

    /** Build the exact wire body the CLI sends (SL schema, verified live). */
    fun buildWire(modelKey: String, openai: JSONObject, requestId: String, sessionId: String): JSONObject {
        val messages = openai.optJSONArray("messages") ?: JSONArray()
        val systemParts = ArrayList<String>()
        val conv = JSONArray()
        var lastUserText = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.opt("content")
            when (role) {
                "system" -> {
                    val t = textOf(content)
                    if (t.isNotEmpty()) systemParts.add(t)
                }
                "tool" -> conv.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", m.optString("tool_call_id")).put("content", textOf(content)))
                else -> {
                    val parts = JSONArray()
                    if (content is String) {
                        parts.put(JSONObject().put("type", "text").put("text", content))
                        if (role == "user") lastUserText = content
                    } else if (content is JSONArray) {
                        for (j in 0 until content.length()) {
                            val p = content.optJSONObject(j) ?: continue
                            if (p.optString("type") == "text") parts.put(JSONObject().put("type", "text").put("text", p.optString("text")))
                        }
                        if (role == "user") lastUserText = textOf(content)
                    }
                    val item = JSONObject().put("role", role).put("content", parts)
                    m.optJSONArray("tool_calls")?.let { item.put("tool_calls", it) }
                    val rc = m.optString("reasoning_content")
                    if (rc.isNotEmpty()) item.put("reasoning_content", rc)
                    conv.put(item)
                }
            }
        }
        val cfg = modelConfig(modelKey)
        val effort = openai.optString("reasoning_effort").ifEmpty { "high" }
        val params = JSONObject()
            .put("max_tokens", openai.optInt("max_tokens", openai.optInt("max_completion_tokens", 32000)))
            .put("reasoning_effort", effort)
            .put("enable_thinking", true)
        return JSONObject()
            .put("defaultReasoningEffort", effort)
            .put("request_id", requestId)
            .put("request_set_id", requestId)
            .put("chat_record_id", requestId)
            .put("session_id", sessionId)
            .put("stream", true)
            .put("chat_task", "FREE_INPUT")
            .put("chat_context", JSONObject()
                .put("text", lastUserText)
                .put("features", JSONArray())
                .put("extra", JSONObject()
                    .put("context", JSONArray())
                    .put("modelConfig", JSONObject().put("key", modelKey).put("is_reasoning", cfg.optBoolean("is_reasoning")))
                    .put("originalContent", lastUserText))
                .put("chatPrompt", "")
                .put("imageUrls", JSONObject.NULL))
            .put("is_reply", true)
            .put("is_retry", false)
            .put("source", 1)
            .put("version", "3")
            .put("agent_id", "agent_common")
            .put("task_id", "common")
            .put("session_type", "cli")
            .put("aliyun_user_type", "")
            .put("model_config", cfg)
            .put("custom_model", JSONObject.NULL)
            .put("system", systemParts.joinToString("\n\n"))
            .put("messages", conv)
            .put("tools", openai.optJSONArray("tools") ?: JSONArray())
            .put("parameters", params)
    }

    private fun textOf(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> (0 until v.length()).joinToString("\n") { textOf(v.opt(it)) }
        else -> ""
    }
}

fun Account.serves(model: String?, engine: RelayEngine): Boolean {
    if (model == null || model.isEmpty()) return true
    if (models.isEmpty()) return true
    val bare = model.removePrefix("qoder-")
    return models.any { it.equals(model, true) || it.contains(bare, true) || model.contains(it, true) }
}
