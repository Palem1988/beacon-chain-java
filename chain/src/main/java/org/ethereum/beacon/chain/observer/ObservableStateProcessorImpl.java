package org.ethereum.beacon.chain.observer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.ethereum.beacon.chain.BeaconChainHead;
import org.ethereum.beacon.chain.BeaconTupleDetails;
import org.ethereum.beacon.chain.LMDGhostHeadFunction;
import org.ethereum.beacon.chain.storage.BeaconChainStorage;
import org.ethereum.beacon.chain.BeaconTuple;
import org.ethereum.beacon.chain.storage.BeaconTupleStorage;
import org.ethereum.beacon.consensus.BeaconStateEx;
import org.ethereum.beacon.consensus.HeadFunction;
import org.ethereum.beacon.consensus.SpecHelpers;
import org.ethereum.beacon.consensus.StateTransition;
import org.ethereum.beacon.core.BeaconBlock;
import org.ethereum.beacon.core.BeaconState;
import org.ethereum.beacon.core.operations.Attestation;
import org.ethereum.beacon.core.state.PendingAttestationRecord;
import org.ethereum.beacon.core.types.BLSPubkey;
import org.ethereum.beacon.core.types.SlotNumber;
import org.ethereum.beacon.core.types.ValidatorIndex;
import org.ethereum.beacon.schedulers.Scheduler;
import org.ethereum.beacon.schedulers.Schedulers;
import org.javatuples.Pair;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.ReplayProcessor;
import tech.pegasys.artemis.util.collections.ReadList;

public class ObservableStateProcessorImpl implements ObservableStateProcessor {
  private static final int MAX_TUPLE_CACHE_SIZE = 256;
  private final BeaconTupleStorage tupleStorage;

  private final HeadFunction headFunction;
  private final SpecHelpers spec;
  private final StateTransition<BeaconStateEx> perSlotTransition;
  private final StateTransition<BeaconStateEx> perEpochTransition;

  private final Publisher<SlotNumber> slotTicker;
  private final Publisher<Attestation> attestationPublisher;
  private final Publisher<BeaconTupleDetails> beaconPublisher;

  private static final int UPDATE_MILLIS = 500;
  private Scheduler regularJobExecutor;
  private Scheduler continuousJobExecutor;
  private Map<BeaconBlock, BeaconTupleDetails> tupleDetails = Collections.synchronizedMap(
      new LinkedHashMap<BeaconBlock, BeaconTupleDetails>() {
        @Override
        protected boolean removeEldestEntry(Entry eldest) {
          return size() > MAX_TUPLE_CACHE_SIZE;
        }
      });

  private final List<Attestation> attestationBuffer = new ArrayList<>();
  private final Map<Pair<BLSPubkey, SlotNumber>, Attestation> attestationCache = new HashMap<>();
  private final Schedulers schedulers;

  private final ReplayProcessor<BeaconChainHead> headSink = ReplayProcessor.cacheLast();
  private final Publisher<BeaconChainHead> headStream;

  private final ReplayProcessor<ObservableBeaconState> observableStateSink =
      ReplayProcessor.cacheLast();
  private final Publisher<ObservableBeaconState> observableStateStream;

  private final ReplayProcessor<PendingOperations> pendingOperationsSink =
      ReplayProcessor.cacheLast();
  private final Publisher<PendingOperations> pendingOperationsStream;

  public ObservableStateProcessorImpl(
      BeaconChainStorage chainStorage,
      Publisher<SlotNumber> slotTicker,
      Publisher<Attestation> attestationPublisher,
      Publisher<BeaconTupleDetails> beaconPublisher,
      SpecHelpers spec,
      StateTransition<BeaconStateEx> perSlotTransition,
      StateTransition<BeaconStateEx> perEpochTransition,
      Schedulers schedulers) {
    this.tupleStorage = chainStorage.getTupleStorage();
    this.spec = spec;
    this.perSlotTransition = perSlotTransition;
    this.perEpochTransition = perEpochTransition;
    this.headFunction = new LMDGhostHeadFunction(chainStorage, spec);
    this.slotTicker = slotTicker;
    this.attestationPublisher = attestationPublisher;
    this.beaconPublisher = beaconPublisher;
    this.schedulers = schedulers;

    headStream = Flux.from(headSink)
        .publishOn(this.schedulers.reactorEvents())
        .onBackpressureError()
        .name("ObservableStateProcessor.head");
    observableStateStream = Flux.from(observableStateSink)
        .publishOn(this.schedulers.reactorEvents())
        .onBackpressureError()
        .name("ObservableStateProcessor.observableState");
    pendingOperationsStream = Flux.from(pendingOperationsSink)
            .publishOn(this.schedulers.reactorEvents())
            .onBackpressureError()
            .name("PendingOperationsProcessor.pendingOperations");
  }

