# Uji lokasi perangkat dan fallback IP

APK diagnostik v0.1.21 memakai lokasi perangkat jika tersedia dan perkiraan IP jika lokasi gagal atau mati

## Uji tanpa mengirim laporan

1. Pasang sebagai pembaruan tanpa menghapus data
2. Siapkan login, notifikasi, dan izin alarm
3. Matikan sakelar lokasi HP
4. Buka Beranda → Alat pengujian → Uji lokasi 90 detik
5. Kunci layar dan tunggu hasil
6. Ekspor Download logs atau gunakan pintasan Diagnostik jika layar utama macet

Hasil harus menunjukkan sumber IP tanpa meminta izin lokasi atau membuka Sesi terlebih dahulu

Ulangi dengan lokasi aktif dan izin tersedia untuk memeriksa sumber perangkat
Akses lokasi latar belakang bersifat opsional, diperlukan hanya untuk memilih lokasi perangkat saat aplikasi tertutup

## Uji pengiriman nyata

Pada hari kerja yang laporannya belum diisi, simpan laporan yang memang akan dikirim

- Manual dengan lokasi mati harus menampilkan peringatan pendek dan pilihan Kirim dengan IP
- Pengisian darurat dengan lokasi mati harus berjalan tanpa dialog
- Periksa sumber isi, sumber lokasi, waktu WIB, dan hasil API di Log
- Cocokkan akun, tanggal, dan isi laporan pada Monev
- Periksa bahwa laporan tercatat sekali dan layanan berhenti

## Bukti dan batas

IP diperkirakan oleh ipwhois.io melalui HTTPS dari koneksi HP dan dapat mengikuti jaringan operator atau VPN
Penyedia menerima IP koneksi tanpa token Monev atau isi laporan
Jika lokasi perangkat dan lookup IP gagal, aplikasi mempertahankan draf
Tes lokal tidak membuktikan Monev menerima koordinat IP pada akun nyata atau bahwa ANR perangkat telah hilang
Publikasi GitHub tetap ditunda
