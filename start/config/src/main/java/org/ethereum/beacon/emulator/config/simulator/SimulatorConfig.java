package org.ethereum.beacon.emulator.config.simulator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import java.util.List;
import org.ethereum.beacon.emulator.config.Config;
import org.ethereum.beacon.emulator.config.YamlPrinter;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SimulatorConfig implements Config {
  private List<Peer> peers;

  @JsonProperty("bls-verify")
  private boolean blsVerifyEnabled = true;

  @JsonProperty("genesis-time")
  private int genesisTime = 600;

  private long seed = System.currentTimeMillis();

  public List<Peer> getPeers() {
    return peers;
  }

  public void setPeers(List<Peer> peers) {
    this.peers = peers;
  }

  public boolean isBlsVerifyEnabled() {
    return blsVerifyEnabled;
  }

  public void setBlsVerifyEnabled(boolean blsVerifyEnabled) {
    this.blsVerifyEnabled = blsVerifyEnabled;
  }

  public int getGenesisTime() {
    return genesisTime;
  }

  public void setGenesisTime(int genesisTime) {
    this.genesisTime = genesisTime;
  }

  public long getSeed() {
    return seed;
  }

  public void setSeed(long seed) {
    this.seed = seed;
  }

  @Override
  public String toString() {
    return new YamlPrinter(this).getString() + "---";
  }
}
