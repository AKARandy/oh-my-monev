---- MODULE ReminderController ----
EXTENDS Naturals

VARIABLES alarmState, ready, wakeLockHeld, ringtonePlaying, notifState

vars == <<alarmState, ready, wakeLockHeld, ringtonePlaying, notifState>>

AlarmStates == {"Idle", "Ringing", "Closed"}
NotifStates == {"None", "Alarm", "PersistentNormal"}

TypeOK ==
    /\ alarmState \in AlarmStates
    /\ ready \in BOOLEAN
    /\ wakeLockHeld \in BOOLEAN
    /\ ringtonePlaying \in BOOLEAN
    /\ notifState \in NotifStates

WakeLockOnlyWhenRinging == wakeLockHeld => (alarmState = "Ringing")
RingtoneOnlyWhenRinging == ringtonePlaying => (alarmState = "Ringing")
AlarmNotifMatchesRinging == (notifState = "Alarm") <=> (alarmState = "Ringing")
ClosedReleasesAll == (alarmState = "Closed") => (~wakeLockHeld /\ ~ringtonePlaying /\ notifState # "Alarm")

Init ==
    /\ alarmState = "Idle"
    /\ ready = TRUE
    /\ wakeLockHeld = FALSE
    /\ ringtonePlaying = FALSE
    /\ notifState = "None"

StartAlarm ==
    /\ alarmState = "Idle"
    /\ ready
    /\ alarmState' = "Ringing"
    /\ wakeLockHeld' = TRUE
    /\ ringtonePlaying' \in BOOLEAN
    /\ notifState' = "Alarm"
    /\ UNCHANGED ready

StopOrSnooze ==
    /\ alarmState = "Ringing"
    /\ alarmState' = "Idle"
    /\ wakeLockHeld' = FALSE
    /\ ringtonePlaying' = FALSE
    /\ notifState' = "None"
    /\ UNCHANGED ready

Timeout ==
    /\ alarmState = "Ringing"
    /\ alarmState' = "Idle"
    /\ wakeLockHeld' = FALSE
    /\ ringtonePlaying' = FALSE
    /\ notifState' = "PersistentNormal"
    /\ UNCHANGED ready

ReadinessRevoked ==
    /\ ready
    /\ ready' = FALSE
    /\ IF alarmState = "Ringing" THEN
           /\ alarmState' = "Idle"
           /\ wakeLockHeld' = FALSE
           /\ ringtonePlaying' = FALSE
           /\ notifState' = "None"
       ELSE
           UNCHANGED <<alarmState, wakeLockHeld, ringtonePlaying, notifState>>

ReadinessRestored ==
    /\ ~ready
    /\ ready' = TRUE
    /\ UNCHANGED <<alarmState, wakeLockHeld, ringtonePlaying, notifState>>

Close ==
    /\ alarmState # "Closed"
    /\ alarmState' = "Closed"
    /\ wakeLockHeld' = FALSE
    /\ ringtonePlaying' = FALSE
    /\ notifState' = IF alarmState = "Ringing" THEN "None" ELSE notifState
    /\ UNCHANGED ready

ClosedDone ==
    /\ alarmState = "Closed"
    /\ UNCHANGED vars

Next ==
    \/ StartAlarm
    \/ StopOrSnooze
    \/ Timeout
    \/ ReadinessRevoked
    \/ ReadinessRestored
    \/ Close
    \/ ClosedDone

Spec == Init /\ [][Next]_vars

ClosedIsTerminal == [][(alarmState = "Closed") => (alarmState' = "Closed")]_vars

====
