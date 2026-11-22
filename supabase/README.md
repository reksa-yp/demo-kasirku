# PinaPos – Sinkron Supabase (opsional) & Update

## Mode kerja
- **Offline saja** (bawaan): semua data di perangkat, tanpa internet.
- **Terhubung Supabase**: tetap lokal-dulu (bisa jualan offline); perubahan dikirim otomatis saat online
  dan dibagikan ke 2–5 perangkat lain di toko yang sama. Stok digabung sebagai selisih (+/−),
  nomor invoice memakai huruf perangkat (mis. `26-200-A000012`) supaya tidak bentrok.

## Pasang sekali
1. Supabase → project baru → **SQL Editor** → tempel isi `pinapos_sync.sql` → Run.
2. Di PinaPos: **Pengaturan → Sinkron Supabase** → isi URL project + anon/publishable key →
   **Buat toko baru & unggah data ini**. (Jangan pakai service_role key.)
3. Perangkat lain: Pengaturan → tempel **kode sambung** (tombol "Salin kode sambung") → **Gabung toko**.
   Data di perangkat yang bergabung diganti dengan data toko.

Tabel Supabase terkunci (RLS, tanpa policy); aplikasi hanya memanggil 3 fungsi RPC dengan kode toko acak 32 karakter.

## Update aplikasi
- Otomatis: dicek saat dibuka, tiap 30 menit, dan saat kembali online/ke tab. Dimuat ulang sendiri
  hanya jika keranjang kosong dan tidak ada dialog/isian terbuka; selain itu muncul banner.
- Manual: **Pengaturan → Pembaruan aplikasi → Periksa update** (atau menu akun → Periksa update).
- Rilis versi baru: `tools/bump-version.sh 1.4.1 "Catatan singkat"` lalu commit & push.
