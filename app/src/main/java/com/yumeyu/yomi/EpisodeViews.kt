package com.yumeyu.yomi

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

enum class GenStatus { Idle, Queued, Generating }

fun genStatus(episode: Episode, pending: List<String>): GenStatus = when {
    pending.firstOrNull() == episode.id -> GenStatus.Generating
    episode.id in pending -> GenStatus.Queued
    else -> GenStatus.Idle
}

/** 一覧とページで共通の操作 */
class EpisodeActions(private val context: Context, private val store: EpisodeStore) {
    fun play(episode: Episode) {
        // 途中までのものは、聞きながら続きを作る
        if (!episode.isComplete && episode.id !in store.pending.value) {
            GenerateService.enqueue(context, episode.id)
        }
        SpeakService.playEpisode(context, episode.id)
    }

    fun pause() = SpeakService.pause(context)

    /** エピソード全体での位置に移動する。再生していなければ「続きから」の位置を変える */
    fun seek(episode: Episode, globalMs: Long) {
        val (index, position) = episode.locate(globalMs)
        if (SpeakService.playback.value?.episodeId == episode.id) {
            SpeakService.seek(context, episode.id, index, position)
        } else {
            store.update(episode.id) { it.copy(resumeIndex = index, resumePositionMs = position) }
        }
    }

    /** その位置から再生する（スクリプトのタップ用） */
    fun playFrom(episode: Episode, globalMs: Long) {
        seek(episode, globalMs)
        val p = SpeakService.playback.value
        if (p?.episodeId != episode.id || !p.isPlaying) play(episode)
    }

    fun generate(episode: Episode) = GenerateService.enqueue(context, episode.id)

    fun cancel(episode: Episode) = GenerateService.cancel(context, episode.id)

    fun delete(episode: Episode) {
        if (SpeakService.playingEpisodeId.value == episode.id) SpeakService.stop(context)
        if (episode.id in store.pending.value) GenerateService.cancel(context, episode.id)
        store.delete(episode.id)
    }
}

// ---- 一覧 ----

@Composable
fun EpisodesTab(
    episodes: List<Episode>,
    pending: List<String>,
    playback: PlaybackInfo?,
    listState: LazyListState,
    actions: EpisodeActions,
    onOpen: (Episode) -> Unit,
) {
    if (episodes.isEmpty()) {
        Text(
            "「読み上げる」か「作るだけ」で作ったものがここに残ります。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeCard(
                episode = episode,
                status = genStatus(episode, pending),
                playback = playback?.takeIf { it.episodeId == episode.id },
                actions = actions,
                onOpen = { onOpen(episode) },
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    status: GenStatus,
    playback: PlaybackInfo?,
    actions: EpisodeActions,
    onOpen: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                (if (playback?.isPlaying == true) "▶ " else "") + episode.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(describe(episode, status), style = MaterialTheme.typography.bodySmall)
            EpisodeSeekBar(episode, playback, onSeek = null)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlayPauseButton(episode, playback, actions, primary = false)
                GenerateButton(episode, status, actions)
            }
        }
    }
}

// ---- エピソードのページ ----

