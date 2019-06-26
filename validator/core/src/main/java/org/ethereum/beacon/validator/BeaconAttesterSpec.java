package org.ethereum.beacon.validator;

import com.google.common.annotations.VisibleForTesting;
import org.ethereum.beacon.chain.observer.ObservableBeaconState;
import org.ethereum.beacon.consensus.BeaconChainSpec;
import org.ethereum.beacon.core.BeaconBlock;
import org.ethereum.beacon.core.BeaconState;
import org.ethereum.beacon.core.operations.Attestation;
import org.ethereum.beacon.core.operations.attestation.AttestationData;
import org.ethereum.beacon.core.operations.attestation.AttestationDataAndCustodyBit;
import org.ethereum.beacon.core.operations.attestation.Crosslink;
import org.ethereum.beacon.core.types.BLSSignature;
import org.ethereum.beacon.core.types.Bitfield;
import org.ethereum.beacon.core.types.EpochNumber;
import org.ethereum.beacon.core.types.ShardNumber;
import org.ethereum.beacon.core.types.SlotNumber;
import org.ethereum.beacon.core.types.ValidatorIndex;
import org.ethereum.beacon.validator.crypto.MessageSigner;
import tech.pegasys.artemis.ethereum.core.Hash32;
import tech.pegasys.artemis.util.bytes.BytesValue;
import tech.pegasys.artemis.util.bytes.MutableBytesValue;
import tech.pegasys.artemis.util.uint.UInt64;
import tech.pegasys.artemis.util.uint.UInt64s;

import java.util.List;

import static org.ethereum.beacon.core.spec.SignatureDomains.ATTESTATION;

/** Beacon attester routines according to the spec */
public class BeaconAttesterSpec {
  private final BeaconChainSpec spec;

  public BeaconAttesterSpec(BeaconChainSpec spec) {
    this.spec = spec;
  }

  /*
   Let aggregation_bitfield be a byte array filled with zeros of length (len(committee) + 7) // 8.
   Let index_into_committee be the index into the validator's committee at which validator_index is
     located.
   Set aggregation_bitfield[index_into_committee // 8] |= 2 ** (index_into_committee % 8).
  */
  private static BytesValue getParticipationBitfield(
      ValidatorIndex index, List<ValidatorIndex> committee) {
    int indexIntoCommittee = committee.indexOf(index);
    assert indexIntoCommittee >= 0;

    int aggregationBitfieldSize = (committee.size() + 7) / 8;
    MutableBytesValue aggregationBitfield =
        MutableBytesValue.wrap(new byte[aggregationBitfieldSize]);
    int indexIntoBitfield = indexIntoCommittee / 8;
    aggregationBitfield.set(indexIntoBitfield, (byte) ((1 << (indexIntoCommittee % 8)) & 0xFF));
    return aggregationBitfield;
  }

  /**
   * Prepares attestation with signature stubbed with zero BLS Signature Later signer could easily
   * sign it
   *
   * @param validatorIndex index of the validator.
   * @param shard shard number.
   * @param observableState a state that attestation is based on.
   * @param slot attestation slot
   * @return unsigned attestation
   */
  public Attestation prepareAttestation(
      ValidatorIndex validatorIndex,
      ShardNumber shard,
      ObservableBeaconState observableState,
      SlotNumber slot) {
    BeaconState state = observableState.getLatestSlotState();

    Hash32 beaconBlockRoot = spec.signing_root(observableState.getHead());
    EpochNumber targetEpoch = spec.slot_to_epoch(slot);
    Hash32 targetRoot = getTargetRoot(state, observableState.getHead());
    EpochNumber sourceEpoch = state.getCurrentJustifiedEpoch();
    Hash32 sourceRoot = getSourceRoot(state, observableState.getHead());
    Crosslink crosslink = getCrosslink(state, shard, targetEpoch);
    AttestationData data =
        new AttestationData(
            beaconBlockRoot, sourceEpoch, sourceRoot, targetEpoch, targetRoot, crosslink);

    List<ValidatorIndex> committee = getCommittee(state, shard);
    BytesValue participationBitfield = getParticipationBitfield(validatorIndex, committee);
    BytesValue custodyBitfield = getCustodyBitfield(validatorIndex, committee);

    return new Attestation(
        Bitfield.of(participationBitfield), data, Bitfield.of(custodyBitfield), BLSSignature.ZERO);
  }

  /**
   * Returns a committee at a state slot for a given shard.
   *
   * @param state a state.
   * @param shard a shard.
   * @return a committee.
   */
  @VisibleForTesting
  List<ValidatorIndex> getCommittee(BeaconState state, ShardNumber shard) {
    EpochNumber epoch = spec.get_current_epoch(state);
    return spec.get_crosslink_committee(state, epoch, shard);
  }

  /*
   Note: This can be looked up in the state using
     get_block_root_at_slot(state, head.slot - head.slot % SLOTS_PER_EPOCH).
  */
  @VisibleForTesting
  Hash32 getTargetRoot(BeaconState state, BeaconBlock head) {
    SlotNumber epochBoundarySlot = spec.get_epoch_start_slot(spec.slot_to_epoch(head.getSlot()));
    if (epochBoundarySlot.equals(head.getSlot())) {
      return spec.signing_root(head);
    } else {
      return spec.get_block_root_at_slot(state, epochBoundarySlot);
    }
  }

  /*
   Set attestation_data.justified_block_root = hash_tree_root(justified_block)
     where justified_block is the block at state.justified_slot in the chain defined by head.

   Note: This can be looked up in the state using get_block_root_at_slot(state, justified_slot).
  */
  @VisibleForTesting
  Hash32 getSourceRoot(BeaconState state, BeaconBlock head) {
    return state.getCurrentJustifiedRoot();
  }

  private Crosslink getCrosslink(BeaconState state, ShardNumber shard, EpochNumber targetEpoch) {
    Hash32 dataRoot = Hash32.ZERO; // Note: This is a stub for phase 0.
    Crosslink parentCrosslink = state.getCurrentCrosslinks().get(shard);
    Hash32 parentRoot = spec.hash_tree_root(parentCrosslink);
    EpochNumber startEpoch = parentCrosslink.getEndEpoch();
    EpochNumber endEpoch =
        UInt64s.min(
            targetEpoch,
            parentCrosslink.getEndEpoch().plus(spec.getConstants().getMaxEpochsPerCrosslink()));

    return new Crosslink(shard, startEpoch, endEpoch, parentRoot, dataRoot);
  }

  /*
   Let custody_bitfield be a byte array filled with zeros of length (len(committee) + 7) // 8.
  */
  private BytesValue getCustodyBitfield(ValidatorIndex index, List<ValidatorIndex> committee) {
    int custodyBitfieldSize = (committee.size() + 7) / 8;
    return BytesValue.wrap(new byte[custodyBitfieldSize]);
  }

  /**
   * Creates {@link AttestationDataAndCustodyBit} instance and signs off on it.
   *
   * @param state a state at a slot that validator is attesting in.
   * @param data an attestation data instance.
   * @param signer message signer.
   * @return signature of attestation data and custody bit.
   */
  public BLSSignature getAggregateSignature(
      BeaconState state, AttestationData data, MessageSigner<BLSSignature> signer) {
    AttestationDataAndCustodyBit attestationDataAndCustodyBit =
        new AttestationDataAndCustodyBit(data, false);
    Hash32 hash = spec.hash_tree_root(attestationDataAndCustodyBit);
    UInt64 domain = spec.get_domain(state, ATTESTATION);
    return signer.sign(hash, domain);
  }
}
