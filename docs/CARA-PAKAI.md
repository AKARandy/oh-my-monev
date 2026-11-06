# Panduan memakai oh my monev

Tujuan aplikasi: mengisi laporan harian **MagangHub Monev** untukmu di jam yang kamu tentukan,
supaya tidak ada hari yang terlewat. Membuka aplikasi ini = kamu juga bisa melihat langsung
halaman Monev di dalamnya (tab **Sesi**).

## 1. Melihat dan mencoba aplikasi TANPA HP (lewat HP virtual)

Semua alat sudah ada di folder `.tools` repo ini. Kamu tidak perlu menginstall Android Studio.

1. Buka folder repo: `C:\Users\LENOVO\Documents\oh-my-monev`
2. Klik kanan `scripts\run-emulator.ps1` → **Run with PowerShell**
   (atau buka PowerShell dan jalankan: `powershell -ExecutionPolicy Bypass -File scripts\run-emulator.ps1`)
3. Tunggu 2–5 menit. Akan muncul jendela **HP virtual (Pixel)**. Itu Android sungguhan:
   klik = sentuh, scroll = usap, ketik = keyboard. Aplikasi otomatis terpasang dan terbuka.
4. Kalau aplikasinya tidak otomatis terbuka: di HP virtual, buka ikon **"oh my monev"** (warna hijau, tanda centang).

> Catatan: script ini memakai emulator yang sudah diunduh ke `.tools`. Tidak ada yang diinstall
> ke Windows-mu. Kalau emulator macet, tutup jendelanya lalu jalankan script sekali lagi.

## 2. Isi tab-tab aplikasi

| Tab | Isinya |
|---|---|
| **Beranda** | Tanggal hari ini (WIB) + tiga jam alarm (20:00 / 21:00 / 22:30). Kartu "Mode Uji" (laporan diisi tapi TIDAK dikirim otomatis sampai kamu ganti kebijakan), kartu **Izin lokasi** (wajib: situs mengunci tombol Kirim sampai lokasi terbaca), tombol **Jalankan sekarang (sesuai kebijakan)**, dan **Tes alarm 90 detik**. |
| **Jadwal** | Atur jam pengingat/isi-otomatis/eskalasi, hari aktif, dan kebijakan. Pilihan **"Kirim otomatis"** hanya bisa dipilih lewat dialog konfirmasi. Menyimpan = alarm langsung dijadwal ulang. |
| **Log** | Riwayat tiap percobaan pengisian: tanggal, hasil (mis. `FILL_OK — terisi & terverifikasi, belum dikirim`), dan pesan detail. |
| **Sesi** | Halaman login MagangHub (SIAPkerja) langsung di dalam aplikasi + tombol **Cek sesi sekarang**. Login di sini sekali; sesi disimpan dan dipakai pengisian otomatis nanti malam. |

## 3. Mencoba alurnya (aman, Mode Uji)

1. Tab **Sesi** → login sekali. Kalau tombol "Cek sesi sekarang" menampilkan *Sesi AKTIF*, beres.
2. Tab **Beranda** → tombol **Izinkan akses lokasi** (kalau muncul peringatan) → allow.
3. Tab **Beranda** → **Jalankan sekarang**. Aplikasi membuka halaman Monev secara internal,
   mengisi form laporan hari ini, lalu berhenti sebelum kotak konfirmasi dan tombol Kirim.
4. Tab **Log** → baris hasil pengisian (mis. `FILL_OK`). Kalau gagal, pesannya jelas:
   `LOGIN_REQUIRED` (sesi habis), `LOCATION_CHECK_FAILS` (GPS), `DAY_NOT_FILLABLE` (hari sudah terisi/libur).
5. Tab **Jadwal** → **Tes alarm 90 detik** untuk membuktikan alarm HP-mu benar-benar menyala.

Kalau hari ini belum kamu isi, cek sendiri di Chrome/HP: form akan terlihat sudah terisi
oleh aplikasi (tapi belum terkirim — kamu yang tekan Kirim bila mau). Kalau hari ini sudah
diisi manual, aplikasi otomatis berhenti dengan `DAY_NOT_FILLABLE` — itu normal dan aman.

## 4. Kalau ingin kirim otomatis (nanti, setelah yakin)

Tab **Jadwal** → Kebijakan pengisian → pilih **Kirim otomatis (FILL_AND_SUBMIT)** → dialog
konfirmasi menjelaskan bahwa aplikasi akan mencentang kotak pernyataan dan menekan
**Simpan dan Kirim** atas namamu. Setelah itu aktif, batas waktunya tetap `23.59 WIB`.

## 5. Kalau ada masalah

- **Sesi habis**: bot tidak akan pernah mengirim tanpa sesi; ia berhenti dan memberi tahu.
  Buka tab Sesi, login ulang, cek lagi.
- **GPS/lokasi**: pastikan GPS HP aktif dan izin lokasi diberikan. Situs mengikat tombol Kirim
  dengan lokasi.
- **Alarm tidak bunyi** (HP asli, terutama Xiaomi/Oppo/Vivo): izinkan "tanpa batas baterai"
  / autostart untuk aplikasi ini; lalu uji dengan **Tes alarm 90 detik**.
