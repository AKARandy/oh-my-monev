# Target site research — monev.maganghub.kemnaker.go.id

Status: **verified against the live site on 2026-11-06 (18:00–18:50 WIB)** through two independent channels:
1. A real logged-in Chrome session (user's profile) inspected read-only via accessibility trees.
2. The production SPA bundles fetched from `/_nuxt/*` and mined for strings, payloads and rules.

## Verified facts

### Account/dashboard
- SPA (Nuxt 3). Logged-in session shows top nav links: `Beranda` (`/dashboard`), `Riwayat` (`/dashboard/riwayat`), `Perkembangan` (`/dashboard/evaluasi`), `Akun` (`/dashboard/profil`); plus a `Survei` link.
- Beranda greeting `Halo, <name> 👋`; org line "Pusat Data dan Teknologi Informasi"; server clock widget `Waktu Server … WIB (GMT+7)`.
- "Hari Ini" card: weekday/date (`Jumat, 6 November 2026`), `Periode magang 21 Sep 2026–20 Mar 2027`, status `Aktif`.
- When filled: `Laporan hari ini sudah diisi` / `Laporan dan kehadiran Anda sudah tercatat.` + button `Lihat Laporan Hari Ini` + **`Batas pengisian laporan hari ini pukul 23.59 WIB`**.
- When not filled the button says `Isi Laporan Hari Ini`.
- Izin policy banner: "Izin hingga 3 hari per periode dibayar. Izin ke-4 dan seterusnya tidak dibayar, tetapi tidak dihitung untuk peringatan atau pemberhentian akibat ketidakhadiran."
- Deactivation policy (from code): `ABSENCE_DEACTIVATION` — "Status magang dinonaktifkan karena ketidakhadiran mencapai 5 hari berturut-turut atau 10 hari dalam 1 periode."
- Inactive accounts: forms disabled with "Pengisian laporan dinonaktifkan".

### Riwayat calendar (verified labels)
`Hadir disetujui`, `Izin disetujui`, `Menunggu Persetujuan` (submitted, awaiting mentor), `Belum Diisi`, `Jadwal libur posisi` (weekends/off-days). Period switcher `21 Okt 2026 - 20 Nov 2026`.
Note: past `Belum Diisi` days are **not editable** (code: `st()` requires `NEEDS_REVISION` for past dates; today only when no attendance or `DRAFT`). This matches the user's "missed day is permanently lost".

### Daily report form (code-verified from chunk `DMLWG_CQ.js`)
Opened from Beranda (`Isi Laporan Hari Ini`) or today's day in Riwayat. Component title switches:
`Tambah laporan` (new) · `Edit laporan` (server draft) · `Perbaiki laporan` (`NEEDS_REVISION`).

Fields and real model/payload names:

| Label (verbatim) | Placeholder (verbatim) | Payload key | Rule |
|---|---|---|---|
| `Uraian aktivitas` | `Tulis uraian aktivitas minimal 100 karakter` | `activity_log` | `e && e.length>=100 \|\| 'Minimal 100 karakter'` |
| `Pembelajaran yang diperoleh` | `Tulis ilmu/pembelajaran yang diperoleh minimal 100 karakter` | `lesson_learned` | same |
| `Kendala yang dialami` | `Tulis kendala/hambatan yang dialami minimal 100 karakter` | `obstacles` | same |
| `Alasan tidak hadir` (only if status = 1) | `Tulis alasan tidak hadir minimal 100 karakter` | `leave_reason` | — |

Attendance select options: `Hadir` (0/`PRESENT`), `Tidak Hadir Dengan Keterangan` (1/`ON_LEAVE`), `Tidak Hadir Tanpa Keterangan` (2/`ABSENT`), holiday `DAYOFF`.
Confirmation checkbox label (verbatim): `Saya menyatakan telah meninjau dan memastikan isian laporan ini sudah benar`.
Buttons: `Batal` (outlined) and `Simpan dan Kirim` (primary, disabled until form valid AND checkbox checked).
Extra for revision days: reviewer-notes banner `Uraian aktivitas perlu diperbaiki` with helper `Kehadiran Anda tidak ditolak dan tidak perlu diubah.`

### Location check = real geolocation (critical)
The form calls `navigator.geolocation.getCurrentPosition({enableHighAccuracy:true, timeout:12000, maximumAge:60000})` and displays status text:
`granted` → `Lokasi berhasil diakses` · `checking` → `Mengambil lokasi perangkat` · `prompt` → `Izinkan akses lokasi` · `denied` → `Akses lokasi belum diizinkan` · `error` → `Lokasi belum tersedia` (helper: "Tidak bisa mengambil lokasi. Pastikan GPS/lokasi perangkat aktif.") plus `Koordinat ditemukan.`
**Latitude/longitude are submitted with the report** (`createAttendance` payload). Therefore the Android app must hold runtime location permission and the WebView must grant geolocation to the origin; GPS must be on.

### Submit flow (code-verified)
Save path builds:
`{ date, status: PRESENT|ON_LEAVE|ABSENT, activity_log, lesson_learned, obstacles, latitude?, longitude? }` → `createAttendance`. Success toast:
`Kehadiran dan laporan harian berhasil dikirim.` — update/revision: `Laporan harian berhasil diperbarui.` / `Perbaikan laporan harian berhasil dikirim.`
On transport error the SPA re-fetches the day and, if the attendance/log already exists, treats it as success (idempotent retry behavior worth mirroring).
Other success notices seen in code: `Laporan harian berhasil dikirim.`

### Submission deadline
Site explicitly displays `23.59 WIB` and the day rolls at Asia/Jakarta (+07:00, no DST). Our ladder: reminder 20:00, auto-run 21:00, escalation 22:30; retry ladder clamps to the same-night escalation deadline.

## Implementation implications (applied)

- `SiteProfile.textareasByPart` placeholders above are correct as-is (match by placeholder, ids are generated).
- WebViewEngine must: enable `settings.geolocationEnabled`, implement `WebChromeClient.onGeolocationPermissionsShowPrompt` granting the origin, and the app must request `ACCESS_FINE_LOCATION` at runtime (Home tab onboarding card).
- Success detection now accepts the site's success snackbar text OR leaving the `?view=edit` form URL.
- Weekend/off-days produce `Jadwal libur posisi` and no form; the engine treats "no textareas" as `DAY_NOT_FILLABLE` (safe, no submit).
- Past days cannot be backfilled unless the mentor requested a revision — catch-up is intentionally not implemented (same-night doctrine).
- The write-gate rules (G-1..G-9, ≥100 chars, no pronouns, bullets) still apply from our side; the site independently enforces ≥100 chars.

## Evidence artifacts
- Browser-session accessibility trees: `.tools/monev-beranda.json`, `.tools/monev-riwayat.json`, `.tools/monev-form.json`
- SPA bundles: `.tools/monev-js-*.js`, `.tools/chunk-DMLWG_CQ.js` (the form component)
