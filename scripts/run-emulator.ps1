# Jalankan HP-virtual (emulator) + pasang oh my monev
# Cara pakai: klik kanan file ini -> "Run with PowerShell", atau dari PowerShell:
#   powershell -ExecutionPolicy Bypass -File scripts\run-emulator.ps1
#
# Script ini HANYA memakai folder .tools di dalam repo (emulator, sistem Android,
# AVD, dan APK sudah disiapkan). Tidak menginstall apa pun ke sistem.

$ErrorActionPreference = 'Stop'
$ws  = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $ws '.tools\android-sdk'
$emu = Join-Path $sdk 'emulator\emulator.exe'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$apk = Join-Path $ws 'app\build\outputs\apk\debug\app-debug.apk'

if (-not (Test-Path $emu)) { throw "Emulator tidak ditemukan: $emu" }
if (-not (Test-Path $apk)) { throw "APK belum dibangun. Jalankan scripts\build-apk.ps1 dulu." }

$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_AVD_HOME = Join-Path $ws '.tools\avd'
New-Item -ItemType Directory -Force (Join-Path $ws '.tools\temp') | Out-Null
$env:TEMP = (Join-Path $ws '.tools\temp'); $env:TMP = $env:TEMP

Write-Host ""
Write-Host "  Menjalankan HP virtual 'omv' (jendela Android akan muncul)..." -ForegroundColor Cyan
Write-Host "  Klik di jendela itu = sentuh layar. Scroll = usap. Ketik = keyboard." -ForegroundColor DarkGray
Write-Host ""

$emuProc = Start-Process -FilePath $emu -ArgumentList `
    '-avd','omv','-no-audio','-gpu','auto' -PassThru

& $adb start-server | Out-Null
$boot = ''
$deadline = (Get-Date).AddMinutes(8)
Write-Host "  Menunggu Android selesai boot (bisa 2-5 menit pertama kali)..."
while (($boot -join '').Trim() -ne '1' -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $devices = & $adb devices
    if (-not ($devices -match 'emulator-\d+\s+device')) { continue }
    $boot = & $adb shell getprop sys.boot_completed
    Write-Host ("  boot: " + ($boot -join '').Trim())
}
if (($boot -join '').Trim() -ne '1') {
    Write-Host "Emulator belum selesai boot. Tutup jendelanya lalu jalankan script ini lagi." -ForegroundColor Red
    exit 1
}

Write-Host "  Memasang oh my monev..." -ForegroundColor Cyan
& $adb install -r $apk

Write-Host "  Membuka aplikasi..." -ForegroundColor Cyan
& $adb shell am start -n id.ohmymonev.android/.MainActivity

Write-Host ""
Write-Host "  SELESAI. Lihat jendela HP virtual: aplikasi 'oh my monev' sudah terbuka." -ForegroundColor Green
Write-Host "  Tab: Beranda / Jadwal / Log / Sesi." -ForegroundColor Green
Write-Host ""
Write-Host "  Tips: untuk melihat situs Monev di dalam aplikasi, buka tab 'Sesi'," -ForegroundColor DarkGray
Write-Host "  login sekali di halaman yang tampil (SIAPkerja), lalu tombol 'Cek sesi sekarang'." -ForegroundColor DarkGray
Write-Host ""