  @Override
  public void start() {
    regularJobExecutor =
        schedulers.newSingleThreadDaemon("observable-state-processor-regular");
    continuousJobExecutor =
        schedulers.newSingleThreadDaemon("observable-state-processor-continuous");
    Flux.from(slotTicker).subscribe(this::onNewSlot);
    Flux.from(attestationPublisher).subscribe(this::onNewAttestation);
    Flux.from(beaconPublisher).subscribe(this::onNewBlockTuple);
    regularJobExecutor.executeAtFixedRate(
        Duration.ZERO, Duration.ofMillis(UPDATE_MILLIS), this::doHardWork);
  }

  private void runTaskInSeparateThread(Runnable task) {
    continuousJobExecutor.execute(task::run);
  }

  private void onNewSlot(SlotNumber newSlot) {
    // From spec: Verify that attestation.data.slot <= state.slot - MIN_ATTESTATION_INCLUSION_DELAY
    // < attestation.data.slot + SLOTS_PER_EPOCH
    // state.slot - MIN_ATTESTATION_INCLUSION_DELAY < attestation.data.slot + SLOTS_PER_EPOCH
    // state.slot - MIN_ATTESTATION_INCLUSION_DELAY - SLOTS_PER_EPOCH < attestation.data.slot
    SlotNumber slotMinimum =
        newSlot
            .minus(spec.getConstants().getSlotsPerEpoch())
            .minus(spec.getConstants().getMinAttestationInclusionDelay());
    runTaskInSeparateThread(
        () -> {
          purgeAttestations(slotMinimum);
          newSlot(newSlot);
        });
  }

  private void doHardWork() {
    if (latestState == null) {
      return;
    }
    List<Attestation> attestations = drainAttestations(latestState.getSlot());
    for (Attestation attestation : attestations) {

      List<ValidatorIndex> participants =
          spec.get_attestation_participants(
              latestState, attestation.getData(), attestation.getAggregationBitfield());

      List<BLSPubkey> pubKeys = spec.mapIndicesToPubKeys(latestState, participants);

      for (BLSPubkey pubKey : pubKeys) {
        addValidatorAttestation(pubKey, attestation);
      }
    }
  }

  private synchronized void addValidatorAttestation(BLSPubkey pubKey, Attestation attestation) {
    attestationCache.put(Pair.with(pubKey, attestation.getData().getSlot()), attestation);
  }

  private synchronized void onNewAttestation(Attestation attestation) {
    attestationBuffer.add(attestation);
  }

  private synchronized List<Attestation> drainAttestations(SlotNumber uptoSlotInclusive) {
    List<Attestation> ret = new ArrayList<>();
    Iterator<Attestation> it = attestationBuffer.iterator();
    while (it.hasNext()) {
      Attestation att = it.next();
      if (att.getData().getSlot().lessEqual(uptoSlotInclusive)) {
        ret.add(att);
        it.remove();
      }
    }
    return ret;
  }


  private void onNewBlockTuple(BeaconTupleDetails beaconTuple) {
    tupleDetails.put(beaconTuple.getBlock(), beaconTuple);
    runTaskInSeparateThread(
        () -> {
          addAttestationsFromState(beaconTuple.getState());
          updateHead();
        });
  }

  private void addAttestationsFromState(BeaconState beaconState) {
    ReadList<Integer, PendingAttestationRecord> pendingAttestationRecords =
        beaconState.getLatestAttestations();
    for (PendingAttestationRecord pendingAttestationRecord : pendingAttestationRecords) {
      List<ValidatorIndex> participants =
          spec.get_attestation_participants(
              beaconState,
              pendingAttestationRecord.getData(),
              pendingAttestationRecord.getAggregationBitfield());
      List<BLSPubkey> pubKeys = spec.mapIndicesToPubKeys(beaconState, participants);
      SlotNumber slot = pendingAttestationRecord.getData().getSlot();
      pubKeys.forEach(
          pubKey -> {
            removeValidatorAttestation(pubKey, slot);
          });
    }
  }

  private synchronized void removeValidatorAttestation(BLSPubkey pubkey, SlotNumber slot) {
    attestationCache.remove(Pair.with(pubkey, slot));
  }

  /** Purges all entries for slot and before */
  private synchronized void purgeAttestations(SlotNumber slot) {
    attestationCache.entrySet()
        .removeIf(entry -> entry.getValue().getData().getSlot().lessEqual(slot));
  }

  private synchronized Map<BLSPubkey, List<Attestation>> copyAttestationCache() {
    return attestationCache.entrySet().stream()
        .collect(
            Collectors.groupingBy(
                e -> e.getKey().getValue0(),
                Collectors.mapping(Entry::getValue, Collectors.toList())));
  }

