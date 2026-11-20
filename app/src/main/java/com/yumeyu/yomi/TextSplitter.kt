package com.yumeyu.yomi

/**
 * 文章を読み上げ用のチャンクに分ける。
 * 最初のチャンクだけ短めにして、声が出るまでの待ちを減らす。
 */
object TextSplitter {
    private val SENTENCE_END = Regex("(?<=[。！？!?\\n])")
    private val SOFT_BREAKS = charArrayOf('、', '，', ',', ' ', '　')

    fun split(text: String, firstMax: Int = 40, max: Int = 100): List<String> {
        val sentences = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in sentences) {
            for (piece in hardSplit(sentence, max)) {
                val limit = if (out.isEmpty()) firstMax else max
                if (buf.isNotEmpty() && buf.length + piece.length > limit) {
                    out += buf.toString()
                    buf.clear()
                }
                buf.append(piece)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /** 句点の無い長文を、読点などの位置で max 文字以内に切る。 */
    private fun hardSplit(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val parts = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            val i = rest.lastIndexOfAny(SOFT_BREAKS, max - 1)
            val cut = if (i < max / 3) max else i + 1
            parts += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}
