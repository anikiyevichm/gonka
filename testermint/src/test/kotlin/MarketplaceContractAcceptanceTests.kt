import com.productscience.EpochStage
import com.productscience.data.AppState
import com.productscience.data.EpochParams
import com.productscience.data.InferenceParams
import com.productscience.data.InferenceState
import com.productscience.data.RestrictionsParams
import com.productscience.data.RestrictionsState
import com.productscience.data.TokenomicsParams
import com.productscience.data.spec
import com.productscience.inferenceConfig
import com.productscience.initCluster
import com.productscience.logSection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

@Timeout(value = 35, unit = TimeUnit.MINUTES)
class MarketplaceContractAcceptanceTests : TestermintTest() {
    @Test
    fun `marketplace funded claim settles and releases on real Gonka`() {
        val fastSpec = spec {
            this[AppState::inference] = spec<InferenceState> {
                this[InferenceState::params] = spec<InferenceParams> {
                    this[InferenceParams::tokenomicsParams] = spec<TokenomicsParams> {
                        this[TokenomicsParams::workVestingPeriod] = 2L
                        this[TokenomicsParams::rewardVestingPeriod] = 2L
                    }
                    this[InferenceParams::epochParams] = spec<EpochParams> {
                        this[EpochParams::epochLength] = 25L
                    }
                }
            }
            this[AppState::restrictions] = spec<RestrictionsState> {
                this[RestrictionsState::params] = spec<RestrictionsParams> {
                    this[RestrictionsParams::restrictionEndBlock] = 0L
                }
            }
        }
        val config = inferenceConfig.copy(
            genesisSpec = inferenceConfig.genesisSpec?.merge(fastSpec) ?: fastSpec
        )
        val (cluster, genesis) = initCluster(config = config, reboot = true)
        cluster.allPairs.forEach { it.waitForMlNodesToLoad() }

        val participant = cluster.joinPairs.first()
        val targetEpoch = genesis.getEpochData().latestEpoch.index + 3

        logSection("Deploy Marketplace, configure exact Deal recipient, and fund CW20")
        runHarness(
            "bootstrap",
            "--context", requiredEnv("A8_CONTEXT"),
            "--run-id", requiredEnv("A8_RUN_ID"),
            "--target-epoch", targetEpoch.toString(),
            "--deal-wasm", requiredEnv("A8_DEAL_WASM"),
            "--factory-wasm", requiredEnv("A8_FACTORY_WASM"),
            "--cw20-wasm", requiredEnv("A8_CW20_WASM"),
            "--caller-wasm", requiredEnv("A8_CALLER_WASM"),
        )

        val noSaleEpoch = targetEpoch + 1
        val gasEpoch = targetEpoch + 2
        val expiryEpoch = targetEpoch + 3
        val emergencyEpoch = targetEpoch + 4
        prepareDeal("no-sale", noSaleEpoch, funded = false)
        prepareDeal("gas-claimed", gasEpoch, funded = true)
        prepareDeal("claim-expiry", expiryEpoch, funded = true)
        prepareDeal(
            "network-unconfirmed",
            emergencyEpoch,
            funded = true,
            hostNode = "genesis-node",
            hostKey = "a8-inactive-${requiredEnv("A8_RUN_ID")}",
        )
        prepareDeal(
            "routing-mismatch",
            targetEpoch,
            funded = true,
            hostNode = "genesis-node",
            hostKey = "genesis",
        )
        runHarness(
            "set-scenario-routing",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-mismatch",
            "--recipient", "buyer",
        )
        prepareDeal(
            "routing-missing",
            noSaleEpoch,
            funded = true,
            hostNode = "genesis-node",
            hostKey = "genesis",
        )
        runHarness(
            "set-scenario-routing",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-missing",
            "--recipient", "missing",
        )
        prepareDeal(
            "lock-e-plus-4",
            gasEpoch,
            funded = false,
            hostNode = "genesis-node",
            hostKey = "genesis",
        )
        prepareDeal(
            "lock-e-plus-5",
            gasEpoch,
            funded = false,
            hostNode = "join2-node",
            hostKey = "join2",
        )

        logSection("Wait for target epoch $targetEpoch and lock the exact routing proof")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness("lock", "--context", requiredEnv("A8_CONTEXT"))
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-mismatch",
            "--expect", "success",
            "--reason", "routing_mismatch",
        )

