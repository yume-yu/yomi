package com.yumeyu.yomi

/**
 * 台本まわり。掛け合いの台本の読み取りなど。
 * 台本を書かせるプロンプトは OpenWebUI 側のカスタムモデルにある（docs/openwebui-script-models.md）。
 *
 * 掛け合いの台本は、各行の頭に【A】か【B】を付けた形にする。
 * A と B の声は設定（voiceA / voiceB）で決める。
 */
object Script {
    enum class Mode(val label: String) { Monologue("一人語り"), Dialogue("掛け合い") }

    /**
     * 題材だけを送る。台本の書き方（システムプロンプト）は OpenWebUI 側のカスタムモデルに持たせる。
     * 掛け合い用のモデルは、各行の頭に【A】か【B】を付けて出力すること（[parseDialogue] が読む形）。
     */
    fun userMessage(material: String) = material

    /**
     * 推論型のモデルが出す <think>…</think> を取り除く。
     * 閉じていない <think> は、まだ考えている途中なので、そこから後ろを隠す。
     */
    fun stripThinking(text: String): String {
        val closed = THINK_BLOCK.replace(text, "")
        val open = closed.indexOf("<think>")
        return (if (open >= 0) closed.substring(0, open) else closed).trimStart()
    }

    /** まだ <think> の中にいるか（生成中の表示用） */
    fun isThinking(text: String): Boolean = THINK_BLOCK.replace(text, "").contains("<think>")

    private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>")

    /** 話し手ごとの発言 */
    data class Line(val speaker: Char, val text: String)

    private val TAG = Regex("""^\s*(?:【\s*([ABＡＢ])\s*】|([ABＡＢ])\s*[:：])\s*(.*)$""")

    /**
     * 掛け合いの台本として読めれば発言のリストを返す。タグの付いた行が無ければ null（一人語り）。
     * タグの無い行は、直前の話し手の発言の続きとして扱う。
     */
    fun parseDialogue(text: String): List<Line>? {
        val lines = mutableListOf<Line>()
        var tagged = false
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val m = TAG.find(raw)
            if (m != null) {
                tagged = true
                val speaker = normalize((m.groupValues[1] + m.groupValues[2]).first())
                val body = m.groupValues[3].trim()
                if (body.isNotEmpty()) lines += Line(speaker, body)
            } else {
                val last = lines.lastOrNull()
                if (last != null) lines[lines.lastIndex] = last.copy(text = last.text + raw.trim())
                else lines += Line('A', raw.trim())
            }
        }
        return if (tagged && lines.isNotEmpty()) lines else null
    }

    private fun normalize(c: Char) = when (c) {
        'Ａ' -> 'A'
        'Ｂ' -> 'B'
        else -> c
    }
}
