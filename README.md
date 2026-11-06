# oh my monev

An offline-first Android app that helps MagangHub participants (Kemnaker, Indonesia) never lose a stipend
day just because they forgot to fill the daily attendance on https://monev.maganghub.kemnaker.go.id.

- Alarm at a set time (default 20:00) when attendance is still unrecorded
- Pre-fill tonight's report, auto-submit at a set time unless cancelled
- If forgotten: rule-based continuation from the previous report (e.g. "Melakukan aktivitas X" →
  "Melanjutkan aktivitas X") and rotating user pools — never fabricated content
- Submitted content is always gated through a delivery gate (min 100 chars per part, no first-person
  pronouns, 3–5 bulleted activities, one-paragraph lessons/struggles, no clichés)
- Runs unattended from an idle phone (Doze-exact alarms, foreground service, full-screen intent)

Status: v0.1 in development (milestones in `docs/plan.md`).

## Layout

- `:core` — pure Kotlin: delivery gate, verb-continuation generator, scheduling math, site profile
- `:app` — Android app (Compose, WebView engine, alarms)
- `docs/` — plan, site research, behavior spec (`monev-logic.md`), recon spike checklist
- `.tools/` — locally provisioned build toolchain (gitignored)

## Build (once toolchain provisioning finishes)

```powershell
# from repo root
sdk_dir will be written to local.properties by scripts; then:
.tools\gradle-8.10.2\bin\gradle.bat :core:test      # pure logic tests run without SDK too
.tools\gradle-8.10.2\bin\gradle.bat :app:assembleDebug
```

## Responsible use

The app automates **your own** actions in **your own** session and never invents report content; you remain
responsible for what is submitted under your name (auto-submissions are logged with their exact text and can
be disabled per-day).