        val seedConfig = participant.api.getConfig()
        val rewardSeed = seedConfig.currentSeed
        val noSaleSeed = seedConfig.upcomingSeed
        check(rewardSeed.epochIndex == targetEpoch) {
            "Testermint reward seed epoch ${rewardSeed.epochIndex} != Deal epoch $targetEpoch"
        }
        check(noSaleSeed.epochIndex == noSaleEpoch) {
            "Testermint upcoming seed epoch ${noSaleSeed.epochIndex} != no-sale epoch $noSaleEpoch"
        }
        genesis.markNeedsReboot()
        participant.stopApiContainer()
        logSection("Auto-claim stopped; wait for native claim window")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)

        runHarness(
            "claim-settle",
            "--context", requiredEnv("A8_CONTEXT"),
            "--reward-seed", rewardSeed.seed.toString(),
            "--reward-epoch", rewardSeed.epochIndex.toString(),
        )

        runHarness("lock-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-missing",
            "--expect", "success",
            "--reason", "routing_missing",
        )

        participant.restartApiContainer()
        genesis.node.waitForNextBlock(2)
        val gasSeed = participant.api.getConfig().upcomingSeed
        check(gasSeed.epochIndex == gasEpoch)
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = -1)
        participant.stopApiContainer()

        logSection("Advance to $gasEpoch: first funded unlock and no-sale native claim")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
        genesis.node.waitForNextBlock(2)
        runHarness("release", "--context", requiredEnv("A8_CONTEXT"))
        runHarness("lock-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "gas-claimed")
        runHarness(
            "donate-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--label", "before_settlement",
            "--amount", "3",
        )
        runHarness(
            "contaminate-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--amount", "7",
        )
        runHarness(
            "claim-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--reward-seed", noSaleSeed.seed.toString(),
            "--reward-epoch", noSaleSeed.epochIndex.toString(),
        )
        runHarness("settle-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness(
            "donate-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--label", "after_settlement",
            "--amount", "2",
        )
        participant.restartApiContainer()
        genesis.node.waitForNextBlock(2)
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = -1)
        participant.stopApiContainer()
        logSection("Advance to $expiryEpoch: complete funded Deal and claim gas-regression Deal")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
        genesis.node.waitForNextBlock(2)
        runHarness("release", "--context", requiredEnv("A8_CONTEXT"))
        runHarness("release-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness("late-donation", "--context", requiredEnv("A8_CONTEXT"))
        runHarness("lock-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "claim-expiry")
        runHarness(
            "claim-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "gas-claimed",
            "--reward-seed", gasSeed.seed.toString(),
            "--reward-epoch", gasSeed.epochIndex.toString(),
        )

        logSection("Advance to $emergencyEpoch: finish no-sale vesting and lock absent-summary Deal")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
        genesis.node.waitForNextBlock(2)
        runHarness("release-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
            "--expect", "failure",
            "--reason", "too_early",
        )

        logSection("Advance to E+2 claim-expiry and E+3 gas boundary")
        genesis.waitForNextEpoch()
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
            "--expect", "success",
            "--reason", "claim_expiry",
        )
        runHarness(
            "gas-sweep-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "gas-claimed",
        )

        logSection("Advance to emergency E+2: unavailable summary must still fail closed")
        genesis.waitForNextEpoch()
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expect", "failure",
            "--reason", "too_early",
        )
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "lock-e-plus-4",
        )

        logSection("Advance to emergency E+3 and recipient pruning boundary E+5")
        genesis.waitForNextEpoch()
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expect", "success",
            "--reason", "network_unconfirmed",
        )
        runHarness(
            "lock-rejected-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "lock-e-plus-5",
            "--routing", "pruned",
        )
    }

    private fun runHarness(vararg args: String) {
        val command = listOf(requiredEnv("A8_PYTHON"), requiredEnv("A8_HARNESS")) + args
        val output = runMarketplaceHarnessProcess(
            command = command,
            directory = File(requiredEnv("A8_MARKETPLACE_DIR")),
            phase = args.firstOrNull(),
            timeout = 8,
            timeoutUnit = TimeUnit.MINUTES,
        )
        println(output.trim())
    }

    private fun prepareDeal(
        name: String,
        targetEpoch: Long,
        funded: Boolean,
        hostNode: String = "join1-node",
        hostKey: String = "join1",
    ) {
        val args = mutableListOf(
            "create-deal",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", name,
            "--target-epoch", targetEpoch.toString(),
            "--host-node", hostNode,
            "--host-key", hostKey,
            "--route-exact",
        )
        if (funded) args += "--fund"
        runHarness(*args.toTypedArray())
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("Required environment variable $name is missing")
}
