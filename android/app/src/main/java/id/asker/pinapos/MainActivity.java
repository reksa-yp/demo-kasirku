/*
 * PinaPos — aplikasi kasir untuk UMKM.
 * Copyright (c) 2026 ASKER. All rights reserved. Powered by ASKER.
 *
 * Pembungkus WebView: menjalankan PinaPos (kasirku/) atau CuciAja (cuciaja/) dari dalam APK,
 * tanpa server dan tanpa internet. Data tersimpan di penyimpanan aplikasi.
 */
package id.asker.pinapos;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/www/" + BuildConfig.APP_DIR + "/";
    private static final int FILE_REQUEST = 7;
    private static final int BT_REQUEST = 8;
    private static final int CAM_REQUEST = 9;
    private PermissionRequest camRequest;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private WebView web;
    private WebView printWeb;               // disimpan agar tidak dibersihkan GC saat mencetak
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            /** Kamera untuk scan barcode (getUserMedia): minta izin Android dulu, lalu izinkan halaman aplikasi sendiri. */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean video = false;
                    for (String r : request.getResources()) { if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { video = true; } }
                    if (!video || !request.getOrigin().getHost().equals("appassets.androidplatform.net")) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        if (camRequest != null) { camRequest.deny(); }
                        camRequest = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAM_REQUEST);
                    }
                });
            }

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

        web.addJavascriptInterface(new Bridge(), BuildConfig.BRIDGE);   // KasirkuAndroid (PinaPos) / CuciAjaAndroid (CuciAja)

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(BASE + "index.html");
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAM_REQUEST && camRequest != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) { camRequest.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); } else { camRequest.deny(); }
            camRequest = null;
        }
        if (requestCode == BT_REQUEST) {
            final boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            web.post(() -> web.evaluateJavascript("window.ksrBtPerm && window.ksrBtPerm(" + ok + ")", null));
        }
    }

    private boolean btAllowed() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void apkMsg(String stage, String msg) {
        final String js = "window.ksrApk && window.ksrApk(" + JSONObject.quote(stage) + "," + JSONObject.quote(msg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void notifyBt(boolean ok, String msg) {
        final String js = "window.ksrBtDone && window.ksrBtDone(" + ok + "," + JSONObject.quote(msg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
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

    private void notifyImage(boolean ok, String msg) {
        final String js = "window.ksrImage && window.ksrImage(" + ok + "," + JSONObject.quote(msg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    /** Jembatan yang dipanggil dari halaman web (window.KasirkuAndroid atau window.CuciAjaAndroid). */
    private class Bridge {

        /** Cetak dokumen HTML lewat layanan cetak Android (printer, RawBT, atau Simpan sebagai PDF). */
        @JavascriptInterface
        public void print(final String html, final String jobName) {
            runOnUiThread(() -> {
                final String job = (jobName == null || jobName.isEmpty()) ? BuildConfig.APP_NAME : jobName;
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

        /** Daftar printer Bluetooth yang sudah di-pairing di Android. Meminta izin "Perangkat sekitar" bila belum ada. */
        @JavascriptInterface
        public String btDevices() {
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) {
                    return "{\"ok\":false,\"err\":\"nobt\"}";
                }
                if (!btAllowed()) {
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BT_REQUEST));
                    return "{\"ok\":false,\"err\":\"perm\"}";
                }
                if (!ad.isEnabled()) {
                    return "{\"ok\":false,\"err\":\"off\"}";
                }
                JSONArray arr = new JSONArray();
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        JSONObject o = new JSONObject();
                        o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                        o.put("addr", d.getAddress());
                        arr.put(o);
                    }
                }
                return new JSONObject().put("ok", true).put("list", arr).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"err\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** Kirim data ESC/POS (base64) langsung ke printer Bluetooth Classic (SPP). Hasil lewat window.ksrBtDone. */
        @JavascriptInterface
        public void btPrint(final String addr, final String b64) {
            new Thread(() -> {
                BluetoothSocket sock = null;
                try {
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (ad == null || !ad.isEnabled()) {
                        notifyBt(false, "Bluetooth HP belum menyala.");
                        return;
                    }
                    if (!btAllowed()) {
                        runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BT_REQUEST));
                        notifyBt(false, "Izinkan akses \"Perangkat sekitar\", lalu coba cetak lagi.");
                        return;
                    }
                    byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    BluetoothDevice dev = ad.getRemoteDevice(addr);
                    try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }   // butuh izin SCAN di Android 12+; tidak wajib
                    try {
                        sock = dev.createRfcommSocketToServiceRecord(SPP);
                        sock.connect();
                    } catch (Exception first) {
                        try { if (sock != null) { sock.close(); } } catch (Exception ignored) { }
                        // cadangan untuk printer murah yang tidak mengiklankan layanan SPP
                        sock = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                        sock.connect();
                    }
                    OutputStream os = sock.getOutputStream();
                    int off = 0;
                    while (off < data.length) {
                        int n = Math.min(512, data.length - off);
                        os.write(data, off, n);
                        os.flush();
                        off += n;
                        Thread.sleep(30);
                    }
                    Thread.sleep(500);   // beri waktu printer menerima sisa buffer sebelum koneksi ditutup
                    notifyBt(true, "ok");
                } catch (SecurityException e) {
                    notifyBt(false, "Izin Bluetooth ditolak. Aktifkan \"Perangkat sekitar\" untuk aplikasi ini di Setelan.");
                } catch (Exception e) {
                    notifyBt(false, "Gagal tersambung ke printer: " + e.getMessage() + ". Pastikan printer menyala, dekat, dan tidak sedang dipakai aplikasi lain.");
                } finally {
                    try { if (sock != null) { sock.close(); } } catch (Exception ignored) { }
                }
            }).start();
        }

        /** Unduh APK pembaruan (hanya dari rilis GitHub proyek ini) lalu buka pemasang Android. Hasil lewat window.ksrApk. */
        @JavascriptInterface
        public void installApk(final String url, final String name) {
            if (url == null || !url.startsWith("https://github.com/reksa-yp/demo-kasirku/releases/download/")) {
                apkMsg("fail", "Alamat unduhan tidak dikenali.");
                return;
            }
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) { }
                });
                apkMsg("perm", "");
                return;
            }
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "apk");
                    if (!dir.exists()) { dir.mkdirs(); }
                    File[] old = dir.listFiles();
                    if (old != null) { for (File f : old) { f.delete(); } }
                    File out = new File(dir, "update.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    if (c.getResponseCode() != 200) {
                        throw new IllegalStateException("HTTP " + c.getResponseCode());
                    }
                    try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); }
                    }
                    c.disconnect();
                    final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out);
                    runOnUiThread(() -> {
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(uri, "application/vnd.android.package-archive");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception e) {
                            apkMsg("fail", "Tidak dapat membuka pemasang: " + e.getMessage());
                        }
                    });
                    apkMsg("ready", "");
                } catch (Exception e) {
                    apkMsg("fail", "Gagal mengunduh pembaruan: " + e.getMessage());
                }
            }).start();
        }

        /** Buka setelan Bluetooth Android (untuk pairing printer). */
        @JavascriptInterface
        public void btSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception e) { toast("Tidak dapat membuka setelan Bluetooth."); }
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
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + BuildConfig.APP_NAME);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        throw new IllegalStateException("Penyimpanan tidak tersedia");
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(bytes);
                    }
                    where = "Download/" + BuildConfig.APP_NAME + "/" + name;
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

        /** Simpan gambar PNG (base64) ke galeri: Pictures/<nama aplikasi>. Hasil lewat window.ksrImage. */
        @JavascriptInterface
        public void saveImage(final String name, final String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + BuildConfig.APP_NAME);
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        throw new IllegalStateException("Penyimpanan tidak tersedia");
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        os.write(bytes);
                    }
                    where = "Galeri, album " + BuildConfig.APP_NAME;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    File f = new File(dir, name);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(bytes);
                    }
                    where = f.getAbsolutePath();
                }
                notifyImage(true, where);
            } catch (Exception e) {
                notifyImage(false, "Gagal menyimpan gambar: " + e.getMessage());
            }
        }

        /** Buka tautan luar (WhatsApp, Saweria, RawBT) di aplikasi lain. */
        @JavascriptInterface
        public void openExternal(final String url) {
            runOnUiThread(() -> MainActivity.this.openExternal(url));
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
