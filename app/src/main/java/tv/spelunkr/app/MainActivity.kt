package tv.spelunkr.app

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.roundToInt

/**
 * SPELUNKR for phones, tablets and Android TV: the spelunkr.tv web app in a WebView tuned for
 * video (autoplay with sound, native fullscreen, picture-in-picture) and for TV remotes (the
 * site's own `?tv` mode, with channel, media and back keys handed to the page as the keys it
 * already listens for).
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var splash: View
    private lateinit var offline: View
    private lateinit var lockShield: View
    private lateinit var lockHint: View
    private lateinit var lockLayer: FrameLayout
    private lateinit var lockButton: ImageButton
    private lateinit var lockPanels: List<View>
    private lateinit var brightnessSlider: VerticalSlider
    private lateinit var volumeSlider: VerticalSlider
    private lateinit var webView: WebView

    private val chromeClient = ShellChromeClient()
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isPlaying = false
    private var isLocked = false
    private var backInFlight = false
    private var pendingTune: String? = null
    private var okDownAt: KeyEvent? = null
    private var okLongPressed = false
    private var lastBackAt = 0L
    private val main = Handler(Looper.getMainLooper())

    private val isTv by lazy {
        val uiMode = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { if (offline.isVisible) retry() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        splash = findViewById(R.id.splash)
        offline = findViewById(R.id.offline)
        findViewById<View>(R.id.retry).setOnClickListener { retry() }
        setUpScreenLock()

        setUpWindow()
        webView = createWebView()
        webContainer.addView(webView)
        webView.loadUrl(startUrl(intent))
        if (intent?.data?.scheme == TvHomeRow.SCHEME) pendingTune = intent.data?.lastPathSegment
        if (isTv) TvHomeRow.syncInBackground(applicationContext)
        main.postDelayed({ splash.isVisible = false }, SPLASH_TIMEOUT_MS)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.data?.scheme) {
            TvHomeRow.SCHEME -> tuneFromIntent(intent)
            null -> Unit
            else -> webView.loadUrl(startUrl(intent))
        }
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
        webView.resumeTimers()
        webView.evaluateJavascript(RESUME_JS, null)
    }

    override fun onStop() {
        super.onStop()
        // Out of sight (and not in picture-in-picture): stop the sound, and pick up where the channel is on return.
        webView.evaluateJavascript(PAUSE_JS, null)
        webView.onPause()
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        main.removeCallbacksAndMessages(null)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ---- Window ------------------------------------------------------------------------------

    private fun setUpWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        root.setBackgroundColor(Color.BLACK)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            webContainer.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            lockLayer.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        if (isTv) setUpTvPerformance()
    }

    /**
     * TV boxes have weak GPUs driving a 1080p screen, so every full-screen fill counts: the page
     * is the only thing that draws once it is up, and the phone-only overlays are taken out.
     */
    private fun setUpTvPerformance() {
        setSystemBarsHidden(true)
        lockLayer.isVisible = false
        lockShield.isVisible = false
        val power = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && power.isSustainedPerformanceModeSupported) {
            window.setSustainedPerformanceMode(true)
        }
    }

    /** Once the page has painted, nothing behind the WebView needs drawing on every frame. */
    private fun dropBackgroundsBehindPage() {
        if (!isTv) return
        window.setBackgroundDrawable(null)
        root.background = null
    }

    private fun setSystemBarsHidden(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- WebView -----------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        return WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(getColor(R.color.spelunkr_purple))
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false // channels tune in with sound, like a TV
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(false) // window.open (clips) opens in place
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100 // the pixel type is laid out to the pixel; system font scaling breaks it
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString SpelunkrAndroid/${packageManager.getPackageInfo(packageName, 0).versionName}"
            }
            val view = this
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true) // sign-in goes through supabase.co
            }

            webViewClient = ShellClient()
            webChromeClient = chromeClient
            installPlaybackBridge(this)
            overScrollMode = View.OVER_SCROLL_NEVER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Keep the page's renderer as important as the app while it's on screen.
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }
    }

    /** Tells the app when a video is playing, so the screen stays on and picture-in-picture is offered. */
    private fun installPlaybackBridge(view: WebView) {
        val canListen = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val canInject = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!canListen || !canInject) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        WebViewCompat.addWebMessageListener(view, "SpelunkrNative", SITE_ORIGINS) { _, message, _, _, _ ->
            val data = message.data.orEmpty()
            when {
                data == "playing" -> onPlaybackChanged(true)
                data == "paused" -> onPlaybackChanged(false)
                data == "leave" -> finish()
                data.startsWith("favs:") -> onFavoritesChanged(data.removePrefix("favs:"))
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view, PLAYBACK_BRIDGE_JS, SITE_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(view, CHANNEL_FEATURES_JS, SITE_ORIGINS)
        if (isTv) WebViewCompat.addDocumentStartJavaScript(view, TV_GUIDE_JS, SITE_ORIGINS)
    }

    /** The page's favorites, kept by the app for the Android TV home row. */
    private fun onFavoritesChanged(json: String) {
        val prefs = getSharedPreferences(TvHomeRow.PREFS, MODE_PRIVATE)
        if (prefs.getString(TvHomeRow.KEY_FAVORITES, null) == json) return
        prefs.edit().putString(TvHomeRow.KEY_FAVORITES, json).apply()
        if (isTv) TvHomeRow.syncInBackground(applicationContext)
    }

    /** A home-row card (spelunkr://tune/<channel>) asks for a channel: tune to it once the guide is there. */
    private fun tuneFromIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != TvHomeRow.SCHEME || data.host != "tune") return
        val channel = data.lastPathSegment?.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: return
        pendingTune = channel
        webView.evaluateJavascript("window.SPK && SPK.tuneTo('$channel')") { result ->
            if (result == "true" || result == "false") pendingTune = null
        }
    }

    private fun onPlaybackChanged(playing: Boolean) {
        if (playing == isPlaying) return
        isPlaying = playing
        if (playing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updatePictureInPictureParams()
    }

    private fun startUrl(intent: Intent?): String {
        val link = intent?.data?.takeIf { it.scheme == "https" && it.host in SITE_HOSTS }
        val uri = link ?: Uri.parse(HOME_URL)
        if (!isTv || uri.getQueryParameter("tv") != null) return uri.toString()
        return uri.buildUpon().appendQueryParameter("tv", "").build().toString()
    }

    private fun retry() {
        offline.isVisible = false
        webView.isVisible = true
        val url = webView.url
        if (url.isNullOrEmpty() || url.startsWith("data:") || url == "about:blank") {
            webView.loadUrl(startUrl(null))
        } else {
            webView.reload()
        }
        webView.requestFocus()
    }

    private fun showOffline() {
        splash.isVisible = false
        offline.isVisible = true
        webView.isVisible = false
        findViewById<View>(R.id.retry).requestFocus()
    }

    private fun openExternally(uri: Uri): Boolean {
        val intent = if (uri.scheme == "intent") {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        } ?: return false
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private inner class ShellClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            val host = uri.host.orEmpty()
            if (uri.scheme == "http" || uri.scheme == "https") {
                if (IN_APP_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                // Elsewhere on the web goes to the browser; a TV with no browser keeps it here.
                return openExternally(uri)
            }
            openExternally(uri)
            return true
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            splash.isVisible = false
            dropBackgroundsBehindPage()
        }

        override fun onPageFinished(view: WebView, url: String) {
            pendingTune?.let { channel ->
                if (channel.matches(Regex("[a-z0-9-]+"))) view.evaluateJavascript("window.SPK && SPK.tuneTo('$channel')", null)
                pendingTune = null
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showOffline()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer died (usually memory pressure on a TV box): rebuild instead of crashing.
            val url = view.url
            if (customView != null) exitFullscreen()
            webContainer.removeView(view)
            view.destroy()
            isPlaying = false
            webView = createWebView()
            webContainer.addView(webView)
            webView.loadUrl(url ?: startUrl(null))
            webView.requestFocus()
            return true
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            fullscreenContainer.isVisible = true
            webContainer.isVisible = false
            setSystemBarsHidden(true)
            if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            view.isFocusable = true
            view.requestFocus()
            updatePictureInPictureParams()
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.isVisible = false
            webContainer.isVisible = true
            customView = null
            customViewCallback = null
            if (isLocked) unlockScreen()
            main.removeCallbacks(hideLockButton)
            hideLockButton.run() // the lock belongs to fullscreen; it leaves with it
            if (!isTv) {
                setSystemBarsHidden(false)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            webView.requestFocus()
            updatePictureInPictureParams()
        }

        // Without a poster Chromium flashes a grey play button before each tape starts.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onCloseWindow(window: WebView) = finish()
    }

    private fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        if (customView != null) chromeClient.onHideCustomView()
    }

    // ---- Remote keys -------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
            return dispatchOk(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_LAST_CHANNEL) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) webView.evaluateJavascript("window.SPK && SPK.flip()", null)
            return true
        }
        val key = REMOTE_KEYS[event.keyCode] ?: return super.dispatchKeyEvent(event)
        // WebView doesn't hand these to the page, so they are sent as the DOM keys the site listens for.
        if (event.action == KeyEvent.ACTION_DOWN) {
            webView.evaluateJavascript("($PRESS_KEY_JS)('$key')", null)
        }
        return true
    }

    /**
     * TV OK button: a tap is OK as always (sent on release); holding it stars the channel in focus.
     * If there's no channel to star (say, on the main menu), the hold counts as an ordinary OK.
     */
    private fun dispatchOk(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                okDownAt = event
                okLongPressed = false
                main.postDelayed(okLongPress, OK_HOLD_MS)
            } else if (event.isLongPress && !okLongPressed) {
                // Android flags the held button's repeat as a long press; act on it right away.
                main.removeCallbacks(okLongPress)
                okLongPress.run()
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(okLongPress)
                val down = okDownAt
                okDownAt = null
                if (!okLongPressed && down != null) {
                    super.dispatchKeyEvent(down)
                    super.dispatchKeyEvent(event)
                }
            }
        }
        return true
    }

    private val okLongPress = Runnable {
        val down = okDownAt ?: return@Runnable
        okLongPressed = true
        webView.evaluateJavascript("!!(window.SPK && SPK.toggleFavorite())") { starred ->
            if (starred != "true") {
                super@MainActivity.dispatchKeyEvent(down)
                super@MainActivity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
            }
        }
    }

    private fun onBack() {
        when {
            isLocked -> showLockButton()
            customView != null -> exitFullscreen()
            offline.isVisible -> finish()
            // "Press back again to exit" is up: the page already passed on back, so a quick second press leaves.
            SystemClock.elapsedRealtime() - lastBackAt < EXIT_WINDOW_MS -> finish()
            backInFlight -> Unit
            else -> askPageToGoBack()
        }
    }

    /**
     * Back is offered to the page first as Escape (the site's own "back" key, which closes the
     * guide, panels and dialogs). Only if the page did nothing with it does the app go back a
     * page, and at the start, leave.
     */
    private fun askPageToGoBack() {
        backInFlight = true
        webView.evaluateJavascript(BACK_PROBE_JS) { before ->
            main.postDelayed({
                webView.evaluateJavascript(PAGE_STATE_JS) { after ->
                    backInFlight = false
                    val handled = before == null || before.contains("prevented") ||
                        stripPrevented(before) != after
                    if (!handled) appBack()
                }
            }, BACK_SETTLE_MS)
        }
    }

    private fun stripPrevented(state: String) = state.replace("prevented|", "")

    private fun appBack() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackAt = now
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Screen lock (phones) ----------------------------------------------------------------

    /**
     * A lock for watching on a phone: touching the screen during playback brings up a lock
     * button for a few seconds. Locked, every touch (and back) is held off the page, so a stray
     * thumb can't change the channel; a touch brings the button back, and tapping it unlocks.
     * Locked, the button comes with brightness and volume sliders, the two things still worth
     * reaching for.
     */
    private fun setUpScreenLock() {
        lockShield = findViewById(R.id.lock_shield)
        lockHint = findViewById(R.id.lock_hint)
        lockLayer = findViewById(R.id.lock_layer)
        lockButton = findViewById(R.id.lock_button)
        lockPanels = listOf(findViewById(R.id.brightness_panel), findViewById(R.id.volume_panel))
        brightnessSlider = findViewById(R.id.brightness_slider)
        volumeSlider = findViewById(R.id.volume_slider)
        lockShield.setOnTouchListener { _, _ -> true }
        lockButton.setOnClickListener { if (isLocked) unlockScreen() else lockScreen() }

        brightnessSlider.onChange = { value ->
            // This window only; the system setting is left alone and comes back when the app closes.
            window.attributes = window.attributes.apply { screenBrightness = value.coerceAtLeast(MIN_BRIGHTNESS) }
            showLockButton()
        }
        volumeSlider.onChange = { value ->
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
            showLockButton()
        }
    }

    /** Puts the sliders where brightness and volume are now (volume keys may have moved it). */
    private fun syncLockSliders() {
        val own = window.attributes.screenBrightness
        brightnessSlider.value = if (own >= 0) own else {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }
        val audio = getSystemService(AudioManager::class.java)
        volumeSlider.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The lock is for watching full screen; on the page the site's own buttons should just work.
        val watching = isPlaying && customView != null
        if (!isTv && event.actionMasked == MotionEvent.ACTION_DOWN && (isLocked || watching) && !isInPip()) {
            showLockButton()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun lockScreen() {
        isLocked = true
        lockShield.isVisible = true
        lockButton.setImageResource(R.drawable.ic_lock)
        lockButton.contentDescription = getString(R.string.unlock_screen)
        lockHint.animate().cancel()
        lockHint.alpha = 1f
        lockHint.animate().alpha(0f).setStartDelay(LOCK_HINT_MS).setDuration(FADE_MS).start()
        showLockButton()
    }

    private fun unlockScreen() {
        isLocked = false
        lockShield.isVisible = false
        lockButton.setImageResource(R.drawable.ic_lock_open)
        lockButton.contentDescription = getString(R.string.lock_screen)
        showLockButton()
    }

    private val hideLockButton = Runnable { (lockPanels + lockButton).forEach(::fadeOut) }

    private fun showLockButton() {
        main.removeCallbacks(hideLockButton)
        fadeIn(lockButton)
        if (isLocked) {
            if (!lockPanels[0].isVisible) syncLockSliders()
            lockPanels.forEach(::fadeIn)
        } else {
            lockPanels.forEach(::fadeOut)
        }
        main.postDelayed(hideLockButton, LOCK_BUTTON_MS)
    }

    private fun fadeIn(view: View) {
        view.animate().cancel()
        view.isVisible = true
        view.animate().alpha(1f).setDuration(FADE_MS / 2).start()
    }

    private fun fadeOut(view: View) {
        if (!view.isVisible) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction { view.isVisible = false }.start()
    }

    private fun isInPip() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // The little window shows the video alone.
        lockLayer.isVisible = !isInPictureInPictureMode
        lockShield.isVisible = isLocked && !isInPictureInPictureMode
    }

    // ---- Picture-in-picture ------------------------------------------------------------------

    private val canPip
        get() = !isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isPlaying && customView != null)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePictureInPictureParams() {
        if (canPip) pipParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (setAutoEnterEnabled); earlier versions are asked here.
        if (canPip && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isPlaying && customView != null) {
            pipParams()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
    }

    companion object {
        private const val HOME_URL = "https://spelunkr.tv/"
        private val SITE_HOSTS = setOf("spelunkr.tv", "www.spelunkr.tv")
        private val SITE_ORIGINS = setOf("https://spelunkr.tv", "https://www.spelunkr.tv")

        /** Hosts that stay inside the app: the site, its sign-in backend, and Discord's sign-in. */
        private val IN_APP_HOSTS = listOf("spelunkr.tv", "supabase.co", "discord.com", "discordapp.com")

        private const val SPLASH_TIMEOUT_MS = 8_000L
        private const val BACK_SETTLE_MS = 250L
        private const val EXIT_WINDOW_MS = 2_000L
        private const val LOCK_BUTTON_MS = 3_000L
        private const val LOCK_HINT_MS = 1_500L
        private const val FADE_MS = 400L
        private const val OK_HOLD_MS = 600L
        private const val MIN_BRIGHTNESS = 0.02f // never all the way to a black screen

        private val REMOTE_KEYS = mapOf(
            KeyEvent.KEYCODE_CHANNEL_UP to "ChannelUp",
            KeyEvent.KEYCODE_CHANNEL_DOWN to "ChannelDown",
            KeyEvent.KEYCODE_PAGE_UP to "ChannelUp",
            KeyEvent.KEYCODE_PAGE_DOWN to "ChannelDown",
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "MediaPlayPause",
            KeyEvent.KEYCODE_HEADSETHOOK to "MediaPlayPause",
            KeyEvent.KEYCODE_MEDIA_PLAY to "MediaPlay",
            KeyEvent.KEYCODE_MEDIA_PAUSE to "MediaPause",
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "MediaFastForward",
            KeyEvent.KEYCODE_MEDIA_REWIND to "MediaRewind",
            KeyEvent.KEYCODE_MEDIA_NEXT to "ChannelUp",
            KeyEvent.KEYCODE_MEDIA_PREVIOUS to "ChannelDown",
        )

        private const val PRESS_KEY_JS = """function (k) {
            var t = document.activeElement || document.body, o = { key: k, code: k, bubbles: true, cancelable: true };
            var down = t.dispatchEvent(new KeyboardEvent('keydown', o));
            t.dispatchEvent(new KeyboardEvent('keyup', o));
            return down;
        }"""

        /** What the page looks like, for telling whether a back press changed anything. */
        private const val PAGE_STATE_JS = """(function () {
            var d = document, a = d.activeElement;
            return [location.href, d.documentElement.className, d.body ? d.body.className : '',
                d.querySelectorAll('[role=dialog],[aria-modal=true],[aria-expanded=true],[open]').length,
                d.fullscreenElement ? 1 : 0, a ? a.tagName + '.' + a.className : ''].join('|');
        })()"""

        private const val BACK_PROBE_JS = """(function () {
            var before = $PAGE_STATE_JS;
            var t = document.activeElement || document.body, o = { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true };
            var notPrevented = t.dispatchEvent(new KeyboardEvent('keydown', o));
            t.dispatchEvent(new KeyboardEvent('keyup', o));
            return notPrevented ? before : 'prevented|' + before;
        })()"""

        private const val PLAYBACK_BRIDGE_JS = """(function () {
            if (window.__spelunkrBridge) return;
            window.__spelunkrBridge = true;
            // The site's own hook for a native shell: LEAVE on the "Leave Spelunkr?" card closes the app.
            window.SpelunkrApp = { leave: function () { SpelunkrNative.postMessage('leave'); } };
            var last = null;
            function report() {
                var playing = false, v = document.getElementsByTagName('video');
                for (var i = 0; i < v.length; i++) if (!v[i].paused && !v[i].ended) playing = true;
                var state = playing ? 'playing' : 'paused';
                if (state !== last && window.SpelunkrNative) { last = state; SpelunkrNative.postMessage(state); }
            }
            ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (e) {
                document.addEventListener(e, report, true);
            });

            // Full loudness: the page's own volume (and its per-tape quieting) is pinned to 100% so the
            // device volume alone sets how loud it is. Silence (0) and mute still get through.
            var volume = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'volume');
            Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                configurable: true,
                get: volume.get,
                set: function (v) { volume.set.call(this, this.tagName === 'VIDEO' && v > 0 ? 1 : v); }
            });
            // The player's volume slider (it starts at half and isn't saved) is held at full too.
            var setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            function fullSlider() {
                var s = document.querySelectorAll('input.player-volume-slider');
                for (var i = 0; i < s.length; i++) {
                    if (s[i].value === s[i].max || s[i].value === '1') continue;
                    setValue.call(s[i], s[i].max || '1');
                    s[i].dispatchEvent(new Event('input', { bubbles: true }));
                    s[i].dispatchEvent(new Event('change', { bubbles: true }));
                }
            }
            setInterval(fullSlider, 1000);
            document.addEventListener('input', function (e) {
                if (e.target.matches && e.target.matches('input.player-volume-slider')) setTimeout(fullSlider, 0);
            }, true);

            // Swiping the player up or down changes channel only in fullscreen, so on the page the
            // on-screen channel buttons (and scrolling) aren't hijacked by a swipe.
            var start = null;
            function fullscreen() {
                return !!document.fullscreenElement || document.documentElement.classList.contains('player-fills-window');
            }
            window.addEventListener('touchstart', function (e) {
                var t = e.touches[0];
                start = t && e.target.closest && e.target.closest('.stage-video-wrap') ? { x: t.clientX, y: t.clientY } : null;
            }, true);
            window.addEventListener('touchend', function (e) {
                var t = e.changedTouches[0];
                if (!start || !t || fullscreen()) return;
                var dx = t.clientX - start.x, dy = t.clientY - start.y;
                if (Math.abs(dy) >= 50 && Math.abs(dy) >= Math.abs(dx)) e.stopPropagation();
            }, true);
        })();"""

        /**
         * Favorites and channel recall, on phones and TV. `window.SPK` is what the app calls into:
         * tuneTo(id) clicks that channel's guide row (it exists on every screen and tunes in place,
         * even full screen), flip() goes back to the channel before this one, and toggleFavorite()
         * stars the channel in focus. Favorites float to the top of the guide with a star, and are
         * handed to the app for the Android TV home row.
         */
        private const val CHANNEL_FEATURES_JS = """(function () {
            if (window.SPK) return;
            var FAVS = 'spelunkr-app-favorites', RECENT = 'spelunkr-app-recent';
            function load(k) { try { return JSON.parse(localStorage.getItem(k)) || []; } catch (e) { return []; } }
            function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
            function send(m) { try { if (window.SpelunkrNative) SpelunkrNative.postMessage(m); } catch (e) {} }
            var favs = load(FAVS), recent = load(RECENT);

            var style = document.createElement('style');
            style.textContent =
                '.tv-grid>.grid-time-header{order:-2}' +
                '.tv-grid>.grid-row.spk-fav{order:-1}' +
                '.grid-row.spk-fav .grid-channel-info{position:relative}' +
                '.grid-row.spk-fav .grid-channel-info::before{content:"\\2605";position:absolute;left:2px;top:0;' +
                'font-size:.8em;color:#ffd740;text-shadow:0 0 4px #000;z-index:2}' +
                '#spk-toast{position:fixed;left:50%;top:7%;transform:translateX(-50%);z-index:2147483647;' +
                'background:#1c0a57;color:#ffd740;border:3px solid #ffd740;padding:.5em 1.2em;' +
                'font:700 clamp(14px,2.2vw,22px) monospace;letter-spacing:2px;white-space:nowrap;' +
                'box-shadow:0 4px 0 #000;pointer-events:none;opacity:0}' +
                '.spk-last-btn{white-space:nowrap}';
            (document.head || document.documentElement).appendChild(style);

            function rowOf(id) { return document.querySelector('.grid-row[data-channel="' + id + '"]'); }
            function current() { var r = document.querySelector('.grid-row.highlighted'); return r ? r.dataset.channel : null; }
            function nameOf(id) {
                var r = rowOf(id);
                var num = r && r.querySelector('.grid-channel-num'), name = r && r.querySelector('.grid-channel-name-text');
                var text = (name ? name.textContent : id).replace(/\s+/g, ' ').trim().toUpperCase();
                return num ? 'CH ' + num.textContent.trim() + ' · ' + text : text;
            }
            var toastTimer = null;
            function toast(text) {
                var t = document.getElementById('spk-toast');
                if (!t) { t = document.createElement('div'); t.id = 'spk-toast'; document.body.appendChild(t); }
                // Shows at once (a fade-in can be skipped entirely on a slow TV box); only fades out.
                t.textContent = text;
                t.style.transition = 'none';
                t.style.opacity = '1';
                clearTimeout(toastTimer);
                toastTimer = setTimeout(function () { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; }, 2000);
            }

            function paint() {
                var rows = document.querySelectorAll('.grid-row[data-channel]');
                for (var i = 0; i < rows.length; i++) {
                    var on = favs.indexOf(rows[i].dataset.channel) >= 0;
                    if (rows[i].classList.contains('spk-fav') !== on) rows[i].classList.toggle('spk-fav', on);
                }
                var last = document.querySelector('.spk-last-btn');
                if (last) last.style.display = recent[1] && rowOf(recent[1]) ? '' : 'none';
            }

            // Channel recall: the last two channels watched, newest first.
            function track() {
                var now = current();
                if (now && now !== recent[0]) {
                    recent = [now].concat(recent.filter(function (c) { return c !== now; })).slice(0, 2);
                    save(RECENT, recent);
                }
                addLastButton();
                paint();
            }

            function tuneTo(id, tries) {
                var row = rowOf(id);
                if (row) { if (current() !== id) row.click(); return true; }
                if ((tries || 0) < 40) setTimeout(function () { tuneTo(id, (tries || 0) + 1); }, 500);
                return false;
            }
            function flip() {
                var back = recent[1];
                if (!back || !rowOf(back)) { toast('NO LAST CHANNEL YET'); return false; }
                toast('⇄ ' + nameOf(back));
                return tuneTo(back);
            }

            function target() {
                var r = document.querySelector('.grid-row.spk-pending') ||
                    (document.activeElement && document.activeElement.closest && document.activeElement.closest('.grid-row[data-channel]'));
                return r ? r.dataset.channel : current();
            }
            function toggleFavorite(id) {
                var screen = history.state && history.state.screen;
                if (!id && screen !== 'guide' && screen !== 'watch') return false;
                id = id || target();
                if (!id) return false;
                var at = favs.indexOf(id);
                if (at >= 0) favs.splice(at, 1); else favs.push(id);
                save(FAVS, favs);
                send('favs:' + JSON.stringify(favs));
                paint();
                toast((at >= 0 ? '☆ REMOVED ' : '★ FAVORITE: ') + nameOf(id));
                return true;
            }

            // LAST button in the player's control row, beside SPELUNK.
            function addLastButton() {
                var spelunk = document.querySelector('.player-spelunk-btn');
                if (!spelunk || spelunk.parentNode.querySelector('.spk-last-btn')) return;
                var b = document.createElement('button');
                b.type = 'button';
                b.className = 'bbtn bbtn-outline spk-last-btn';
                b.textContent = '⇄ LAST';
                b.addEventListener('click', function (e) { e.stopPropagation(); flip(); });
                spelunk.parentNode.insertBefore(b, spelunk);
            }
            // The site takes OK for itself before a focused button sees it, so OK on LAST is caught first.
            window.addEventListener('keydown', function (e) {
                var a = document.activeElement;
                if ((e.key === 'Enter' || e.key === ' ') && a && a.classList && a.classList.contains('spk-last-btn')) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    if (!e.repeat) flip();
                }
            }, true);

            // Phones: hold a channel row in the guide to star it.
            var hold = null, held = false;
            window.addEventListener('touchstart', function (e) {
                var row = e.target.closest && e.target.closest('.grid-row[data-channel]');
                held = false;
                if (!row) return;
                hold = setTimeout(function () {
                    held = true;
                    if (navigator.vibrate) navigator.vibrate(30);
                    toggleFavorite(row.dataset.channel);
                }, 550);
            }, { capture: true, passive: true });
            ['touchend', 'touchmove', 'touchcancel'].forEach(function (t) {
                window.addEventListener(t, function () { clearTimeout(hold); }, { capture: true, passive: true });
            });
            window.addEventListener('click', function (e) {
                if (held) { held = false; e.preventDefault(); e.stopImmediatePropagation(); }
            }, true);
            window.addEventListener('contextmenu', function (e) {
                if (e.target.closest && e.target.closest('.grid-row[data-channel]')) e.preventDefault();
            }, true);

            setInterval(track, 1000);
            document.addEventListener('DOMContentLoaded', function () {
                track();
                send('favs:' + JSON.stringify(favs));
            });
            window.SPK = { tuneTo: tuneTo, flip: flip, toggleFavorite: toggleFavorite, favorites: function () { return favs.slice(); } };
        })();"""

        /**
         * TV guide: up/down move a highlight through the channel rows instead of tuning (the site
         * tunes on every press); Select tunes to the highlighted channel, back drops the highlight.
         */
        private const val TV_GUIDE_JS = """(function () {
            if (window.__spelunkrGuide) return;
            window.__spelunkrGuide = true;
            var pending = null;
            var style = document.createElement('style');
            style.textContent = '.grid-row.spk-pending{outline:4px solid #fff;outline-offset:-4px;' +
                'background-color:rgba(255,255,255,.14)}';
            (document.head || document.documentElement).appendChild(style);

            function rows() {
                // In on-screen order: starred favorites are drawn first (CSS order), then the rest.
                var all = [].slice.call(document.querySelectorAll('.grid-row[data-channel]:not([aria-disabled])'));
                return all.filter(function (r) { return r.classList.contains('spk-fav'); })
                    .concat(all.filter(function (r) { return !r.classList.contains('spk-fav'); }));
            }
            function playing(list) {
                for (var i = 0; i < list.length; i++) if (list[i].classList.contains('highlighted')) return i;
                return -1;
            }
            function mark(row) {
                if (pending) pending.classList.remove('spk-pending');
                pending = row;
                if (row) {
                    row.classList.add('spk-pending');
                    row.scrollIntoView({ block: 'nearest' });
                }
            }
            function inGuide() {
                var a = document.activeElement;
                return document.querySelector('.stage--guide') && (!a || a === document.body) &&
                    !document.querySelector('[role=dialog]');
            }
            window.addEventListener('keydown', function (e) {
                if (pending && !pending.isConnected) pending = null;
                if (!inGuide()) { mark(null); return; }
                var down = e.key === 'ArrowDown', up = e.key === 'ArrowUp';
                if (up || down) {
                    var list = rows();
                    if (!list.length) return;
                    var at = pending ? list.indexOf(pending) : playing(list);
                    var next = Math.max(0, Math.min(list.length - 1, (at < 0 ? 0 : at) + (down ? 1 : -1)));
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    mark(list[next].classList.contains('highlighted') ? null : list[next]);
                } else if ((e.key === 'Enter' || e.key === ' ') && pending) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    var row = pending;
                    mark(null);
                    row.click();
                } else if ((e.key === 'Escape' || e.key === 'GoBack') && pending) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    mark(null);
                }
            }, true);

            // Back from the guide asks "Leave Spelunkr?" with STAY / LEAVE; on TV it also offers the
            // main menu (the TUNE IN page), and LEAVE says what it does: EXIT.
            function addMainMenu(card) {
                var stay = card.querySelector('.leave-card-stay'), leave = card.querySelector('.leave-card-leave');
                if (!stay || !leave || card.querySelector('.spk-main-menu')) return;
                var menu = document.createElement('button');
                menu.type = 'button';
                menu.className = leave.className.replace('leave-card-leave', '') + ' spk-main-menu';
                menu.textContent = 'MAIN MENU';
                menu.addEventListener('click', function (e) {
                    e.stopPropagation();
                    // The rest of the OK press that chose this must not land on the menu that opens.
                    swallowOkUntil = performance.now() + 500;
                    stay.click();
                    // The site keeps its screen in history.state; "home" is the TUNE IN page.
                    history.pushState({ screen: 'home' }, '', '/' + location.search);
                    window.dispatchEvent(new PopStateEvent('popstate', { state: history.state }));
                });
                leave.parentNode.insertBefore(menu, leave);
                leave.textContent = 'EXIT';
            }
            // Main menu (the TUNE IN page): laid out like the desktop site, everything above the guide
            // strip (on TV it ran under it), with TUNE IN highlighted on arrival in a heavy yellow
            // border. The remote moves on a simple map instead of the site's spatial search, which
            // starts on an invisible container and wastes presses.
            style.textContent += 'html.tv .hp-content{zoom:.8;position:relative;left:-200px}' +
                // "FREE. THAT'S THE WHOLE OFFER." card: beside the menu on the right, smaller.
                'html.tv .hp-newspaper-ad{position:fixed;right:38px;top:236px;width:660px;zoom:.66;margin:0;z-index:5}' +
                'html.tv .hp-corner{left:-26px;right:-42px}' +
                'html.tv .hp-tv-wrapper{zoom:1.3}' +
                'html.tv .hp-pitch{margin-top:6px;margin-bottom:10px}' +
                'html.tv .hp-pitch-line{margin:0;line-height:1.5}' +
                'html.tv .hp-stats{zoom:.85}' +
                'html.tv .hp-archive-line{display:none}' +
                'html.tv .hp-tunein{margin-top:14px}' +
                'html.tv .hp-tunein-btn{position:relative;z-index:30;margin-bottom:8px}' +
                'html.tv .hp-tunein-btn:focus{outline:4px solid #ffff00!important;outline-offset:3px}' +
                'html.tv .hp-tunein-btn,html.tv .hp-corner a{transition:transform .12s ease-out}' +
                'html.tv .hp-tunein-btn:focus,html.tv .hp-corner a:focus{transform:scale(1.04)}';
            var focusedTuneIn = null;
            function homeTuneIn() {
                var b = document.querySelector('.hp-tunein-btn');
                return b && b.getBoundingClientRect().height ? b : null;
            }
            function highlightTuneIn() {
                var b = homeTuneIn();
                if (!b) { focusedTuneIn = null; return; }
                if (focusedTuneIn === b) return;
                // Keep a control the viewer already moved to; anything else (nothing, the link row's
                // container, or a leftover from the screen before) gives way to TUNE IN.
                var a = document.activeElement, home = b.closest('.hp-content');
                if (a && a !== document.body && home && home.contains(a) && !a.classList.contains('hp-corner')) return;
                focusedTuneIn = b;
                b.focus({ preventScroll: true });
            }
            window.addEventListener('keydown', function (e) {
                var tune = homeTuneIn();
                if (!tune || document.querySelector('[role=dialog]')) return;
                var dir = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' }[e.key];
                if (!dir) return;
                var links = [].slice.call(document.querySelectorAll('.hp-corner a'));
                var a = document.activeElement, at = links.indexOf(a), next = null;
                if (a === tune) {
                    if (dir === 'up') next = links[Math.min(1, links.length - 1)] || null;
                } else if (at >= 0) {
                    if (dir === 'down') next = tune;
                    else if (dir === 'left') next = links[Math.max(0, at - 1)];
                    else if (dir === 'right') next = links[Math.min(links.length - 1, at + 1)];
                } else {
                    next = tune;
                }
                e.preventDefault();
                e.stopImmediatePropagation();
                if (next) next.focus({ preventScroll: true });
            }, true);

            var swallowOkUntil = 0;
            ['keydown', 'keypress', 'keyup'].forEach(function (type) {
                window.addEventListener(type, function (e) {
                    if ((e.key === 'Enter' || e.key === ' ') && performance.now() < swallowOkUntil) {
                        e.preventDefault();
                        e.stopImmediatePropagation();
                    }
                }, true);
            });

            new MutationObserver(function () {
                var card = document.querySelector('.leave-card');
                if (card) addMainMenu(card);
                highlightTuneIn();
            }).observe(document.documentElement, { childList: true, subtree: true });
        })();"""

        private const val PAUSE_JS = """(function () {
            var v = document.getElementsByTagName('video');
            for (var i = 0; i < v.length; i++) if (!v[i].paused) { v[i].dataset.spelunkrResume = '1'; v[i].pause(); }
        })()"""

        private const val RESUME_JS = """(function () {
            var v = document.querySelectorAll('video[data-spelunkr-resume]');
            for (var i = 0; i < v.length; i++) { delete v[i].dataset.spelunkrResume; v[i].play().catch(function () {}); }
        })()"""
    }
}
