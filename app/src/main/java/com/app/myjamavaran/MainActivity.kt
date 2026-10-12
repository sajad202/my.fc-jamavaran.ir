package com.app.myjamavaran

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private var startUrl: String = ""

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipe = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        startUrl = getString(R.string.start_url)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            var results: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                if (cameraPhotoUri != null) {
                    results = arrayOf(cameraPhotoUri!!)
                    cameraPhotoUri = null
                } else if (result.data != null) {
                    results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                }
            }
            callback?.onReceiveValue(results ?: arrayOf())
        }

        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                dispatchCaptureIntent()
            } else {
                filePathCallback?.onReceiveValue(arrayOf())
                filePathCallback = null
            }
        }

        locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingGeoCallback?.invoke(pendingGeoOrigin ?: "", granted, false)
            pendingGeoCallback = null
            pendingGeoOrigin = null
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        setupWebView()
        setupSwipe()
        setupBackHandling()

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            if (isOnline()) {
                errorView.visibility = View.GONE
                webView.reload()
            } else {
                Toast.makeText(this, R.string.no_connection, Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (isOnline()) {
            webView.loadUrl(startUrl)
        } else {
            showError()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(BuildConfig.ZOOM_ENABLED)
        s.builtInZoomControls = BuildConfig.ZOOM_ENABLED
        s.displayZoomControls = false
        s.mixedContentMode = if (BuildConfig.ALLOW_HTTP)
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = s.userAgentString + " " + getString(R.string.ua_suffix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = true
        webView.setBackgroundColor(Color.parseColor("#ffffff"))

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                swipe.isRefreshing = false
                injectCustomAssets()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                if (!BuildConfig.UPLOAD_ENABLED) return false
                if (filePathCallback != null) filePathCallback?.onReceiveValue(arrayOf())
                filePathCallback = callback
                if (params.isCaptureEnabled) {
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    } else {
                        dispatchCaptureIntent()
                    }
                } else {
                    try {
                        fileChooserLauncher.launch(
                            Intent.createChooser(params.createIntent(), getString(R.string.choose_file))
                        )
                    } catch (e: ActivityNotFoundException) {
                        filePathCallback = null
                        return false
                    }
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    callback.invoke(origin, true, false)
                }
            }
        }

        if (BuildConfig.DOWNLOADS_ENABLED) {
            webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setMimeType(mimeType)
                        .setTitle(fileName)
                        .setDescription(getString(R.string.downloading))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null) request.addRequestHeader("cookie", cookies)
                    request.addRequestHeader("User-Agent", userAgent)
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    openExternal(Uri.parse(url))
                }
            }
        }
    }

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            if (BuildConfig.EXTERNAL_IN_APP) return false
            val host = uri.host?.lowercase() ?: return false
            val allowed = resources.getStringArray(R.array.allowed_domains)
            val internal = allowed.any { host == it || host.endsWith(".$it") }
            return if (internal) false else { openExternal(uri); true }
        }
        openExternal(uri)
        return true
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchCaptureIntent() {
        try {
            val photoFile = File.createTempFile("upload_", ".jpg", cacheDir)
            cameraPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            }
            fileChooserLauncher.launch(intent)
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(arrayOf())
            filePathCallback = null
        }
    }

    private fun setupSwipe() {
        swipe.isEnabled = BuildConfig.PULL_REFRESH
        swipe.setOnRefreshListener {
            if (isOnline()) webView.reload() else { swipe.isRefreshing = false; showError() }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun injectCustomAssets() {
        val sb = StringBuilder()
        val css = BuildConfig.CUSTOM_CSS
        if (css.isNotBlank()) {
            val safe = css.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")
            sb.append("try{var s=document.createElement('style');s.textContent=`")
                .append(safe).append("`;document.head.appendChild(s);}catch(e){}")
        }
        if (BuildConfig.CUSTOM_JS.isNotBlank()) {
            sb.append("try{").append(BuildConfig.CUSTOM_JS).append("}catch(e){}")
        }
        if (sb.isNotEmpty()) webView.evaluateJavascript(sb.toString(), null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra("url")
        if (!url.isNullOrEmpty() && this::webView.isInitialized) webView.loadUrl(url)
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}