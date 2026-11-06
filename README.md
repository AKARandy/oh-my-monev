# oh my monev

oh my monev adalah aplikasi Android. Aplikasi ini membantu peserta MagangHub (Kemnaker, Indonesia)
mencatat kehadiran harian di https://monev.maganghub.kemnaker.go.id.

Versi bahasa Inggris: [README.en.md](README.en.md).

MagangHub memberi uang kepada peserta magang. Tetapi peserta wajib mencatat kehadiran setiap hari.
Jika peserta tidak mengirim catatan sebelum pukul 23.59 WIB, peserta kehilangan uang untuk hari itu.

Aplikasi ini melakukan hal-hal ini:

- Aplikasi memberi alarm pada jam yang ditetapkan (bawaan: 20.00) jika kehadiran belum tercatat.
- Aplikasi dapat mengisi formulir laporan. Aplikasi juga dapat mengirim formulir itu. Anda memilih
  jamnya. Anda dapat menghentikan otomatisasi untuk satu malam.
- Jika Anda tidak menulis laporan, aplikasi membuatnya. Aplikasi memakai laporan hari sebelumnya.
  Contoh: "Melakukan aktivitas X" menjadi "Melanjutkan aktivitas X". Aplikasi tidak mengarang kata.
- Aplikasi memeriksa semua teks sebelum mengisi formulir. Setiap bagian wajib memiliki 100 karakter
  atau lebih. Tanpa kata seperti "saya" atau "kami". 3 sampai 5 baris aktivitas. Tanpa klise.
- Ponsel boleh dalam keadaan diam. Alarm tetap tepat waktu. Sebuah layanan menjaga ponsel tetap
  menyala selama pengisian berlangsung.

Status: versi 0.1 masih dalam pengembangan. Lihat `docs/plan.md`.

## Struktur

- `:core`: kode Kotlin tanpa bagian Android. Memeriksa teks, membuat laporan, menghitung waktu.
- `:app`: aplikasi Android. Kode layar, kendali halaman web, alarm.
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

## Penggunaan yang disetujui

Aplikasi memakai akun Anda dan sesi Anda. Aplikasi tidak mengarang isi laporan.
Anda bertanggung jawab atas teks yang dikirim aplikasi.
Aplikasi menulis setiap tindakan otomatis ke dalam catatan. Anda dapat membaca teks setiap tindakan.
Anda dapat menghentikan otomatisasi untuk satu hari.
