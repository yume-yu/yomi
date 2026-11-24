package com.yumeyu.yomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** 共有で受け取った文章。開いている最中に共有されても入力欄を差し替えられるよう、連番を付ける */
    private val shared = mutableStateOf(Shared(0, ""))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        receiveShared(intent)

        val settings = Settings(this)
        val store = EpisodeStore.get(this)
        setContent {
            YomiTheme {
                MainScreen(settings, store, shared.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveShared(intent)
    }

    private fun receiveShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        shared.value = Shared(shared.value.seq + 1, text)
    }
}

data class Shared(val seq: Int, val text: String)

@Composable
fun YomiTheme(content: @Composable () -> Unit) {
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

private const val CLIP_PREVIEW_MAX = 100
private const val TAB_INPUT = 0
private const val TAB_EPISODES = 1

@Composable
private fun MainScreen(settings: Settings, store: EpisodeStore, shared: Shared) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(shared.text) }
    var voice by rememberSaveable { mutableStateOf(settings.voice) }
    var server by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var model by rememberSaveable { mutableStateOf(settings.model) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(TAB_INPUT) }
    /** 開いているエピソードのページ。null なら一覧 */
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    // 画面の回転などで作り直されたときに上書きしないよう、処理済みの連番を覚えておく
    var sharedSeq by rememberSaveable { mutableStateOf(shared.seq) }
    LaunchedEffect(shared.seq) {
        if (shared.seq != sharedSeq) {
            sharedSeq = shared.seq
            text = shared.text
            tab = TAB_INPUT
            openId = null
        }
    }

    val episodes by store.episodes.collectAsState()
    val pending by store.pending.collectAsState()
    val playback by SpeakService.playback.collectAsState()

    val actions = remember(context, store) {
        EpisodeActions(context, store)
    }

    // LazyColumn は先頭に項目が増えても今見えている項目を保つので、新しいエピソードが画面外に隠れる。
    // 新しいエピソードが増えていたら、エピソードタブを開いたときに先頭までスクロールする
    val listState = rememberLazyListState()
    val newestId = episodes.firstOrNull()?.id
    var scrolledFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(newestId, tab, openId) {
        if (tab == TAB_EPISODES && openId == null && newestId != null && newestId != scrolledFor) {
            listState.scrollToItem(0)
            scrolledFor = newestId
        }
    }

    fun save() {
        settings.voice = voice
        settings.serverUrl = server
        settings.model = model
    }

    /** 入力欄の文章からエピソードを作り、作成を始める。play なら作れた所から再生もする */
    fun createEpisode(play: Boolean) {
        save()
        val episode = store.create(text, settings.model, settings.voice, settings.voiceA, settings.voiceB) ?: return
        GenerateService.enqueue(context, episode.id)
        if (play) SpeakService.playEpisode(context, episode.id)
        tab = TAB_EPISODES
        openId = null
    }

    val opened = openId?.let { id -> episodes.firstOrNull { it.id == id } }
    // 開いていたエピソードが削除されたら一覧に戻る
    LaunchedEffect(openId, opened) {
        if (openId != null && opened == null) openId = null
    }
    BackHandler(enabled = openId != null) { openId = null }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            if (opened != null) {
                EpisodeScreen(
                    episode = opened,
                    status = genStatus(opened, pending),
                    playback = playback?.takeIf { it.episodeId == opened.id },
                    actions = actions,
                    onBack = { openId = null },
                    onRestoreText = {
                        text = opened.text
                        tab = TAB_INPUT
                        openId = null
                    },
                )
                return@Column
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == TAB_INPUT, onClick = { tab = TAB_INPUT }, text = { Text("読み上げ") })
                Tab(
                    selected = tab == TAB_EPISODES,
                    onClick = { tab = TAB_EPISODES },
                    text = {
                        Text("エピソード（${episodes.size}）" + if (pending.isNotEmpty()) "・作成中" else "")
                    },
                )
            }
            when (tab) {
                TAB_INPUT -> InputTab(
                    settings = settings,
                    text = text,
                    onTextChange = { text = it },
                    voice = voice,
                    onVoiceChange = { voice = it },
                    onPresetVoice = { voice = it; settings.voice = it },
                    server = server,
                    onServerChange = { server = it },
                    model = model,
                    onModelChange = { model = it },
                    showSettings = showSettings,
                    onToggleSettings = { showSettings = !showSettings },
                    onSpeak = { createEpisode(play = true) },
                    onGenerateOnly = {
                        createEpisode(play = false)
                        Toast.makeText(context, "作成を始めました", Toast.LENGTH_SHORT).show()
                    },
                    onStop = { SpeakService.stop(context) },
                    onSaveSettings = {
                        save()
                        Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                    },
                    onClearCache = {
                        TtsClient.clearCache(context)
                        Toast.makeText(context, "キャッシュを削除しました", Toast.LENGTH_SHORT).show()
                    },
                )
                else -> EpisodesTab(
                    episodes = episodes,
                    pending = pending,
                    playback = playback,
                    listState = listState,
                    actions = actions,
                    onOpen = { openId = it.id },
                )
            }
        }
    }
}

