package org.ethereum.beacon.test.type.state.tmp;

import org.ethereum.beacon.test.type.state.StateTestCase;

import java.util.Map;

public interface PostField extends FieldLoader {
  default StateTestCase.BeaconStateData getPre() {
    try {
      for (Map.Entry<String, String> file : getFiles().entrySet()) {
        if (file.getKey().equals("post.yaml")) {
          return getMapper().readValue(file.getValue(), StateTestCase.BeaconStateData.class);
        }
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    throw new RuntimeException("`post` field not defined");
  }
}
