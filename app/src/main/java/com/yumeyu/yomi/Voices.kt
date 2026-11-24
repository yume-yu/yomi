package com.yumeyu.yomi

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * サーバーで使える声の一覧。speech-proxy の `/v1/models`（speech.cpp の応答をそのまま中継）の
 * `speech.voices` から取る。最後に取れた一覧は端末に覚えておき、サーバーに届かないときもそれを出す。
 */
object Voices {
    private const val PREFS = "yomi_voices"
    private const val KEY = "voices"

    /** 参照音声なしで読む声。読み上げには使わないので一覧から外す */
    private val EXCLUDED = setOf("none")

    private val _list = MutableStateFlow<List<String>>(emptyList())

    /** 声の名前。まだ一度も取れていなければ、決め打ちの声を出す */
    val list: StateFlow<List<String>> = _list.asStateFlow()

    private var loaded = false

    fun init(context: Context) = synchronized(this) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _list.value = saved?.let { s -> runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: Settings.PRESET_VOICES
    }

    /** サーバーに聞き直す。model が空欄なら、一覧の先頭のモデル（サーバーの既定）の声を使う */
    suspend fun refresh(context: Context, serverUrl: String, model: String): Result<List<String>> = runCatching {
        init(context)
        val voices = withContext(Dispatchers.IO) { fetch(serverUrl, model) }
        if (voices.isEmpty()) throw IOException("声の一覧が空でした")
        _list.value = voices
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(voices).toString()).apply()
        voices
    }

    private fun fetch(serverUrl: String, model: String): List<String> {
        val req = Request.Builder().url(serverUrl.trimEnd('/') + "/v1/models").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("サーバーが ${resp.code} を返しました")
            resp.body?.string().orEmpty()
        }
        return parse(body, model)
    }

    /** `/v1/models` の応答から声の名前を取り出す */
    fun parse(json: String, model: String): List<String> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val models = List(data.length()) { data.getJSONObject(it) }
        val m = models.firstOrNull { it.optString("id") == model } ?: models.firstOrNull() ?: return emptyList()
        val speech = m.optJSONObject("speech") ?: return emptyList()
        val names = speech.optJSONArray("voices")?.let { a ->
            List(a.length()) { a.optJSONObject(it)?.optString("name").orEmpty() }
        } ?: run {
            // voices が無い版では、voice オプションの choices を使う
            val options = speech.optJSONArray("options") ?: JSONArray()
            List(options.length()) { options.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "voice" }
                ?.optJSONArray("choices")?.let { a -> List(a.length()) { a.getString(it) } }
                .orEmpty()
        }
        return names.filter { it.isNotBlank() && it !in EXCLUDED }.distinct()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}

/**
 * 声を選ぶチップの列。声が増えても高さが変わらないよう横にスクロールする。
 * 選んでいる声が一覧に無ければ（サーバーから消えたなど）、それとわかるように残す。
 */
@Composable
fun VoiceChips(
    voices: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        voices.forEach { name ->
            FilterChip(selected = selected == name, onClick = { onSelect(name) }, label = { Text(name) })
        }
        if (selected.isNotBlank() && selected !in voices) {
            FilterChip(selected = true, onClick = {}, label = { Text("$selected（一覧にない）") })
        }
    }
}
