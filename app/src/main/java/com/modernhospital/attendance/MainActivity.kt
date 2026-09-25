package com.modernhospital.attendance

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// CONFIG: change these two lines only if your website address changes.
// ---------------------------------------------------------------------------
private const val BASE_URL = "https://mhpattendancesystem.top"
private const val HOST = "mhpattendancesystem.top"

// Makes window.print() (used by payslip / report pages) work inside the app.
private const val PRINT_SHIM =
    "window.print=function(){try{AndroidApp.print();}catch(e){}};"

// Detects which main menu links this user is allowed to see (permission based).
// Returns 'X' on the login page (no sidebar), otherwise a string like '1101'.
private const val NAV_JS =
    """(function(){var s=document.querySelector('.sidebar');if(!s)return 'X';var p=['/dashboard','/attendance','/staff','/reports/monthly'];var r='';for(var i=0;i<p.length;i++){r+=s.querySelector('a[href$="'+p[i]+'"]')?'1':'0';}return r;})()"""

private const val TOGGLE_SIDEBAR_JS =
    "(function(){var b=document.querySelector('[data-toggle-sidebar]');if(b){b.click();}})()"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var offline: View
    private lateinit var splash: View

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPress = 0L
    private var loadFailed = false
    private var navAllowed = false
    private var imeVisible = false

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileCallback?.onReceiveValue(uris)
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        webView = findViewById(R.id.webview)
        swipe = findViewById(R.id.swipe)
        progress = findViewById(R.id.progress)
        bottomNav = findViewById(R.id.bottom_nav)
        offline = findViewById(R.id.offline)
        splash = findViewById(R.id.splash)

        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            imeVisible = ime.bottom > 0
            updateNavVisibility()
            WindowInsetsCompat.CONSUMED
        }

        // Clear files left over from earlier downloads.
        File(cacheDir, "downloads").listFiles()?.forEach { it.delete() }

        setupWebView()
        setupBottomNav()

        swipe.setColorSchemeColors(0xFF079BD3.toInt())
        swipe.setOnRefreshListener { webView.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        findViewById<Button>(R.id.retry).setOnClickListener {
            offline.visibility = View.GONE
            loadFailed = false
            val current = webView.url
            if (current.isNullOrBlank() || current == "about:blank") {
                webView.loadUrl("$BASE_URL/dashboard")
            } else {
                webView.reload()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val path = Uri.parse(webView.url ?: "").path ?: ""
                val atRoot = path.isEmpty() || path == "/" || path == "/dashboard" || path == "/login"
                if (!atRoot && webView.canGoBack()) {
                    webView.goBack()
                } else if (System.currentTimeMillis() - lastBackPress < 2000) {
                    finish()
                } else {
                    lastBackPress = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("$BASE_URL/dashboard")
        }
    }

    // -----------------------------------------------------------------------
    // WebView
    // -----------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportMultipleWindows(false)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.userAgentString = s.userAgentString + " MHPAttendanceApp/1.0"

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.addJavascriptInterface(PrintBridge(), "AndroidApp")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, PRINT_SHIM, setOf(BASE_URL))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (isInternal(uri)) return false
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(PRINT_SHIM, null)
                }
                if (!loadFailed) {
                    offline.visibility = View.GONE
                    splash.visibility = View.GONE
                    refreshBottomNav()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    loadFailed = true
                    swipe.isRefreshing = false
                    splash.visibility = View.GONE
                    offline.visibility = View.VISIBLE
                    navAllowed = false
                    updateNavVisibility()
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                // Laravel returns 419 "Page Expired" when the session timed out
                // (SESSION_LIFETIME is short on this site). Send the user to login.
                if (request?.isForMainFrame == true && errorResponse?.statusCode == 419) {
                    view?.loadUrl("$BASE_URL/login")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val intent = fileChooserParams?.createIntent() ?: return false
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            downloadAndOpen(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun isInternal(uri: Uri): Boolean {
        if (uri.scheme == "about") return true
        return uri.scheme == "https" && (uri.host == HOST || uri.host == "www.$HOST")
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // No app can handle this link; ignore.
        }
    }

    // window.print() bridge -> Android print / "Save as PDF" dialog
    inner class PrintBridge {
        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
                val name = getString(R.string.app_name) + " - " + (webView.title ?: "Document")
                pm.print(name, webView.createPrintDocumentAdapter(name), null)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Downloads (PDF reports, payslips, CSV, database backup ...)
    // -----------------------------------------------------------------------
    private fun downloadAndOpen(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?) {
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
        val cookie = CookieManager.getInstance().getCookie(url)
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                if (cookie != null) conn.setRequestProperty("Cookie", cookie)
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent)
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")

                val type = conn.contentType?.substringBefore(';')?.trim()
                    ?: mimetype
                    ?: "application/octet-stream"
                val disposition = conn.getHeaderField("Content-Disposition") ?: contentDisposition
                val name = URLUtil.guessFileName(url, disposition, type)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")

                val dir = File(cacheDir, "downloads").apply { mkdirs() }
                val file = File(dir, name)
                conn.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread { openFile(file, type) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun openFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            // No viewer for this type (e.g. .sql backup): offer share / save instead.
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, file.name))
        }
    }

    // -----------------------------------------------------------------------
    // Bottom navigation (hidden on login page and while the keyboard is open)
    // -----------------------------------------------------------------------
    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item -> handleNav(item.itemId) }
        bottomNav.setOnItemReselectedListener { item -> handleNav(item.itemId) }
    }

    private fun handleNav(id: Int): Boolean {
        when (id) {
            R.id.nav_dashboard -> webView.loadUrl("$BASE_URL/dashboard")
            R.id.nav_attendance -> webView.loadUrl("$BASE_URL/attendance")
            R.id.nav_staff -> webView.loadUrl("$BASE_URL/staff")
            R.id.nav_reports -> webView.loadUrl("$BASE_URL/reports/monthly")
            R.id.nav_menu -> {
                webView.evaluateJavascript(TOGGLE_SIDEBAR_JS, null)
                return false
            }
        }
        return true
    }

    private fun refreshBottomNav() {
        webView.evaluateJavascript(NAV_JS) { raw ->
            val v = raw?.trim('"') ?: "X"
            if (v.length < 4 || v.contains('X')) {
                navAllowed = false
            } else {
                navAllowed = true
                val ids = intArrayOf(R.id.nav_dashboard, R.id.nav_attendance, R.id.nav_staff, R.id.nav_reports)
                ids.forEachIndexed { i, id -> bottomNav.menu.findItem(id).isVisible = v[i] == '1' }

                val path = Uri.parse(webView.url ?: "").path ?: ""
                val active = when {
                    path.startsWith("/attendance") -> R.id.nav_attendance
                    path.startsWith("/staff") -> R.id.nav_staff
                    path.startsWith("/reports") -> R.id.nav_reports
                    path.startsWith("/dashboard") -> R.id.nav_dashboard
                    else -> null
                }
                if (active != null) bottomNav.menu.findItem(active).isChecked = true
            }
            updateNavVisibility()
        }
    }

    private fun updateNavVisibility() {
        bottomNav.visibility = if (navAllowed && !imeVisible) View.VISIBLE else View.GONE
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
