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
        main.postDelayed({ splash.isVisible = false }, SPLASH_TIMEOUT_MS)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) webView.loadUrl(startUrl(intent))
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
            when (message.data) {
                "playing" -> onPlaybackChanged(true)
                "paused" -> onPlaybackChanged(false)
                "leave" -> finish()
            }
        }
        WebViewCompat.addDocumentStartJavaScript(view, PLAYBACK_BRIDGE_JS, SITE_ORIGINS)
        if (isTv) WebViewCompat.addDocumentStartJavaScript(view, TV_GUIDE_JS, SITE_ORIGINS)
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
        val key = REMOTE_KEYS[event.keyCode] ?: return super.dispatchKeyEvent(event)
        // WebView doesn't hand these to the page, so they are sent as the DOM keys the site listens for.
        if (event.action == KeyEvent.ACTION_DOWN) {
            webView.evaluateJavascript("($PRESS_KEY_JS)('$key')", null)
        }
        return true
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
                return [].slice.call(document.querySelectorAll('.grid-row[data-channel]:not([aria-disabled])'));
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
