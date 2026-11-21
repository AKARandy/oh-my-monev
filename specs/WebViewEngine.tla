---- MODULE WebViewEngine ----
EXTENDS Naturals

CONSTANTS MaxCallbacks

VARIABLES phase, locationOnly, locationOk, filled, readBackOk, callbackCount

vars == <<phase, locationOnly, locationOk, filled, readBackOk, callbackCount>>

Phases == {
    "PreparingLocation",
    "CheckingSession",
    "OpeningForm",
    "WaitingLocation",
    "Filling",
    "ReadingBack",
    "Submitting",
    "AwaitingConfirmation",
    "Terminated"
}

TypeOK ==
    /\ phase \in Phases
    /\ locationOnly \in BOOLEAN
    /\ locationOk \in BOOLEAN
    /\ filled \in BOOLEAN
    /\ readBackOk \in BOOLEAN
    /\ callbackCount \in 0..MaxCallbacks

CallbackAtMostOnce == callbackCount <= 1

NoFillWithoutLocationOk == filled => locationOk

SubmitRequiresValidReadBack == (phase \in {"Submitting", "AwaitingConfirmation"}) => (filled /\ readBackOk)

Init ==
    /\ phase \in {"PreparingLocation", "CheckingSession"}
    /\ locationOnly \in BOOLEAN
    /\ locationOk = FALSE
    /\ filled = FALSE
    /\ readBackOk = FALSE
    /\ callbackCount = 0

LocationReady ==
    /\ phase = "PreparingLocation"
    /\ phase' = IF locationOnly THEN "WaitingLocation" ELSE "CheckingSession"
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk, callbackCount>>

TabsFound ==
    /\ phase = "CheckingSession"
    /\ phase' = "OpeningForm"
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk, callbackCount>>

TextareasReady ==
    /\ phase = "OpeningForm"
    /\ phase' = "WaitingLocation"
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk, callbackCount>>

LocationMarkerFound ==
    /\ phase = "WaitingLocation"
    /\ locationOk' = TRUE
    /\ IF locationOnly THEN
           /\ phase' = "Terminated"
           /\ callbackCount' = callbackCount + 1
       ELSE
           /\ phase' = "Filling"
           /\ UNCHANGED callbackCount
    /\ UNCHANGED <<locationOnly, filled, readBackOk>>

FillSucceeded ==
    /\ phase = "Filling"
    /\ filled' = TRUE
    /\ phase' = "ReadingBack"
    /\ UNCHANGED <<locationOnly, locationOk, readBackOk, callbackCount>>

ReadBackPassed ==
    /\ phase = "ReadingBack"
    /\ readBackOk' = TRUE
    /\ phase' = "Submitting"
    /\ UNCHANGED <<locationOnly, locationOk, filled, callbackCount>>

SubmitClicked ==
    /\ phase = "Submitting"
    /\ phase' = "AwaitingConfirmation"
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk, callbackCount>>

ConfirmSucceeded ==
    /\ phase = "AwaitingConfirmation"
    /\ phase' = "Terminated"
    /\ callbackCount' = callbackCount + 1
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk>>

TerminateWithFailure ==
    /\ phase # "Terminated"
    /\ phase' = "Terminated"
    /\ callbackCount' = callbackCount + 1
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk>>

Cancel ==
    /\ phase # "Terminated"
    /\ phase' = "Terminated"
    /\ UNCHANGED <<locationOnly, locationOk, filled, readBackOk, callbackCount>>

TerminatedDone ==
    /\ phase = "Terminated"
    /\ UNCHANGED vars

Next ==
    \/ LocationReady
    \/ TabsFound
    \/ TextareasReady
    \/ LocationMarkerFound
    \/ FillSucceeded
    \/ ReadBackPassed
    \/ SubmitClicked
    \/ ConfirmSucceeded
    \/ TerminateWithFailure
    \/ Cancel
    \/ TerminatedDone

Spec == Init /\ [][Next]_vars /\ WF_vars(Next)

NoStepAfterTerminated == [][(phase = "Terminated") => (phase' = "Terminated")]_vars
EventuallyTerminates == <>(phase = "Terminated")

====
