package com.yumeyu.yomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val shared = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        } else ""

        val settings = Settings(this)
        setContent {
            YomiTheme {
                MainScreen(settings, shared)
            }
        }
    }
}

@Composable
private fun YomiTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun MainScreen(settings: Settings, initialText: String) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    var voice by rememberSaveable { mutableStateOf(settings.voice) }
    var server by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    fun save() {
        settings.voice = voice
        settings.serverUrl = server
        settings.model = model
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("読み上げる文章") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Settings.PRESET_VOICES.forEach { name ->
                    FilterChip(
                        selected = voice == name,
                        onClick = { voice = name; settings.voice = name },
                        label = { Text(name) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        save()
                        SpeakService.speak(context, text)
                    },
                    enabled = text.isNotBlank(),
                ) { Text("読み上げる") }
                OutlinedButton(onClick = { SpeakService.stop(context) }) { Text("止める") }
                TextButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "設定を閉じる" else "設定")
                }
            }

            if (showSettings) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("サーバー") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("モデル（空欄ならサーバーの既定）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = voice,
                    onValueChange = { voice = it },
                    label = { Text("声（一覧にないものも指定できます）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        save()
                        Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                    OutlinedButton(onClick = {
                        TtsClient.clearCache(context)
                        Toast.makeText(context, "キャッシュを削除しました", Toast.LENGTH_SHORT).show()
                    }) { Text("キャッシュを削除") }
                }
            }
        }
    }
}
