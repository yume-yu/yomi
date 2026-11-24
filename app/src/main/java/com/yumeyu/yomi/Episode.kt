package com.yumeyu.yomi

import org.json.JSONArray
import org.json.JSONObject

/**
 * 保存しておく読み上げ 1 本分。入力の文章と、それを分けたチャンクの音声をセットで持つ。
 *
 * チャンクの音声は先頭から順に作るので、「先頭から何個できているか」（[ready]）だけで進み具合を表せる。
 */
data class Episode(
    val id: String,
    val createdAt: Long,
    val text: String,
    val model: String,
    val voice: String,
    val chunks: List<String>,
    /** 掛け合いのとき、チャンクごとの声。空なら全チャンク [voice] */
    val chunkVoices: List<String> = emptyList(),
    /** 先頭から作り終わったチャンクの数 */
    val ready: Int = 0,
    /** 作り終わったチャンクそれぞれの長さ（ミリ秒）。ready 個ぶん */
    val durationsMs: List<Long> = emptyList(),
    /** 作り終わったチャンクそれぞれのファイルサイズ（バイト）。ready 個ぶん */
    val sizesBytes: List<Long> = emptyList(),
    /** 最後に作ろうとして失敗したときの理由。作り直しを始めたら消す */
    val error: String? = null,
    /** 続きから再生するための位置 */
    val resumeIndex: Int = 0,
    val resumePositionMs: Long = 0,
) {
    val isComplete: Boolean get() = ready >= chunks.size

    fun voiceOf(index: Int): String = chunkVoices.getOrNull(index) ?: voice

    val isDialogue: Boolean get() = chunkVoices.distinct().size > 1

    /** 一覧などに出す声の名前。掛け合いなら「kanna・girl」 */
    val voicesLabel: String
        get() = if (isDialogue) chunkVoices.distinct().joinToString("・") else voice

    val title: String
        get() = text.lineSequence().map { it.trim().replace(SPEAKER_TAG, "") }.firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }
            ?: "（空）"

    val readyDurationMs: Long get() = durationsMs.sum()

    /** 作り終わった分の音声ファイルの合計サイズ */
    val readySizeBytes: Long get() = sizesBytes.sum()

    /** チャンク index の頭が、エピソード全体の何ミリ秒目にあたるか */
    fun chunkStartMs(index: Int): Long = durationsMs.take(index).sum()

    /** (チャンク, チャンク内の位置) → エピソード全体での位置 */
    fun globalPositionMs(index: Int, positionMs: Long): Long = chunkStartMs(index) + positionMs

    /** エピソード全体での位置 → (チャンク, チャンク内の位置)。作り終わったチャンクの範囲に収める */
    fun locate(globalMs: Long): Pair<Int, Long> {
        if (ready == 0) return 0 to 0L
        var rest = globalMs.coerceAtLeast(0)
        for (i in 0 until ready) {
            val d = durationsMs.getOrElse(i) { 0L }
            if (rest < d || i == ready - 1) return i to rest.coerceAtMost(d)
            rest -= d
        }
        return 0 to 0L
    }

    val resumeGlobalMs: Long get() = globalPositionMs(resumeIndex, resumePositionMs)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("createdAt", createdAt)
        .put("text", text)
        .put("model", model)
        .put("voice", voice)
        .put("chunks", JSONArray(chunks))
        .put("chunkVoices", JSONArray(chunkVoices))
        .put("ready", ready)
        .put("durationsMs", JSONArray(durationsMs))
        .put("sizesBytes", JSONArray(sizesBytes))
        .put("error", error ?: JSONObject.NULL)
        .put("resumeIndex", resumeIndex)
        .put("resumePositionMs", resumePositionMs)

    companion object {
        private const val TITLE_MAX = 40
        private val SPEAKER_TAG = Regex("""^(【\s*[ABＡＢ]\s*】|[ABＡＢ]\s*[:：])\s*""")

        fun fromJson(o: JSONObject): Episode {
            val chunks = o.getJSONArray("chunks").let { a -> List(a.length()) { a.getString(it) } }
            val durations = o.optJSONArray("durationsMs")
                ?.let { a -> List(a.length()) { a.getLong(it) } }
                .orEmpty()
            val chunkVoices = o.optJSONArray("chunkVoices")
                ?.let { a -> List(a.length()) { a.getString(it) } }
                .orEmpty()
            val sizes = o.optJSONArray("sizesBytes")
                ?.let { a -> List(a.length()) { a.getLong(it) } }
                .orEmpty()
            return Episode(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                text = o.getString("text"),
                model = o.optString("model"),
                voice = o.getString("voice"),
                chunks = chunks,
                chunkVoices = chunkVoices,
                ready = o.optInt("ready"),
                durationsMs = durations,
                sizesBytes = sizes,
                error = if (o.isNull("error")) null else o.optString("error"),
                resumeIndex = o.optInt("resumeIndex"),
                resumePositionMs = o.optLong("resumePositionMs"),
            )
        }
    }
}
