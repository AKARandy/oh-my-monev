# Monev — behavior specification

What the Monev website (`monev.maganghub.kemnaker.go.id`) does, exactly. Every statement below
comes from either live observation of a real account (2026-11-06) or the site's own production
code. Items marked **?** are unknown and must not be guessed.

oh-my-monev exists **only** for the gaps in this document. If Monev did these things, the app
would have no reason to exist.

---

## 1. What Monev is

The daily attendance ledger for MagangHub interns (Kemnaker). One record per calendar day:

- **Kehadiran** (attendance): `Hadir` (present) / `Tidak Hadir Dengan Keterangan` (leave, with reason) /
  `Tidak Hadir Tanpa Keterangan` (absent) — plus `DAYOFF` for scheduled off-days.
- **Three evidence texts**, each ≥100 characters (site-enforced, max 5000):
  - `activity_log` — "Uraian aktivitas" (bulleted lines)
  - `lesson_learned` — "Pembelajaran yang diperoleh" (one paragraph)
  - `obstacles` — "Kendala yang dialami" (one paragraph)
  - `leave_reason` — only when status is leave.
- **Device location**: the page requests GPS (`navigator.geolocation`, high accuracy) and the
  coordinates are submitted **with** the record. The submit button stays disabled until location
  is granted and a fix is found.
- **Confirmation checkbox**: "Saya menyatakan telah meninjau dan memastikan isian laporan ini
  sudah benar" — required before the button enables.
- **One action**: `Simpan dan Kirim`. Attendance + report are recorded together; there is no
  separate attendance-only button and no draft save.

Deadline: **23.59 WIB** (shown on the Beranda card). Day boundary = Asia/Jakarta, no DST.

## 2. The normal path

1. Beranda "Hari Ini" card shows today's date, the internship period, and status:
   - unfilled → **"Laporan hari ini belum diisi"** + button **Isi Laporan Hari Ini**
   - filled → **"Laporan hari ini sudah diisi"** + "Laporan dan kehadiran Anda sudah tercatat" +
     button **Lihat Laporan Hari Ini** + **"Menunggu peninjauan Mentor"** info.
   - Both states show: **"Batas pengisian laporan hari ini pukul 23.59 WIB"**.
2. **Isi Laporan Hari Ini** opens the form (title "Tambah laporan"):
   location check → Kehadiran selector (Hadir preselected) → three texts (live counter) →
   confirmation checkbox → **Simpan dan Kirim** (disabled until everything is valid).
3. On success: toast **"Kehadiran dan laporan harian berhasil dikirim."** The Riwayat calendar
   flips today to **"Menunggu Persetujuan"** (mentor review pending).
4. **After a successful send the day is closed to you.** The form cannot be reopened for editing —
   not the same day, not any day. The only state that reopens it is a mentor-requested revision.

## 3. Record lifecycle

```
                       ┌──────────────────────────────┐
                       ▼                              │ (mentor requests revision)
Belum Diisi ──fill+send──► SUBMITTED ──────────────► NEEDS_REVISION ──fix+resend──► SUBMITTED
 ("no report")             "Menunggu                    "Perbaiki laporan"
                           Persetujuan"                 attendance NOT rejected
       │
       │ 23:59 passes
       ▼
 permanently locked "Belum Diisi"  (= counted as absence; no form ever opens again)
```

- Editable states: `DRAFT` (server-side draft — normal flow never leaves one) and
  `NEEDS_REVISION`. `SUBMITTED` is final to the participant.
- Weekend / off-day: calendar shows **"Jadwal libur posisi"** — no form, no penalty.
- Internship inactive/ended: all forms disabled — "Pengisian laporan dinonaktifkan."

## 4. Every scenario

| # | Scenario | Monev's behavior |
|---|---|---|
| 1 | Send succeeds | Success toast; calendar → "Menunggu Persetujuan"; Beranda flips to "sudah diisi"; day locked to you. |
| 2 | Send attempted with <100 chars or unticked box | Impossible — the button is disabled by validation. |
| 3 | Nothing filled by 23.59 | Day becomes locked "Belum Diisi" forever. Counts as an absence. Stipend for that day lost. |
| 4 | Absence ladder | **5 consecutive or 10 total absences in a period → termination/deactivation** (site's own wording: "ABSENCE_DEACTIVATION"). |
| 5 | Izin (leave with reason) | Paid for days 1–3 per period; day 4+ unpaid but does **not** count as an absence strike. Requires ≥100-char reason. |
| 6 | Absent without reason | Counts as a full absence strike (same ladder as #3). |
| 7 | Weekend / off-day | "Jadwal libur posisi" — nothing expected. |
| 8 | Connection dies during send | The site re-fetches the day after the transport error; if the record actually landed it shows success ("Kehadiran berhasil dikirim") instead of letting you double-submit. |
| 9 | Mentor wants text changes | Day → revision state; form reopens as **"Perbaiki laporan"** with the mentor's note. Attendance is explicitly **not** rejected; only the text is revised. |
| 10 | Open a past "Belum Diisi" day | Impossible — no form. Locked. |
| 11 | Open an approved day | Read-only view ("Hadir disetujui"). |
| 12 | Location permission missing | "Akses lokasi belum diizinkan" — submit blocked. |
| 13 | Location granted, no GPS fix | "Lokasi belum tersedia. Pastikan GPS/lokasi perangkat aktif." — submit blocked. |
| 14 | Account inactive/ended | Forms disabled with notice; history remains viewable. |

## 5. Unknowns (**?** — do not guess)

- Exact stipend arithmetic per absence type (policy text only says izin 1–3 paid).
- Whether a mentor can outright **reject** attendance vs only request text revision (code shows
  revision strings only).
- Whether the 23:59 wall is strict to the second in server time.
- Whether any same-day "unsend" exists secretly for admins. (Assume not, for safety.)

## 6. The gaps Monev leaves — and what oh-my-monev therefore is

| Gap in Monev | oh-my-monev's answer |
|---|---|
| #3/#4: silence before the cliff — no reminder, no help | Exact alarms 20:00 (nudge) / 21:00 (auto-fill) / 22:30 (last warning); retries +5/+20/+60 clamped before midnight. |
| "Fill it now, today, in person" | Time-shift: hand the duty to the app in the morning; it executes at 21:00 with the phone merely powered on (screen off is fine). |
| No memory of what you write | Yesterday's report becomes today's continuation ("Melakukan X" → "Melanjutkan X") + rotating personal sentence pools; **never fabricates** — no material means refusal, not invention. |
| #8/#12: silent traps (GPS, session, network) | Preflight at 20:00 names problems while you're still awake; every failure is logged with its exact reason. |
| #8: risk of double-send | Readback verification + success-toast detection; an already-filled day is left untouched, silently. |
| No "not tonight" | Per-night opt-out ("Jangan isi otomatis malam ini") honored by every alarm. |
| Submit is irreversible (#3 lifecycle) | Default fill-only mode: the app fills, **you** tick and send. Auto-send only behind an explicit warning. |
| Attendance is the point, text is evidence | The app only ever files **Hadir**. Leave/absent statuses are never automated. |

## 7. Non-negotiable rules for the app (derived from this spec)

1. Never create a record for a locked/filled/libur day (rows 10/7).
2. Never send without location granted and readback verified (rows 12/13, §1).
3. Never automate leave/absent statuses (rows 5/6).
4. Never claim a status it didn't read (notifications, badges).
5. Never retry a send that may have landed (row 8).
6. Always leave an honest trail (Catatan) of every attempt and its reason.
