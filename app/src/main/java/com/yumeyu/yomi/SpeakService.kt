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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 読み上げ本体。チャンクを順に取得してプレイリストに積み、
 * 1 つ目が届いた時点で再生を始める。再生中は通知から止められる。
 */
class SpeakService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var tts: TtsClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("Yomi", "playback failed", error)
            toast("再生できませんでした: ${error.errorCodeName}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TtsClient(this)
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
        session = MediaSession.Builder(this, player).build()
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> intent.getStringExtra(EXTRA_TEXT)?.let { speak(it) }
            ACTION_STOP -> stopSpeaking()
        }
        return super.onStartCommand(intent, flags, startId)
    }

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
                enqueue(file, chunk, voice)
            }
        }
    }

    private fun enqueue(file: java.io.File, text: String, voice: String) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(text)
                    .setArtist(voice)
                    .build()
            )
            .build()
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
        job?.cancel()
        job = null
        player.stop()
        player.clearMediaItems()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // 解放がタイムアウトすると ExoPlayer は再生エラーとして通知してくる。
        // 後片付け中のエラーは利用者に知らせる必要がないので、先にリスナーを外す。
        player.removeListener(errorListener)
        session.release()
        player.release()
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val ACTION_SPEAK = "com.yumeyu.yomi.SPEAK"
        private const val ACTION_STOP = "com.yumeyu.yomi.STOP"
        private const val EXTRA_TEXT = "text"

        private val _failures = MutableStateFlow(0)

        /**
         * 音声の取得に失敗した回数。同じプロセスの ProcessTextActivity が、
         * 再生を待たずに閉じてよいかを知るために使う。
         */
        val failures: StateFlow<Int> get() = _failures

        fun speak(context: Context, text: String) {
            context.startService(
                Intent(context, SpeakService::class.java)
                    .setAction(ACTION_SPEAK)
                    .putExtra(EXTRA_TEXT, text)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpeakService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
