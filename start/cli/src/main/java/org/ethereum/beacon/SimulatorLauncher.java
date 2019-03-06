package org.ethereum.beacon;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.ethereum.beacon.chain.observer.ObservableBeaconState;
import org.ethereum.beacon.chain.storage.impl.MemBeaconChainStorageFactory;
import org.ethereum.beacon.consensus.SpecHelpers;
import org.ethereum.beacon.consensus.TransitionType;
import org.ethereum.beacon.consensus.transition.EpochTransitionSummary;
import org.ethereum.beacon.core.BeaconBlock;
import org.ethereum.beacon.core.operations.Attestation;
import org.ethereum.beacon.core.operations.Deposit;
import org.ethereum.beacon.core.spec.SpecConstants;
import org.ethereum.beacon.core.state.Eth1Data;
import org.ethereum.beacon.core.types.SlotNumber;
import org.ethereum.beacon.core.types.Time;
import org.ethereum.beacon.core.types.ValidatorIndex;
import org.ethereum.beacon.crypto.BLS381;
import org.ethereum.beacon.emulator.config.main.MainConfig;
import org.ethereum.beacon.emulator.config.main.action.Action;
import org.ethereum.beacon.emulator.config.main.action.ActionSimulate;
import org.ethereum.beacon.pow.DepositContract;
import org.ethereum.beacon.schedulers.ControlledSchedulers;
import org.ethereum.beacon.schedulers.LoggerMDCExecutor;
import org.ethereum.beacon.schedulers.Schedulers;
import org.ethereum.beacon.util.SimulateUtils;
import org.ethereum.beacon.wire.LocalWireHub;
import org.ethereum.beacon.wire.WireApi;
import org.javatuples.Pair;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.pegasys.artemis.ethereum.core.Hash32;
import tech.pegasys.artemis.util.bytes.Bytes32;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

public class SimulatorLauncher implements Runnable {
  private static final Logger logger = LogManager.getLogger("simulator");
  private static final Logger logPeer = LogManager.getLogger("peer");

  private final ActionSimulate simulateConfig;
  private final MainConfig mainConfig;
  private final SpecConstants specConstants;
  private final SpecHelpers spec;
  private final Level logLevel;
  private final Consumer<MainConfig> onUpdateConfig;

  /**
   * Creates Simulator launcher with following settings
   *
   * @param mainConfig Configuration and run plan
   * @param specConstants Chain specification
   * @param logLevel Log level, Apache log4j type
   * @param onUpdateConfig Callback to run when mainConfig is updated
   */
  public SimulatorLauncher(
      MainConfig mainConfig,
      SpecConstants specConstants,
      Level logLevel,
      Consumer<MainConfig> onUpdateConfig) {
    this.mainConfig = mainConfig;
    this.specConstants = specConstants;
    this.spec = SpecHelpers.createWithSSZHasher(specConstants);
    List<Action> actions = mainConfig.getPlan().getValidator();
    Optional<ActionSimulate> actionSimulate =
        actions.stream()
            .filter(a -> a instanceof ActionSimulate)
            .map(a -> (ActionSimulate) a)
            .findFirst();
    if (!actionSimulate.isPresent()) {
      throw new RuntimeException("Simulate settings are not set");
    }
    this.simulateConfig = actionSimulate.get();
    if (simulateConfig.getCount() == null && simulateConfig.getPrivateKeys() == null) {
      throw new RuntimeException("Set either number of validators or private keys.");
    }
    this.logLevel = logLevel;
    this.onUpdateConfig = onUpdateConfig;
  }

