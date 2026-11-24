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

    /**
     * 文章からエピソードを作る。行の頭に【A】【B】が付いた掛け合いの台本なら、
     * 発言ごとに段落を分け（話し手をまたいでまとめない）、A を [voiceA]、B を [voiceB] で読む。
     */
    fun create(text: String, model: String, voice: String, voiceA: String, voiceB: String): Episode? {
        val dialogue = Script.parseDialogue(text)
        val chunks = mutableListOf<String>()
        val chunkVoices = mutableListOf<String>()
        if (dialogue == null) {
            chunks += TextSplitter.split(text)
        } else {
            for (line in dialogue) {
                // 最初のチャンクだけ短くして、声が出るまでの待ちを減らす
                val parts = if (chunks.isEmpty()) TextSplitter.split(line.text)
                else TextSplitter.split(line.text, firstMax = 100)
                chunks += parts
                chunkVoices += List(parts.size) { if (line.speaker == 'B') voiceB else voiceA }
            }
        }
        if (chunks.isEmpty()) return null
        val episode = Episode(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            text = text,
            model = model,
            voice = if (dialogue == null) voice else voiceA,
            chunks = chunks,
            chunkVoices = chunkVoices,
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

    /**
     * 途中で落ちた場合に備え、ready を実際にあるファイルに合わせる。
     * ファイルサイズを持っていない（この項目を足す前に作った）エピソードは、ファイルから埋める。
     */
    private fun Episode.consistentWithFiles(): Episode {
        var n = 0
        while (n < ready && chunkFile(id, n).length() > 0) n++
        val sizes = if (sizesBytes.size >= n) sizesBytes.take(n) else List(n) { chunkFile(id, it).length() }
        return if (n == ready && sizes == sizesBytes) this
        else copy(ready = n, durationsMs = durationsMs.take(n), sizesBytes = sizes)
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
