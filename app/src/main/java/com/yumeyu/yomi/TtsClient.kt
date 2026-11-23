package com.yumeyu.yomi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resumeWithException
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

    /**
     * 音声を取得してファイルを返す。
     *
     * [dest] を省略するとキャッシュに保存する。指定した場合はそこに保存し、
     * キャッシュに同じ音声があればコピーして済ませる（通信しない）。
     *
     * [dictVersion] は proxy の読み辞書の版（DictClient.version）。キャッシュのキーに含めるので、
     * 辞書を直したあとは古い読みの音声を使わずに作り直す。
     */
    suspend fun fetch(
        serverUrl: String,
        model: String,
        voice: String,
        text: String,
        dest: File? = null,
        dictVersion: String = "",
    ): File =
        withContext(Dispatchers.IO) {
            val key = sha256("$model|$voice|$dictVersion|$text")
            val cached = File(dir, "$key.ogg")
            val file = dest ?: cached
            if (file.length() > 0) return@withContext file
            if (dest != null && cached.length() > 0) {
                // 一時ファイル経由にして、途中で落ちても壊れたファイルを完成品と見なさないようにする
                val tmp = File(dest.parentFile, "${dest.name}.tmp")
                val copied = runCatching { cached.copyTo(tmp, overwrite = true) }.isSuccess &&
                    tmp.renameTo(dest)
                if (copied) return@withContext dest
            }

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

            await(http.newCall(request)).use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string()?.take(200).orEmpty()
                    throw IOException("サーバーが ${resp.code} を返しました: $detail")
                }
                // 「キャッシュを削除」でフォルダごと消えていることがあるので、毎回作る。
                // エピソードのフォルダは作らない（削除済みのエピソードを復活させないため）
                if (dest == null) dir.mkdirs()
                val tmp = File(file.parentFile, "$key.tmp")
                resp.body!!.byteStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (!tmp.renameTo(file)) throw IOException("音声を保存できません: $file")
            }
            file
        }

    /** キャンセルされたら通信も打ち切る */
    private suspend fun await(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            })
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
