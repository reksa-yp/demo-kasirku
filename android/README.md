# Aplikasi Android offline — Kasirku & CuciAja

Satu proyek Android (dua *flavor*) yang membungkus `../kasirku/index.html` dan
`../cuciaja/index.html` menjadi aplikasi yang berjalan **tanpa server dan tanpa internet**.

- Data disimpan di file milik aplikasi di HP (`data.json`), bisa dibackup / dipulihkan dari **Pengaturan**.
- Login pertama: `admin` / `admin123`.
- Cetak struk: **🖨 Printer → Printer Bluetooth langsung** (printer yang sudah di-pairing di Setelan Bluetooth HP),
  atau lewat layanan cetak Android / RawBT.
- WhatsApp, simpan file (backup, CSV), dan unggah logo memakai fitur HP.

## Build

Otomatis lewat GitHub Actions (`.github/workflows/android.yml`) setiap ada perubahan; APK muncul di **Releases**.

Manual (butuh Android SDK + JDK 17 + Gradle 8.9):

```bash
python3 android/prepare_assets.py
gradle -p android assembleKasirkuRelease assembleCuciajaRelease
```

`keystore/demo.keystore` dipakai supaya update APK bisa dipasang menimpa versi lama tanpa kehilangan data.
Untuk rilis ke Play Store, buat keystore sendiri yang dirahasiakan.
