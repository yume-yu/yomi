package com.yumeyu.yomi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 読み上げの再生。2 通りの使い方がある。
 *
 * - 使い捨て（文字選択メニューから）: チャンクを順に取得してプレイリストに積み、
 *   1 つ目が届いた時点で再生を始める。止めたら取得もやめる。
 * - エピソード（アプリの画面から）: 音声は GenerateService が作るので、ここは再生だけ。
 *   作り終わったチャンクから順にプレイリストに足し、再生位置をエピソードに保存する。
 *
 * どちらも再生中は通知から止められる。
 */
class SpeakService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var tts: TtsClient
    private lateinit var store: EpisodeStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** 再生中のエピソードと、そのうちプレイリストに積んだチャンクの数 */
    private var episodeId: String? = null
    private var appended = 0

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("Yomi", "playback failed", error)
            toast("再生できませんでした: ${error.errorCodeName}")
        }
    }

    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveProgress()

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveProgress()
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TtsClient(this)
        store = EpisodeStore.get(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        player.addListener(errorListener)
        player.addListener(progressListener)
        session = MediaSession.Builder(this, player).build()
        addSession(session)

        // 画面のシークバー用に、再生位置を定期的に配る
        scope.launch {
            while (true) {
                publishPlayback()
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    private fun publishPlayback() {
        val id = episodeId
        _playback.value = if (id == null || player.mediaItemCount == 0) {
            if (id == null) null else PlaybackInfo(id, 0, 0, false)
        } else {
            PlaybackInfo(id, player.currentMediaItemIndex, player.currentPosition, player.isPlaying)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> intent.getStringExtra(EXTRA_TEXT)?.let { speak(it) }
            ACTION_PLAY_EPISODE -> intent.getStringExtra(EXTRA_EPISODE_ID)?.let { playEpisode(it) }
            ACTION_PAUSE -> player.pause()
            ACTION_SEEK -> {
                val id = intent.getStringExtra(EXTRA_EPISODE_ID)
                if (id != null && id == episodeId) {
                    val index = intent.getIntExtra(EXTRA_INDEX, 0)
                    if (index in 0 until player.mediaItemCount) {
                        player.seekTo(index, intent.getLongExtra(EXTRA_POSITION, 0))
                        saveProgress()
                        publishPlayback()
                    }
                }
            }
            ACTION_STOP -> stopSpeaking()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ---- 使い捨て ----

    private fun speak(text: String) {
        val chunks = TextSplitter.split(text)
        if (chunks.isEmpty()) return
        stopSpeaking()

        val settings = Settings(this)
        val server = settings.serverUrl
        val model = settings.model
        val voice = settings.voice

        job = scope.launch {
            for (chunk in chunks) {
                val file = try {
                    tts.fetch(server, model, voice, chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Yomi", "fetch failed: $server", e)
                    toast("読み上げに失敗しました: ${e.message}")
                    _failures.update { it + 1 }
                    return@launch
                }
                enqueue(mediaItem(file, title = chunk, artist = voice))
            }
        }
    }

    // ---- エピソード ----

    private fun playEpisode(id: String) {
        // 一時停止中の同じエピソードなら、その場から再開する
        if (id == episodeId && player.mediaItemCount > 0 && player.playbackState != Player.STATE_ENDED) {
            player.play()
            return
        }
        stopSpeaking()
        val episode = store.get(id) ?: return
        episodeId = id
        _playingEpisodeId.value = id

        val items = (0 until episode.ready).map { episodeItem(episode, it) }
        appended = items.size
        if (items.isNotEmpty()) {
            val (index, position) = resumePoint(episode)
            player.setMediaItems(items, index, position)
            player.prepare()
            player.play()
        }

        // 作成中なら、できたチャンクから足していく。削除されたら止める
        if (!episode.isComplete) {
            job = scope.launch {
                store.episodes.collect { list ->
                    val e = list.firstOrNull { it.id == id }
                    if (e == null) {
                        stopSpeaking()
                        return@collect
                    }
                    while (appended < e.ready) {
                        enqueue(episodeItem(e, appended))
                        appended++
                    }
                    if (e.isComplete) job?.cancel()
                }
            }
        }
    }

    private fun resumePoint(episode: Episode): Pair<Int, Long> {
        val index = episode.resumeIndex
        // 保存位置がおかしければ頭から（聞き終わったときは 0 に戻してある）
        if (index !in 0 until episode.ready) return 0 to 0L
        return index to episode.resumePositionMs
    }

    private fun saveProgress() {
        val id = episodeId ?: return
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val ended = player.playbackState == Player.STATE_ENDED
        store.update(id) { e ->
            val finished = ended && e.isComplete && index == e.chunks.size - 1
            if (finished) e.copy(resumeIndex = 0, resumePositionMs = 0)
            else e.copy(resumeIndex = index, resumePositionMs = position)
        }
    }

    private fun episodeItem(episode: Episode, index: Int): MediaItem =
        mediaItem(
            store.chunkFile(episode.id, index),
            title = episode.title,
            artist = episode.chunks[index],
        ).buildUpon().setMediaId("${episode.id}#$index").build()

    // ---- 共通 ----

    private fun mediaItem(file: File, title: String, artist: String): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build()
            )
            .build()

    private fun enqueue(item: MediaItem) {
        player.addMediaItem(item)
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }
            // 取得が再生に追いつかず止まっていたら、追加した所から再開する
            Player.STATE_ENDED -> {
                player.seekTo(player.mediaItemCount - 1, 0)
                player.play()
            }
            else -> Unit
        }
    }

    private fun stopSpeaking() {
        saveProgress()
        job?.cancel()
        job = null
        episodeId = null
        appended = 0
        _playingEpisodeId.value = null
        _playback.value = null
        player.stop()
        player.clearMediaItems()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        saveProgress()
        _playingEpisodeId.value = null
        _playback.value = null
        scope.cancel()
        // 解放がタイムアウトすると ExoPlayer は再生エラーとして通知してくる。
        // 後片付け中のエラーは利用者に知らせる必要がないので、先にリスナーを外す。
        player.removeListener(errorListener)
        player.removeListener(progressListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val ACTION_SPEAK = "com.yumeyu.yomi.SPEAK"
        private const val ACTION_PLAY_EPISODE = "com.yumeyu.yomi.PLAY_EPISODE"
        private const val ACTION_STOP = "com.yumeyu.yomi.STOP"
        private const val ACTION_PAUSE = "com.yumeyu.yomi.PAUSE"
        private const val ACTION_SEEK = "com.yumeyu.yomi.SEEK"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_POSITION = "position"
        private const val PUBLISH_INTERVAL_MS = 500L
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_EPISODE_ID = "episode"

        private val _failures = MutableStateFlow(0)

        /**
         * 音声の取得に失敗した回数。同じプロセスの ProcessTextActivity が、
         * 再生を待たずに閉じてよいかを知るために使う。
         */
        val failures: StateFlow<Int> get() = _failures

        private val _playingEpisodeId = MutableStateFlow<String?>(null)

        /** 再生中（一時停止中を含む）のエピソード。画面の表示と、削除前に止めるのに使う */
        val playingEpisodeId: StateFlow<String?> = _playingEpisodeId.asStateFlow()

        fun speak(context: Context, text: String) {
            context.startService(
                Intent(context, SpeakService::class.java)
                    .setAction(ACTION_SPEAK)
                    .putExtra(EXTRA_TEXT, text)
            )
        }

        fun playEpisode(context: Context, id: String) {
            context.startService(
                Intent(context, SpeakService::class.java)
                    .setAction(ACTION_PLAY_EPISODE)
                    .putExtra(EXTRA_EPISODE_ID, id)
            )
        }

        private val _playback = MutableStateFlow<PlaybackInfo?>(null)

        /** 再生中（一時停止中を含む）のエピソードの位置。シークバーに使う */
        val playback: StateFlow<PlaybackInfo?> = _playback.asStateFlow()

        fun pause(context: Context) {
            context.startService(Intent(context, SpeakService::class.java).setAction(ACTION_PAUSE))
        }

        /** 再生中のエピソードの中で移動する。再生中でなければ何もしない */
        fun seek(context: Context, id: String, index: Int, positionMs: Long) {
            context.startService(
                Intent(context, SpeakService::class.java)
                    .setAction(ACTION_SEEK)
                    .putExtra(EXTRA_EPISODE_ID, id)
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_POSITION, positionMs)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpeakService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** 再生中のエピソードのどこを再生しているか */
data class PlaybackInfo(
    val episodeId: String,
    val index: Int,
    val positionMs: Long,
    val isPlaying: Boolean,
)
