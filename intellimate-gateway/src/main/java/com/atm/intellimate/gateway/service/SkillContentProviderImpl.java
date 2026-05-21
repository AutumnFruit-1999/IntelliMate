package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.skills.SkillContentProvider;
import com.atm.intellimate.gateway.entity.SkillGroupEntity;
import com.atm.intellimate.gateway.entity.SkillGroupMemberEntity;
import com.atm.intellimate.gateway.repository.SkillDefinitionRepository;
import com.atm.intellimate.gateway.repository.SkillGroupMemberRepository;
import com.atm.intellimate.gateway.repository.SkillGroupRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SkillContentProviderImpl implements SkillContentProvider {

    private static final Logger log = LoggerFactory.getLogger(SkillContentProviderImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillDefinitionRepository repository;
    private final SkillFileService fileService;
    private final SkillGroupRepository groupRepository;
    private final SkillGroupMemberRepository memberRepository;

    @Value("${intellimate.skills.dir:../skills}")
    private String skillsDir;

    public SkillContentProviderImpl(SkillDefinitionRepository repository,
                                    SkillFileService fileService,
                                    SkillGroupRepository groupRepository,
                                    SkillGroupMemberRepository memberRepository) {
        this.repository = repository;
        this.fileService = fileService;
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
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

    @Override
    public List<SkillGroupSummary> listGroups() {
        try {
            List<SkillGroupEntity> groups = groupRepository.findAllByEnabledOrderBySortOrderAsc(1)
                    .collectList().block();
            if (groups == null || groups.isEmpty()) return List.of();

            List<Long> groupIds = groups.stream().map(SkillGroupEntity::getId).toList();
            List<SkillGroupMemberEntity> members = memberRepository.findByGroupIdIn(groupIds)
                    .collectList().block();

            Map<Long, Long> countMap = (members != null)
                    ? members.stream().collect(Collectors.groupingBy(SkillGroupMemberEntity::getGroupId, Collectors.counting()))
                    : Map.of();

            return groups.stream()
                    .map(g -> new SkillGroupSummary(
                            g.getName(),
                            g.getDisplayName() != null ? g.getDisplayName() : g.getName(),
                            g.getDescription() != null ? g.getDescription() : "",
                            countMap.getOrDefault(g.getId(), 0L).intValue()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list skill groups: {} — ensure this method is not called from a non-blocking thread",
                    e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public Map<String, List<SkillSummary>> listSkillsByGroups(List<String> groupNames) {
        try {
            List<SkillGroupEntity> groups = groupRepository.findEnabledByNames(groupNames)
                    .collectList().block();
            if (groups == null || groups.isEmpty()) return Map.of();

            List<Long> groupIds = groups.stream().map(SkillGroupEntity::getId).toList();
            List<SkillGroupMemberEntity> members = memberRepository.findByGroupIdIn(groupIds)
                    .collectList().block();
            if (members == null || members.isEmpty()) return Map.of();

            Set<Long> skillIds = members.stream()
                    .map(SkillGroupMemberEntity::getSkillId)
                    .collect(Collectors.toSet());

            Map<Long, SkillSummary> skillMap = new HashMap<>();
            repository.findAllById(skillIds)
                    .filter(s -> s.getEnabled() != null && s.getEnabled() == 1)
                    .doOnNext(s -> skillMap.put(s.getId(), new SkillSummary(s.getName(), s.getDescription())))
                    .blockLast();

            Map<Long, String> groupIdToName = groups.stream()
                    .collect(Collectors.toMap(SkillGroupEntity::getId, SkillGroupEntity::getName));

            Map<String, List<SkillSummary>> result = new LinkedHashMap<>();
            for (SkillGroupMemberEntity member : members) {
                String gName = groupIdToName.get(member.getGroupId());
                SkillSummary skill = skillMap.get(member.getSkillId());
                if (gName != null && skill != null) {
                    result.computeIfAbsent(gName, k -> new ArrayList<>()).add(skill);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list skills by groups: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<String> listAllSkillNames() {
        try {
            return repository.findAllByEnabled(1)
                    .map(s -> s.getName())
                    .collectList()
                    .block();
        } catch (Exception e) {
            log.warn("Failed to list all skill names: {}", e.getMessage());
            return List.of();
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
