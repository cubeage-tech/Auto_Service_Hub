package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.MechanicAssignmentRequestDTO;
import com.autoservicehub.dto.MechanicAssignmentResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.service.impl.MechanicAssignmentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MechanicAssignmentServiceImpl} (SRS 5.4, FR-AI-17..20).
 *
 * <p>Pure Mockito — no Spring context, no database, no external AI call.
 *
 * <p>Test cases: T1 valid recommendation, T2 feature type, T3 candidate pool and
 * real workload, T4 job card not found, T5 mechanic not found, T6 inactive
 * mechanic rejected, T7 no active mechanics, T8 provider throws, T9 noop
 * provider, T10 provider names a non-candidate (discarded), T11 low confidence,
 * T12 no recommendation returned, T13 PII minimisation, T14 non-persisting.
 */
@ExtendWith(MockitoExtension.class)
class MechanicAssignmentServiceImplTest {

    @Mock AiOrchestrationService aiOrchestrationService;
    @Mock JobCardRepository      jobCardRepository;
    @Mock MechanicRepository     mechanicRepository;

    @InjectMocks MechanicAssignmentServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private MechanicAssignmentRequestDTO request() {
        MechanicAssignmentRequestDTO req = new MechanicAssignmentRequestDTO();
        req.setJobCardId(7L);
        return req;
    }

    private Mechanic mechanic(Long id, String code, String name, Integer years, String status) {
        Mechanic m = new Mechanic();
        m.setId(id);
        m.setEmployeeCode(code);
        m.setName(name);
        m.setExperienceYears(years);
        m.setStatus(status);
        return m;
    }

    private JobCard jobCard(Mechanic assigned) {
        Vehicle v = new Vehicle();
        v.setMake("Maruti");
        v.setModel("Swift");
        JobCard jc = new JobCard();
        jc.setId(7L);
        jc.setJobCardNumber("JC-20260101120000");
        jc.setServiceType("BRAKES");
        jc.setComplaint("Squeal under braking");
        jc.setStatus("RECEIVED");
        jc.setVehicle(v);
        jc.setMechanic(assigned);
        return jc;
    }

    private AiResult validResult(long recommendedId) {
        return new AiResult(AiFeatureType.MECHANIC_ASSIGNMENT,
                "Recommended",
                new BigDecimal("0.71"),
                Map.of("recommendedMechanicId", recommendedId,
                       "rationale", "Lowest current open workload among active mechanics."),
                true);
    }

    private AiResult noopResult() {
        return new AiResult(AiFeatureType.MECHANIC_ASSIGNMENT,
                "AI provider not configured.", BigDecimal.ZERO, Map.of(), true);
    }

    // ── T1/T2: Happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — valid request returns a recommendation with human review required")
    void t1_validRequest_returnsRecommendation() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(2L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(4L));

        MechanicAssignmentResponseDTO response = service.recommend(request());

