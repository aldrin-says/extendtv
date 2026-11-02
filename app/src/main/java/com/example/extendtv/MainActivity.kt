package com.example.extendtv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * ExtendTV: a TV-friendly shell around tiktok.com.
 *
 * Remote:
 *  - UP / DOWN   next / previous video
 *  - CENTER      play / pause
 *  - RIGHT       move the highlight onto Like / Comment / Share / Follow
 *                (then UP/DOWN moves the highlight, CENTER presses it, LEFT goes back)
 *  - Comments    UP/DOWN scrolls the comments, LEFT closes them
 *  - MENU or long-press CENTER   options (guest feed, sign in, reload, sign out)
 *  - BACK        go back / exit
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var centerDownAt = 0L

    private val forYouUrl = "https://www.tiktok.com/foryou"
    private val loginUrl = "https://www.tiktok.com/login"

    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        webView = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress)
        setContentView(root)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = desktopUa
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                view?.evaluateJavascript(TV_JS, null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return false
                return !(host.endsWith("tiktok.com") || host.endsWith("tiktokcdn.com") ||
                    host.endsWith("tiktokv.com") || host.endsWith("byteoversea.com"))
            }
        }
        webView.webChromeClient = WebChromeClient()

        if (savedInstanceState == null) webView.loadUrl(forYouUrl)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    // ---------- Remote control ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN

        // On login / search pages let the D-pad move between fields normally.
        val isNavKey = event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (isNavKey && onFormPage()) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (down && event.repeatCount == 0) tvKey("down")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (down && event.repeatCount == 0) tvKey("up")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down && event.repeatCount == 0) tvKey("right")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down && event.repeatCount == 0) tvKey("left")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (down) tvKey("ok")
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) centerDownAt = event.eventTime
                    if (event.repeatCount > 0 && event.eventTime - centerDownAt > 700) {
                        showMenu()
                        centerDownAt = Long.MAX_VALUE
                    }
                } else if (event.eventTime - centerDownAt in 0..700) {
                    tvKey("ok")
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (!down) showMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (!down) {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun onFormPage(): Boolean {
        val url = webView.url ?: return false
        return url.contains("/login") || url.contains("/search")
    }

    private fun tvKey(k: String) {
        // TV_JS is guarded, so re-running it is safe and covers page changes.
        webView.evaluateJavascript(TV_JS + "\nwindow.tvKey && window.tvKey('" + k + "');", null)
    }

    // ---------- Menu ----------

    private fun showMenu() {
        val items = arrayOf(
            "For You (guest)",
            "Sign in / QR login",
            "Following",
            "Reload",
            "Sign out & clear data"
        )
        AlertDialog.Builder(this)
            .setTitle("ExtendTV")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.loadUrl(forYouUrl)
                    1 -> webView.loadUrl(loginUrl)
                    2 -> webView.loadUrl("https://www.tiktok.com/following")
                    3 -> webView.reload()
                    4 -> clearSession()
                }
            }
            .show()
    }

    private fun clearSession() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(forYouUrl)
        }
    }

    companion object {
        /**
         * TV layer injected into tiktok.com. It does NOT rely on synthetic keyboard
         * events; it scrolls between videos itself and clicks the page's own buttons.
         */
        private const val TV_JS = """
(function(){
  if (window.tvKey) return;
  var st = {mode:'feed', idx:0, items:[]};

  var css = document.createElement('style');
  css.textContent =
    '.tv-focus{outline:6px solid #25F4EE !important;outline-offset:4px !important;' +
    'border-radius:14px !important;background:rgba(37,244,238,.35) !important;}' +
    '#tv-toast{position:fixed;left:50%;bottom:28px;transform:translateX(-50%);' +
    'background:rgba(0,0,0,.75);color:#fff;font:600 22px sans-serif;padding:10px 22px;' +
    'border-radius:22px;z-index:2147483647;pointer-events:none;transition:opacity .3s;}';
  document.head.appendChild(css);

  var hideCss = document.createElement('style');
  hideCss.textContent =
    'header,[class*="DivSideNavContainer"],[class*="DivHeaderContainer"],' +
    '[class*="DivTopNavContainer"]{display:none !important;}';
  function syncChrome(){
    var onLogin = location.pathname.indexOf('login') >= 0;
    if (onLogin && hideCss.parentNode) hideCss.parentNode.removeChild(hideCss);
    if (!onLogin && !hideCss.parentNode) document.head.appendChild(hideCss);
  }
  syncChrome();
  setInterval(syncChrome, 1500);

  function toast(t){
    var e = document.getElementById('tv-toast');
    if (!e) { e = document.createElement('div'); e.id = 'tv-toast'; document.body.appendChild(e); }
    e.textContent = t; e.style.opacity = '1';
    clearTimeout(window.__tvT);
    window.__tvT = setTimeout(function(){ e.style.opacity = '0'; }, 1800);
  }

  function articles(){
    var a = [].slice.call(document.querySelectorAll('article[data-e2e="recommend-list-item-container"]'));
    if (!a.length) a = [].slice.call(document.querySelectorAll('article'));
    return a;
  }
  function current(){
    var a = articles(); if (!a.length) return null;
    var mid = window.innerHeight / 2, best = null, bd = 1e9;
    a.forEach(function(x){
      var r = x.getBoundingClientRect(), d = Math.abs((r.top + r.bottom) / 2 - mid);
      if (d < bd) { bd = d; best = x; }
    });
    return best;
  }
  function visible(el){
    var r = el.getBoundingClientRect();
    return r.width > 4 && r.height > 4 && r.bottom > 0 && r.top < window.innerHeight;
  }
  function clickable(el){ return el.closest('button,[role="button"],a') || el; }

  function scrollVideo(dir){
    var a = articles(), cur = current(); if (!cur) return;
    var i = a.indexOf(cur) + dir;
    if (i < 0 || i >= a.length) { window.scrollBy(0, dir * window.innerHeight); return; }
    a[i].scrollIntoView({behavior:'smooth', block:'center'});
  }
  function togglePlay(){
    var cur = current();
    var v = cur ? cur.querySelector('video') : document.querySelector('video');
    if (!v) { toast('No video here'); return; }
    if (v.paused) { v.play(); toast('Play'); } else { v.pause(); toast('Paused'); }
  }

  function collectActions(){
    var cur = current(); if (!cur) return [];
    var sel = '[data-e2e*="like"],[data-e2e*="comment-icon"],[data-e2e*="comment"],' +
              '[data-e2e*="share"],[data-e2e*="follow"],[data-e2e*="favorite"],' +
              '[data-e2e*="save"]';
    var seen = [], out = [];
    var scope = cur;
    var found = scope.querySelectorAll(sel);
    if (!found.length && cur.parentElement) found = cur.parentElement.querySelectorAll(sel);
    [].slice.call(found).forEach(function(n){
      var c = clickable(n);
      if (seen.indexOf(c) >= 0 || !visible(c)) return;
      var key = (n.getAttribute('data-e2e') || '');
      if (/count|text|title|desc|author|music|hashtag|user|avatar-container/.test(key) &&
          !/follow/.test(key)) return;
      seen.push(c); out.push(c);
    });
    out.sort(function(a, b){ return a.getBoundingClientRect().top - b.getBoundingClientRect().top; });
    return out;
  }
  function label(el){
    var k = (el.getAttribute('data-e2e') || '') +
            ((el.querySelector('[data-e2e]') || {getAttribute:function(){return '';}}).getAttribute('data-e2e') || '');
    k = k.toLowerCase();
    if (k.indexOf('like') >= 0) return 'Like';
    if (k.indexOf('comment') >= 0) return 'Comments';
    if (k.indexOf('share') >= 0) return 'Share';
    if (k.indexOf('follow') >= 0) return 'Follow';
    if (k.indexOf('favorite') >= 0 || k.indexOf('save') >= 0) return 'Save';
    return 'Button';
  }
  function clearFocus(){
    [].slice.call(document.querySelectorAll('.tv-focus')).forEach(function(e){ e.classList.remove('tv-focus'); });
  }
  function focusItem(i){
    clearFocus();
    if (!st.items.length) return;
    st.idx = (i + st.items.length) % st.items.length;
    var el = st.items[st.idx];
    el.classList.add('tv-focus');
    toast(label(el) + '   (OK = press, LEFT = back)');
  }

  function scrollComments(dir){
    var base = document.querySelector('[data-e2e="comment-list"]') ||
               document.querySelector('[class*="DivCommentListContainer"]') ||
               document.querySelector('[class*="CommentList"]');
    var n = base;
    while (n && n !== document.body) {
      var oy = getComputedStyle(n).overflowY;
      if ((oy === 'auto' || oy === 'scroll') && n.scrollHeight > n.clientHeight + 10) {
        n.scrollBy({top: dir * 320, behavior:'smooth'}); return;
      }
      n = n.parentElement;
    }
    window.scrollBy(0, dir * 320);
  }
  function closeComments(){
    var c = document.querySelector('[data-e2e="browse-close"],[data-e2e="close-icon"],button[aria-label*="lose"]');
    if (c) clickable(c).click();
    else {
      var o = {key:'Escape', code:'Escape', keyCode:27, which:27, bubbles:true};
      document.dispatchEvent(new KeyboardEvent('keydown', o));
    }
  }

  window.tvKey = function(k){
    if (st.mode === 'feed') {
      if (k === 'down') scrollVideo(1);
      else if (k === 'up') scrollVideo(-1);
      else if (k === 'ok') togglePlay();
      else if (k === 'right') {
        st.items = collectActions();
        if (!st.items.length) { toast('No buttons found on this video'); return; }
        st.mode = 'actions'; focusItem(0);
      }
      else if (k === 'left') toast('UP/DOWN videos  OK play  RIGHT buttons');
    } else if (st.mode === 'actions') {
      if (k === 'down') focusItem(st.idx + 1);
      else if (k === 'up') focusItem(st.idx - 1);
      else if (k === 'left') { clearFocus(); st.mode = 'feed'; toast('Back to videos'); }
      else if (k === 'ok') {
        var el = st.items[st.idx]; if (!el) return;
        var isComments = label(el) === 'Comments';
        el.click();
        if (isComments) { clearFocus(); st.mode = 'comments'; toast('UP/DOWN scroll, LEFT close'); }
        else toast(label(el) + ' pressed');
      }
    } else if (st.mode === 'comments') {
      if (k === 'down') scrollComments(1);
      else if (k === 'up') scrollComments(-1);
      else if (k === 'left' || k === 'ok') { closeComments(); st.mode = 'feed'; toast('Back to videos'); }
    }
  };
  toast('ExtendTV ready: UP/DOWN videos, OK pause, RIGHT buttons');
})();
"""
    }
}
