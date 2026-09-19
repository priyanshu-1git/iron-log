package com.ironlog.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            filePathCallback?.onReceiveValue(
                if (uri != null) arrayOf(uri) else null
            )
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {}

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePath: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                // Cancel any previous unfinished file request.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePath

                // The Iron Log restore function expects a JSON backup file.
                fileChooserLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/plain",
                        "application/octet-stream"
                    )
                )

                return true
            }
        }

        webView.addJavascriptInterface(
            WebAppInterface(this),
            "AndroidBridge"
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1001
            )
        }

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** Exposed to the page's JS as window.AndroidBridge. */
    inner class WebAppInterface(private val activity: MainActivity) {

        @JavascriptInterface
        fun saveFile(
            filename: String,
            content: String,
            mime: String,
            isBase64: Boolean
        ) {
            try {
                val bytes =
                    if (isBase64) {
                        Base64.decode(content, Base64.DEFAULT)
                    } else {
                        content.toByteArray(Charsets.UTF_8)
                    }

                writeToDownloads(filename, mime, bytes)
                runOnUiThreadToast("Saved to Downloads: $filename")

            } catch (e: Exception) {
                runOnUiThreadToast(
                    "Couldn't save $filename — ${e.message}"
                )
            }
        }

        private fun writeToDownloads(
            filename: String,
            mime: String,
            bytes: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val resolver = activity.contentResolver

                val values = ContentValues().apply {
                    put(
                        MediaStore.Downloads.DISPLAY_NAME,
                        filename
                    )
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        mime
                    )
                    put(
                        MediaStore.Downloads.IS_PENDING,
                        1
                    )
                }

                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException(
                    "MediaStore refused the file"
                )

                resolver.openOutputStream(uri)?.use {
                    it.write(bytes)
                }

                values.clear()
                values.put(
                    MediaStore.Downloads.IS_PENDING,
                    0
                )

                resolver.update(
                    uri,
                    values,
                    null,
                    null
                )

            } else {

                if (
                    ActivityCompat.checkSelfPermission(
                        activity,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException(
                        "Storage permission not granted yet — try again"
                    )
                }

                val dir =
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val file = File(dir, filename)

                FileOutputStream(file).use {
                    it.write(bytes)
                }
            }
        }

        private fun runOnUiThreadToast(msg: String) {
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    msg,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}