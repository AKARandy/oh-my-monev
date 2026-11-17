# Uji pemulihan aplikasi

Build diagnostik v0.1.20 memperbaiki pekerjaan yang memblokir layar dan menyediakan ekspor terpisah.

## Pasang dan buka diagnostik

1. Pasang APK sebagai pembaruan v0.1.19. Jangan uninstall atau hapus data.
2. Tekan lama ikon **oh my monev** pada launcher HP.
3. Pilih **Diagnostik**, lalu **Ekspor diagnostik**.
4. Simpan ZIP dan kirim berkas tersebut untuk diperiksa.

Layar ini memakai proses terpisah. Membukanya tidak menjalankan sesi, model, atau fitur otomatis. Tombol **Download logs** lama tetap tersedia di Beranda.

## Jika aplikasi macet lagi

Pemantau menyimpan stack thread utama setelah lima detik tanpa respons dan mencatat saat pulih. Ekspor ZIP melalui pintasan Diagnostik. Jangan mengulang pengiriman laporan sebelum status Monev diperiksa.

ZIP memuat log aktivitas, jejak macet, metadata build/WebView, serta riwayat penghentian proses yang disediakan Android 11+. Trace sistem dapat tidak tersedia. Ekspor tidak memuat berkas token, draf, koordinat, atau model.

## Pemeriksaan perangkat

Uji pembukaan aplikasi, pindah tab, kembali dari latar belakang, dan fitur yang sebelumnya memicu macet. Biarkan melewati jadwal serta pergantian hari; buka kembali tanpa reinstall. Jika macet, ekspor diagnostik dan catat perkiraan jam serta tindakan terakhir.

Tes lokal tidak membuktikan ANR pada Samsung telah hilang. Validasi semalam dan ekspor saat proses utama macet masih memerlukan HP pengguna. Publikasi GitHub belum dilakukan.
