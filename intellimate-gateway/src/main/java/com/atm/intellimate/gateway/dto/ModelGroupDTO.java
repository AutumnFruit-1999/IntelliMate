package com.atm.intellimate.gateway.dto;

import java.util.ArrayList;
import java.util.List;

public record ModelGroupDTO(
        Long providerId,
        String providerName,
        String providerType,
        List<ModelDTO> models
) {
    public ModelGroupDTO {
        models = models != null ? models : new ArrayList<>();
    }
}
