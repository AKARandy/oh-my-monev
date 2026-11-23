# oh my monev

[![Build](https://github.com/AKARandy/oh-my-monev/actions/workflows/build.yml/badge.svg)](https://github.com/AKARandy/oh-my-monev/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/AKARandy/oh-my-monev?label=release)](https://github.com/AKARandy/oh-my-monev/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/AKARandy/oh-my-monev/total)](https://github.com/AKARandy/oh-my-monev/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(ARM64)-3DDC84?logo=android&logoColor=white)](https://github.com/AKARandy/oh-my-monev/releases/latest)
[![License](https://img.shields.io/github/license/AKARandy/oh-my-monev)](LICENSE)

oh my monev adalah aplikasi Android. Aplikasi ini membantu peserta MagangHub (Kemnaker, Indonesia)
mencatat kehadiran harian di https://monev.maganghub.kemnaker.go.id.

English version: [README.en.md](README.en.md).

[Download stable version here](https://github.com/AKARandy/oh-my-monev/releases/latest) (Untuk user) | [Download dev build version here](https://github.com/AKARandy/oh-my-monev/releases/tag/dev) (Untuk developer jika ingin debugging).

MagangHub memberi uang kepada peserta magang. Tetapi peserta wajib mencatat kehadiran setiap hari.
Jika peserta tidak mengirim catatan sebelum pukul 23.59 WIB, peserta kehilangan uang untuk hari itu.

Oleh karena itu saya mengembangkan aplikasi **oh my monev** ini

Aplikasi ini bisa melakukan hal-hal berikut:

- Aplikasi memberi alarm pada jam yang ditetapkan oleh pengguna jika kehadiran belum tercatat (Defaultnya 20:00).
- Aplikasi dapat mengisi formulir laporan, lalu mengirimnya pada jam yang Anda pilih. Sumber isian
  wajib dipilih satu: templat isi absen buatan Anda, atau lanjutan otomatis dari laporan hari sebelumnya.
- Dimana lanjutan otomatis itu sifatnya MASIH eksperimental dan berisiko. Aplikasi meminta konfirmasi sebelum menyalakannya.
  Aplikasi menyusun ulang kalimat laporan sebelumnya secara offline di perangkat (perlu unduh file bahasa satu kali).
  Makna kalimat dapat berubah. Pratinjau menunjukkan teks yang akan dikirim.
- Aplikasi memeriksa semua teks sebelum mengisi formulir. Setiap bagian wajib berisi 100 sampai 5000 karakter.
- Ponsel boleh dalam keadaan diam dan alarm tetap tepat waktu

Status: rilis stabil v1.1.0 tersedia (build dev terpisah pada tag `dev`).

## Tampilan (taken from a real phone)

| Halaman Beranda | Halaman Otomatis |
| :---: | :---: |
| <img src="screenshots/WhatsApp Image 2026-11-23 at 08.51.52.jpeg" alt="Beranda" width="300" /> | <img src="screenshots/WhatsApp Image 2026-11-23 at 08.51.53.jpeg" alt="Otomatis" width="300" /> |

## Struktur

- `:core`: kode Kotlin tanpa bagian Android. Memeriksa teks, membuat laporan, menghitung waktu.
- `:app`: aplikasi Android. Kode layar, kendali halaman web, alarm.
- `specs/`: spesifikasi formal TLA+ untuk verifikasi mesin status inti.
- `docs/`: rencana kerja. Aturan situs Monev (`monev-logic.md`). Catatan lain.
- `.tools/`: alat bantu build untuk komputer ini. Tidak ada di repositori.

## Build

```powershell
# Langkah 1: Buka folder repositori.
# Langkah 2: Buat local.properties. Tulis jalur SDK di dalamnya.
# Langkah 3: Jalankan perintah ini:
.tools\gradle-8.10.2\bin\gradle.bat :core:test      # mulai dari tes ini. SDK tidak diperlukan.
.tools\gradle-8.10.2\bin\gradle.bat :app:assembleDebug
```

## Penggunaan aplikasi 

Aplikasi memakai akun Anda dan sesi Anda.
Anda bertanggung jawab atas teks yang dikirim aplikasi.
Aplikasi menulis setiap tindakan otomatis ke dalam catatan. Anda dapat membaca teks setiap tindakan.
Anda dapat menghentikan otomatisasi untuk satu hari.

## Cara kerja teknis

### Sesi dan jadwal

Anda masuk ke situs Monev melalui WebView di tab **Sesi**. Aplikasi mengenkripsi token akses dengan
kunci dari Android Keystore sebelum menyimpannya di ponsel. Aplikasi memakai token ini untuk membaca
profil peserta, kehadiran, dan laporan harian melalui API Monev.

`AlarmManager` menjadwalkan pengingat dan pengisian darurat sesuai hari kerja dan jam pilihan Anda (WIB).
Pada jam pengingat, alarm berbunyi jika laporan hari ini masih perlu diisi. Pada jam pengisian darurat,
layanan latar depan Android memeriksa status lagi. Layanan ini melewati hari libur, cuti, dan laporan
yang sudah terkirim.

### Sumber draf

Pengisian darurat memakai draf yang sudah Anda tulis untuk hari ini. Jika belum ada draf, aplikasi
memakai sumber yang Anda pilih.

- **Templat.** Aplikasi mengganti `{tanggal}` dalam templat dengan tanggal laporan.
- **Lanjutan otomatis.** Aplikasi membaca laporan lengkap terakhir yang Anda kirim untuk hari berstatus
  **Hadir**. Aturan kata kerja menyusun ulang kalimat aktivitas yang dikenali. Model IndoT5 menyusun
  ulang kalimat aktivitas lainnya di ponsel dengan ONNX Runtime. Aplikasi menyalin bagian pelajaran
  dan kendala dari laporan sumber.

Aplikasi menyimpan draf per peserta dan tanggal agar pengisian darurat dapat memakai kembali teks pratinjau.

### Pengiriman dan konfirmasi

Pengiriman dari **Beranda** dan pengisian darurat memakai formulir Monev di WebView. Aplikasi meminta
lokasi perangkat terlebih dahulu. Jika lokasi belum tersedia, aplikasi mencoba perkiraan lokasi dari
alamat IP. Setelah situs mengonfirmasi akses lokasi, JavaScript mengisi kolom aktivitas, pelajaran, dan kendala.

Aplikasi membaca kembali ketiga kolom. Jika teks cocok dengan draf dan setiap kolom berisi 100 sampai
5.000 karakter, aplikasi mencentang kotak pernyataan lalu menekan **Simpan dan Kirim**. Aplikasi
menandai pengiriman berhasil di tab **Log** setelah API Monev mengonfirmasi laporan untuk peserta
dan tanggal yang sama.

Lihat kode [penjadwalan](app/src/main/java/id/ohmymonev/android/schedule/AlarmScheduler.kt),
[penyusunan draf](app/src/main/java/id/ohmymonev/android/engine/PreparedReportService.kt),
[pengisian formulir](app/src/main/java/id/ohmymonev/android/engine/WebViewEngine.kt), dan
[konfirmasi pengiriman](app/src/main/java/id/ohmymonev/android/engine/EngineAutoFillDelegate.kt).
