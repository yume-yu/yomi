package com.yumeyu.yomi

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 段落の読みをその場で直す。辞書にルールを足して保存し、その段落の音声だけ作り直す。
 *
 * 辞書は proxy にあるので、直した読みは OpenWebUI や LiveKit の読み上げにも効く。
 */
@Composable
fun FixReadingDialog(
    episode: Episode,
    index: Int,
    onDismiss: () -> Unit,
    onFixed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chunk = episode.chunks[index]
    val client = remember { DictClient(Settings(context).serverUrl) }

    var snapshot by remember { mutableStateOf<DictClient.Snapshot?>(null) }
    var currentSpoken by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var pattern by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var newSpoken by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        snapshot = client.load()
        currentSpoken = client.preview(chunk)
    }

    LaunchedEffect(Unit) {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "辞書を読み込めませんでした: ${e.message}"
        }
    }

    // 入力のたびに問い合わせないよう、少し待ってから追加後の読みを確かめる
    LaunchedEffect(pattern, replacement, isRegex, snapshot) {
        newSpoken = null
        previewError = null
        val snap = snapshot ?: return@LaunchedEffect
        if (pattern.isBlank()) return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        try {
            newSpoken = client.previewRules(merge(snap.rules, newRule(pattern, replacement, isRegex)), chunk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            previewError = "確かめられませんでした（${e.message}）"
        }
    }

    fun save() {
        val snap = snapshot ?: return
        busy = true
        message = null
        scope.launch {
            try {
                val rules = merge(snap.rules, newRule(pattern, replacement, isRegex))
                when (val r = client.save(rules, snap.loadedAt)) {
                    DictClient.SaveResult.Saved -> {
                        message = "辞書に追加しました。音声を作り直しています…"
                        try {
                            regenerateChunk(context, EpisodeStore.get(context), episode, index)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("Yomi", "regenerate failed", e)
                            message = "辞書には追加しましたが、音声を作り直せませんでした: ${e.message}"
                            busy = false
                            return@launch
                        }
                        onFixed()
                        return@launch
                    }
                    DictClient.SaveResult.Conflict -> {
                        reload()
                        message = "辞書がほかの所で更新されていたので、読み込み直しました。もう一度押してください。"
                    }
                    is DictClient.SaveResult.Invalid -> {
                        message = "保存できませんでした: " + r.errors.joinToString(" / ")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Yomi", "fix reading failed", e)
                message = "失敗しました: ${e.message}"
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("読みを直す") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label("段落")
                Text(chunk, style = MaterialTheme.typography.bodyMedium)
                Label("今の辞書で合成に渡る文")
                Text(
                    loadError ?: currentSpoken ?: "読み込み中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("直したい表記") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("読み") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRegex, onCheckedChange = { isRegex = it }, enabled = !busy)
                    Text("正規表現として扱う", style = MaterialTheme.typography.bodyMedium)
                }
                if (pattern.isNotBlank()) {
                    Label("追加後に合成に渡る文")
                    Text(
                        previewError ?: newSpoken ?: "確かめています…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (previewError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (newSpoken != null && newSpoken == currentSpoken) {
                        Text(
                            "この段落には当てはまっていません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save() },
                enabled = !busy && snapshot != null && pattern.isNotBlank() && replacement.isNotEmpty(),
            ) { Text("辞書に追加して作り直す") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("やめる") }
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

private const val PREVIEW_DEBOUNCE_MS = 400L

private fun newRule(pattern: String, replacement: String, isRegex: Boolean) =
    DictClient.Rule(if (isRegex) "regex" else "literal", pattern, replacement)

/** 同じ種類・同じ表記のルールがあれば読みを書き換え、なければ末尾に足す */
private fun merge(rules: List<DictClient.Rule>, rule: DictClient.Rule): List<DictClient.Rule> {
    val i = rules.indexOfFirst { it.kind == rule.kind && it.pattern == rule.pattern }
    return if (i >= 0) rules.toMutableList().also { it[i] = rule } else rules + rule
}

/**
 * 作り終わったチャンクの音声を作り直して差し替える。
 * 一時ファイルに作ってから置き換えるので、失敗しても元の音声は残る。
 */
suspend fun regenerateChunk(context: Context, store: EpisodeStore, episode: Episode, index: Int) {
    require(index < episode.ready) { "まだ作られていない段落です" }
    val server = Settings(context).serverUrl
    val dictVersion = DictClient.version(server)
    val target = store.chunkFile(episode.id, index)
    val tmp = File(target.parentFile, "$index.ogg.new")
    withContext(Dispatchers.IO) { tmp.delete() }
    TtsClient(context).fetch(server, episode.model, episode.voiceOf(index), episode.chunks[index], tmp, dictVersion)
    val (duration, size) = withContext(Dispatchers.IO) {
        if (!tmp.renameTo(target)) throw IOException("音声を差し替えられません")
        GenerateService.probeDurationMs(target) to target.length()
    }
    store.update(episode.id) { e ->
        e.copy(
            durationsMs = e.durationsMs.replaced(index, duration),
            sizesBytes = e.sizesBytes.replaced(index, size),
        )
    }
}

/** index が範囲内なら置き換えたリストを、範囲外ならそのまま返す */
private fun List<Long>.replaced(index: Int, value: Long): List<Long> =
    if (index !in indices) this else toMutableList().also { it[index] = value }
