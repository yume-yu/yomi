package com.yumeyu.yomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {
    @Test
    fun parsesTaggedDialogue() {
        val lines = Script.parseDialogue(
            """
            【A】今日はエーピーアイの話です。
            【B】えっ、それって何ですか？

            【A】ざっくり言うと、
            プログラム同士の窓口です。
            B：なるほど。
            """.trimIndent()
        )!!
        assertEquals(listOf('A', 'B', 'A', 'B'), lines.map { it.speaker })
        // タグの無い行は直前の発言の続き
        assertEquals("ざっくり言うと、プログラム同士の窓口です。", lines[2].text)
        assertEquals("なるほど。", lines[3].text)
    }

    @Test
    fun acceptsFullWidthTags() {
        val lines = Script.parseDialogue("【Ａ】こんにちは。\n【Ｂ】どうも。")!!
        assertEquals(listOf('A', 'B'), lines.map { it.speaker })
    }

    @Test
    fun plainTextIsNotDialogue() {
        assertNull(Script.parseDialogue("吾輩は猫である。\n名前はまだ無い。"))
    }

    @Test
    fun stripsThinking() {
        assertEquals("本文です。", Script.stripThinking("<think>考え中\nまだ</think>\n本文です。"))
        // 閉じていない <think> の後ろは隠す
        assertEquals("前半。", Script.stripThinking("前半。<think>途中"))
        assertTrue(Script.isThinking("<think>途中"))
        assertFalse(Script.isThinking("<think>済</think>本文"))
    }
}
