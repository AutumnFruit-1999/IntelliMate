package com.atm.javaclaw.gateway.service;

import com.atm.javaclaw.agent.skills.SkillContentProvider;
import com.atm.javaclaw.gateway.repository.SkillDefinitionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Component
public class SkillContentProviderImpl implements SkillContentProvider {

    private static final Logger log = LoggerFactory.getLogger(SkillContentProviderImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillDefinitionRepository repository;
    private final SkillFileService fileService;

    @Value("${javaclaw.skills.dir:./skills}")
    private String skillsDir;

    public SkillContentProviderImpl(SkillDefinitionRepository repository, SkillFileService fileService) {
        this.repository = repository;
        this.fileService = fileService;
    }

    @Override
    public Mono<List<SkillSummary>> resolveSkillSummaries(String skillsEnabledSpec) {
        if (skillsEnabledSpec == null || skillsEnabledSpec.isBlank()) {
            return Mono.just(List.of());
        }

        if ("full".equalsIgnoreCase(skillsEnabledSpec.trim())) {
            return repository.findAllByEnabled(1)
                    .map(s -> new SkillSummary(s.getName(), s.getDescription()))
                    .collectList();
        }

        Set<String> names = parseSkillNames(skillsEnabledSpec);
        if (names.isEmpty()) return Mono.just(List.of());

        return repository.findAllByNameIn(names)
                .filter(s -> s.getEnabled() != null && s.getEnabled() == 1)
                .map(s -> new SkillSummary(s.getName(), s.getDescription()))
                .collectList();
    }

    @Override
    public String getSkillsBasePath() {
        return Path.of(skillsDir).toAbsolutePath().toString();
    }

    @Override
    public String readSkillContent(String skillName) {
        String fromFile = fileService.readContent(skillName);
        if (fromFile != null) {
            return fromFile;
        }
        try {
            var entity = repository.findByName(skillName).block();
            return entity != null ? entity.getContent() : null;
        } catch (Exception e) {
            log.warn("Failed to read skill content from DB for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    private Set<String> parseSkillNames(String spec) {
        try {
            String trimmed = spec.trim();
            if (trimmed.startsWith("[")) {
                List<String> list = MAPPER.readValue(trimmed, new TypeReference<>() {});
                return Set.copyOf(list);
            }
            return Set.of(trimmed);
        } catch (Exception e) {
            log.warn("Failed to parse skillsEnabled spec '{}': {}", spec, e.getMessage());
            return Set.of();
        }
    }
}