  private void setupLogging() {
    try (InputStream inputStream = ClassLoader.class.getResourceAsStream("/log4j2.xml")) {
      ConfigurationSource source = new ConfigurationSource(inputStream);
      Configurator.initialize(null, source);
    } catch (Exception e) {
      throw new RuntimeException("Cannot read log4j default configuration", e);
    }

    // set logLevel
    if (logLevel != null) {
      LoggerContext context =
          (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
      Configuration config = context.getConfiguration();
      LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
      loggerConfig.setLevel(logLevel);
      context.updateLoggers();
    }
  }

  private void onUpdateConfig() {
    this.onUpdateConfig.accept(mainConfig);
  }

  private Pair<List<Deposit>, List<BLS381.KeyPair>> getValidatorDeposits() {
    if (simulateConfig.getPrivateKeys() != null && !simulateConfig.getPrivateKeys().isEmpty()) {
      List<BLS381.KeyPair> keyPairs = new ArrayList<>();
      for (String pKey : simulateConfig.getPrivateKeys()) {
        keyPairs.add(BLS381.KeyPair.create(BLS381.PrivateKey.create(Bytes32.fromHexString(pKey))));
      }
      return Pair.with(SimulateUtils.getDepositsForKeyPairs(keyPairs, spec), keyPairs);
    } else {
      Pair<List<Deposit>, List<BLS381.KeyPair>> anyDeposits =
          SimulateUtils.getAnyDeposits(spec, simulateConfig.getCount());
      List<String> pKeysEncoded = new ArrayList<>();
      anyDeposits
          .getValue1()
          .forEach(
              pk -> {
                pKeysEncoded.add(pk.getPrivate().getEncodedBytes().toString());
              });
      simulateConfig.setPrivateKeys(pKeysEncoded);
      onUpdateConfig();
      return anyDeposits;
    }
  }

  public void run() {
    setupLogging();
    Pair<List<Deposit>, List<BLS381.KeyPair>> validatorDeposits = getValidatorDeposits();
    List<Deposit> deposits = validatorDeposits.getValue0();
    List<BLS381.KeyPair> keyPairs = validatorDeposits.getValue1();

    Random rnd = new Random(1);
    Time genesisTime = Time.of(10 * 60);

    MDCControlledSchedulers controlledSchedulers = new MDCControlledSchedulers();
    controlledSchedulers.setCurrentTime(genesisTime.getMillis().getValue() + 1000);

    Eth1Data eth1Data = new Eth1Data(Hash32.random(rnd), Hash32.random(rnd));

    LocalWireHub localWireHub = new LocalWireHub(s -> {});
    DepositContract.ChainStart chainStart =
        new DepositContract.ChainStart(genesisTime, eth1Data, deposits);
    DepositContract depositContract = new SimpleDepositContract(chainStart);

    List<Launcher> peers = new ArrayList<>();

    logger.info("Creating validators...");
    for (int i = 0; i < keyPairs.size(); i++) {
      ControlledSchedulers schedulers = controlledSchedulers.createNew("" + i);
      SpecHelpers specHelpers =
          SpecHelpers.createWithSSZHasher(specConstants);
      WireApi wireApi = localWireHub.createNewPeer("" + i);

      Launcher launcher =
          new Launcher(
              specHelpers,
              depositContract,
              keyPairs.get(i),
              wireApi,
              new MemBeaconChainStorageFactory(),
              schedulers);

      peers.add(launcher);
    }
    logger.info("Validators created");

    Map<Integer, ObservableBeaconState> latestStates = new HashMap<>();
    for (int i = 0; i < peers.size(); i++) {
      Launcher launcher = peers.get(i);

      int finalI = i;
      Flux.from(launcher.slotTicker.getTickerStream()).subscribe(slot ->
          logPeer.debug("New slot: " + slot.toString(specConstants, genesisTime)));
      Flux.from(launcher.observableStateProcessor.getObservableStateStream())
          .subscribe(os -> {
            latestStates.put(finalI, os);
            logPeer.debug("New observable state: " + os.toString(specHelpers));
          });
      Flux.from(launcher.beaconChainValidator.getProposedBlocksStream())
          .subscribe(block -> logPeer.info("New block created: "
              + block.toString(specConstants, genesisTime, specHelpers::hash_tree_root)));
      Flux.from(launcher.beaconChainValidator.getAttestationsStream())
          .subscribe(attest -> logPeer.info("New attestation created: "
              + attest.toString(specConstants, genesisTime)));
      Flux.from(launcher.beaconChain.getBlockStatesStream())
          .subscribe(blockState -> logPeer.debug("Block imported: "
              + blockState.getBlock().toString(specConstants, genesisTime, specHelpers::hash_tree_root)));
    }

    logger.info("Creating observer peer...");
    ControlledSchedulers schedulers = controlledSchedulers.createNew("X");
    WireApi wireApi = localWireHub.createNewPeer("X");

    Launcher observer =
        new Launcher(
            specHelpers,
            depositContract,
            null,
            wireApi,
            new MemBeaconChainStorageFactory(),
            schedulers);

    peers.add(observer);

    List<SlotNumber> slots = new ArrayList<>();
    List<Attestation> attestations = new ArrayList<>();
    List<BeaconBlock> blocks = new ArrayList<>();
    List<ObservableBeaconState> states = new ArrayList<>();

    Flux.from(observer.slotTicker.getTickerStream()).subscribe(slot -> {
      slots.add(slot);
      logger.debug("New slot: " + slot.toString(chainSpec, genesisTime));
    });
    Flux.from(observer.observableStateProcessor.getObservableStateStream())
        .subscribe(os -> {
          states.add(os);
          logger.debug("New observable state: " + os.toString(specHelpers));
        });
    Flux.from(observer.wireApi.inboundAttestationsStream())
        .subscribe(att -> {
          attestations.add(att);
          logger.debug("New attestation received: " + att.toStringShort(chainSpec));
        });
    Flux.from(observer.beaconChain.getBlockStatesStream())
        .subscribe(blockState -> {
          blocks.add(blockState.getBlock());
          logger.debug("Block imported: "
              + blockState.getBlock().toString(chainSpec, genesisTime, specHelpers::hash_tree_root));
        });

    logger.info("Time starts running ...");
    while (true) {
      controlledSchedulers.addTime(Duration.ofMillis(chainSpec.getSlotDuration().getValue() * 1000 - 1));

      if (slots.size() > 1) {
        logger.warn("More than 1 slot generated: " + slots);
      }
      if (slots.isEmpty()) {
        logger.error("No slots generated");
      }

      Map<Hash32, List<ObservableBeaconState>> grouping = Stream
          .concat(latestStates.values().stream(), states.stream())
          .collect(Collectors.groupingBy(s -> specHelpers.hash_tree_root(s.getLatestSlotState())));

      String statesInfo;
      if (grouping.size() == 1) {
        statesInfo = "all peers on the state " + grouping.keySet().iterator().next().toStringShort();
      } else {
        statesInfo = "peers states differ:  " + grouping.entrySet().stream()
            .map(e -> e.getKey().toStringShort() + ": " + e.getValue().size() + " peers")
            .collect(Collectors.joining(", "));

      }

      logger.info("Slot " + slots.get(0).toStringNumber(chainSpec)
          + ", committee: " + specHelpers.get_crosslink_committees_at_slot(states.get(0).getLatestSlotState(), slots.get(0))
          + ", blocks: " + blocks.size()
          + ", attestations: " + attestations.size()
          + ", " + statesInfo);

      ObservableBeaconState lastState = states.get(states.size() - 1);
      if (lastState.getLatestSlotState().getTransition() == TransitionType.EPOCH) {
        ObservableBeaconState preEpochState = states.get(states.size() - 2);
        EpochTransitionSummary summary = observer.perEpochTransition
            .applyWithSummary(preEpochState.getLatestSlotState());
        logger.info("Epoch transition "
            + specHelpers.get_current_epoch(preEpochState.getLatestSlotState()).toString(chainSpec)
            + "=>"
            + specHelpers.get_current_epoch(preEpochState.getLatestSlotState()).increment().toString(chainSpec)
            + ": Justified/Finalized epochs: "
            + summary.getPreState().getJustifiedEpoch().toString(chainSpec)
            + "/"
            + summary.getPreState().getFinalizedEpoch().toString(chainSpec)
            + " => "
            + summary.getPostState().getJustifiedEpoch().toString(chainSpec)
            + "/"
            + summary.getPostState().getFinalizedEpoch().toString(chainSpec)
        );
        logger.info("  Validators rewarded:"
            + getValidators(" attestations: ", summary.getAttestationRewards())
            + getValidators(" boundary: ", summary.getBoundaryAttestationRewards())
            + getValidators(" head: ", summary.getBeaconHeadAttestationRewards())
            + getValidators(" head: ", summary.getBeaconHeadAttestationRewards())
            + getValidators(" include distance: ", summary.getInclusionDistanceRewards())
            + getValidators(" attest inclusion: ", summary.getAttestationInclusionRewards())
        );
        logger.info("  Validators penalized:"
            + getValidators(" attestations: ", summary.getAttestationPenalties())
            + getValidators(" boundary: ", summary.getBoundaryAttestationPenalties())
            + getValidators(" head: ", summary.getBeaconHeadAttestationPenalties())
            + getValidators(" penalized epoch: ", summary.getPenalizedEpochPenalties())
            + getValidators(" no finality: ", summary.getNoFinalityPenalties())
        );
      }

      controlledSchedulers.addTime(Duration.ofMillis(1));

      slots.clear();
      attestations.clear();
      blocks.clear();
      states.clear();
    }
  }

  private static String getValidators(String info, Map<ValidatorIndex, ?> records) {
    if (records.isEmpty()) return "";
    return info + " ["
        + records.entrySet().stream().map(e -> e.getKey().toString()).collect(Collectors.joining(","))
        + "]";
  }

  private static class SimpleDepositContract implements DepositContract {
    private final ChainStart chainStart;

    public SimpleDepositContract(ChainStart chainStart) {
      this.chainStart = chainStart;
    }

    @Override
    public Publisher<ChainStart> getChainStartMono() {
      return Mono.just(chainStart);
    }

    @Override
    public Publisher<Deposit> getDepositStream() {
      return Mono.empty();
    }

    @Override
    public List<DepositInfo> peekDeposits(
        int maxCount, Eth1Data fromDepositExclusive, Eth1Data tillDepositInclusive) {
      return Collections.emptyList();
    }

    @Override
    public boolean hasDepositRoot(Hash32 blockHash, Hash32 depositRoot) {
      return true;
    }

    @Override
    public Optional<Eth1Data> getLatestEth1Data() {
      return Optional.of(chainStart.getEth1Data());
    }

    @Override
    public void setDistanceFromHead(long distanceFromHead) {}
  }

  static class MDCControlledSchedulers {
    private DateFormat localTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    private List<ControlledSchedulers> schedulersList = new ArrayList<>();
    private long currentTime;

    public ControlledSchedulers createNew(String validatorId) {
      ControlledSchedulers[] newSched = new ControlledSchedulers[1];
      LoggerMDCExecutor mdcExecutor = new LoggerMDCExecutor()
          .add("validatorTime", () -> localTimeFormat.format(new Date(newSched[0].getCurrentTime())))
          .add("validatorIndex", () -> "" + validatorId);
      newSched[0] = Schedulers.createControlled(() -> mdcExecutor);
      newSched[0].setCurrentTime(currentTime);
      schedulersList.add(newSched[0]);

      return newSched[0];
    }

    public void setCurrentTime(long time) {
      currentTime = time;
      schedulersList.forEach(cs -> cs.setCurrentTime(time));
    }

    void addTime(Duration duration) {
      addTime(duration.toMillis());
    }

    void addTime(long millis) {
      setCurrentTime(currentTime + millis);
    }
  }
}
