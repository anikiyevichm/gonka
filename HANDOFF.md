# A8 package B Gonka handoff — PARTIAL-PREPARED

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
Host-second amounts; the Kotlin test applies only the exact `Deal -> Buyer`
exemption, proves send #2 rollback, waits for restriction expiry, and invokes
the same selected-Deal retry/repeat route. No global restriction is presented
as proof of send #2.

No Docker/Testermint/E2E/Actions were run and no production runtime file changed.
`./gradlew compileTestKotlin` was attempted from `testermint` but did not start:
this host has no `JAVA_HOME` and no `java` on `PATH`; no global toolchain was
changed. Run it where JDK 21 is available before integration. R7.2 must be
connected to package C's NetworkUnconfirmed/HostOnly fixture; it is not an
alternative summary-fault implementation.
