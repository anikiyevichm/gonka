# A8 package A handoff — Gonka Testermint harness

## Merge handoff status

The agreed acceptance checks are complete at their recorded proof levels;
no additional live run is required for these MRs. Marketplace's
`docs/reviews/a8-final-coverage-matrix.md` is the current status authority.
Native exact-E PASS, 73 reviewed C cases, Go JSON and synthetic Wasm ABI PASS,
and two named contract tests with full logs are preserved in Marketplace evidence.
This does not claim production Go/libwasmvm/keeper fault reachability or a
successful historical C JUnit after timeout. Unified runner is separate follow-up.
The preparation/run instructions and pending labels below are historical;
use the current matrix for acceptance status, not those old TODOs.



Status: **PREPARED** (not a live PASS).

Base Marketplace SHA: `6b5df7fbdebdc7504a86a9be2c5631288f132f60`.
Base Gonka SHA: `bbde35c87757d7519962d67424f16b5613f1fc36`.
Prepared Marketplace code HEAD: `7877b26b5048552da95111d533fd4e017587afed`.
Prepared Gonka code HEAD: `76ecaa3e9ce3c7a185b4933f888ee15eb66f3f64`.

Changed file: `testermint/src/test/kotlin/MarketplaceContractAcceptanceTests.kt`.
It adds one package selector/test that starts one cluster, bootstraps once, and
uses distinct Deal/Host-E fixtures for R1 and R2. R1 uses `E=current+3` with a
dedicated Host; bootstrap already owns `join1/E`, so R2 uses `join1/(E+1)`.
R2's original two releases must make it Completed before R1 Refund at E+5.
Then the native governance path transfers a positive `10000000001ngonka` gift
with two vesting epochs and releases each newly unlocked tranche. Persisted
R2 checkpoints prove pre-gift Completed/zero vesting, fully locked gift, first
unlock with vesting remaining and unchanged counters, and final zero remainder
with frozen-share cumulative counters.

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