@Composable
fun EpisodeScreen(
    episode: Episode,
    status: GenStatus,
    playback: PlaybackInfo?,
    actions: EpisodeActions,
    onBack: () -> Unit,
    onRestoreText: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 一覧") }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(episode.title, style = MaterialTheme.typography.titleLarge)
            }
            item {
                Text(describe(episode, status), style = MaterialTheme.typography.bodyMedium)
                episode.error?.let {
                    Text(
                        "直前の作成は失敗しました: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                EpisodeSeekBar(episode, playback, onSeek = { actions.seek(episode, it) })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlayPauseButton(episode, playback, actions, primary = true)
                    GenerateButton(episode, status, actions)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRestoreText) { Text("入力欄へ") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("削除") }
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("スクリプト", style = MaterialTheme.typography.titleMedium)
                Text(
                    "タップするとそこから再生します。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            itemsIndexed(episode.chunks) { index, chunk ->
                val ready = index < episode.ready
                val current = playback?.index == index
                Text(
                    chunk,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (current) Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            else Modifier
                        )
                        .then(
                            if (ready) Modifier.clickable {
                                actions.playFrom(episode, episode.chunkStartMs(index))
                            } else Modifier
                        )
                        .alpha(if (ready) 1f else 0.4f)
                        .padding(8.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("削除しますか？") },
            text = { Text(episode.title) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.delete(episode) }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }
}

// ---- 部品 ----

/**
 * エピソード全体を 1 本の時間軸にしたシークバー。
 * 再生中はその位置、再生していなければ「続きから」の位置を表示する。
 * 作成中は、できている分の長さが全体になる。
 *
 * [onSeek] が null なら動かせない（一覧のカード用。表示だけのバーにする）。
 */
// 太さを変えるためにトラックとつまみを差し替える Slider は、Material3 1.3 時点で実験的 API
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSeekBar(episode: Episode, playback: PlaybackInfo?, onSeek: ((Long) -> Unit)?) {
    val total = episode.readyDurationMs
    val current = (playback?.let { episode.globalPositionMs(it.index, it.positionMs) }
        ?: episode.resumeGlobalMs).coerceIn(0, total.coerceAtLeast(0))
    // ドラッグ中は再生位置の更新で動かされないよう、指の位置を優先する
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column {
        if (onSeek == null) {
            LinearProgressIndicator(
                progress = { if (total > 0) current.toFloat() / total else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(SEEK_TRACK_HEIGHT),
            )
        } else {
            val interactionSource = remember { MutableInteractionSource() }
            Slider(
                value = dragging ?: current.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..total.coerceAtLeast(1).toFloat(),
                enabled = total > 0,
                interactionSource = interactionSource,
                // 既定（トラック 16dp・つまみ 44dp）の半分の太さにする
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        enabled = total > 0,
                        thumbSize = DpSize(4.dp, SEEK_THUMB_HEIGHT),
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        enabled = total > 0,
                        modifier = Modifier.height(SEEK_TRACK_HEIGHT),
                    )
                },
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                formatClock(dragging?.toLong() ?: current),
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatClock(total) + if (episode.isComplete) "" else "（作成中の分まで）",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private val SEEK_TRACK_HEIGHT = 8.dp
private val SEEK_THUMB_HEIGHT = 22.dp

@Composable
private fun PlayPauseButton(
    episode: Episode,
    playback: PlaybackInfo?,
    actions: EpisodeActions,
    primary: Boolean,
) {
    val playing = playback?.isPlaying == true
    val label = when {
        playing -> "一時停止"
        playback != null -> "再生" // 一時停止中。その場から再開する
        episode.resumeIndex > 0 || episode.resumePositionMs > 0 -> "続きから"
        else -> "再生"
    }
    val onClick = { if (playing) actions.pause() else actions.play(episode) }
    if (primary) Button(onClick = onClick) { Text(label) }
    else TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun GenerateButton(episode: Episode, status: GenStatus, actions: EpisodeActions) {
    when {
        status != GenStatus.Idle -> TextButton(onClick = { actions.cancel(episode) }) { Text("作成をキャンセル") }
        !episode.isComplete -> TextButton(onClick = { actions.generate(episode) }) { Text("続きを作る") }
    }
}

private fun describe(episode: Episode, status: GenStatus): String {
    val date = DateFormat.format("M/d HH:mm", episode.createdAt)
    val progress = "${episode.ready}/${episode.chunks.size}"
    val state = when {
        status == GenStatus.Generating -> "作成中 $progress"
        status == GenStatus.Queued -> "作成待ち $progress"
        episode.error != null -> "失敗 $progress"
        !episode.isComplete -> "途中まで $progress"
        else -> formatDuration(episode.readyDurationMs)
    }
    return "$date · ${episode.voice} · $state"
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}分${s % 60}秒" else "${s}秒"
}

private fun formatClock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
