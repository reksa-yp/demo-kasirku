package com.hdt.kasir;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Pembungkus aplikasi kasir (Kasirku / CuciAja) untuk Android, 100% offline.
 * Tampilan & logika ada di assets/index.html; kelas ini hanya menyediakan
 * jembatan ke fitur HP: simpan data ke file, cetak, simpan/buka file,
 * membuka WhatsApp/RawBT, dan printer struk Bluetooth (yang sudah di-pairing).
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_SAVE = 12, REQ_BT = 13;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private WebView web;
    private WebView printView;                 // disimpan supaya tidak di-GC saat mencetak
    private ValueCallback<Uri[]> fileCallback;
    private String pendingSaveData, pendingSaveName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#1f2937"));

        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        web.addJavascriptInterface(new Bridge(), BuildConfig.BRIDGE);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) { fileCallback.onReceiveValue(null); }
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return openUrl(req.getUrl().toString());
            }
        });

        if (savedInstanceState != null) { web.restoreState(savedInstanceState); }
        else { web.loadUrl("file:///android_asset/index.html"); }
    }

    /** Tautan di luar aplikasi (WhatsApp, RawBT, browser) dibuka lewat aplikasi lain. */
    private boolean openUrl(String url) {
        if (url == null || url.startsWith("file:") || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("blob:")) { return false; }
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                try { startActivity(i); }
                catch (ActivityNotFoundException e) {
                    String pkg = i.getPackage();
                    if (pkg != null) {
                        toast("Aplikasi belum terpasang. Membuka Play Store…");
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                    } else { toast("Tidak ada aplikasi untuk membuka tautan ini."); }
                }
                return true;
            }
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            toast("Tidak dapat membuka: " + e.getMessage());
        }
        return true;
    }

    private void js(final String code) {
        runOnUiThread(() -> { if (web != null) { web.evaluateJavascript(code, null); } });
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.ksrBack && window.ksrBack()) ? '1' : '0'", v -> {
            if (v == null || !v.contains("1")) {
                if (web.canGoBack()) { web.goBack(); } else { finish(); }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) { web.evaluateJavascript("window.ksrFlush && window.ksrFlush()", null); }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCallback = null;
            }
        } else if (req == REQ_SAVE) {
            String name = pendingSaveName, body = pendingSaveData;
            pendingSaveName = null; pendingSaveData = null;
            if (res != RESULT_OK || data == null || data.getData() == null || body == null) {
                js("window.ksrSaved && window.ksrSaved(false, 'Dibatalkan')");
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                js("window.ksrSaved && window.ksrSaved(true, " + JSONObject.quote(name) + ")");
            } catch (Exception e) {
                js("window.ksrSaved && window.ksrSaved(false, " + JSONObject.quote("Gagal menyimpan: " + e.getMessage()) + ")");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_BT) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "Izin Bluetooth diberikan. Tekan \"Muat ulang daftar\"." : "Izin Bluetooth ditolak — printer Bluetooth tidak bisa dipakai.");
        }
    }

    /* ------------------------------------------------------------------ */

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT));
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private File dataFile() { return new File(getFilesDir(), "data.json"); }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); }
        return bo.toString("UTF-8");
    }

    private BluetoothSocket openSocket(BluetoothDevice d) throws Exception {
        Exception last = null;
        try { BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP); s.connect(); return s; } catch (Exception e) { last = e; }
        try { BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(SPP); s.connect(); return s; } catch (Exception e) { last = e; }
        try {
            Method m = d.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(d, 1);
            s.connect();
            return s;
        } catch (Exception e) { last = e; }
        throw last;
    }

    /** Dipanggil dari JavaScript lewat window.KasirkuAndroid / window.CuciAjaAndroid. */
    private class Bridge {
        @JavascriptInterface
        public String readData() {
            File f = dataFile();
            if (!f.exists()) { return null; }
            try (FileInputStream in = new FileInputStream(f)) { return readAll(in); }
            catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public boolean writeData(String json) {
            File f = dataFile(), tmp = new File(getFilesDir(), "data.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) { return false; }
            return tmp.renameTo(f);
        }

        @JavascriptInterface
        public void print(final String html, final String job) {
            runOnUiThread(() -> {
                final WebView pv = new WebView(MainActivity.this);
                printView = pv;
                pv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        String name = (job == null || job.isEmpty()) ? BuildConfig.APP_NAME : job;
                        PrintDocumentAdapter ad = v.createPrintDocumentAdapter(name);
                        pm.print(name, ad, new PrintAttributes.Builder().build());
                    }
                });
                pv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String data) {
            runOnUiThread(() -> {
                pendingSaveName = name; pendingSaveData = data;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); }
                catch (Exception e) { js("window.ksrSaved && window.ksrSaved(false, " + JSONObject.quote("Tidak dapat menyimpan file: " + e.getMessage()) + ")"); }
            });
        }

        @JavascriptInterface
        public void openExternal(final String url) { runOnUiThread(() -> openUrl(url)); }

        @JavascriptInterface
        public void openBtSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
                catch (Exception e) { toast("Buka Setelan → Bluetooth secara manual."); }
            });
        }

        /** Daftar printer/perangkat Bluetooth yang sudah di-pairing di HP. */
        @JavascriptInterface
        public String btList() {
            try {
                JSONObject r = new JSONObject();
                BluetoothAdapter ad = adapter();
                if (ad == null) { return r.put("ok", false).put("error", "NONE").toString(); }
                if (!hasBtPermission()) { askBtPermission(); return r.put("ok", false).put("error", "PERM").toString(); }
                if (!ad.isEnabled()) { return r.put("ok", false).put("error", "OFF").toString(); }
                JSONArray arr = new JSONArray();
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        String nm = d.getName();
                        arr.put(new JSONObject().put("name", nm == null ? "" : nm).put("address", d.getAddress()));
                    }
                }
                return r.put("ok", true).put("devices", arr).toString();
            } catch (SecurityException e) {
                askBtPermission();
                return "{\"ok\":false,\"error\":\"PERM\"}";
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        /** Kirim data ESC/POS (base64) ke printer Bluetooth. Hasil lewat window.__nbtDone(ok, pesan). */
        @JavascriptInterface
        public void btPrint(final String address, final String b64) {
            new Thread(() -> {
                String err = null;
                BluetoothSocket sock = null;
                try {
                    BluetoothAdapter ad = adapter();
                    if (ad == null) { throw new Exception("HP ini tidak punya Bluetooth."); }
                    if (!hasBtPermission()) { askBtPermission(); throw new Exception("Izinkan akses Bluetooth / Perangkat sekitar, lalu cetak lagi."); }
                    if (!ad.isEnabled()) { throw new Exception("Bluetooth HP masih mati. Nyalakan Bluetooth lalu cetak lagi."); }
                    try { ad.cancelDiscovery(); } catch (SecurityException ignored) { }
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    BluetoothDevice dev = ad.getRemoteDevice(address);
                    try { sock = openSocket(dev); }
                    catch (Exception e) { throw new Exception("Tidak bisa tersambung ke printer. Pastikan printer menyala, sudah di-pairing, dan tidak sedang dipakai HP/aplikasi lain."); }
                    OutputStream os = sock.getOutputStream();
                    for (int i = 0; i < bytes.length; i += 512) {
                        os.write(bytes, i, Math.min(512, bytes.length - i));
                        os.flush();
                        Thread.sleep(15);
                    }
                    // beri waktu printer menerima semua data sebelum koneksi ditutup
                    Thread.sleep(400 + bytes.length / 8);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    if (sock != null) { try { sock.close(); } catch (Exception ignored) { } }
                }
                js("window.__nbtDone && window.__nbtDone(" + (err == null) + ", " + JSONObject.quote(err == null ? "" : err) + ")");
            }).start();
        }
    }
}
