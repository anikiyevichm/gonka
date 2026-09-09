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

No Docker/Testermint/E2E/Actions were run and no production runtime file changed.
`./gradlew compileTestKotlin` was attempted from `testermint` but did not start:
this host has no `JAVA_HOME` and no `java` on `PATH`; no global toolchain was
changed. Run it where JDK 21 is available before integration. R7.2 must be
connected to package C's NetworkUnconfirmed/HostOnly fixture; it is not an
alternative summary-fault implementation.
