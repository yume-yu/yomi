package com.yumeyu.yomi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * エピソードの音声を作る前面サービス。再生とは切り離してあるので、
 * 再生せずに作るだけ、再生を止めても最後まで作る、ができる。
 *
 * proxy は合成を 1 本ずつしか処理しないので、こちらもエピソードを 1 本ずつ順に作る。
 * 作成中は進み具合とキャンセルボタン付きの通知を出す。
 */
class GenerateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: EpisodeStore
    private lateinit var tts: TtsClient

    private val queue = ArrayDeque<String>()
    private var currentId: String? = null
    private var currentJob: Job? = null
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = EpisodeStore.get(this)
        tts = TtsClient(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() で起動されたら、すぐ前面に出さないと落とされる
        goForeground()
        when (intent?.action) {
            ACTION_ENQUEUE -> intent.getStringExtra(EXTRA_ID)?.let { enqueue(it) }
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let { cancel(it) }
            ACTION_CANCEL_ALL -> cancelAll()
        }
        if (worker == null) stopIfIdle()
        return START_NOT_STICKY
    }

    private fun enqueue(id: String) {
        if (id == currentId || id in queue) return
        val episode = store.get(id) ?: return
        if (episode.isComplete) return
        queue.addLast(id)
        publish()
        if (worker == null) {
            worker = scope.launch {
                while (queue.isNotEmpty()) {
                    val next = queue.removeFirst()
                    currentId = next
                    publish()
                    // worker の子にすると、想定外の例外で worker ごと止まるので、scope から起動する
                    val job = scope.launch {
                        try {
                            generate(next)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "generate crashed: $next", e)
                            runCatching { store.update(next) { it.copy(error = e.toString()) } }
                        }
                    }
                    currentJob = job
                    job.join()
                    currentJob = null
                    currentId = null
                }
                publish()
                worker = null
                stopIfIdle()
            }
        }
    }

    private fun cancel(id: String) {
        if (id == currentId) currentJob?.cancel()
        if (queue.remove(id)) publish()
    }

    private fun cancelAll() {
        queue.clear()
        currentJob?.cancel()
        publish()
    }

    private suspend fun generate(id: String) {
        val start = store.update(id) { it.copy(error = null) } ?: return
        val server = Settings(this).serverUrl
        val dictVersion = DictClient.version(server)
        for (i in start.ready until start.chunks.size) {
            val episode = store.get(id) ?: return
            notifyProgress(episode)
            val dest = store.chunkFile(id, i)
            try {
                tts.fetch(server, episode.model, episode.voice, episode.chunks[i], dest, dictVersion)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.get(id) == null) return // 作成中に削除された
                Log.w(TAG, "generate failed: $id #$i", e)
                store.update(id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
                return
            }
            val duration = withContext(Dispatchers.IO) { probeDurationMs(dest) }
            val updated = store.update(id) {
                it.copy(ready = i + 1, durationsMs = it.durationsMs.take(i) + duration)
            }
            if (updated == null) {
                // 作成中に削除された。書き込んだ分を片付ける
                withContext(Dispatchers.IO) { store.dir(id).deleteRecursively() }
                return
            }
        }
    }

    private fun publish() {
        store.setPending(listOfNotNull(currentId) + queue)
        if (currentId == null && queue.isEmpty()) return
        val episode = (currentId ?: queue.firstOrNull())?.let { store.get(it) } ?: return
        notifyProgress(episode)
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun stopIfIdle() {
        if (worker != null || queue.isNotEmpty()) return
        store.setPending(emptyList())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notifyProgress(episode: Episode) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(episode))
    }

    private fun buildNotification(episode: Episode?): Notification {
        val waiting = queue.size
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
        if (episode == null) {
            return b.setContentTitle("エピソードを作成中").build()
        }
        b.setContentTitle("作成中: ${episode.title}")
            .setContentText(
                "${episode.ready} / ${episode.chunks.size}" +
                    if (waiting > 0) "（ほか $waiting 件待ち）" else ""
            )
            .setProgress(episode.chunks.size, episode.ready, false)
            .addAction(0, "キャンセル", servicePendingIntent(ACTION_CANCEL, episode.id, 1))
        if (waiting > 0) {
            b.addAction(0, "すべてキャンセル", servicePendingIntent(ACTION_CANCEL_ALL, null, 2))
        }
        return b.build()
    }

    private fun servicePendingIntent(action: String, id: String?, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, GenerateService::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "エピソードの作成", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Android 15 以降、dataSync の前面サービスは 1 日 6 時間までで、超えるとこれが呼ばれる */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out")
        currentId?.let { id -> store.update(id) { it.copy(error = "作成の時間制限に達しました") } }
        cancelAll()
        worker?.cancel()
        worker = null
        stopIfIdle()
    }

    override fun onDestroy() {
        scope.cancel()
        store.setPending(emptyList())
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Yomi"
        private const val CHANNEL_ID = "generate"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_ENQUEUE = "com.yumeyu.yomi.GENERATE"
        private const val ACTION_CANCEL = "com.yumeyu.yomi.CANCEL_GENERATE"
        private const val ACTION_CANCEL_ALL = "com.yumeyu.yomi.CANCEL_ALL_GENERATE"
        private const val EXTRA_ID = "id"

        /** 画面から呼ぶ。アプリが前面にいる間しか前面サービスを開始できない */
        fun enqueue(context: Context, id: String) {
            context.startForegroundService(
                Intent(context, GenerateService::class.java).setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, id)
            )
        }

        fun cancel(context: Context, id: String) {
            context.startService(
                Intent(context, GenerateService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id)
            )
        }

        fun probeDurationMs(file: File): Long {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.path)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            } catch (e: Exception) {
                0
            } finally {
                r.release()
            }
        }
    }
}
