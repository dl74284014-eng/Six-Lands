package com.sixlands.game;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Игра грузится с того же адреса appassets.androidplatform.net, поэтому сохранения
 * не теряются. Каждый файл берётся так: если в интернете есть новая версия
 * (по manifest.json на GitHub) — она скачивается и кэшируется; без интернета
 * используется кэш или файлы, встроенные в APK.
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String PREFIX = "/assets/www/";
    static final String REPO = "dl74284014-eng/Six-Lands";
    static final String PATH = "/app/src/main/assets/www/";
    private volatile String remoteBase = "https://raw.githubusercontent.com/" + REPO + "/main" + PATH;
    private volatile String status = "проверка обновлений...";
    private volatile int fetched = 0;

    private WebView web;
    private final Map<String, String> bundled = new HashMap<>();
    private volatile Map<String, String> remote = null;
    private File cacheDir;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0d0b08"));
        w.setNavigationBarColor(Color.parseColor("#13110e"));
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cacheDir = new File(getFilesDir(), "www");
        cacheDir.mkdirs();
        try { bundled.putAll(parse(new String(readAll(getAssets().open("www/manifest.json")), "UTF-8"))); } catch (Exception ignored) {}

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d0b08"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost()) || u.getPath() == null || !u.getPath().startsWith(PREFIX)) return null;
                return serve(u.getPath().substring(PREFIX.length()));
            }
        });
        setContentView(web);

        new Thread(() -> {
            try {
                String rev = null;
                byte[] c = download("https://api.github.com/repos/" + REPO + "/commits/main", 6000);
                if (c != null) rev = new JSONObject(new String(c, "UTF-8")).getString("sha");
                if (rev != null) remoteBase = "https://raw.githubusercontent.com/" + REPO + "/" + rev + PATH;
                byte[] m = download(remoteBase + "manifest.json", 8000);
                status = m != null ? ("онлайн, сборка " + (rev != null ? rev.substring(0, 7) : "main")) : "нет связи, офлайн-версия";
                if (m != null) {
                    remote = parse(new String(m, "UTF-8"));
                    write(new File(cacheDir, "manifest.json"), m);
                }
            } catch (Exception ignored) { status = "нет связи, офлайн-версия"; }
            if (remote == null) {
                try { remote = parse(new String(readFile(new File(cacheDir, "manifest.json")), "UTF-8")); } catch (Exception ignored) {}
            }
            runOnUiThread(() -> web.loadUrl("https://" + HOST + PREFIX + "index.html"));
        }).start();
    }

    private WebResourceResponse serve(String rel) {
        try {
            String want = remote != null ? remote.get(rel) : null;
            if (want == null) want = bundled.get(rel);
            File f = new File(cacheDir, rel);
            File h = new File(cacheDir, rel + ".sha");
            byte[] data = null;
            if (want != null && want.equals(bundled.get(rel))) {
                data = readAll(getAssets().open("www/" + rel));
            } else if (want != null && f.exists() && h.exists() && want.equals(new String(readFile(h), "UTF-8"))) {
                data = readFile(f);
            } else if (want != null && remote != null) {
                data = download(remoteBase + rel, 15000);
                if (data != null && !want.equals(sha16(data))) data = null;
                if (data != null) { f.getParentFile().mkdirs(); write(f, data); write(h, want.getBytes("UTF-8")); fetched++; }
            }
            if (data == null && f.exists()) data = readFile(f);
            if (data == null) data = readAll(getAssets().open("www/" + rel));
            WebResourceResponse r = new WebResourceResponse(mime(rel), rel.endsWith(".html") ? "utf-8" : null, new java.io.ByteArrayInputStream(data));
            Map<String, String> hd = new HashMap<>();
            hd.put("Cache-Control", "no-cache");
            hd.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(hd);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    static String sha16(byte[] d) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(d);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) { return ""; }
    }

    static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    static Map<String, String> parse(String json) throws Exception {
        JSONObject o = new JSONObject(json).getJSONObject("files");
        Map<String, String> m = new HashMap<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, o.getString(k)); }
        return m;
    }

    static byte[] download(String url, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout * 3);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "SixLands-Android");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    static byte[] readFile(File f) throws Exception { return readAll(new FileInputStream(f)); }

    static void write(File f, byte[] d) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        o.write(d);
        o.close();
        tmp.renameTo(f);
    }

    class Bridge {
        @JavascriptInterface
        public void exit() { runOnUiThread(() -> finishAndRemoveTask()); }
        @JavascriptInterface
        public String status() { return status + (fetched > 0 ? ", скачано файлов: " + fetched : ""); }
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){try{return window.onAndroidBack?onAndroidBack():0}catch(e){return 0}})()",
            v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }
}
