package com.yumeyu.yomi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 台本の作成まわりの保存先。
 *
 * - 作成中の仕事（[Job]）: OpenWebUI 側のチャットの ID など。アプリが落ちても、生成はサーバー側で
 *   続いているので、次に開いたときに続きを待てるよう保存しておく（同時に 1 つだけ）
 * - 下書き（[Draft]）: できた台本。入力欄に入れて確かめてから読み上げる
 */
class ScriptStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("yomi_script", Context.MODE_PRIVATE)
    private val draftDir = File(context.filesDir, "script_drafts").apply { mkdirs() }

    data class Job(
        val id: String,
        val mode: Script.Mode,
        val material: String,
        val model: String,
        val startedAt: Long,
        val userId: String,
        val assistantId: String,
        val chatId: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("mode", mode.name).put("material", material).put("model", model)
            .put("startedAt", startedAt).put("userId", userId).put("assistantId", assistantId)
            .put("chatId", chatId ?: JSONObject.NULL)

        companion object {
            fun new(mode: Script.Mode, material: String, model: String) = Job(
                id = UUID.randomUUID().toString(),
                mode = mode,
                material = material,
                model = model,
                startedAt = System.currentTimeMillis(),
                userId = UUID.randomUUID().toString(),
                assistantId = UUID.randomUUID().toString(),
            )

            fun fromJson(o: JSONObject) = Job(
                id = o.getString("id"),
                mode = Script.Mode.valueOf(o.getString("mode")),
                material = o.getString("material"),
                model = o.getString("model"),
                startedAt = o.getLong("startedAt"),
                userId = o.getString("userId"),
                assistantId = o.getString("assistantId"),
                chatId = if (o.isNull("chatId")) null else o.getString("chatId"),
            )
        }
    }

    data class Draft(
        val id: String,
        val createdAt: Long,
        val mode: Script.Mode,
        val script: String,
        val sources: Int,
        val chatId: String?,
    ) {
        val title: String
            get() = script.lineSequence().map { it.trim().replace(SPEAKER_TAG, "") }
                .firstOrNull { it.isNotEmpty() }?.take(40) ?: "（空）"

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("createdAt", createdAt).put("mode", mode.name)
            .put("script", script).put("sources", sources).put("chatId", chatId ?: JSONObject.NULL)

        companion object {
            private val SPEAKER_TAG = Regex("""^(【\s*[ABＡＢ]\s*】|[ABＡＢ]\s*[:：])\s*""")

            fun fromJson(o: JSONObject) = Draft(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                mode = Script.Mode.valueOf(o.getString("mode")),
                script = o.getString("script"),
                sources = o.optInt("sources"),
                chatId = if (o.isNull("chatId")) null else o.getString("chatId"),
            )
        }
    }

    // ---- 作成中の仕事 ----

    var job: Job?
        get() = prefs.getString("job", null)?.let { runCatching { Job.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) {
            prefs.edit().apply { if (v == null) remove("job") else putString("job", v.toJson().toString()) }.apply()
        }

    // ---- 下書き ----

    private val _drafts = MutableStateFlow(loadDrafts())

    /** 新しい順 */
    val drafts: StateFlow<List<Draft>> = _drafts.asStateFlow()

    fun addDraft(draft: Draft) = synchronized(this) {
        File(draftDir, "${draft.id}.json").writeText(draft.toJson().toString())
        _drafts.value = loadDrafts()
    }

    fun deleteDraft(id: String) = synchronized(this) {
        File(draftDir, "$id.json").delete()
        _drafts.value = loadDrafts()
    }

    private fun loadDrafts(): List<Draft> =
        draftDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { Draft.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    companion object {
        @Volatile
        private var instance: ScriptStore? = null

        fun get(context: Context): ScriptStore =
            instance ?: synchronized(this) {
                instance ?: ScriptStore(context.applicationContext).also { instance = it }
            }
    }
}