@Composable
private fun InputTab(
    settings: Settings,
    text: String,
    onTextChange: (String) -> Unit,
    voice: String,
    onVoiceChange: (String) -> Unit,
    onPresetVoice: (String) -> Unit,
    server: String,
    onServerChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onSpeak: () -> Unit,
    onGenerateOnly: () -> Unit,
    onStop: () -> Unit,
    onSaveSettings: () -> Unit,
    onClearCache: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    /** 置き換えの確認中のクリップボードの文章。null なら確認ダイアログを出さない */
    var pendingClip by remember { mutableStateOf<String?>(null) }
    var showScript by remember { mutableStateOf(false) }

    if (showScript) {
        ScriptDialog(
            material = text,
            onDismiss = { showScript = false },
            onAccept = {
                onTextChange(it)
                showScript = false
            },
        )
    }

    pendingClip?.let { clip ->
        AlertDialog(
            onDismissRequest = { pendingClip = null },
            title = { Text("入力欄を置き換えますか？") },
            text = {
                Text(
                    "今の文章は消えて、次の文章になります。\n\n" +
                        clip.take(CLIP_PREVIEW_MAX) + if (clip.length > CLIP_PREVIEW_MAX) "…" else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { onTextChange(clip); pendingClip = null }) { Text("置き換える") }
            },
            dismissButton = {
                TextButton(onClick = { pendingClip = null }) { Text("やめる") }
            },
        )
    }
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = {
                    when {
                        settings.owuiKey.isBlank() ||
                            (settings.owuiModelMonologue.isBlank() && settings.owuiModelDialogue.isBlank()) ->
                            Toast.makeText(
                                context,
                                "設定で OpenWebUI の API キーとモデルを入れてください",
                                Toast.LENGTH_LONG,
                            ).show()
                        text.isBlank() ->
                            Toast.makeText(context, "入力欄に題材を入れてください", Toast.LENGTH_SHORT).show()
                        else -> showScript = true
                    }
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp),
            ) { Text("台本を作る", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = {
                    val clip = clipboard.getText()?.text
                    when {
                        clip.isNullOrBlank() ->
                            Toast.makeText(context, "クリップボードに文章がありません", Toast.LENGTH_SHORT).show()
                        // 入力欄が空なら消えるものがないので、確認せずに入れる
                        text.isBlank() -> onTextChange(clip)
                        else -> pendingClip = clip
                    }
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp),
            ) { Text("クリップボードで置き換え", style = MaterialTheme.typography.labelLarge) }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("読み上げる文章") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 360.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.PRESET_VOICES.forEach { name ->
                FilterChip(
                    selected = voice == name,
                    onClick = { onPresetVoice(name) },
                    label = { Text(name) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSpeak, enabled = text.isNotBlank()) { Text("読み上げる") }
            OutlinedButton(onClick = onGenerateOnly, enabled = text.isNotBlank()) { Text("作るだけ") }
            OutlinedButton(onClick = onStop) { Text("止める") }
        }

        TextButton(onClick = onToggleSettings) {
            Text(if (showSettings) "設定を閉じる" else "設定")
        }

        if (showSettings) {
            OutlinedTextField(
                value = server,
                onValueChange = onServerChange,
                label = { Text("サーバー") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                label = { Text("モデル（空欄ならサーバーの既定）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = voice,
                onValueChange = onVoiceChange,
                label = { Text("声（一覧にないものも指定できます）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSaveSettings) { Text("保存") }
                OutlinedButton(onClick = onClearCache) { Text("キャッシュを削除") }
            }
            OutlinedButton(onClick = { DictActivity.open(context) }) { Text("読み辞書を編集") }
            ScriptSettings(settings)
        }
    }
}

/** 台本の生成（OpenWebUI）と掛け合いの声の設定。ほかの設定とは別に保存する */
@Composable
private fun ScriptSettings(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(settings.owuiUrl) }
    var key by rememberSaveable { mutableStateOf(settings.owuiKey) }
    var modelMono by rememberSaveable { mutableStateOf(settings.owuiModelMonologue) }
    var modelDialogue by rememberSaveable { mutableStateOf(settings.owuiModelDialogue) }
    /** モデルの一覧から選んだものを入れる先 */
    var pickingFor by remember { mutableStateOf<Script.Mode?>(null) }
    var voiceA by rememberSaveable { mutableStateOf(settings.voiceA) }
    var voiceB by rememberSaveable { mutableStateOf(settings.voiceB) }
    var models by remember { mutableStateOf<List<OpenWebUiClient.Model>?>(null) }
    var loadingModels by remember { mutableStateOf(false) }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text("台本の生成（OpenWebUI）", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("OpenWebUI の URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API キー") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "プロンプトは OpenWebUI 側のカスタムモデルに設定してください（docs/openwebui-script-models.md）。",
        style = MaterialTheme.typography.bodySmall,
    )
    for ((mode, value) in listOf(Script.Mode.Monologue to modelMono, Script.Mode.Dialogue to modelDialogue)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { if (mode == Script.Mode.Dialogue) modelDialogue = it else modelMono = it },
                label = { Text("${mode.label}用のモデル") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    loadingModels = true
                    scope.launch {
                        try {
                            models = OpenWebUiClient(url, key).models()
                            pickingFor = mode
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, "モデルの一覧を取れませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        loadingModels = false
                    }
                },
                enabled = !loadingModels && key.isNotBlank(),
            ) { Text("一覧から") }
        }
    }
    Text("掛け合いの声", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = voiceA,
            onValueChange = { voiceA = it },
            label = { Text("A（案内役）") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = voiceB,
            onValueChange = { voiceB = it },
            label = { Text("B（聞き手）") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Button(onClick = {
        settings.owuiUrl = url
        settings.owuiKey = key
        settings.owuiModelMonologue = modelMono
        settings.owuiModelDialogue = modelDialogue
        settings.voiceA = voiceA
        settings.voiceB = voiceB
        Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
    }) { Text("台本の設定を保存") }

    models?.let { list ->
        AlertDialog(
            onDismissRequest = { models = null },
            title = { Text("${pickingFor?.label ?: ""}用のモデルを選ぶ") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(list) { m ->
                        Text(
                            if (m.name == m.id) m.id else "${m.name}（${m.id}）",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (pickingFor == Script.Mode.Dialogue) modelDialogue = m.id else modelMono = m.id
                                    models = null
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { models = null }) { Text("閉じる") } },
        )
    }
}
