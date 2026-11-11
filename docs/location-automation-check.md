# Uji pengiriman tanpa membuka Sesi

APK diagnostik v0.1.19 mengambil lokasi Android selama pengiriman dan meneruskannya ke formulir Monev.

## Uji lokasi tanpa mengirim laporan

1. Pasang APK sebagai pembaruan. Jangan hapus data aplikasi.
2. Izinkan lokasi akurat dan notifikasi. Aktifkan lokasi perangkat.
3. Buka Beranda → Alat pengujian → Izinkan lokasi selalu. Pilih **Selalu izinkan** di pengaturan Android.
4. Aktifkan izin alarm yang tepat. Mulai ulang HP; jangan buka Sesi atau aplikasi peta.
5. Buka Beranda → Alat pengujian → **Uji lokasi 90 detik**. Kunci layar.
6. Tunggu notifikasi hasil. Ekspor **Download logs**. Tes ini tidak mengirim laporan.

## Uji pengiriman terjadwal

Pada hari kerja yang laporannya belum diisi, simpan laporan nyata, aktifkan Pengisian darurat, dan atur waktunya. Kunci HP tanpa membuka Sesi. Setelah proses selesai, cocokkan laporan, tanggal, dan akun di Monev. Ekspor log sebelum mengirim manual jika percobaan gagal.

Lulus: lokasi diperoleh tanpa Sesi, laporan tercatat sekali, API membaca FILLED, dan layanan pengiriman berhenti. Login aktif, koneksi, lokasi perangkat, dan izin diperlukan. Force stop Android menghentikan alarm aplikasi.

## Bukti dan batas

Log lama membuktikan satu percobaan gagal di lokasi dan satu percobaan berhasil setelah membuka Sesi. Log tersebut tidak membuktikan bahwa Sesi menyebabkan keberhasilan. Tes JVM dan JavaScript memakai fixture; keberhasilan di Samsung dengan layar terkunci harus diverifikasi dari log perangkat. Tidak ada koordinat, token, atau isi laporan dalam log diagnostik.

Uji JavaScript: `node scripts/test-location-bridge.cjs`.
