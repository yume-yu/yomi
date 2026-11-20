package com.yumeyu.yomi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * speech-proxy から Ogg Opus を取ってきて端末にキャッシュする。
 * 同じ model / voice / 文章ならキャッシュを返すので通信しない。
 */
class TtsClient(context: Context) {
    private val dir = cacheDir(context).apply { mkdirs() }

    suspend fun fetch(serverUrl: String, model: String, voice: String, text: String): File =
        withContext(Dispatchers.IO) {
            val key = sha256("$model|$voice|$text")
            val file = File(dir, "$key.ogg")
            if (file.length() > 0) return@withContext file

            val json = JSONObject()
            if (model.isNotBlank()) json.put("model", model)
            val body = json
                .put("voice", voice)
                .put("input", text)
                .put("response_format", "opus")
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(serverUrl.trimEnd('/') + "/v1/audio/speech")
                .post(body)
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.take(200).orEmpty()
                    throw IOException("サーバーが ${resp.code} を返しました: $detail")
                }
                val tmp = File(dir, "$key.tmp")
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (!tmp.renameTo(file)) throw IOException("キャッシュに保存できません")
            }
            file
        }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private fun cacheDir(context: Context) = File(context.cacheDir, "tts")

        fun clearCache(context: Context): Boolean = cacheDir(context).deleteRecursively()

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
