package com.yumeyu.yomi

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * 文字選択メニューの「読み上げ」。
 *
 * 画面は出さず、選択された文字列をサービスに渡す。
 * アプリが背景にいる状態だとフォアグラウンドサービスを開始できないため、
 * 再生が始まるまで透明なまま残り、始まったら閉じる。
 * その間もタッチは下の画面に素通しする。
 */
class ProcessTextActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )

        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (text.isNullOrBlank()) {
            finish()
            return
        }

        SpeakService.speak(this, text)

        val token = SessionToken(this, ComponentName(this, SpeakService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener finish()
            if (controller.isPlaying) return@addListener finish()
            controller.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) finish()
                }

                override fun onPlayerError(error: PlaybackException) = finish()
            })
        }, mainExecutor)

        // 取得に失敗した場合などに備えて、一定時間で必ず閉じる
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
    }
}
