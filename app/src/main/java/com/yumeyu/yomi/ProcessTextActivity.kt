package com.yumeyu.yomi

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 文字選択メニューの「読み上げ」。
 *
 * 画面は出さず、選択された文字列をサービスに渡す。
 * アプリが背景にいる状態だとフォアグラウンドサービスを開始できないため、
 * 再生が始まるまで透明なまま残り、始まったら閉じる。
 * 取得に失敗した場合もすぐ閉じる。
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

        // speak() より前に今の値を取っておけば、この依頼の失敗だけを拾える
        val failuresBefore = SpeakService.failures.value
        SpeakService.speak(this, text)
        lifecycleScope.launch {
            SpeakService.failures.first { it > failuresBefore }
            finish()
        }

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
        }, ContextCompat.getMainExecutor(this))

        // 取得に失敗した場合などに備えて、一定時間で必ず閉じる
        handler.postDelayed({ finish() }, TIMEOUT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    companion object {
        // 最初のチャンクの合成が、他の利用者のリクエストの後ろで待たされることもあるので長めにとる
        private const val TIMEOUT_MS = 60_000L
    }
}
