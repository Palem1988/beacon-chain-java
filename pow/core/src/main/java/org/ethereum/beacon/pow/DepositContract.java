package org.ethereum.beacon.pow;

import java.util.List;
import org.ethereum.beacon.core.operations.Deposit;
import org.ethereum.beacon.core.state.Eth1Data;
import org.reactivestreams.Publisher;
import tech.pegasys.artemis.ethereum.core.Hash32;
import tech.pegasys.artemis.util.uint.UInt64;

public interface DepositContract {

  Publisher<ChainStart> getChainStartMono();

  Publisher<Deposit> getAfterDepositsStream();

  class ChainStart {
    private final UInt64 time;
    private final Eth1Data eth1Data;
    private final List<Deposit> initialDeposits;


    public ChainStart(UInt64 time, Eth1Data eth1Data,
        List<Deposit> initialDeposits) {
      this.time = time;
      this.eth1Data = eth1Data;
      this.initialDeposits = initialDeposits;
    }

    public UInt64 getTime() {
      return time;
    }

    public Eth1Data getEth1Data() {
      return eth1Data;
    }

    public List<Deposit> getInitialDeposits() {
      return initialDeposits;
    }
  }
}
