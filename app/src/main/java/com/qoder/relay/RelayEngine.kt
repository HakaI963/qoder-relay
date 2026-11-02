package com.qoder.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Account routing + Qoder wire-format builder. Tuning is fixed to sane values (no knobs to break). */
class RelayEngine(val repo: AccountRepo) {
    private val cursor = AtomicInteger(0)
    /** model key -> (display name, reasoning) */
    val modelInfo = ConcurrentHashMap<String, Pair<String, Boolean>>()

    val firstTokenMs = 300_000L    // max wait for upstream headers before trying the next account
    val cooldownMs = 15_000L       // how long a failing account sits out
    val overallMs = 600_000L       // total budget per client request
    val heartbeatMs = 8_000L       // SSE keep-alive comment interval
    val maxAttempts = 4

    /** Small rolling log shown on the home screen. */
    val recent = ArrayDeque<JSONObject>()

    fun logEvent(e: JSONObject) = synchronized(recent) {
        recent.addFirst(e)
        while (recent.size > 30) recent.removeLast()
    }

    fun recentCopy(): List<JSONObject> = synchronized(recent) { recent.toList() }

    /**
     * Ordered list of accounts to try. Round-robin over healthy accounts, then — instead of the old
     * hard "all accounts are cooling down" 403 — accounts in cooldown are appended as a last resort,
     * so one network blip can never lock the relay out for every client.
     */
    fun candidates(pinned: String?): List<Account> {
        val now = System.currentTimeMillis()
        if (pinned != null) repo.find(pinned)?.let { return listOf(it) }
        val usable = repo.accounts.filter { it.enabled && it.token.isNotEmpty() && it.status != "auth_error" }
            .ifEmpty { repo.accounts.filter { it.enabled && it.token.isNotEmpty() } }
        if (usable.isEmpty()) return emptyList()
        val start = Math.floorMod(cursor.getAndIncrement(), usable.size)
        val rotated = usable.indices.map { usable[(start + it) % usable.size] }
        val (healthy, cooling) = rotated.partition { it.cooldownUntil < now }
        return (healthy + cooling.sortedBy { it.cooldownUntil }).take(maxAttempts)
    }

    fun resolveModel(requested: String?): String {
        val m = requested?.trim().orEmpty()
        if (m.isEmpty()) return "auto"
        if (m in catalog()) return m
        val bare = m.removePrefix("qoder-")
        catalog().firstOrNull { it.equals(bare, true) }?.let { return it }
        catalog().firstOrNull { it.contains(bare, true) }?.let { return it }
        // unknown names (gpt-4o, claude-…, etc.) → let Qoder pick
        return "auto"
    }

    fun penalize(a: Account, err: String, code: Int) {
        a.errs++
        if (code == 401 || code == 403) { a.status = "auth_error"; a.needsFields = true } else a.status = "error"
        a.lastError = err.take(200)
        a.cooldownUntil = System.currentTimeMillis() + cooldownMs
    }

    fun catalog(): Set<String> {
        val s = linkedSetOf("auto")
        s.addAll(modelInfo.keys)
        repo.accounts.forEach { s.addAll(it.discovered) }
        if (s.size == 1) s.addAll(listOf("qfmodel", "dfmodel", "fast", "efficient", "ultimate"))
        return s
    }

    fun rememberModels(a: Account, entries: List<JSONObject>) {
        a.discovered = entries.map { it.optString("key") }.filter { it.isNotEmpty() }.toMutableList()
        entries.forEach { m ->
            val k = m.optString("key")
            if (k.isNotEmpty()) modelInfo[k] = (m.optString("display_name").ifEmpty { k }) to m.optBoolean("is_reasoning")
        }
    }

    private fun modelConfig(key: String): JSONObject {
        val info = modelInfo[key]
        val reasoning = info?.second ?: (key !in setOf("auto", "fast", "efficient") && !key.startsWith("qwork"))
        return JSONObject()
            .put("key", key).put("display_name", info?.first ?: key).put("model", "")
            .put("format", "openai").put("is_vl", true).put("is_reasoning", reasoning)
            .put("api_key", "").put("url", "").put("source", "system").put("max_input_tokens", 180000)
    }

    /** Build the exact wire body the Qoder CLI sends. */
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
                "system", "developer" -> textOf(content).takeIf { it.isNotEmpty() }?.let { systemParts.add(it) }
                "tool" -> conv.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", m.optString("tool_call_id")).put("content", textOf(content)))
                else -> {
                    val parts = JSONArray()
                    if (content is String) {
                        if (content.isNotEmpty()) parts.put(JSONObject().put("type", "text").put("text", content))
                    } else if (content is JSONArray) {
                        for (j in 0 until content.length()) {
                            val p = content.optJSONObject(j) ?: continue
                            if (p.optString("type") == "text") parts.put(JSONObject().put("type", "text").put("text", p.optString("text")))
                        }
                    }
                    if (role == "user") lastUserText = textOf(content)
                    val item = JSONObject().put("role", role).put("content", parts)
                    m.optJSONArray("tool_calls")?.let { item.put("tool_calls", it) }
                    m.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { item.put("reasoning_content", it) }
                    conv.put(item)
                }
            }
        }
        val cfg = modelConfig(modelKey)
        val effort = openai.optString("reasoning_effort").ifEmpty { "high" }
        val maxTok = openai.optInt("max_tokens", openai.optInt("max_completion_tokens", 32000))
        return JSONObject()
            .put("defaultReasoningEffort", effort)
            .put("request_id", requestId).put("request_set_id", requestId).put("chat_record_id", requestId)
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
            .put("is_reply", true).put("is_retry", false)
            .put("source", 1).put("version", "3")
            .put("agent_id", "agent_common").put("task_id", "common")
            .put("session_type", "cli").put("aliyun_user_type", "")
            .put("model_config", cfg)
            .put("custom_model", JSONObject.NULL)
            .put("system", systemParts.joinToString("\n\n"))
            .put("messages", conv)
            .put("tools", openai.optJSONArray("tools") ?: JSONArray())
            .put("parameters", JSONObject()
                .put("max_tokens", maxTok).put("reasoning_effort", effort).put("enable_thinking", true))
    }

    private fun textOf(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> (0 until v.length()).mapNotNull { i ->
            when (val p = v.opt(i)) {
                is JSONObject -> p.optString("text").takeIf { it.isNotEmpty() }
                is String -> p
                else -> null
            }
        }.joinToString("\n")
        else -> ""
    }
}
