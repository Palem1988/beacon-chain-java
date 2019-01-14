package org.ethereum.beacon.consensus.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ethereum.beacon.core.BeaconState;
import org.ethereum.beacon.core.spec.ChainSpec;
import org.ethereum.beacon.core.state.ValidatorRecord;
import org.ethereum.beacon.pow.DepositContract;
import tech.pegasys.artemis.util.bytes.Bytes48;
import tech.pegasys.artemis.util.uint.UInt24;
import tech.pegasys.artemis.util.uint.UInt64;

/**
 * Interface to read from validator registry.
 *
 * <p>Can be instantly created from {@link BeaconState} by {@link #fromState(BeaconState,
 * ChainSpec)} method.
 */
public interface ValidatorRegistryReader {

  /**
   * Creates a reader instance by fetching required data from {@link BeaconState}.
   *
   * @param state beacon state.
   * @param chainSpec beacon chain spec.
   * @return constructed reader.
   */
  static ValidatorRegistryReader fromState(BeaconState state, ChainSpec chainSpec) {
    return new InMemoryValidatorRegistryUpdater(
        new ArrayList<>(state.getValidatorRegistry()),
        new ArrayList<>(state.getValidatorBalances()),
        state.getValidatorRegistryDeltaChainTip(),
        state.getSlot(),
        chainSpec);
  }

  /**
   * Returns validator's balance capped by {@link ChainSpec#getMaxDeposit()} value.
   *
   * @param index index of the validator.
   * @return a deposit value in GWei.
   * @throws IndexOutOfBoundsException if index is invalid.
   */
  UInt64 getEffectiveBalance(UInt24 index);

  /**
   * Returns validator record with given index.
   *
   * @param index validator index.
   * @return validator record.
   * @throws IndexOutOfBoundsException if index is invalid.
   */
  ValidatorRecord get(UInt24 index);

  /**
   * Returns a number of validator records.
   *
   * @return a size.
   */
  UInt24 size();

  /**
   * Returns all validator records
   */
  default List<ValidatorRecord> getAll() {
    int size = size().getValue();
    List<ValidatorRecord> ret = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      ret.add(get(UInt24.valueOf(i)));
    }
    return ret;
  }

  /**
   * Returns validator record that public key is equal to given one.
   *
   * @param pubKey a public key.
   * @return validator record if it's been found.
   */
  Optional<ValidatorRecord> getByPubKey(Bytes48 pubKey);
}
