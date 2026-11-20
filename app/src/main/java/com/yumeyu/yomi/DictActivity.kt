package com.yumeyu.yomi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * speech-proxy の読み辞書編集画面（/dict/ui）をアプリ内で開く。
 *
 * - confirm() を出すため WebChromeClient を付ける（付けないと常にキャンセル扱いになる）
 * - TSV ダウンロードは DownloadManager を使わない。システムのプロセスが取りに行くので
 *   アプリ単位の VPN を通らず、proxy に届かないため。保存先を選んでもらい、このアプリで取得して書く
 * - 戻るときに未保存の編集（ページの dirty 変数）があれば確認する
 */
class DictActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var error by mutableStateOf<String?>(null)
    private var confirmDiscard by mutableStateOf(false)
    private var pendingDownload: String? = null

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
            val url = pendingDownload
            pendingDownload = null
            if (uri != null && url != null) download(url, uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = Settings(this).serverUrl.trimEnd('/') + "/dict/ui"
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                    if (request.isForMainFrame) {
                        error = "開けませんでした（${err.description}）。サーバーや VPN を確認してください。\n$url"
                    }
                }
            }
            setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                pendingDownload = downloadUrl
                createDocument.launch(URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType))
            }
        }
        // 画面の回転では作り直さない設定（マニフェストの configChanges）なので、編集中の内容は消えない
        webView.loadUrl(url)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeIfClean()
        })

        setContent {
            YomiTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .imePadding()
                            .fillMaxSize(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { closeIfClean() }) { Text("← 戻る") }
                            Text("読み辞書", style = MaterialTheme.typography.titleMedium)
                        }
                        val message = error
                        if (message != null) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(message)
                                Button(onClick = {
                                    error = null
                                    webView.loadUrl(url)
                                }) { Text("再読み込み") }
                            }
                        } else {
                            AndroidView(
                                factory = { webView },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                    }
                    if (confirmDiscard) {
                        AlertDialog(
                            onDismissRequest = { confirmDiscard = false },
                            title = { Text("保存していない編集があります") },
                            text = { Text("破棄して閉じますか？") },
                            confirmButton = {
                                TextButton(onClick = { confirmDiscard = false; finish() }) { Text("破棄して閉じる") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmDiscard = false }) { Text("戻って保存する") }
                            },
                        )
                    }
                }
            }
        }
    }

    /** ページの dirty 変数を見て、未保存の編集があれば確認してから閉じる */
    private fun closeIfClean() {
        if (error != null) {
            finish()
            return
        }
        webView.evaluateJavascript("typeof dirty !== 'undefined' && dirty === true") { result ->
            if (result == "true") confirmDiscard = true else finish()
        }
    }

    /** TSV を取得して、選んでもらった場所に書く。VPN を通るよう、このアプリから取りに行く */
    private fun download(url: String, dest: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        contentResolver.openOutputStream(dest)?.use { out ->
                            resp.body!!.byteStream().copyTo(out)
                        } ?: throw IOException("保存先を開けません")
                    }
                    true
                } catch (e: Exception) {
                    Log.w("Yomi", "dict export failed", e)
                    false
                }
            }
            Toast.makeText(
                this@DictActivity,
                if (ok) "保存しました" else "保存できませんでした",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private val http = OkHttpClient()

        fun open(context: Context) {
            context.startActivity(Intent(context, DictActivity::class.java))
        }
    }
}
