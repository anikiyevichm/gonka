package app

import (
	"testing"

	wasmvmtypes "github.com/CosmWasm/wasmvm/v2/types"
	"github.com/cosmos/gogoproto/proto"
	"github.com/productscience/inference/x/inference/types"
	"github.com/stretchr/testify/require"
)

// This fixture is deliberately test-only. It specifies the narrow matching
// rule a future A8 runtime decorator must use; it is not linked into a node.
const a8EpochSummaryPath = "/inference.inference.Query/EpochPerformanceSummaryByParticipant"

type a8FaultKind string

const (
	a8HandlerError a8FaultKind = "handler_error"
	a8Unsupported  a8FaultKind = "unsupported_request"
	a8BadProto     a8FaultKind = "malformed_protobuf"
	a8Oversized     a8FaultKind = "oversized_response"
	a8MissingNested a8FaultKind = "missing_nested_summary"
	a8WrongHost     a8FaultKind = "wrong_host"
	a8WrongEpoch    a8FaultKind = "wrong_epoch"
	a8InvalidJSON   a8FaultKind = "invalid_response_envelope"
)

type a8FaultLayer string

const (
	a8ContractResultLayer a8FaultLayer = "contract_result"
	a8SystemResultLayer   a8FaultLayer = "system_result"
	a8RawResponseLayer    a8FaultLayer = "raw_response"
)

// a8FaultDeliveryLayer prevents a test plan from presenting an ordinary Go
// error as a typed SystemError. Invalid JSON must be injected at the FFI JSON
// envelope, not returned by the keeper.
func a8FaultDeliveryLayer(kind a8FaultKind) a8FaultLayer {
	switch kind {
	case a8HandlerError:
		return a8ContractResultLayer
	case a8Unsupported:
		return a8SystemResultLayer
	case a8BadProto, a8Oversized, a8MissingNested, a8WrongHost, a8WrongEpoch:
		return a8RawResponseLayer
	case a8InvalidJSON:
		return a8SystemResultLayer
	default:
		panic("unknown A8 fault kind")
	}
}

type a8SummaryFaultSelector struct {
	Enabled bool
	Host    string
	Epoch   uint64
	Kind    a8FaultKind
}

// matches is fail-closed: a disabled selector, another route, malformed
// request, another host, or another epoch is always delegated unchanged.
func (s a8SummaryFaultSelector) matches(request *wasmvmtypes.GrpcQuery) bool {
	if !s.Enabled || request == nil || request.Path != a8EpochSummaryPath {
		return false
	}
	var decoded types.QueryEpochPerformanceSummaryByParticipantRequest
	if err := proto.Unmarshal(request.Data, &decoded); err != nil {
		return false
	}
	return decoded.ParticipantId == s.Host && decoded.EpochIndex == s.Epoch
}

func TestA8SummaryFaultSelectorIsNarrowAndDeterministic(t *testing.T) {
	selector := a8SummaryFaultSelector{
		Enabled: true,
		Host:    "gonka1host",
		Epoch:   42,
		Kind:    a8HandlerError,
	}

	request := func(path, host string, epoch uint64) *wasmvmtypes.GrpcQuery {
		data, err := proto.Marshal(&types.QueryEpochPerformanceSummaryByParticipantRequest{
			ParticipantId: host,
			EpochIndex:     epoch,
		})
		require.NoError(t, err)
		return &wasmvmtypes.GrpcQuery{Path: path, Data: data}
	}

	require.True(t, selector.matches(request(a8EpochSummaryPath, "gonka1host", 42)))
	require.False(t, selector.matches(request("/inference.inference.Query/GetCurrentEpoch", "gonka1host", 42)))
	require.False(t, selector.matches(request(a8EpochSummaryPath, "gonka1other", 42)))
	require.False(t, selector.matches(request(a8EpochSummaryPath, "gonka1host", 43)))
	require.False(t, selector.matches(&wasmvmtypes.GrpcQuery{Path: a8EpochSummaryPath, Data: []byte{0xff}}))

	selector.Enabled = false
	require.False(t, selector.matches(request(a8EpochSummaryPath, "gonka1host", 42)))
}

func TestA8FaultKindsKeepTheirActualWasmBoundary(t *testing.T) {
	tests := map[a8FaultKind]a8FaultLayer{
		a8HandlerError: a8ContractResultLayer,
		a8Unsupported:  a8SystemResultLayer,
		a8BadProto:     a8RawResponseLayer,
		a8Oversized:     a8RawResponseLayer,
		a8MissingNested: a8RawResponseLayer,
		a8WrongHost:     a8RawResponseLayer,
		a8WrongEpoch:    a8RawResponseLayer,
		a8InvalidJSON:   a8SystemResultLayer,
	}
	for kind, want := range tests {
		require.Equal(t, want, a8FaultDeliveryLayer(kind), string(kind))
	}
}
