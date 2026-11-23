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
 * speech-proxy の読み辞書 API（/dict 以下）を呼ぶ。
 *
 * 辞書は OpenWebUI や LiveKit の読み上げにも効くので、保存は「読んだ版から変わっていなければ」
 * 書き込む（expected_loaded_at）。古い proxy はこのパラメータを無視して保存する。
 */
class DictClient(serverUrl: String) {
    private val base = serverUrl.trimEnd('/')

    data class Rule(val kind: String, val pattern: String, val replacement: String) {
        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind)
            .put("pattern", pattern)
            .put("replacement", replacement)
    }

    data class Snapshot(val rules: List<Rule>, val loadedAt: Double)

    sealed interface SaveResult {
        data object Saved : SaveResult
        /** 読んだ後に別の保存が入っていた */
        data object Conflict : SaveResult
        data class Invalid(val errors: List<String>) : SaveResult
    }

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        val o = getJson("/dict")
        val a = o.getJSONArray("rules")
        val rules = List(a.length()) { i ->
            val r = a.getJSONObject(i)
            Rule(r.getString("kind"), r.getString("pattern"), r.getString("replacement"))
        }
        Snapshot(rules, o.getJSONObject("status").optDouble("loaded_at", 0.0))
    }

    suspend fun save(rules: List<Rule>, expectedLoadedAt: Double): SaveResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("rules", JSONArray(rules.map { it.toJson() }))
            .put("expected_loaded_at", expectedLoadedAt)
        http.newCall(post("/dict", body)).execute().use { resp ->
            val o = runCatching { JSONObject(resp.body!!.string()) }.getOrNull()
            when {
                resp.isSuccessful && o?.optBoolean("saved") == true -> SaveResult.Saved
                resp.code == 409 -> SaveResult.Conflict
                resp.code == 400 && o != null -> SaveResult.Invalid(
                    o.optJSONArray("errors")?.let { a -> List(a.length()) { a.getString(it) } }
                        ?: listOf(o.optString("detail", o.toString()))
                )
                else -> throw IOException("サーバーが ${resp.code} を返しました")
            }
        }
    }

    /** 今の辞書で、合成に渡る文（Markdown 除去 → 置換の結果） */
    suspend fun preview(text: String): String = withContext(Dispatchers.IO) {
        postJson("/dict/preview", JSONObject().put("text", text)).getString("after")
    }

    /** 保存前のルールで、合成に渡る文がどうなるか */
    suspend fun previewRules(rules: List<Rule>, text: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("rules", JSONArray(rules.map { it.toJson() })).put("text", text)
        postJson("/dict/preview-rules", body).getString("after")
    }

    private fun getJson(path: String): JSONObject =
        http.newCall(Request.Builder().url(base + path).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("サーバーが ${resp.code} を返しました")
            JSONObject(resp.body!!.string())
        }

    private fun postJson(path: String, body: JSONObject): JSONObject =
        http.newCall(post(path, body)).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw IOException("サーバーが ${resp.code} を返しました: ${text.take(200)}")
            JSONObject(text)
        }

    private fun post(path: String, body: JSONObject): Request =
        Request.Builder().url(base + path).post(body.toString().toRequestBody(JSON)).build()

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 辞書の版（最後に反映した時刻）。音声キャッシュのキーに含め、辞書を直したら作り直されるようにする。
         * 取れなければ空文字（キャッシュは辞書の版を区別しなくなるだけ）。
         */
        suspend fun version(serverUrl: String): String = withContext(Dispatchers.IO) {
            runCatching {
                DictClient(serverUrl).getJson("/dict/status")
                    .getJSONObject("dictionary").optDouble("loaded_at", 0.0).toString()
            }.getOrDefault("")
        }
    }
}
