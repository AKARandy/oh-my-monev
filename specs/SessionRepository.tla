---- MODULE SessionRepository ----
EXTENDS Naturals

CONSTANTS Tokens, MaxVersion

None == "none"

VARIABLES phase, version, token, rejected, recordActive, flight, prevVersion

vars == <<phase, version, token, rejected, recordActive, flight, prevVersion>>

Phases == {"Initializing", "SignedOut", "Expired", "Unverified", "Active"}

TypeOK ==
    /\ phase \in Phases
    /\ version \in 0..MaxVersion
    /\ prevVersion \in 0..MaxVersion
    /\ token \in Tokens \cup {None}
    /\ rejected \in Tokens \cup {None}
    /\ recordActive \in {"none", "true", "false"}
    /\ flight \in [active: BOOLEAN, expectedVersion: 0..MaxVersion, preserve: BOOLEAN]

VersionMonotonic == version >= prevVersion

PhaseConsistency ==
    /\ (phase = "Initializing") => (token = None /\ version = 0)
    /\ (phase = "SignedOut") => (token = None)
    /\ (phase = "Expired") => (token = None)
    /\ (phase = "Unverified") => (token \in Tokens)
    /\ (phase = "Active") => (token \in Tokens /\ recordActive = "true")

Init ==
    /\ phase = "Initializing"
    /\ version = 0
    /\ prevVersion = 0
    /\ token = None
    /\ rejected = None
    /\ recordActive = "none"
    /\ flight = [active |-> FALSE, expectedVersion |-> 0, preserve |-> FALSE]

Load(hasTok, tok, recAct, isSignedOut) ==
    /\ phase = "Initializing"
    /\ prevVersion' = version
    /\ version' = version
    /\ rejected' = None
    /\ recordActive' = recAct
    /\ UNCHANGED flight
    /\ IF isSignedOut THEN
           /\ phase' = "SignedOut"
           /\ token' = None
       ELSE IF hasTok THEN
           /\ token' = tok
           /\ phase' = IF recAct = "true" THEN "Active" ELSE "Unverified"
       ELSE
           /\ phase' = "Expired"
           /\ token' = None

DoCapture(tok, expVer, afterLogin, preserve) ==
    /\ phase # "Initializing"
    /\ version < MaxVersion
    /\ tok \in Tokens
    /\ tok # token
    /\ (~afterLogin => tok # rejected)
    /\ version = expVer
    /\ (phase = "SignedOut" => afterLogin)
    /\ prevVersion' = version
    /\ version' = version + 1
    /\ token' = tok
    /\ rejected' = rejected
    /\ IF preserve THEN
           /\ recordActive' = recordActive
           /\ phase' = IF recordActive = "true" THEN "Active" ELSE "Unverified"
       ELSE
           /\ recordActive' = "none"
           /\ phase' = "Unverified"

Capture(tok, expVer, afterLogin, preserve) ==
    /\ DoCapture(tok, expVer, afterLogin, preserve)
    /\ UNCHANGED flight

Confirm(tok, expVer) ==
    /\ phase \in {"Unverified", "Active"}
    /\ version = expVer
    /\ token = tok
    /\ prevVersion' = version
    /\ version' = version
    /\ token' = token
    /\ rejected' = rejected
    /\ recordActive' = "true"
    /\ phase' = "Active"
    /\ UNCHANGED flight

DoInvalidate(isSignOut) ==
    LET sameInvalid ==
            \/ (phase = "SignedOut" /\ isSignOut /\ recordActive = "false")
            \/ (phase = "Expired" /\ ~isSignOut /\ recordActive = "false")
    IN
    /\ phase # "Initializing"
    /\ IF sameInvalid THEN
           /\ UNCHANGED <<phase, version, token, rejected, recordActive>>
           /\ prevVersion' = version
       ELSE
           /\ version < MaxVersion
           /\ prevVersion' = version
           /\ version' = version + 1
           /\ rejected' = IF token # None THEN token ELSE rejected
           /\ token' = None
           /\ recordActive' = "false"
           /\ phase' = IF isSignOut THEN "SignedOut" ELSE "Expired"

Reject(expVer) ==
    /\ phase # "Initializing"
    /\ version = expVer
    /\ DoInvalidate(FALSE)
    /\ UNCHANGED flight

SignOut ==
    /\ phase # "Initializing"
    /\ DoInvalidate(TRUE)
    /\ UNCHANGED flight

StartRefresh(expVer) ==
    /\ phase \in {"Unverified", "Active", "Expired"}
    /\ ~flight.active
    /\ version = expVer
    /\ flight' = [active |-> TRUE, expectedVersion |-> expVer, preserve |-> (token # None)]
    /\ prevVersion' = version
    /\ UNCHANGED <<phase, version, token, rejected, recordActive>>

FinishRefreshToken(tok) ==
    /\ flight.active
    /\ DoCapture(tok, flight.expectedVersion, FALSE, flight.preserve)
    /\ flight' = [active |-> FALSE, expectedVersion |-> 0, preserve |-> FALSE]

FinishRefreshRejectOrCancel ==
    /\ flight.active
    /\ IF version = flight.expectedVersion /\ phase # "Initializing" THEN
           DoInvalidate(FALSE)
       ELSE
           /\ prevVersion' = version
           /\ UNCHANGED <<phase, version, token, rejected, recordActive>>
    /\ flight' = [active |-> FALSE, expectedVersion |-> 0, preserve |-> FALSE]

Next ==
    \/ \E hasTok \in BOOLEAN, tok \in Tokens, recAct \in {"none", "true", "false"}, isSO \in BOOLEAN :
           Load(hasTok, tok, recAct, isSO)
    \/ \E tok \in Tokens, expVer \in 0..MaxVersion, afterLogin \in BOOLEAN, preserve \in BOOLEAN :
           Capture(tok, expVer, afterLogin, preserve)
    \/ \E tok \in Tokens, expVer \in 0..MaxVersion :
           Confirm(tok, expVer)
    \/ \E expVer \in 0..MaxVersion : Reject(expVer)
    \/ SignOut
    \/ \E expVer \in 0..MaxVersion : StartRefresh(expVer)
    \/ \E tok \in Tokens : FinishRefreshToken(tok)
    \/ FinishRefreshRejectOrCancel

Spec == Init /\ [][Next]_vars

SignedOutOnlyRecoveredByAfterLogin ==
    [][(phase = "SignedOut" /\ phase' \in {"Unverified", "Active"}) =>
           (\E tok \in Tokens, expVer \in 0..MaxVersion, preserve \in BOOLEAN :
                Capture(tok, expVer, TRUE, preserve))]_vars

StaleRefreshCannotResurrectSignedOut ==
    [][(phase = "SignedOut" /\ FinishRefreshRejectOrCancel) => (phase' = "SignedOut")]_vars

NoRejectedRecaptureWithoutLogin ==
    [][(\E tok \in Tokens, expVer \in 0..MaxVersion, preserve \in BOOLEAN :
            (tok = rejected /\ Capture(tok, expVer, FALSE, preserve))) => FALSE]_vars

====
