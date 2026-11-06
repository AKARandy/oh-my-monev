# M0 recon spike (interactive, with the account owner)

Goal: close the unknowns in `docs/site-research.md` before/while M2 lands. Everything here is read-only
or user-approved-action except the dry-run fill, which never ticks the checkbox and never submits.

## Checklist

1. **Login persistence in a WebView**
   - On the Android device (or emulator with Play), open the app's WebView → `https://monev.maganghub.kemnaker.go.id/dashboard/evaluasi`, complete SSO login.
   - Restart the app process. Are the tabs (Beranda/Riwayat/Perkembangan/Akun) still visible? Record: cookie persistence OK / session TTL unknown.
   - Note the exact SSO redirect URL chain (login host, whether a CAPTCHA or OTP appears).
2. **Location check**
   - On home Wi-Fi (and ideally mobile data), open the form: what does the area above **Kehadiran** show? Record exact text and whether GPS on/off changes it.
3. **Same-night deadline reality check**
   - Does the form refuse/alter behavior near midnight? Fill at 21:00 region one day and at 23:50 on another (both with owner present). Record.
4. **HAR capture (optional but valuable)**
   - While logged in on a desktop browser: DevTools → Network → reproduce: Beranda → Isi Laporan → fill → Simpan dan Kirim **on a day that is safe to submit**.
   - Export a HAR **with the owner's consent**, strip cookies/authorization headers, save as `docs/m0-har.md` (summary only; never commit raw credentials/tokens to the repo).
5. **States observed**
   - Record which Riwayat states appear for: submitted-but-unreviewed day, approved day, weekend ("Hari Libur"?).
6. Dry-run result: paste the three real values filled by the app vs the readback — attach screenshots if possible.

Output: append findings to the bottom of this file with the date.
