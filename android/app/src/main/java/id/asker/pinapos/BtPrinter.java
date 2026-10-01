/*
 * PinaPos — Copyright (c) 2026 ASKER. All rights reserved. Powered by ASKER.
 *
 * Koneksi printer thermal Bluetooth Classic (SPP / RFCOMM) untuk mencetak struk ESC/POS.
 * Semua operasi jaringan berjalan di satu thread latar belakang.
 */
package id.asker.pinapos;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
class BtPrinter {

    interface Done {
        void done(boolean ok, String message);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile BluetoothSocket socket;
    private volatile OutputStream out;
    private volatile String connectedAddress;

    BtPrinter(Context ctx) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
    }

    boolean available() {
        return adapter != null;
    }

    boolean enabled() {
        return adapter != null && adapter.isEnabled();
    }

    /** Daftar perangkat yang sudah dipasangkan (pair) di HP, dalam JSON. */
    String bonded() {
        JSONArray arr = new JSONArray();
        Set<BluetoothDevice> set = adapter.getBondedDevices();
        if (set != null) {
            for (BluetoothDevice d : set) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? "" : d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
        }
        return arr.toString();
    }

    boolean isConnected(String address) {
        BluetoothSocket socket = this.socket;
        return socket != null && socket.isConnected() && address != null && address.equals(connectedAddress);
    }

    void connect(final String address, final Done cb) {
        worker.execute(() -> {
            try {
                ensure(address);
                cb.done(true, "");
            } catch (Exception e) {
                close();
                cb.done(false, "Tidak bisa tersambung ke printer. Pastikan printer menyala, dekat, dan tidak sedang dipakai HP/aplikasi lain. (" + e.getMessage() + ")");
            }
        });
    }

    void write(final String address, final byte[] data, final Done cb) {
        worker.execute(() -> {
            Exception last = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    ensure(address);
                    send(data);
                    cb.done(true, "");
                    return;
                } catch (Exception e) {
                    last = e;
                    close();     // sambungan lama putus: coba sekali lagi dengan sambungan baru
                }
            }
            cb.done(false, "Gagal mencetak ke printer Bluetooth. (" + (last != null ? last.getMessage() : "") + ")");
        });
    }

    synchronized void close() {
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        out = null;
        socket = null;
        connectedAddress = null;
    }

    private synchronized void ensure(String address) throws Exception {
        if (isConnected(address) && out != null) {
            return;
        }
        close();
        if (adapter == null) {
            throw new IOException("Bluetooth tidak tersedia");
        }
        if (!adapter.isEnabled()) {
            throw new IOException("Bluetooth HP mati");
        }
        BluetoothDevice dev = adapter.getRemoteDevice(address);
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }

        BluetoothSocket s = null;
        Exception err = null;
        // 1) cara standar, 2) tanpa enkripsi, 3) kanal 1 langsung (printer murah tertentu)
        for (int i = 0; i < 3 && s == null; i++) {
            BluetoothSocket t = null;
            try {
                if (i == 0) {
                    t = dev.createRfcommSocketToServiceRecord(SPP);
                } else if (i == 1) {
                    t = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                } else {
                    Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                    t = (BluetoothSocket) m.invoke(dev, 1);
                }
                t.connect();
                s = t;
            } catch (Exception e) {
                err = e;
                try {
                    if (t != null) {
                        t.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        if (s == null) {
            throw err != null ? err : new IOException("gagal tersambung");
        }
        socket = s;
        out = s.getOutputStream();
        connectedAddress = address;
    }

    private void send(byte[] data) throws IOException, InterruptedException {
        final int chunk = 512;
        for (int i = 0; i < data.length; i += chunk) {
            int n = Math.min(chunk, data.length - i);
            out.write(data, i, n);
            out.flush();
            Thread.sleep(20);   // beri waktu buffer printer
        }
    }
}
