package com.yumeyu.yomi

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("yomi", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("server", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var voice: String
        get() = prefs.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(v) = prefs.edit().putString("voice", v.trim()).apply()

    companion object {
        const val DEFAULT_SERVER = "http://192.168.1.6:8090"
        // 空欄ならリクエストに model を含めず、サーバーの既定モデルを使う
        const val DEFAULT_MODEL = ""
        const val DEFAULT_VOICE = "kanna"
        val PRESET_VOICES = listOf("kanna", "girl")
    }
}
