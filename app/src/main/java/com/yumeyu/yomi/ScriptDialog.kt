package com.yumeyu.yomi

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 入力欄の文章を題材に、OpenWebUI で台本を作るよう頼む。
 *
 * 検索込みだと 5〜10 分かかるので、ここでは頼むだけ。待つのは [ScriptService]（通知に進み具合を出す）で、
 * できた台本は下書きとして読み上げタブに並ぶ。
 * 書き方のプロンプトは OpenWebUI 側のカスタムモデル（一人語り用・掛け合い用）に持たせてある。
 */
@Composable
fun ScriptDialog(
    material: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var mode by remember {
        mutableStateOf(
            if (settings.owuiModelMonologue.isBlank()) Script.Mode.Dialogue else Script.Mode.Monologue
        )
    }
    val model = settings.owuiModel(mode)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("台本を作る") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Script.Mode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                    }
                }
                if (model.isBlank()) {
                    Text(
                        "設定で${mode.label}用のモデルを選んでください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "入力欄の文章を題材に、$model で台本を書かせます。" +
                            "調べながら書くので、数分から 10 分ほどかかります。できたら通知でお知らせします。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (mode == Script.Mode.Dialogue) {
                    Text(
                        "A を ${settings.voiceA}、B を ${settings.voiceB} が読みます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    ScriptService.start(context, mode, material)
                    Toast.makeText(context, "台本の作成を始めました", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                enabled = model.isNotBlank(),
            ) { Text("作る") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("やめる") }
        },
    )
}
