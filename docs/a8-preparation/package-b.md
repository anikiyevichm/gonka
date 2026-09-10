# A8 package B Gonka handoff — PARTIAL-PREPARED

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



Base/head before this package: `bbde35c87757d7519962d67424f16b5613f1fc36`.
Branch: `test/a8-package-b`; isolated checkout: `a8-package-b-gonka`.

Changed only test sources:

- `testermint/src/test/kotlin/A8PackageBBankFaultPlan.kt`
- `testermint/src/test/kotlin/A8PackageBBankFaultPlanTests.kt`

`a8BankSendFaultPlan` uses existing restrictions parameters, not a Go runtime
hook: an active window has exactly one `Deal -> Buyer` exemption for the exact
first payout amount. Therefore Buyer send #1 may execute while Host send #2 has
no matching exemption and fails. It rejects aliased recipients and zero amounts.
`a8HostOnlyBankSendFaultPlan` has no exemption because Host is the first and only
send. Both plans are default-off: they do nothing until an integration test
passes their params through the existing Testermint governance update path.

`MarketplaceContractAcceptanceTests` now wires the existing NetworkUnconfirmed
HostOnly sequence to the Marketplace `bank-release-rollback-scenario` and then
`bank-release-retry-scenario`: one selected fault phase, restriction expiry,
oracle-checked retry, and terminal no-double-payout phase are retained under the
same scenario evidence. It reads only the already-written A8 context to bind the
expected Host recipient; it does not modify runtime state or summary faults.

It also contains a focused R7.1 scenario: one bootstrap Deal reaches a
proportional release; the Marketplace oracle records actual Buyer-first and
Host-second amounts. It first proves rollback of Buyer send #1 under an active
restriction without exemptions, then applies only the exact `Deal -> Buyer`
exemption and proves Host send #2 rollback, waits for restriction expiry, and
invokes the selected-Deal retry/repeat route. The Marketplace command reads
live `restrictions params` and rejects missing, expired, broad, or insufficient
exemptions; no launcher argument or global restriction is proof of send #2.

The focused R6.1 Kotlin scenario bootstraps with price `1000`, making Host net,
fee, and Buyer refund non-zero, then runs CW20 fault positions `1,2,3` before
the single successful settlement retry and repeat rejection.

No Docker/Testermint/E2E/Actions were run and no production runtime file changed.
`./gradlew compileTestKotlin` was attempted from `testermint` but did not start:
this host has no `JAVA_HOME` and no `java` on `PATH`; no global toolchain was
changed. Run it where JDK 21 is available before integration. R7.2 must be
connected to package C's NetworkUnconfirmed/HostOnly fixture; it is not an
alternative summary-fault implementation.