  private BeaconTupleDetails head;
  private BeaconStateEx latestState;

  private void newHead(BeaconTupleDetails head) {
    this.head = head;
    headSink.onNext(new BeaconChainHead(this.head));

    if (latestState == null || head.getBlock().getSlot().greater(latestState.getSlot())) {
      return;
    } else {
      updateCurrentObservableState(head, latestState.getSlot());
    }
  }

  private void newSlot(SlotNumber newSlot) {
    if (head.getBlock().getSlot().greater(newSlot)) {
      return;
    }
    updateCurrentObservableState(head, newSlot);
  }

  private void updateCurrentObservableState(BeaconTupleDetails head, SlotNumber slot) {
    assert slot.greaterEqual(head.getBlock().getSlot());

    PendingOperations pendingOperations = new PendingOperationsState(copyAttestationCache());
    if (slot.greater(head.getBlock().getSlot())) {
      BeaconStateEx stateWithoutEpoch = applySlotTransitionsWithoutEpoch(head.getFinalState(), slot);
      latestState = stateWithoutEpoch;
      observableStateSink.onNext(
          new ObservableBeaconState(head.getBlock(), stateWithoutEpoch, pendingOperations));
      BeaconStateEx epochState = applyEpochTransitionIfNeeded(head.getFinalState(), stateWithoutEpoch);
      if (epochState != null) {
        latestState = epochState;
        observableStateSink.onNext(new ObservableBeaconState(
            head.getBlock(), epochState, pendingOperations));
      }
    } else {
      if (head.getPostSlotState().isPresent()) {
        latestState = head.getPostSlotState().get();
        observableStateSink.onNext(new ObservableBeaconState(
            head.getBlock(), head.getPostSlotState().get(), pendingOperations));
      }
      if (head.getPostBlockState().isPresent()) {
        latestState = head.getPostBlockState().get();
        observableStateSink.onNext(new ObservableBeaconState(
            head.getBlock(), head.getPostBlockState().get(), pendingOperations));
        if (head.getPostEpochState().isPresent()) {
          latestState = head.getPostEpochState().get();
          observableStateSink.onNext(new ObservableBeaconState(
              head.getBlock(), head.getPostEpochState().get(), pendingOperations));
        }
      } else {
        latestState = head.getFinalState();
        observableStateSink.onNext(new ObservableBeaconState(
            head.getBlock(), head.getFinalState(), pendingOperations));
      }
    }
  }

  private BeaconStateEx applyEpochTransitionIfNeeded(
      BeaconStateEx originalState, BeaconStateEx stateWithoutEpoch) {

    if (spec.is_epoch_end(stateWithoutEpoch.getSlot())
        && originalState.getSlot().less(stateWithoutEpoch.getSlot())) {
      return perEpochTransition.apply(stateWithoutEpoch);
    } else {
      return null;
    }
  }

  /**
   * Applies next slot transitions until the <code>targetSlot</code> but
   * doesn't apply EpochTransition for the <code>targetSlot</code>
   *
   * @param source Source state
   * @return new state, result of applied transition to the latest input state
   */
  private BeaconStateEx applySlotTransitionsWithoutEpoch(
      BeaconStateEx source, SlotNumber targetSlot) {

    BeaconStateEx state = source;
    for (SlotNumber slot : source.getSlot().increment().iterateTo(targetSlot.increment())) {
      state = perSlotTransition.apply(state);
      if (spec.is_epoch_end(slot) && !slot.equals(targetSlot)) {
        state = perEpochTransition.apply(state);
      }
    }
    return state;
  }

  private void updateHead() {
    PendingOperations pendingOperations = new PendingOperationsState(copyAttestationCache());
    BeaconBlock newHead =
        headFunction.getHead(
            validatorRecord -> pendingOperations.getLatestAttestation(validatorRecord.getPubKey()));
    if (this.head != null && this.head.getBlock().equals(newHead)) {
      return; // == old
    }
    BeaconTupleDetails tuple = tupleDetails.get(newHead);
    if (tuple == null) {
      BeaconTuple newHeadTuple =
          tupleStorage
              .get(spec.hash_tree_root(newHead))
              .orElseThrow(() -> new IllegalStateException("Beacon tuple not found for new head "));
      tuple = new BeaconTupleDetails(newHeadTuple);
    }
    newHead(tuple);
  }

  @Override
  public Publisher<BeaconChainHead> getHeadStream() {
    return headStream;
  }

  @Override
  public Publisher<ObservableBeaconState> getObservableStateStream() {
    return observableStateStream;
  }

  @Override
  public Publisher<PendingOperations> getPendingOperationsStream() {
    return pendingOperationsStream;
  }
}
