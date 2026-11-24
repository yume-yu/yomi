package com.yumeyu.yomi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenWebUI で台本を生成する。API キーは OpenWebUI の「設定 → アカウント → API キー」で作ったもの。
 *
 * `/api/chat/completions` を素のまま呼ぶと、OpenWebUI は Web 検索もツールの往復も回さない。
 * Web UI と同じく回してもらうには、OpenWebUI にチャットを作り、chat_id・返答の枠の id・session_id を
 * 付けて頼む。生成はサーバー側で続くので、終わるまで [activeTasks] を見に行き、[assistantMessage] で読む。
 * 参考: https://docs.openwebui.com/reference/server-side-tool-calling
 */
class OpenWebUiClient(baseUrl: String, private val apiKey: String) {
    private val base = baseUrl.trimEnd('/')

    data class Model(val id: String, val name: String)

    data class Message(val content: String, val done: Boolean, val sources: Int)

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        val a = get("/api/models").getJSONArray("data")
        List(a.length()) { i ->
            val m = a.getJSONObject(i)
            Model(m.getString("id"), m.optString("name", m.getString("id")))
        }
    }

    /** ユーザーのメッセージと、空の返答の枠を入れたチャットを作る。チャットの ID を返す */
    suspend fun createChat(
        model: String,
        title: String,
        prompt: String,
        userId: String,
        assistantId: String,
    ): String = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis() / 1000
        val messages = JSONObject()
            .put(
                userId,
                JSONObject()
                    .put("id", userId).put("parentId", JSONObject.NULL)
                    .put("childrenIds", JSONArray().put(assistantId))
                    .put("role", "user").put("content", prompt)
                    .put("timestamp", ts).put("models", JSONArray().put(model)),
            )
            .put(
                assistantId,
                JSONObject()
                    .put("id", assistantId).put("parentId", userId)
                    .put("childrenIds", JSONArray())
                    .put("role", "assistant").put("content", "")
                    .put("model", model).put("modelName", model)
                    .put("timestamp", ts).put("done", false),
            )
        val chat = JSONObject()
            .put("title", title)
            .put("models", JSONArray().put(model))
            .put("history", JSONObject().put("currentId", assistantId).put("messages", messages))
            .put("messages", JSONArray().put(JSONObject().put("id", userId).put("role", "user").put("content", prompt)))
        post("/api/v1/chats/new", JSONObject().put("chat", chat)).getString("id")
    }

    /**
     * 生成を頼む。すぐに返り、生成はサーバー側で続く。
     * features.web_search と variables（今日の日付など）は、Web UI が自分で付けているものなので、こちらでも付ける。
     */
    suspend fun startCompletion(
        model: String,
        prompt: String,
        chatId: String,
        userId: String,
        assistantId: String,
        sessionId: String,
        filterIds: List<String>,
        variables: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        val vars = JSONObject()
        variables.forEach { (k, v) -> vars.put(k, v) }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("chat_id", chatId)
            .put("id", assistantId)
            .put("session_id", sessionId)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put(
                "user_message",
                JSONObject().put("id", userId).put("parentId", JSONObject.NULL)
                    .put("role", "user").put("content", prompt),
            )
            .put("features", JSONObject().put("web_search", true))
            .put("variables", vars)
            .put(
                "background_tasks",
                JSONObject().put("title_generation", false).put("tags_generation", false)
                    .put("follow_up_generation", false),
            )
        if (filterIds.isNotEmpty()) body.put("filter_ids", JSONArray(filterIds))
        val r = post("/api/chat/completions", body)
        if (!r.optBoolean("status", false)) throw IOException("生成を始められませんでした: ${r.toString().take(200)}")
    }

    /** そのチャットで動いている生成のタスク。空になったら終わり */
    suspend fun activeTasks(chatId: String): List<String> = withContext(Dispatchers.IO) {
        val a = get("/api/tasks/chat/$chatId").optJSONArray("task_ids") ?: JSONArray()
        List(a.length()) { a.getString(it) }
    }

    suspend fun assistantMessage(chatId: String, assistantId: String): Message = withContext(Dispatchers.IO) {
        val m = get("/api/v1/chats/$chatId").getJSONObject("chat")
            .getJSONObject("history").getJSONObject("messages").optJSONObject(assistantId)
            ?: return@withContext Message("", false, 0)
        Message(
            content = m.optString("content", ""),
            done = m.optBoolean("done", false),
            sources = m.optJSONArray("sources")?.length() ?: 0,
        )
    }

    /** 生成を止める（Web UI の停止ボタンと同じもの。ドキュメントには無いので、失敗しても気にしない） */
    suspend fun stopTask(taskId: String) = withContext(Dispatchers.IO) {
        runCatching { post("/api/tasks/stop/$taskId", JSONObject()) }
    }

    suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request("/api/v1/chats/$chatId").delete().build()).execute().close()
        }
    }

    private fun request(path: String) = Request.Builder().url(base + path).header("Authorization", "Bearer $apiKey")

    private fun get(path: String): JSONObject = execute(request(path).build())

    private fun post(path: String, body: JSONObject): JSONObject =
        execute(request(path).post(body.toString().toRequestBody(JSON)).build())

    private fun execute(req: Request): JSONObject =
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(describe(resp.code, text))
            if (text.isBlank() || text == "null") JSONObject() else JSONObject(text)
        }

    private fun describe(code: Int, body: String): String = when (code) {
        401, 403 -> "API キーが通りません（$code）。設定の API キーを確かめてください"
        else -> "OpenWebUI が $code を返しました: ${body.take(200)}"
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
