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

    // ---- 台本の生成（OpenWebUI） ----

    var owuiUrl: String
        get() = prefs.getString("owui_url", DEFAULT_OWUI_URL) ?: DEFAULT_OWUI_URL
        set(v) = prefs.edit().putString("owui_url", v.trim()).apply()

    /** OpenWebUI の API キー。端末の中だけに置く */
    var owuiKey: String
        get() = prefs.getString("owui_key", "") ?: ""
        set(v) = prefs.edit().putString("owui_key", v.trim()).apply()

    /**
     * 台本を書かせるモデル。プロンプトは OpenWebUI 側のカスタムモデルに持たせる
     * （docs/openwebui-script-models.md）。一人語り用は、以前の owui_model をそのまま使う。
     */
    var owuiModelMonologue: String
        get() = prefs.getString("owui_model", "") ?: ""
        set(v) = prefs.edit().putString("owui_model", v.trim()).apply()

    var owuiModelDialogue: String
        get() = prefs.getString("owui_model_dialogue", "") ?: ""
        set(v) = prefs.edit().putString("owui_model_dialogue", v.trim()).apply()

    /**
     * 生成を頼むときに一緒に有効にするフィルター（OpenWebUI の関数の ID）。
     * Web UI のトグルで切り替えるフィルター（thinking など）は、Web UI がリクエストに入れているので、
     * API から使うときはここで指定する。カンマ区切りで保存する
     */
    var owuiFilterIds: List<String>
        get() = (prefs.getString("owui_filter_ids", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("owui_filter_ids", v.joinToString(",")).apply()

    /** 台本ができたら OpenWebUI のチャットを消すか。残せば、出典や考えた過程を Web UI で見返せる */
    var owuiDeleteChats: Boolean
        get() = prefs.getBoolean("owui_delete_chats", false)
        set(v) = prefs.edit().putBoolean("owui_delete_chats", v).apply()

    fun owuiModel(mode: Script.Mode): String =
        if (mode == Script.Mode.Dialogue) owuiModelDialogue else owuiModelMonologue

    // ---- 掛け合いの声 ----

    var voiceA: String
        get() = prefs.getString("voice_a", PRESET_VOICES[0]) ?: PRESET_VOICES[0]
        set(v) = prefs.edit().putString("voice_a", v.trim()).apply()

    var voiceB: String
        get() = prefs.getString("voice_b", PRESET_VOICES[1]) ?: PRESET_VOICES[1]
        set(v) = prefs.edit().putString("voice_b", v.trim()).apply()

    companion object {
        const val DEFAULT_OWUI_URL = "http://192.168.1.7:3000"
        const val DEFAULT_SERVER = "http://192.168.1.6:8090"
        // 空欄ならリクエストに model を含めず、サーバーの既定モデルを使う
        const val DEFAULT_MODEL = ""
        const val DEFAULT_VOICE = "kanna"
        val PRESET_VOICES = listOf("kanna", "girl")
    }
}
