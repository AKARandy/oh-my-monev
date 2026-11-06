# oh-my-monev implementation plan (approved 2026-11-06)

Standalone offline-first Android app (Kotlin + Compose, minSdk 26, targetSdk 35) that reminds, pre-fills,
and auto-submits the MagangHub Monev daily report via **in-app WebView UI automation**, so nobody loses a
stipend day just for forgetting. No server, no cloud, no fabricated content, **app-side downloads: none**.

## Locked decisions

| # | Decision |
|---|---|
| D1 | Native Kotlin/Compose; full control of alarms, FGS, full-screen intents, WebView |
| D2 | Submission engine = persistent in-app WebView; session/SSO/location checks run natively as they would for the user |
| D3 | No cloud backend; Room/DataStore on-device; full audit log of every automated action |
| D4 | Same-night doctrine: missed day is **permanently lost** (confirmed by user) → alarm → prefill/cancel-window → run → retries (+5/+20/+60 min) → escalation 22:30 → next-morning post-mortem |
| D5 | Generator tiers: T0 template → T1 continuation rules (VerbMapper reshapes yesterday's own content) → optional later T2 on-device IndoBERT-class re-ranker → T3 small seq2seq (exploratory); hard DeliveryGate G-1..G-9 before anything touches the form |
| D6 | Distribution: signed APK via GitHub Releases |
| D7 | Device-local time semantics; defaults assume WIB (+07:00, no DST); reminder 20:00 / auto-run 21:00 / escalation 22:30 |
| D8 | "Mode Uji" (dry-run)default ON: engine stops before checkbox+submit until first live submission is confirmed by the user |
| D9 | Auto-submit opt-in with per-day cancel window (default 30 min); every auto action logged with exact text |

Nightly sequence and failure paths, permission list, generator details, test plan, risks — see the approved
plan in this repository's git history and `docs/site-research.md` for site ground truth.

## Milestones

| M | Deliverable | Acceptance |
|---|---|---|
| M0 | Toolchain + repo scaffold + recon spike with user account | notes doc; dry-run fill verified on real form |
| M1 | App shell: Compose screens skeleton, Room/DataStore, onboarding | installs, onboarding completes |
| M2 | WebView engine + gate + manual run + Mode Uji | real form filled, readback-verified, no submit |
| M3 | Scheduling ladder + retries + escalation + OEM guides + test alarm | 3 unattended nightly dry-runs pass incl. airplane-mode recovery |
| M4 | Generator T0/T1 + pools + settings | golden tests from real past reports pass gate |
| M5 | Hardening: unit/Robolectric/instrumented fixture tests, docs | test suite green; QA checklist |
| M6 | CI + signed APK v0.1.0 + README(id/en) | APK sideloads and runs |

## Non-goals (v0.1)

No server/cloud, no multi-account, no mentor-approval workflow, no Izin/Sakit automation, no root/Xposed, no iOS, **no on-device model downloads in v0.1** (rules + templates only, per user decision).