        assertThat(response.getRecommendedMechanicId()).isEqualTo(4L);
        assertThat(response.getRecommendedMechanicEmployeeCode()).isEqualTo("MEC-004");
        assertThat(response.getRecommendedMechanicName()).isEqualTo("Ravi Kumar");
        assertThat(response.getRationale()).contains("Lowest current open workload");
        assertThat(response.getConfidence()).isEqualByComparingTo("0.71");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDisclaimer()).isEqualTo(MechanicAssignmentServiceImpl.DISCLAIMER);
        assertThat(response.getDisclaimer()).containsIgnoringCase("RECOMMENDATION ONLY");
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("T2 — the correct AiFeatureType is sent to the orchestration layer")
    void t2_usesMechanicAssignmentFeatureType() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(4L));

        service.recommend(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getFeatureType()).isEqualTo(AiFeatureType.MECHANIC_ASSIGNMENT);
        assertThat(captor.getValue().getJobCardId()).isEqualTo(7L);
    }

    // ── T3: Domain context grounding ──────────────────────────────────────

    @Test
    @DisplayName("T3 — candidate pool and real open workload are sent to the provider")
    void t3_contextGroundedInRealData() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE"),
                mechanic(5L, "MEC-005", "Anita Sharma", 3, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(4L, "DELIVERED")).thenReturn(1L);
        when(jobCardRepository.countByMechanicIdAndStatusNot(5L, "DELIVERED")).thenReturn(4L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(4L));

        service.recommend(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();

        assertThat(ctx).containsEntry("serviceType", "BRAKES")
                       .containsEntry("jobStatus", "RECEIVED")
                       .containsEntry("candidateCount", 2)
                       .containsEntry("vehicleModel", "Swift");

        // Candidates are typed DTOs carrying real counts from the job card table.
        @SuppressWarnings("unchecked")
        List<MechanicAssignmentResponseDTO.CandidateDTO> candidates =
                (List<MechanicAssignmentResponseDTO.CandidateDTO>) ctx.get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates).anyMatch(c -> c.getMechanicId() == 4L && c.getOpenJobCards() == 1L);
        assertThat(candidates).anyMatch(c -> c.getMechanicId() == 5L && c.getOpenJobCards() == 4L);
        assertThat(candidates).extracting(MechanicAssignmentResponseDTO.CandidateDTO::getName)
                               .containsExactlyInAnyOrder("Ravi Kumar", "Anita Sharma");
    }

    // ── T4/T5/T6: Not found and invalid ───────────────────────────────────

    @Test
    @DisplayName("T4 — job card not found → ResourceNotFoundException, no AI call")
    void t4_jobCardNotFound_throws() {
        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());
        MechanicAssignmentRequestDTO req = new MechanicAssignmentRequestDTO();
        req.setJobCardId(99L);

        assertThatThrownBy(() -> service.recommend(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T5 — requested candidate mechanic not found → ResourceNotFoundException")
    void t5_mechanicNotFound_throws() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findById(77L)).thenReturn(Optional.empty());

        MechanicAssignmentRequestDTO req = request();
        req.setCandidateMechanicIds(List.of(77L));

        assertThatThrownBy(() -> service.recommend(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Mechanic not found: 77");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T6 — inactive candidate is rejected, never silently substituted")
    void t6_inactiveCandidate_rejected() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findById(9L))
                .thenReturn(Optional.of(mechanic(9L, "MEC-009", "On Leave", 5, "INACTIVE")));

        MechanicAssignmentRequestDTO req = request();
        req.setCandidateMechanicIds(List.of(9L));

        assertThatThrownBy(() -> service.recommend(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not active");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T6b — non-positive candidateMechanicIds → BusinessRuleException")
    void t6b_nonPositiveCandidateId_rejected() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));

        MechanicAssignmentRequestDTO req = request();
        req.setCandidateMechanicIds(List.of(0L));

        assertThatThrownBy(() -> service.recommend(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("candidateMechanicIds");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T7: Insufficient mechanic data ────────────────────────────────────

    @Test
    @DisplayName("T7 — no active mechanics → no recommendation invented")
    void t7_noActiveMechanics_noRecommendation() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of());

        MechanicAssignmentResponseDTO response = service.recommend(request());

        // No mechanic is guessed when the pool is empty.
        assertThat(response.getRecommendedMechanicId()).isNull();
        assertThat(response.getRecommendedMechanicName()).isNull();
        assertThat(response.getRationale()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getCandidatePoolSummary().getActiveCandidateCount()).isZero();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No active mechanics are available"));
        // The provider is never even called.
        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T8/T9: Provider unavailable (fail-safe) ───────────────────────────

    @Test
    @DisplayName("T8 — provider throws → no mechanic recommended, no leak")
    void t8_providerThrows_noRecommendation() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("timeout; apiKey=sk-live-SUPERSECRET"));

        MechanicAssignmentResponseDTO response = service.recommend(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getRecommendedMechanicId()).isNull();
        assertThat(response.getRecommendedMechanicName()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDisclaimer()).doesNotContain("SUPERSECRET");
        assertThat(response.getDataLimitations()).noneMatch(l -> l.contains("SUPERSECRET"));
    }

    @Test
    @DisplayName("T9 — noop provider result → providerUnavailable, no mechanic")
    void t9_noopProvider_noRecommendation() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        MechanicAssignmentResponseDTO response = service.recommend(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getRecommendedMechanicId()).isNull();
        assertThat(response.getCandidatePoolSummary().getActiveCandidateCount()).isEqualTo(1);
    }

    // ── T10/T12: Provider output validation (nothing fabricated) ───────────

    @Test
    @DisplayName("T10 — provider names a mechanic outside the candidate pool → discarded")
    void t10_providerNamesNonCandidate_discarded() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(999L));

        MechanicAssignmentResponseDTO response = service.recommend(request());

        // A hallucinated id is discarded, not passed through or substituted.
        assertThat(response.getRecommendedMechanicId()).isNull();
        assertThat(response.getRecommendedMechanicName()).isNull();
        assertThat(response.getRationale()).isNull();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("not in the eligible candidate pool"));
    }

    @Test
    @DisplayName("T12 — provider returns no mechanic → no recommendation invented")
    void t12_providerReturnsNoMechanic_noneInvented() {
        AiResult noPick = new AiResult(AiFeatureType.MECHANIC_ASSIGNMENT, "Cannot decide",
                new BigDecimal("0.60"), Map.of("rationale", "Insufficient data"), true);

        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(noPick);

        MechanicAssignmentResponseDTO response = service.recommend(request());

        assertThat(response.getRecommendedMechanicId()).isNull();
        assertThat(response.getRationale()).isNull();
        assertThat(response.getConfidence()).isEqualByComparingTo("0.60");
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("did not recommend a mechanic"));
    }

    @Test
    @DisplayName("T11 — low confidence is flagged and manual review is called out")
    void t11_lowConfidence_flagsManualReview() {
        AiResult lowConf = new AiResult(AiFeatureType.MECHANIC_ASSIGNMENT, "Guess",
                new BigDecimal("0.25"), Map.of("recommendedMechanicId", 4L), true);

        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(lowConf);

        MechanicAssignmentResponseDTO response = service.recommend(request());

        assertThat(response.getConfidence()).isEqualByComparingTo("0.25");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.toLowerCase().contains("low confidence"));
    }

    // ── T13: PII minimisation ────────────────────────────────────────────

    @Test
    @DisplayName("T13 — no customer PII is sent to the AI provider")
    void t13_noCustomerPiiInContext() {
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(null)));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(4L));

        service.recommend(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        String ctx = captor.getValue().getContext().toString();

        // Assert on the absence of customer identity keys rather than a raw "@"
        // scan, which would false-positive on Lombok's DTO toString output.
        assertThat(ctx).doesNotContain("customerName")
                       .doesNotContain("customerPhone")
                       .doesNotContain("customerEmail")
                       .doesNotContain("email")
                       .doesNotContain("address");
        assertThat(captor.getValue().getContext())
                .doesNotContainKey("customerName")
                .doesNotContainKey("customerPhone")
                .doesNotContainKey("email")
                .doesNotContainKey("address");
    }

    // ── T14: The recommendation must NOT persist an assignment ───────────

    @Test
    @DisplayName("T14 — a recommendation never writes to the job card")
    void t14_recommendationDoesNotPersistAssignment() {
        JobCard tracked = jobCard(null);
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(tracked));
        when(mechanicRepository.findByStatusIgnoreCase("ACTIVE")).thenReturn(List.of(
                mechanic(4L, "MEC-004", "Ravi Kumar", 8, "ACTIVE")));
        when(jobCardRepository.countByMechanicIdAndStatusNot(anyLong(), anyString())).thenReturn(0L);
        when(aiOrchestrationService.process(any())).thenReturn(validResult(4L));

        MechanicAssignmentResponseDTO response = service.recommend(request());

        // The response itself declares that nothing was assigned.
        assertThat(response.isAssignmentPersisted()).isFalse();
        assertThat(response.isHumanReviewRequired()).isTrue();

        // JobCard.mechanic must remain untouched, and nothing may be saved.
        assertThat(tracked.getMechanic()).isNull();
        verify(jobCardRepository, never()).save(any(JobCard.class));
        verify(jobCardRepository, never()).saveAll(any());
        verify(jobCardRepository, never()).deleteById(anyLong());
        verify(mechanicRepository, never()).save(any(Mechanic.class));
    }
}
