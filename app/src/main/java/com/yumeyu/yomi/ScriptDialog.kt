package com.yumeyu.yomi

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 入力欄の文章を題材に、OpenWebUI で台本を作る。
 * 書き方のプロンプトは OpenWebUI 側のカスタムモデル（一人語り用・掛け合い用）に持たせてある。
 * できた台本は「入力欄に入れる」で入力欄を置き換える。
 *
 * 生成はこのダイアログが開いている間だけ動く。閉じたら通信も止める。
 */
@Composable
fun ScriptDialog(
    material: String,
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()

    var mode by remember {
        mutableStateOf(
            if (settings.owuiModelMonologue.isBlank()) Script.Mode.Dialogue else Script.Mode.Monologue
        )
    }
    val model = settings.owuiModel(mode)
    var raw by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val script = Script.stripThinking(raw)
    val scroll = rememberScrollState()
    // 生成中は、届いた所が見えるように下までスクロールする
    LaunchedEffect(script.length) {
        if (running) scroll.scrollTo(scroll.maxValue)
    }

    fun start() {
        raw = ""
        error = null
        done = false
        running = true
        job = scope.launch {
            try {
                OpenWebUiClient(settings.owuiUrl, settings.owuiKey)
                    .stream(model, Script.userMessage(material))
                    .collect { raw += it }
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Yomi", "script generation failed", e)
                error = "作れませんでした: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("台本を作る") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (model.isBlank()) "設定で${mode.label}用のモデルを選んでください。"
                    else "入力欄の文章を題材に、$model で台本を書かせます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Script.Mode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = { mode = m },
                            enabled = !running,
                            label = { Text(m.label) },
                        )
                    }
                }
                if (mode == Script.Mode.Dialogue) {
                    Text(
                        "A を ${settings.voiceA}、B を ${settings.voiceB} が読みます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (Script.isThinking(raw)) {
                        Text("考えています…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (script.isNotEmpty()) {
                    Text(script, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            when {
                running -> TextButton(onClick = { job?.cancel() }) { Text("止める") }
                done && script.isNotBlank() -> Row {
                    TextButton(onClick = { start() }, enabled = model.isNotBlank()) { Text("作り直す") }
                    TextButton(onClick = { onAccept(script.trim()) }) { Text("入力欄に入れる") }
                }
                else -> TextButton(onClick = { start() }, enabled = model.isNotBlank()) { Text("作る") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("閉じる") }
        },
    )
}
