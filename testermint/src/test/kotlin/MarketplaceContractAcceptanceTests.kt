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

        logSection("Wait for target epoch $targetEpoch and lock the exact routing proof")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness("lock", "--context", requiredEnv("A8_CONTEXT"))

        val rewardSeed = participant.api.getConfig().currentSeed
        check(rewardSeed.epochIndex == targetEpoch) {
            "Testermint reward seed epoch ${rewardSeed.epochIndex} != Deal epoch $targetEpoch"
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

        repeat(2) { tranche ->
            logSection("Wait for vesting unlock tranche ${tranche + 1}/2")
            genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
            genesis.node.waitForNextBlock(2)
            runHarness("release", "--context", requiredEnv("A8_CONTEXT"))
        }

        logSection("Release a liquid donation received after Completed")
        runHarness("late-donation", "--context", requiredEnv("A8_CONTEXT"))
    }

    private fun runHarness(vararg args: String) {
        val command = listOf(requiredEnv("A8_PYTHON"), requiredEnv("A8_HARNESS")) + args
        val process = ProcessBuilder(command)
            .directory(File(requiredEnv("A8_MARKETPLACE_DIR")))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(8, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "Marketplace harness timed out: ${args.firstOrNull()}"
        }
        check(process.exitValue() == 0) {
            "Marketplace harness failed (${args.firstOrNull()}):\n$output"
        }
        println(output.trim())
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("Required environment variable $name is missing")
}
