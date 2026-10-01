/*
 * PinaPos — aplikasi kasir untuk UMKM.
 * Copyright (c) 2026 ASKER. All rights reserved. Powered by ASKER.
 *
 * Pembungkus WebView: menjalankan PinaPos (kasirku/index.html) dari dalam APK,
 * tanpa server dan tanpa internet. Data tersimpan di penyimpanan aplikasi.
 */
package id.asker.pinapos;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.util.Base64;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/www/kasirku/";
    private static final int FILE_REQUEST = 7;
    private static final int BT_PERMISSION = 8;

    private WebView web;
    private WebView printWeb;               // disimpan agar tidak dibersihkan GC saat mencetak
    private ValueCallback<Uri[]> fileCallback;
    private BtPrinter bt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bt = new BtPrinter(this);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("https".equals(u.getScheme()) && HOST.equals(u.getHost())) {
                    return false;
                }
                openExternal(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Bilah "Mode demo / Simulasi: putuskan server" tidak relevan di aplikasi Android.
                view.evaluateJavascript(
                        "(function(){var st=document.createElement('style');" +
                        "st.textContent='.demo-note{display:none!important}';" +
                        "document.head.appendChild(st);})();", null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("Tidak ada aplikasi untuk memilih file.");
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "KasirkuAndroid");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BASE + "index.html?app=1");   // mode aplikasi: data kosong milik toko
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Biarkan aplikasi menutup menu/dialog lebih dulu; bila tidak ada, keluar.
        web.evaluateJavascript("window.ksrBack ? !!window.ksrBack() : false", result -> {
            if (!"true".equals(result)) {
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (bt != null) {
            bt.close();
        }
        super.onDestroy();
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void btCallback(String id, boolean ok, String msg) {
        final String js = "window.__btDone && window.__btDone(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(msg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("window.ksrFlush && window.ksrFlush()", null);
    }

    private void openExternal(String url) {
        try {
            Intent i = url.startsWith("intent:")
                    ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast(url.contains("rawbt") ? "Aplikasi RawBT belum terpasang." : "Tidak ada aplikasi untuk membuka tautan ini.");
        } catch (Exception e) {
            toast("Tidak dapat membuka: " + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void notifySaved(boolean ok, String msg) {
        final String js = "window.ksrSaved && window.ksrSaved(" + ok + "," + JSONObject.quote(msg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    /** Jembatan yang dipanggil dari halaman web (window.KasirkuAndroid). */
    private class Bridge {

        /** Cetak dokumen HTML lewat layanan cetak Android (printer, RawBT, atau Simpan sebagai PDF). */
        @JavascriptInterface
        public void print(final String html, final String jobName) {
            runOnUiThread(() -> {
                final String job = (jobName == null || jobName.isEmpty()) ? "PinaPos" : jobName;
                WebView pw = new WebView(MainActivity.this);
                pw.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                        if (pm != null) {
                            pm.print(job, view.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
                        }
                    }
                });
                printWeb = pw;
                pw.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
            });
        }

        /** Simpan file (mis. backup .json) ke folder Download. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String data) {
            try {
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PinaPos");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        throw new IllegalStateException("Penyimpanan tidak tersedia");
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(bytes);
                    }
                    where = "Download/PinaPos/" + name;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    where = f.getAbsolutePath();
                }
                notifySaved(true, where);
            } catch (Exception e) {
                notifySaved(false, "Gagal menyimpan: " + e.getMessage());
            }
        }

        /** Daftar printer Bluetooth yang sudah dipasangkan; atau {"error": ...}. */
        @JavascriptInterface
        public String btList() {
            if (!bt.available()) {
                return "{\"error\":\"nobt\"}";
            }
            if (!hasBtPermission()) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BT_PERMISSION));
                return "{\"error\":\"permission\"}";
            }
            if (!bt.enabled()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                    } catch (Exception ignored) {
                    }
                });
                return "{\"error\":\"off\"}";
            }
            return bt.bonded();
        }

        @JavascriptInterface
        public boolean btConnected(String address) {
            return bt.isConnected(address);
        }

        @JavascriptInterface
        public void btConnect(String address, final String id) {
            if (!hasBtPermission()) {
                btCallback(id, false, "Izinkan PinaPos mengakses Perangkat sekitar (Bluetooth) lebih dulu.");
                return;
            }
            bt.connect(address, (ok, msg) -> btCallback(id, ok, msg));
        }

        @JavascriptInterface
        public void btWrite(String address, String base64, final String id) {
            if (!hasBtPermission()) {
                btCallback(id, false, "Izinkan PinaPos mengakses Perangkat sekitar (Bluetooth) lebih dulu.");
                return;
            }
            byte[] data;
            try {
                data = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                btCallback(id, false, "Data struk rusak.");
                return;
            }
            bt.write(address, data, (ok, msg) -> btCallback(id, ok, msg));
        }

        @JavascriptInterface
        public void btClose() {
            bt.close();
        }

        /** Samakan warna status bar dengan tema yang dipilih di aplikasi. */
        @JavascriptInterface
        public void setStatusBar(final String hex) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(hex);
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
