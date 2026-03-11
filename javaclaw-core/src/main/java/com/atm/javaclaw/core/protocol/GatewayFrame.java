package com.atm.javaclaw.core.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EventFrame.class, name = "event"),
        @JsonSubTypes.Type(value = RequestFrame.class, name = "request"),
        @JsonSubTypes.Type(value = ResponseFrame.class, name = "response")
})
public sealed interface GatewayFrame permits EventFrame, RequestFrame, ResponseFrame {
    String type();
}
