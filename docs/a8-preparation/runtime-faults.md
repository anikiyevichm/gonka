# A8 query-fault runtime (test-only)

The runtime provider is implemented in `inference-chain/app/a8faults` and
installed immediately after the existing Wasm query plugins. No Marketplace
funds, permissions, storage, claim ledger or native vesting code is changed.

## Build and activation

Normal node builds use `app/a8_faults_disabled.go`: they do not read plans and
do not link the provider through the app hook. An explicit test build is:

```sh
cd inference-chain
go build -mod=readonly -tags=a8faults -o build/inferenced-a8 ./cmd/inferenced
go run -mod=readonly ./cmd/a8-query-fault-plan /absolute/path/plan.json
```

The existing node Dockerfile also accepts `--build-arg TAGS=a8faults`. This
argument must never be used for a production image. Keep the test image name
distinct; retain the binary SHA256, image ID, source SHA and Go build settings.
An A9 production manifest is not evidence for this instrumented runtime.

Before starting the tagged node, explicitly install the validated plan as
`<node-home>/config/a8-query-faults.json`. The normal app `home` option determines
this path. Absence means disabled even for tagged builds; malformed, oversized
or ambiguous plans stop startup. The tagged node logs the SHA256 of the exact
plan bytes and rule count at initialization. No control endpoint, environment
default, governance message, mutable global or per-query file reload exists.

Example (replace addresses, chain and heights with the dedicated fixture):

```json
{
  "version": 1,
  "chain_id": "gonka-mainnet",
  "rules": [{
    "id": "r5-summary-recovery",
    "deal": "gonka1y2a9p56kv044327uycmqdexl7zs82fs5ryv5le",
    "host": "gonka1dkl4mah5erqggvhqkpc8j3qs5tyuetgdy552cp",
    "epoch": 5,
    "route": "/inference.inference.Query/EpochPerformanceSummaryByParticipant",
    "from_height": 150,
    "until_height": 250,
    "kind": "handler_error"
  }]
}
```

The local Testermint chain uses `gonka-mainnet` too. A chain ID is an isolation
key, not evidence that the node is local. Only install this test binary/plan in
the owned A8 environment, never an external network.

## Deterministic activation and recovery

Rules are active at `from_height <= block_height < until_height`. The plan is
read once and copied before keeper construction. Recovery at `until_height`
delegates to the original query handler. Other Deal, chain, route, Host/E and
malformed requests delegate unchanged. Routing requests contain Host but no E;
CurrentEpoch requests contain neither, so both require the exact calling Deal.
Overlapping windows for one Deal/route are rejected.

For a fixture whose Deal address is learned after bootstrap, stop the entire
owned cluster before activating instrumentation; preserve all chain data, then
install the identical plan and tagged binary on **every validator** before
resuming. Do not run mixed plans/binaries across validators or hot-edit files.
Choose future windows from observed block/epoch timing. Retain the activation
height and plan hashes for each validator. Windows must not be shortened to
make a failed boundary check appear successful. A run that misses E+2/E+3 is
inconclusive for that boundary.

R5.1 must recover before the successful settlement. R6.3 runs while R5.2 is
still Locked and the summary fault is active: fail Buyer CW20, prove rollback,
clear only the CW20 fault, then refund. R5.2 summary recovery happens after the
terminal refund; R7.2 exercises HostOnly release without reviving the sale.

## Supported kinds and evidence layers

- All three routes: `handler_error`, `unsupported_request`,
  `malformed_protobuf`, `oversized_response`.
- Summary only: `missing_nested_summary`, `wrong_host` (requires valid different
  `other_host`), `wrong_epoch`, `invalid_participant_address`.
- Routing only: `duplicate_routing` (two exact target-E entries).

`missing_nested_summary` returns an empty valid protobuf response, not a
Go zero-value nested message. Errors and bytes travel through the real query
handler interface; the supplied unit tests also verify Go wasmvm result mapping.
InvalidResponse at the FFI JSON envelope and the other unexpected SystemError
cases remain separate VM-boundary/contract tests. This provider does not claim
to inject those cases. Native OOG/panics are not caught or converted into a
refundable query error.

Required evidence: source/binary/plan SHA256, exact plan, caller/Host/E, query
route, activation/recovery heights, native healthy summary/claim evidence,
included tx receipts, financial before/after snapshots, and plan-off delegate
observations. A successful provider unit test or tagged build is not live PASS.

## No-network checks

```sh
go test -mod=readonly ./app/a8faults
go test -mod=readonly ./app -run A8 -count=1
go test -mod=readonly -tags=a8faults ./app -run A8 -count=1
```

These do not start a node or Docker. The package-C handoff remains the fixed
case list; its future live orchestrator must use this provider and evidence
contract rather than claim that ordinary RPC failures are native query faults.
