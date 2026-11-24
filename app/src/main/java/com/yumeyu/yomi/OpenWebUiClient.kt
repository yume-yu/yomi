package com.yumeyu.yomi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenWebUI の OpenAI 互換 API で台本を生成する。
 * API キーは OpenWebUI の「設定 → アカウント → API キー」で作ったもの。
 */
class OpenWebUiClient(baseUrl: String, private val apiKey: String) {
    private val base = baseUrl.trimEnd('/')

    data class Model(val id: String, val name: String)

    suspend fun models(): List<Model> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/models").header("Authorization", "Bearer $apiKey").build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(describe(resp.code, text))
            val a = JSONObject(text).getJSONArray("data")
            List(a.length()) { i ->
                val m = a.getJSONObject(i)
                Model(m.getString("id"), m.optString("name", m.getString("id")))
            }
        }
    }

    /**
     * 生成された文字を、届いたそばから流す。
     * 読み取りはブロックするので、キャンセルされたら通信ごと切って止める（awaitClose で call.cancel）。
     */
    fun stream(model: String, user: String): Flow<String> = callbackFlow {
        // システムプロンプトは送らない。カスタムモデルに設定したものを使わせる
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder()
            .url("$base/api/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = http.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) throw IOException(describe(resp.code, resp.body?.string().orEmpty()))
                        val source = resp.body!!.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            val delta = runCatching {
                                JSONObject(data).getJSONArray("choices").getJSONObject(0)
                                    .optJSONObject("delta")?.optString("content", "")
                            }.getOrNull()
                            if (!delta.isNullOrEmpty()) trySendBlocking(delta)
                        }
                    }
                    close()
                } catch (e: Exception) {
                    close(e)
                }
            }
        })
        awaitClose { call.cancel() }
    }

    private fun describe(code: Int, body: String): String = when (code) {
        401, 403 -> "API キーが通りません（$code）。設定の API キーを確かめてください"
        else -> "OpenWebUI が $code を返しました: ${body.take(200)}"
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        // セルフホストのモデルは最初の文字まで時間がかかることがあるので、読み取りは長めに待つ
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
}
