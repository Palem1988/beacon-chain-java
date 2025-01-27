package org.ethereum.beacon.start.common.util;

import org.ethereum.beacon.consensus.BeaconChainSpec;
import org.ethereum.beacon.core.operations.Deposit;
import org.ethereum.beacon.core.operations.deposit.DepositData;
import org.ethereum.beacon.core.spec.SignatureDomains;
import org.ethereum.beacon.core.types.BLSPubkey;
import org.ethereum.beacon.core.types.BLSSignature;
import org.ethereum.beacon.core.types.Gwei;
import org.ethereum.beacon.crypto.BLS381;
import org.ethereum.beacon.crypto.BLS381.PrivateKey;
import org.ethereum.beacon.crypto.MessageParameters;
import org.javatuples.Pair;
import tech.pegasys.artemis.ethereum.core.Hash32;
import tech.pegasys.artemis.util.bytes.Bytes32;
import tech.pegasys.artemis.util.bytes.Bytes4;
import tech.pegasys.artemis.util.bytes.Bytes48;
import tech.pegasys.artemis.util.bytes.Bytes96;
import tech.pegasys.artemis.util.uint.UInt64;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class SimulateUtils {

  private static Pair<List<Deposit>, List<BLS381.KeyPair>> cachedDeposits =
      Pair.with(new ArrayList<>(), new ArrayList<>());

  public static synchronized Pair<List<Deposit>, List<BLS381.KeyPair>> getAnyDeposits(
      Random rnd, BeaconChainSpec spec, int count, boolean isProofVerifyEnabled) {
    if (count > cachedDeposits.getValue0().size()) {
      Pair<List<Deposit>, List<BLS381.KeyPair>> newDeposits =
          generateRandomDeposits(UInt64.valueOf(cachedDeposits.getValue0().size()), rnd, spec,
              count - cachedDeposits.getValue0().size(), isProofVerifyEnabled);
      cachedDeposits.getValue0().addAll(newDeposits.getValue0());
      cachedDeposits.getValue1().addAll(newDeposits.getValue1());
    }
    return Pair.with(
        cachedDeposits.getValue0().subList(0, count), cachedDeposits.getValue1().subList(0, count));
  }

  public static Deposit getDepositForKeyPair(Random rnd,
      BLS381.KeyPair keyPair, BeaconChainSpec spec, boolean isProofVerifyEnabled) {
    return getDepositForKeyPair(
        rnd,
        keyPair,
        spec,
        spec.getConstants().getMaxEffectiveBalance(),
        isProofVerifyEnabled);
  }

  public static synchronized Deposit getDepositForKeyPair(Random rnd,
      BLS381.KeyPair keyPair, BeaconChainSpec spec, Gwei initBalance, boolean isProofVerifyEnabled) {
    Hash32 withdrawalCredentials = Hash32.random(rnd);
    DepositData depositDataWithoutSignature =
        new DepositData(
            BLSPubkey.wrap(Bytes48.leftPad(keyPair.getPublic().getEncodedBytes())),
            withdrawalCredentials,
            initBalance,
            BLSSignature.wrap(Bytes96.ZERO));

    BLSSignature signature = BLSSignature.ZERO;

    if (isProofVerifyEnabled) {
      Hash32 msgHash = spec.signing_root(depositDataWithoutSignature);
      UInt64 domain = spec.compute_domain(SignatureDomains.DEPOSIT, Bytes4.ZERO);
      signature =
          BLSSignature.wrap(
              BLS381.sign(MessageParameters.create(msgHash, domain), keyPair).getEncoded());
    }

    Deposit deposit =
        Deposit.create(
            Collections.singletonList(Hash32.random(rnd)),
            new DepositData(
                BLSPubkey.wrap(Bytes48.leftPad(keyPair.getPublic().getEncodedBytes())),
                withdrawalCredentials,
                spec.getConstants().getMaxEffectiveBalance(),
                signature));
    return deposit;
  }

  public static synchronized List<Deposit> getDepositsForKeyPairs(
      UInt64 startIndex,
      Random rnd,
      List<BLS381.KeyPair> keyPairs,
      BeaconChainSpec spec,
      boolean isProofVerifyEnabled) {
    return getDepositsForKeyPairs(
        startIndex,
        rnd,
        keyPairs,
        spec,
        spec.getConstants().getMaxEffectiveBalance(),
        isProofVerifyEnabled);
  }

  public static synchronized List<Deposit> getDepositsForKeyPairs(
      UInt64 startIndex,
      Random rnd,
      List<BLS381.KeyPair> keyPairs,
      BeaconChainSpec spec,
      Gwei initBalance,
      boolean isProofVerifyEnabled) {

    List<Deposit> deposits = new ArrayList<>();

    for (BLS381.KeyPair keyPair : keyPairs) {
      deposits.add(getDepositForKeyPair(rnd, keyPair, spec, initBalance, isProofVerifyEnabled));
    }

    return deposits;
  }

  private static synchronized Pair<List<Deposit>, List<BLS381.KeyPair>> generateRandomDeposits(
      UInt64 startIndex, Random rnd, BeaconChainSpec spec, int count, boolean isProofVerifyEnabled) {
    List<BLS381.KeyPair> validatorsKeys = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      BLS381.KeyPair keyPair = BLS381.KeyPair.create(PrivateKey.create(Bytes32.random(rnd)));
      validatorsKeys.add(keyPair);

    }
    List<Deposit> deposits = getDepositsForKeyPairs(startIndex, rnd, validatorsKeys, spec, isProofVerifyEnabled);
    return Pair.with(deposits, validatorsKeys);
  }
}
