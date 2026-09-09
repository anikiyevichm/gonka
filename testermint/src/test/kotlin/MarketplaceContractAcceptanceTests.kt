import com.google.gson.JsonParser
import com.productscience.EpochStage
import com.productscience.data.Coin
import com.productscience.data.AppState
import com.productscience.data.BitcoinRewardParams
import com.productscience.data.EpochParams
import com.productscience.data.InferenceParams
import com.productscience.data.InferenceState
import com.productscience.data.MsgTransferWithVesting
import com.productscience.data.RestrictionsParams
import com.productscience.data.RestrictionsState
import com.productscience.data.TokenomicsParams
import com.productscience.data.UpdateRestrictionsParams
import com.productscience.data.UnfundedInferenceParticipant
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
    fun `marketplace zero unclaimed summary refunds only at claim expiry`() {
        // This is a special test-network genesis setting, not a production default.
        // Native Params.Validate accepts uint64 zero, and the unchanged settlement
        // keeper still writes an EpochPerformanceSummary for the active participant.
        val config = fastMarketplaceConfig(initialEpochReward = 0L)
        val (cluster, genesis) = initCluster(config = config, reboot = true)
        cluster.allPairs.forEach { it.waitForMlNodesToLoad() }

        val unclaimedHost = cluster.joinPairs.first()
        val targetEpoch = genesis.getEpochData().latestEpoch.index + 3

        logSection("Deploy Marketplace under the zero-subsidy test genesis")
        runHarness(
            "bootstrap",
            "--context", requiredEnv("A8_CONTEXT"),
            "--run-id", requiredEnv("A8_RUN_ID"),
            "--target-epoch", targetEpoch.toString(),
            "--deal-wasm", requiredEnv("A8_DEAL_WASM"),
            "--factory-wasm", requiredEnv("A8_FACTORY_WASM"),
            "--cw20-wasm", requiredEnv("A8_CW20_WASM"),
            "--caller-wasm", requiredEnv("A8_CALLER_WASM"),
            "--host-node", "genesis-node",
            "--host-key", "genesis",
            "--expected-initial-epoch-reward", "0",
        )
        prepareDeal(
            "claim-expiry-zero",
            targetEpoch,
            funded = true,
            hostNode = "join1-node",
            hostKey = "join1",
        )

        genesis.markNeedsReboot()
        logSection("Reach E-1 and preserve the ordinary active Host snapshot")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch - 1) {
            genesis.waitForNextEpoch()
        }
        genesis.waitForStage(EpochStage.END_OF_POC_VALIDATION, offset = 0)
        unclaimedHost.stopApiContainer()

        logSection("Enter E=$targetEpoch and lock the exact native recipient")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-zero",
        )

        logSection("At E+1 the exact zero summary exists but Refund is too early")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 1) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-unclaimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-zero",
            "--require-zero",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-zero",
            "--expect", "failure",
            "--reason", "too_early",
        )

        logSection("At E+2 the same exact zero summary remains unclaimed")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 2) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-unclaimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-zero",
            "--require-zero",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-zero",
            "--expect", "success",
            "--reason", "claim_expiry",
        )
    }

    @Test
    fun `marketplace positive unclaimed summary refunds only at claim expiry`() {
        val config = fastMarketplaceConfig()
        val (cluster, genesis) = initCluster(config = config, reboot = true)
        cluster.allPairs.forEach { it.waitForMlNodesToLoad() }

        // join1 is a real active participant with PoC weight. Its DAPI stays up
        // through target-E PoC validation so native settlement can assign a
        // positive reward, then stops before the following CLAIM_REWARDS stage.
        val unclaimedHost = cluster.joinPairs.first()
        val targetEpoch = genesis.getEpochData().latestEpoch.index + 3

        logSection("Deploy Marketplace and fund an isolated claim-expiry Deal")
        runHarness(
            "bootstrap",
            "--context", requiredEnv("A8_CONTEXT"),
            "--run-id", requiredEnv("A8_RUN_ID"),
            "--target-epoch", targetEpoch.toString(),
            "--deal-wasm", requiredEnv("A8_DEAL_WASM"),
            "--factory-wasm", requiredEnv("A8_FACTORY_WASM"),
            "--cw20-wasm", requiredEnv("A8_CW20_WASM"),
            "--caller-wasm", requiredEnv("A8_CALLER_WASM"),
            "--host-node", "genesis-node",
            "--host-key", "genesis",
        )
        prepareDeal(
            "claim-expiry-positive",
            targetEpoch,
            funded = true,
            hostNode = "join1-node",
            hostKey = "join1",
        )

        genesis.markNeedsReboot()
        logSection("Reach E-1 and keep Host active through target-E settlement")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch - 1) {
            genesis.waitForNextEpoch()
        }
        genesis.waitForStage(EpochStage.END_OF_POC_VALIDATION, offset = 0)
        unclaimedHost.stopApiContainer()

        logSection("Enter E=$targetEpoch and lock the exact native recipient")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-positive",
        )

        logSection("At E+1 the settled summary must be positive and Refund must fail closed")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 1) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-unclaimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-positive",
            "--require-positive",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-positive",
            "--expect", "failure",
            "--reason", "too_early",
        )

        logSection("At E+2 the same positive summary must remain unclaimed")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 2) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-unclaimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-positive",
            "--require-positive",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry-positive",
            "--expect", "success",
            "--reason", "claim_expiry",
        )
    }

    @Test
    fun `marketplace absent native summary refunds only at emergency deadline`() {
        val config = fastMarketplaceConfig()
        val (cluster, genesis) = initCluster(config = config, reboot = true)
        cluster.allPairs.forEach { it.waitForMlNodesToLoad() }

        // Configure a real recipient while join2 is registered, then keep its DAPI
        // offline for five complete epochs before E. Native settlement only writes
        // summaries for the active participant snapshot, so exact Host/E is absent.
        val absentSummaryHost = cluster.joinPairs[1]
        val targetEpoch = genesis.getEpochData().latestEpoch.index + 5

        logSection("Deploy and fund an isolated emergency-refund Deal")
        runHarness(
            "bootstrap",
            "--context", requiredEnv("A8_CONTEXT"),
            "--run-id", requiredEnv("A8_RUN_ID"),
            "--target-epoch", targetEpoch.toString(),
            "--deal-wasm", requiredEnv("A8_DEAL_WASM"),
            "--factory-wasm", requiredEnv("A8_FACTORY_WASM"),
            "--cw20-wasm", requiredEnv("A8_CW20_WASM"),
            "--caller-wasm", requiredEnv("A8_CALLER_WASM"),
            "--host-node", "genesis-node",
            "--host-key", "genesis",
        )
        prepareDeal(
            "network-unconfirmed",
            targetEpoch,
            funded = true,
            hostNode = "join2-node",
            hostKey = "join2",
        )

        genesis.markNeedsReboot()
        absentSummaryHost.stopApiContainer()
        logSection("Keep Host offline through E=$targetEpoch and lock its exact recipient")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
        )

        logSection("At E+2 exact native NotFound still fails closed")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 2) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-missing-summary-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expected-offset", "2",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expect", "failure",
            "--reason", "network_unconfirmed_too_early",
        )

        logSection("At E+3 the same native NotFound permits NetworkUnconfirmed")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 3) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "verify-missing-summary-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expected-offset", "3",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--expect", "success",
            "--reason", "network_unconfirmed",
        )
    }

    @Test
    fun `marketplace funded claim settles and releases on real Gonka`() {
        val config = fastMarketplaceConfig()
        val (cluster, genesis) = initCluster(config = config, reboot = true)
        cluster.allPairs.forEach { it.waitForMlNodesToLoad() }

        val participant = cluster.joinPairs.first()
        val absentSummaryParticipant = cluster.joinPairs[1]
        val lockEPlus4Participant = createInactiveParticipant(genesis, "a8-lock-e-plus-4")
        val lockEPlus5Participant = createInactiveParticipant(genesis, "a8-lock-e-plus-5")
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

        val routingMissingEpoch = targetEpoch + 1
        val gasEpoch = targetEpoch + 2
        val noSaleEpoch = gasEpoch
        // Claim-expiry is prepared at E so its E+1/E+2 boundaries can be
        // exercised before the long settlement/vesting sequence advances time.
        val expiryEpoch = targetEpoch
        val emergencyEpoch = targetEpoch + 4
        prepareDeal("no-sale", noSaleEpoch, funded = false)
        prepareDeal(
            "gas-claimed",
            gasEpoch,
            funded = true,
            hostNode = "genesis-node",
            hostKey = "genesis",
        )
        // Bootstrap already occupies the default join1/E pair. Use the live
        // genesis participant for the unclaimed claim-expiry fixture so the
        // authoritative native summary remains available after join2 is
        // stopped for the NetworkUnconfirmed scenario.
        prepareDeal(
            "claim-expiry",
            expiryEpoch,
            funded = true,
            hostNode = "genesis-node",
            hostKey = lockEPlus4Participant,
        )
        prepareDeal(
            "no-buyer-expired",
            emergencyEpoch,
            funded = false,
        )
        prepareDeal(
            "network-unconfirmed",
            emergencyEpoch,
            funded = true,
            hostNode = "join2-node",
            hostKey = "join2",
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
            routingMissingEpoch,
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
            hostKey = lockEPlus4Participant,
        )
        prepareDeal(
            "lock-e-plus-5",
            gasEpoch,
            funded = false,
            hostNode = "genesis-node",
            hostKey = lockEPlus5Participant,
        )
        // The Host must exist when the native routing row is configured. Stop
        // its off-chain API afterwards so the future epoch has no reward
        // summary and exercises the real NetworkUnconfirmed path.
        genesis.markNeedsReboot()
        logSection("Wait for target epoch $targetEpoch and lock the exact routing proof")
        while (genesis.getEpochData().latestEpoch.index < targetEpoch) {
            genesis.waitForNextEpoch()
        }
        runHarness("lock", "--context", requiredEnv("A8_CONTEXT"))
        runHarness(
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
        )
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-mismatch",
            "--expect", "success",
            "--reason", "routing_mismatch",
            "--fault-cw20",
        )

        runHarness(
            "verify-unclaimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
        )
        val rewardSeed = participant.api.getConfig().currentSeed
        check(rewardSeed.epochIndex == targetEpoch) {
            "Testermint reward seed epoch ${rewardSeed.epochIndex} != Deal epoch $targetEpoch"
        }
        participant.stopApiContainer()
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
            "--expect", "failure",
            "--reason", "too_early",
        )
        while (genesis.getEpochData().latestEpoch.index < targetEpoch + 2) {
            genesis.waitForNextEpoch()
        }
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "claim-expiry",
            "--expect", "success",
            "--reason", "claim_expiry",
        )
        // Keep the inactive host's native summary unclaimed through E/E+2;
        // stop join2 only before the later NetworkUnconfirmed scenario.
        absentSummaryParticipant.stopApiContainer()
        logSection("Auto-claim stopped; wait for native claim window")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)

        runHarness(
            "claim-settle",
            "--context", requiredEnv("A8_CONTEXT"),
            "--reward-seed", rewardSeed.seed.toString(),
            "--reward-epoch", rewardSeed.epochIndex.toString(),
            "--fault-retry",
        )

        participant.restartApiContainer()
        genesis.node.waitForNextBlock(2)
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = -1)
        runHarness(
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "routing-missing",
            "--expect", "success",
            "--reason", "routing_missing",
        )

        logSection("Advance to $gasEpoch: first funded unlock and no-sale native claim")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
        genesis.node.waitForNextBlock(2)
        runHarness(
            "verify-claimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--require-positive",
        )
        while (genesis.getEpochData().latestEpoch.index < gasEpoch) {
            genesis.waitForNextEpoch()
        }
        genesis.node.waitForNextBlock(2)
        runHarness("lock-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness(
            "verify-claimed-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "gas-claimed",
            "--require-positive",
        )
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
        runHarness("settle-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        val vestingDonation = 10_000_000_001L
        val governanceAddress = genesis.node.getModuleAccount("gov").account.value.address
        val genesisAddress = genesis.node.getColdAddress()
        genesis.ensureGenesisSpendableForDevshard(vestingDonation)
        val vestingFundingTx = genesis.submitTransaction(
            listOf(
                "bank", "send", genesisAddress, governanceAddress,
                "$vestingDonation${genesis.config.denom}",
            )
        )
        check(vestingFundingTx.code == 0) { "governance funding failed: ${vestingFundingTx.rawLog}" }
        runHarness(
            "snapshot-vesting-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--label", "before-additional-vesting",
            "--require-non-empty",
        )
        val vestingProposalId = genesis.runProposal(
            cluster,
            MsgTransferWithVesting(
                sender = governanceAddress,
                recipient = scenarioDeal("no-sale"),
                amount = listOf(Coin(genesis.config.denom, vestingDonation)),
                vestingEpochs = 2,
            ),
        )
        runHarness(
            "verify-vesting-addition-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--before-label", "before-additional-vesting",
            "--amount", vestingDonation.toString(),
            "--vesting-epochs", "2",
            "--fund-tx-hash", vestingFundingTx.txhash,
            "--proposal-id", vestingProposalId,
        )
        runHarness(
            "donate-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-sale",
            "--label", "after_settlement",
            "--amount", "2",
        )
        logSection("Complete funded Deal and claim gas-regression Deal")
        genesis.waitForStage(EpochStage.CLAIM_REWARDS, offset = 2)
        genesis.node.waitForNextBlock(2)
        runHarness("release-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")
        runHarness("late-donation", "--context", requiredEnv("A8_CONTEXT"))
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
            "lock-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-buyer-expired",
        )
        logSection("Continue at absolute E+3 gas boundary")
        runHarness(
            "gas-sweep-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "gas-claimed",
        )
        runHarness("release-scenario", "--context", requiredEnv("A8_CONTEXT"), "--name", "no-sale")

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
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-buyer-expired",
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
            "refund-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "no-buyer-expired",
            "--expect", "success",
            "--reason", "claim_expiry",
        )
        runHarness(
            "donate-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--label", "after_terminal_emergency_refund",
            "--amount", "11",
        )
        val restrictionStatus = genesis.node.queryRestrictionsStatus()
        val restrictionEndBlock = restrictionStatus.currentBlockHeight + 50
        val restrictionProposalId = genesis.runProposal(
            cluster,
            UpdateRestrictionsParams(
                params = RestrictionsParams(
                    restrictionEndBlock = restrictionEndBlock,
                    emergencyTransferExemptions = emptyList(),
                    exemptionUsageTracking = emptyList(),
                ),
            ),
        )
        check(genesis.node.queryRestrictionsStatus().isActive) {
            "transfer restrictions did not become active after proposal $restrictionProposalId"
        }
        runHarness(
            "bank-release-rollback-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
            "--proposal-id", restrictionProposalId,
        )
        genesis.node.waitForMinimumBlock(restrictionEndBlock + 1, "A8 restriction expiry")
        check(!genesis.node.queryRestrictionsStatus().isActive) {
            "transfer restrictions remained active after block $restrictionEndBlock"
        }
        runHarness(
            "release-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "network-unconfirmed",
        )
        runHarness(
            "lock-rejected-scenario",
            "--context", requiredEnv("A8_CONTEXT"),
            "--name", "lock-e-plus-5",
            "--routing", "pruned",
        )
        runHarness("verify-factory-isolation", "--context", requiredEnv("A8_CONTEXT"))
    }

    private fun fastMarketplaceConfig(
        initialEpochReward: Long? = null,
    ): com.productscience.ApplicationConfig {
        val fastSpec = spec {
            this[AppState::inference] = spec<InferenceState> {
                this[InferenceState::params] = spec<InferenceParams> {
                    if (initialEpochReward != null) {
                        this[InferenceParams::bitcoinRewardParams] = spec<BitcoinRewardParams> {
                            this[BitcoinRewardParams::initialEpochReward] = initialEpochReward
                        }
                    }
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
        return config
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

    private fun scenarioDeal(name: String): String {
        val root = JsonParser.parseString(File(requiredEnv("A8_CONTEXT")).readText()).asJsonObject
        return root.getAsJsonObject("scenarios")
            .getAsJsonObject(name)
            .getAsJsonObject("contracts")
            .get("deal")
            .asString
    }

    private fun createInactiveParticipant(genesis: com.productscience.LocalInferencePair, prefix: String): String {
        val key = genesis.node.createKey("$prefix-${System.currentTimeMillis()}")
        genesis.api.addUnfundedInferenceParticipant(
            UnfundedInferenceParticipant(
                url = "",
                models = listOf(),
                validatorKey = "",
                pubKey = key.pubkey.key,
                address = key.address,
            ),
        )
        genesis.node.waitForNextBlock(2)
        return key.name
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: error("Required environment variable $name is missing")
}
