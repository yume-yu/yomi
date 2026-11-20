package com.yumeyu.yomi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * エピソードの保存場所。`filesDir/episodes/<id>/` に episode.json とチャンクの音声（0.ogg, 1.ogg…）を置く。
 *
 * cacheDir と違って OS に消されないので、あとから聞き直せる。
 * 画面・再生・合成が同じプロセスで動くので、一覧は StateFlow で配る。
 */
class EpisodeStore private constructor(context: Context) {
    private val root = File(context.filesDir, "episodes").apply { mkdirs() }
    private val lock = Any()

    private val _episodes = MutableStateFlow(loadAll())

    /** 新しい順 */
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _pending = MutableStateFlow<List<String>>(emptyList())

    /** 作成待ち・作成中のエピソード ID（先頭が作成中）。GenerateService が更新する */
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    fun get(id: String): Episode? = _episodes.value.firstOrNull { it.id == id }

    fun dir(id: String) = File(root, id)

    fun chunkFile(id: String, index: Int) = File(dir(id), "$index.ogg")

    fun create(text: String, model: String, voice: String): Episode? {
        val chunks = TextSplitter.split(text)
        if (chunks.isEmpty()) return null
        val episode = Episode(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            text = text,
            model = model,
            voice = voice,
            chunks = chunks,
        )
        dir(episode.id).mkdirs()
        save(episode)
        return episode
    }

    /** 書き換えて保存する。消されていたら何もしない */
    fun update(id: String, change: (Episode) -> Episode): Episode? = synchronized(lock) {
        val current = get(id) ?: return null
        val next = change(current)
        if (next != current) save(next)
        next
    }

    fun delete(id: String) = synchronized(lock) {
        dir(id).deleteRecursively()
        _episodes.update { list -> list.filterNot { it.id == id } }
    }

    internal fun setPending(ids: List<String>) {
        _pending.value = ids
    }

    private fun save(episode: Episode) = synchronized(lock) {
        val dir = dir(episode.id)
        if (!dir.isDirectory) return@synchronized
        val tmp = File(dir, "episode.json.tmp")
        tmp.writeText(episode.toJson().toString())
        tmp.renameTo(File(dir, "episode.json"))
        _episodes.update { list ->
            val rest = list.filterNot { it.id == episode.id }
            (rest + episode).sortedByDescending { it.createdAt }
        }
    }

    private fun loadAll(): List<Episode> =
        root.listFiles().orEmpty()
            .mapNotNull { dir ->
                runCatching {
                    Episode.fromJson(JSONObject(File(dir, "episode.json").readText()))
                }.getOrNull()
            }
            .map { it.consistentWithFiles() }
            .sortedByDescending { it.createdAt }

    /** 途中で落ちた場合に備え、ready を実際にあるファイルに合わせる */
    private fun Episode.consistentWithFiles(): Episode {
        var n = 0
        while (n < ready && chunkFile(id, n).length() > 0) n++
        return if (n == ready) this else copy(ready = n, durationsMs = durationsMs.take(n))
    }

    companion object {
        @Volatile
        private var instance: EpisodeStore? = null

        fun get(context: Context): EpisodeStore =
            instance ?: synchronized(this) {
                instance ?: EpisodeStore(context.applicationContext).also { instance = it }
            }
    }
}
