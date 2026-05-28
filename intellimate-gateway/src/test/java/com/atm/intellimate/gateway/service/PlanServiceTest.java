package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;
import com.atm.intellimate.gateway.repository.PlanRepository;
import com.atm.intellimate.gateway.repository.PlanStepRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock private PlanRepository planRepository;
    @Mock private PlanStepRepository planStepRepository;
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PlanService service;

    @BeforeEach
    void setUp() {
        service = new PlanService(planRepository, planStepRepository, meterRegistry);
    }

    private PlanEntity makePlan(Long id, String status) {
        PlanEntity p = new PlanEntity();
        p.setId(id);
        p.setSessionId(1L);
        p.setTitle("Test Plan");
        p.setStatus(status);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    private PlanStepEntity makeStep(Long id, Long planId, int index, String status) {
        PlanStepEntity s = new PlanStepEntity();
        s.setId(id);
        s.setPlanId(planId);
        s.setStepIndex(index);
        s.setTitle("Step " + index);
        s.setDescription("Description " + index);
        s.setStatus(status);
        return s;
    }

    @Nested
    class CreatePlanTests {

        @Test
        void createPlan_savesAllStepsAndVerifiesCount() {
            PlanEntity saved = makePlan(10L, "draft");
            when(planRepository.save(any())).thenReturn(Mono.just(saved));

            AtomicLong stepIdGen = new AtomicLong(100);
            when(planStepRepository.save(any(PlanStepEntity.class)))
                    .thenAnswer(inv -> {
                        PlanStepEntity step = inv.getArgument(0);
                        step.setId(stepIdGen.getAndIncrement());
                        return Mono.just(step);
                    });

            List<PlanStepEntity> verified = List.of(
                    makeStep(100L, 10L, 1, "pending"),
                    makeStep(101L, 10L, 2, "pending"),
                    makeStep(102L, 10L, 3, "pending")
            );
            when(planStepRepository.findByPlanIdOrderByStepIndex(10L))
                    .thenReturn(Flux.fromIterable(verified));

            List<PlanService.StepInput> steps = List.of(
                    new PlanService.StepInput("Step 1", "Desc 1"),
                    new PlanService.StepInput("Step 2", "Desc 2"),
                    new PlanService.StepInput("Step 3", "Desc 3")
            );

            StepVerifier.create(service.createPlan(1L, "Test", steps))
                    .expectNext(saved)
                    .verifyComplete();

            verify(planStepRepository, times(3)).save(any(PlanStepEntity.class));
        }
    }

    @Nested
    class CompletePlanTests {

        @Test
        void completePlan_savesCompletionSummary() {
            PlanEntity plan = makePlan(10L, "executing");
            when(planStepRepository.findByPlanIdOrderByStepIndex(10L))
                    .thenReturn(Flux.empty());
            when(planRepository.findById(10L)).thenReturn(Mono.just(plan));
            when(planRepository.save(any(PlanEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.completePlan(10L, "All 3 steps completed successfully"))
                    .assertNext(result -> {
                        assertThat(result.getStatus()).isEqualTo("completed");
                        assertThat(result.getCompletionSummary()).isEqualTo("All 3 steps completed successfully");
                    })
                    .verifyComplete();

            ArgumentCaptor<PlanEntity> captor = ArgumentCaptor.forClass(PlanEntity.class);
            verify(planRepository).save(captor.capture());
            assertThat(captor.getValue().getCompletionSummary()).isEqualTo("All 3 steps completed successfully");
        }

        @Test
        void completePlan_skipsPendingStepsAndSavesSummary() {
            PlanEntity plan = makePlan(10L, "executing");
            PlanStepEntity pendingStep = makeStep(201L, 10L, 3, "pending");

            when(planStepRepository.findByPlanIdOrderByStepIndex(10L))
                    .thenReturn(Flux.just(
                            makeStep(200L, 10L, 1, "completed"),
                            makeStep(201L, 10L, 2, "completed"),
                            pendingStep
                    ));
            when(planStepRepository.save(any(PlanStepEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(planRepository.findById(10L)).thenReturn(Mono.just(plan));
            when(planRepository.save(any(PlanEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.completePlan(10L, "Completed early"))
                    .assertNext(result -> {
                        assertThat(result.getStatus()).isEqualTo("completed");
                        assertThat(result.getCompletionSummary()).isEqualTo("Completed early");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    class AddStepReindexTests {

        @Test
        void addStep_reindexesInReverseOrder() {
            List<PlanStepEntity> existing = List.of(
                    makeStep(1L, 10L, 1, "completed"),
                    makeStep(2L, 10L, 2, "pending"),
                    makeStep(3L, 10L, 3, "pending")
            );
            when(planStepRepository.findByPlanIdOrderByStepIndex(10L))
                    .thenReturn(Flux.fromIterable(existing));

            List<PlanStepEntity> savedSteps = new ArrayList<>();
            when(planStepRepository.save(any(PlanStepEntity.class)))
                    .thenAnswer(inv -> {
                        PlanStepEntity step = inv.getArgument(0);
                        if (step.getId() == null) step.setId(99L);
                        savedSteps.add(step);
                        return Mono.just(step);
                    });
            when(planRepository.findById(10L)).thenReturn(Mono.just(makePlan(10L, "executing")));
            when(planRepository.save(any(PlanEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.addStep(10L, 1, "New Step", "New Desc"))
                    .assertNext(step -> {
                        assertThat(step.getTitle()).isEqualTo("New Step");
                        assertThat(step.getStepIndex()).isEqualTo(2);
                    })
                    .verifyComplete();

            List<PlanStepEntity> reindexed = savedSteps.stream()
                    .filter(s -> s.getId() != null && s.getId() != 99L)
                    .toList();
            assertThat(reindexed).hasSize(2);
            assertThat(reindexed.get(0).getId()).isEqualTo(3L);
            assertThat(reindexed.get(1).getId()).isEqualTo(2L);
        }
    }

    @Nested
    class RemoveStepReindexTests {

        @Test
        void removeStep_reindexesSequentially() {
            PlanStepEntity toDelete = makeStep(2L, 10L, 2, "pending");
            when(planStepRepository.findByPlanIdAndStepIndex(10L, 2))
                    .thenReturn(Mono.just(toDelete));
            when(planStepRepository.delete(toDelete)).thenReturn(Mono.empty());

            List<PlanStepEntity> remaining = List.of(
                    makeStep(1L, 10L, 1, "completed"),
                    makeStep(3L, 10L, 3, "pending")
            );
            when(planStepRepository.findByPlanIdOrderByStepIndex(10L))
                    .thenReturn(Flux.fromIterable(remaining));

            when(planStepRepository.save(any(PlanStepEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(planRepository.findById(10L)).thenReturn(Mono.just(makePlan(10L, "executing")));
            when(planRepository.save(any(PlanEntity.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(service.removeStep(10L, 2, "Not needed"))
                    .verifyComplete();

            ArgumentCaptor<PlanStepEntity> captor = ArgumentCaptor.forClass(PlanStepEntity.class);
            verify(planStepRepository, atLeast(2)).save(captor.capture());
            List<PlanStepEntity> saved = captor.getAllValues();

            assertThat(saved.get(0).getStepIndex()).isEqualTo(1);
            assertThat(saved.get(1).getStepIndex()).isEqualTo(2);
        }
    }
}
