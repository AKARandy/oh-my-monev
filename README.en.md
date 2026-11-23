# oh my monev

[![Build](https://github.com/AKARandy/oh-my-monev/actions/workflows/build.yml/badge.svg)](https://github.com/AKARandy/oh-my-monev/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/AKARandy/oh-my-monev?label=release)](https://github.com/AKARandy/oh-my-monev/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/AKARandy/oh-my-monev/total)](https://github.com/AKARandy/oh-my-monev/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(ARM64)-3DDC84?logo=android&logoColor=white)](https://github.com/AKARandy/oh-my-monev/releases/latest)
[![License](https://img.shields.io/github/license/AKARandy/oh-my-monev)](LICENSE)

oh my monev is an Android app. It helps MagangHub participants (Kemnaker, Indonesia) to record
their daily attendance on https://monev.maganghub.kemnaker.go.id.

Read this file in Indonesian: [README.md](README.md).

[Download stable version here](https://github.com/AKARandy/oh-my-monev/releases/latest) (For user) | [Download dev build version here](https://github.com/AKARandy/oh-my-monev/releases/tag/dev) (For debugging if you're developer).

MagangHub gives money to interns. But the participant must record attendance every day.
If the participant does not send the record before 23:59 WIB, the participant loses the money
for that day.

The app does this:

- It gives an alarm at a set time (default: 20:00) if attendance is not recorded.
- The app can fill the report form, then send it at the time you choose. You must pick one content
  source: your own template, or an automatic continuation of the previous day's report.
- The automatic continuation is experimental and risky. The app asks for confirmation before you
  can turn it on. It rewrites the previous workday's report offline on the device (requires a one-time
  language file download). Meaning can change. The preview shows the exact text that will be sent.
- The app checks all text before it fills the form. Each part must have 100 to 5000 characters.
- The phone can be idle. The alarms stay exact. A service keeps the phone awake during the fill.

Status: stable v1.1.0 released (separate rolling dev build on tag `dev`).

## Screenshots

| Homepage | Automation page |
| :---: | :---: |
| <img src="screenshots/WhatsApp Image 2026-11-23 at 08.51.52.jpeg" alt="Beranda" width="300" /> | <img src="screenshots/WhatsApp Image 2026-11-23 at 08.51.53.jpeg" alt="Otomatis" width="300" /> |

## Structure

- `:core`: Kotlin code with no Android parts. Checks the text, makes reports, calculates times.
- `:app`: the Android app. Screen code, web page control, alarms.
- `specs/`: TLA+ formal specifications verifying core state machines.
- `docs/`: the plan. The rules of the Monev website (`monev-logic.md`). Other notes.
- `.tools/`: the build tools for this computer. Not in the repository.

## Build

```powershell
# Step 1: Go to the repository folder.
# Step 2: Make local.properties. Write the SDK path in it.
# Step 3: Run these commands:
.tools\gradle-8.10.2\bin\gradle.bat :core:test      # start with these tests. No SDK is necessary.
.tools\gradle-8.10.2\bin\gradle.bat :app:assembleDebug
```

## Approved use

The app uses your account and your session. It does not invent report text.
You are responsible for the text that the app sends.
The app writes every automatic action to a log. You can read the text of each action.
You can stop the automation for one day.
