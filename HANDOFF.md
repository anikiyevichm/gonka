# A8 package A handoff — Gonka Testermint harness

Status: **PREPARED** (not a live PASS).

Base Marketplace SHA: `6b5df7fbdebdc7504a86a9be2c5631288f132f60`.
Base Gonka SHA: `bbde35c87757d7519962d67424f16b5613f1fc36`.

Changed file: `testermint/src/test/kotlin/MarketplaceContractAcceptanceTests.kt`.
It adds one package selector/test that starts one cluster, bootstraps once, and
uses distinct Deal/Host-E fixtures for R1 and R2. Schedule: target E is current
epoch +3; R2 lock/claim is at E; original two releases make it Completed;
R1 Refund is submitted at E+5; then the native governance path transfers a
positive `10000000001ngonka` gift with two vesting epochs and releases each
newly unlocked tranche.

An R1 assertion is delayed until R2 evidence is attempted, so an isolated R1
assertion does not erase R2 command evidence. A cluster/bootstrap/fixture error
still aborts the package normally.

Future package command (only after the local `ops/a8` launcher allowlist is
changed by the integrator; this checkout does not edit it):

```powershell
./ops/a8/Start-A8.ps1 -Action prepare -RunId package-a-r1-r2-001 -Scenario package-a-r1-r2
./ops/a8/Start-A8.ps1 -Action run -RunId package-a-r1-r2-001 -Scenario package-a-r1-r2
```

Checks: `git diff --check` passed. `gradlew.bat compileTestKotlin` was attempted
without containers but is unavailable on this host because neither `JAVA_HOME`
nor `java` is configured. No Docker, Testermint, E2E, cleanup, push, PR, or
production Go/Rust modification was performed.

Still unverified live: the exact native governance proposal timing, included
DeliverTx fields and fees, current-epoch inclusion brackets, and real balances.
The Marketplace harness persists each completed command phase immediately and
uses the independent cumulative-rounding oracle for release payouts.
