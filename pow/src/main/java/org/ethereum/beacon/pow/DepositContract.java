package org.ethereum.beacon.pow;

import java.util.List;
import org.ethereum.beacon.core.operations.Deposit;
import org.ethereum.beacon.types.Ether;
import tech.pegasys.artemis.ethereum.core.Hash32;
import tech.pegasys.artemis.util.uint.UInt64;

public interface DepositContract {

  /** Maximum number of ETH that can be deposited at once. */
  Ether MAX_DEPOSIT = Ether.valueOf(1 << 5); // 32 ETH

  ChainStart getChainStart();

  List<Deposit> getInitialDeposits();

  class ChainStart {
    private final UInt64 time;
    private final Hash32 receiptRoot;

    public ChainStart(UInt64 time, Hash32 receiptRoot) {
      this.time = time;
      this.receiptRoot = receiptRoot;
    }

    public UInt64 getTime() {
      return time;
    }

    public Hash32 getReceiptRoot() {
      return receiptRoot;
    }
  }
}
