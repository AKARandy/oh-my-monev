# oh my monev

oh my monev is an Android app. It helps MagangHub participants (Kemnaker, Indonesia) to record
their daily attendance on https://monev.maganghub.kemnaker.go.id.

Read this file in Indonesian: [README.md](README.md).

[Download here](https://github.com/AKARandy/oh-my-monev/releases/latest).

MagangHub gives money to interns. But the participant must record attendance every day.
If the participant does not send the record before 23:59 WIB, the participant loses the money
for that day.

The app does this:

- It gives an alarm at a set time (default: 20:00) if attendance is not recorded.
- The app can fill the report form, then send it at the time you choose. You must pick one content
  source: your own template, or an automatic continuation of the previous day's report.
- The automatic continuation is experimental and risky. The app asks for confirmation before you
  can turn it on. Example: "Melakukan aktivitas X" becomes "Melanjutkan aktivitas X". It does not
  invent words. The preview shows the exact text that will be sent.
- The app checks all text before it fills the form. Each part must have 100 or more characters.
  No words such as "saya" or "kami". 3 to 5 activity lines. No clichés.
- The phone can be idle. The alarms stay exact. A service keeps the phone awake during the fill.

Status: still in development. See `docs/plan.md`.

## Structure

- `:core`: Kotlin code with no Android parts. Checks the text, makes reports, calculates times.
- `:app`: the Android app. Screen code, web page control, alarms.
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
