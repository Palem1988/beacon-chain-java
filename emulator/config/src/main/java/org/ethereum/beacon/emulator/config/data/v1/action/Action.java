package org.ethereum.beacon.emulator.config.data.v1.action;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "action")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ActionRun.class, name = "run"),
    @JsonSubTypes.Type(value = ActionEmulate.class, name = "emulate"),
    @JsonSubTypes.Type(value = ActionDeposit.class, name = "deposit")
})
public abstract class Action {
}
