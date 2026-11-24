package com.yumeyu.yomi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * OpenWebUI に台本を作らせて、できるまで待つ前面サービス。
 *
 * 検索込みの生成は 5〜10 分かかることがある。生成そのものは OpenWebUI 側で進む（チャットとして頼む）ので、
 * ここは様子を見に行って進み具合を通知に出し、できたら下書きとして保存して知らせるだけ。
 * 途中でアプリが落ちても、仕事は [ScriptStore.job] に残るので、次に開いたときに [resume] で続きを待てる。
 */
class ScriptService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: ScriptStore
    private lateinit var settings: Settings
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = ScriptStore.get(this)
        settings = Settings(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(null)
        when (intent?.action) {
            ACTION_START -> {
                val mode = Script.Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: Script.Mode.Monologue.name)
                val material = intent.getStringExtra(EXTRA_MATERIAL).orEmpty()
                if (worker == null && store.job == null) start(mode, material)
            }
            ACTION_RESUME -> if (worker == null) store.job?.let { run(it) }
            ACTION_CANCEL -> cancel()
        }
        if (worker == null) stop()
        return START_NOT_STICKY
    }

    private fun start(mode: Script.Mode, material: String) {
        val model = settings.owuiModel(mode)
        if (model.isBlank() || material.isBlank()) return
        val job = ScriptStore.Job.new(mode, material, model)
        store.job = job
        run(job)
    }

    /** 仕事を進める。チャットがまだ無ければ作って生成を頼み、あとは終わるまで見に行く */
    private fun run(initial: ScriptStore.Job) {
        val client = OpenWebUiClient(settings.owuiUrl, settings.owuiKey)
        worker = scope.launch {
            var job = initial
            try {
                if (job.chatId == null) {
                    publish(job, chars = 0)
                    val chatId = client.createChat(
                        model = job.model,
                        title = "Yomi: " + job.material.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(30),
                        prompt = job.material,
                        userId = job.userId,
                        assistantId = job.assistantId,
                    )
                    job = job.copy(chatId = chatId)
                    store.job = job
                    client.startCompletion(
                        model = job.model,
                        prompt = job.material,
                        chatId = chatId,
                        userId = job.userId,
                        assistantId = job.assistantId,
                        sessionId = UUID.randomUUID().toString(),
                        filterIds = settings.owuiFilterIds,
                        variables = dateVariables(),
                    )
                }
                val chatId = job.chatId!!
                val message = waitUntilDone(client, job, chatId)
                val script = Script.clean(message.content)
                if (script.isBlank()) throw IllegalStateException("台本が空でした")
                store.addDraft(
                    ScriptStore.Draft(
                        id = job.id,
                        createdAt = System.currentTimeMillis(),
                        mode = job.mode,
                        script = script,
                        sources = message.sources,
                        chatId = chatId,
                    )
                )
                if (settings.owuiDeleteChats) client.deleteChat(chatId)
                notifyResult("台本ができました", script.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "script job failed", e)
                notifyResult("台本を作れませんでした", e.message ?: e.javaClass.simpleName)
            }
            store.job = null
            _progress.value = null
            worker = null
            stop()
        }
    }

    private suspend fun waitUntilDone(
        client: OpenWebUiClient,
        job: ScriptStore.Job,
        chatId: String,
    ): OpenWebUiClient.Message {
        var failures = 0
        while (true) {
            if (System.currentTimeMillis() - job.startedAt > TIMEOUT_MS) {
                throw IllegalStateException("${TIMEOUT_MS / 60_000} 分たっても終わらなかったので打ち切りました")
            }
            delay(POLL_INTERVAL_MS)
            try {
                val tasks = client.activeTasks(chatId)
                val message = client.assistantMessage(chatId, job.assistantId)
                failures = 0
                publish(job, chars = Script.clean(message.content).length)
                if (tasks.isEmpty()) return message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 電波が一時的に悪いだけかもしれないので、何回か続けて失敗するまでは待ち続ける
                failures++
                Log.w(TAG, "poll failed ($failures)", e)
                if (failures >= MAX_POLL_FAILURES) throw e
            }
        }
    }

    private fun cancel() {
        val job = store.job
        worker?.cancel()
        store.job = null
        _progress.value = null
        val chatId = job?.chatId
        if (chatId == null) {
            worker = null
            return
        }
        // 生成はサーバー側で続いているので止めてもらう（チャットは残す）。
        // 止める通信が終わるまでサービスを終えないよう、worker として待つ
        val client = OpenWebUiClient(settings.owuiUrl, settings.owuiKey)
        worker = scope.launch {
            runCatching { client.activeTasks(chatId).forEach { client.stopTask(it) } }
            worker = null
            stop()
        }
    }

    private fun publish(job: ScriptStore.Job, chars: Int) {
        val p = Progress(job.mode, job.startedAt, chars)
        _progress.value = p
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildProgress(p))
    }

    private fun dateVariables(): Map<String, String> {
        val now = Date()
        return mapOf(
            "{{CURRENT_DATE}}" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
            "{{CURRENT_DATETIME}}" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now),
            "{{CURRENT_WEEKDAY}}" to SimpleDateFormat("EEEE", Locale.US).format(now),
        )
    }

    private fun goForeground(progress: Progress?) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgress(progress),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun stop() {
        if (worker != null) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildProgress(p: Progress?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(0, 0, true)
            .setContentTitle("台本を作成中")
        if (p != null) {
            val elapsed = (System.currentTimeMillis() - p.startedAt) / 1000
            b.setContentTitle("台本を作成中（${p.mode.label}）")
                .setContentText(
                    "%d:%02d 経過".format(elapsed / 60, elapsed % 60) +
                        if (p.chars > 0) "・本文 ${p.chars} 文字" else "・調べています"
                )
                .addAction(0, "キャンセル", cancelIntent())
        }
        return b.build()
    }

    private fun notifyResult(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 3,
        Intent(this, ScriptService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "台本の作成中", NotificationManager.IMPORTANCE_LOW))
        // できたことは気づけるよう、こちらは普通の重要度にする
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "台本の完成", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Android 15 以降、dataSync の前面サービスは 1 日 6 時間まで */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out")
        worker?.cancel()
        worker = null
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 作成中の進み具合。画面の表示用 */
    data class Progress(val mode: Script.Mode, val startedAt: Long, val chars: Int)

    companion object {
        private const val TAG = "Yomi"
        private const val CHANNEL_PROGRESS = "script_progress"
        private const val CHANNEL_RESULT = "script_result"
        private const val NOTIFICATION_ID = 3
        private const val RESULT_NOTIFICATION_ID = 4
        private const val ACTION_START = "com.yumeyu.yomi.SCRIPT_START"
        private const val ACTION_RESUME = "com.yumeyu.yomi.SCRIPT_RESUME"
        private const val ACTION_CANCEL = "com.yumeyu.yomi.SCRIPT_CANCEL"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_MATERIAL = "material"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val TIMEOUT_MS = 30 * 60_000L
        private const val MAX_POLL_FAILURES = 6

        private val _progress = MutableStateFlow<Progress?>(null)
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        /** 画面から呼ぶ。アプリが前面にいる間しか前面サービスを開始できない */
        fun start(context: Context, mode: Script.Mode, material: String) {
            context.startForegroundService(
                Intent(context, ScriptService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_MODE, mode.name)
                    .putExtra(EXTRA_MATERIAL, material)
            )
        }

        /** 前回の仕事が残っていれば、続きを待つ（アプリを開いたときに呼ぶ） */
        fun resumeIfPending(context: Context) {
            if (ScriptStore.get(context).job == null || _progress.value != null) return
            context.startForegroundService(Intent(context, ScriptService::class.java).setAction(ACTION_RESUME))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ScriptService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
