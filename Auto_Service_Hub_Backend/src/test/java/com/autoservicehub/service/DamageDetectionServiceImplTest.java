package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.DamageDetectionRequestDTO;
import com.autoservicehub.dto.DamageDetectionResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.DamageDetectionServiceImpl;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DamageDetectionServiceImpl} (SRS 5.6, FR-AI-13..16).
 *
 * <p>Pure Mockito — no Spring context, no database, no external AI call.
 *
 * <p>Test cases: T1 valid assessment, T2 feature type, T3 context grounding,
 * T4 validation failures, T5 vehicle not found, T6 job card not found,
 * T7 job card belongs to another vehicle, T8 provider throws, T9 noop provider,
 * T10 no fabricated severity, T11 low confidence, T12 PII minimisation,
 * T13 response never claims an image was analysed.
 */
@ExtendWith(MockitoExtension.class)
class DamageDetectionServiceImplTest {

    @Mock AiOrchestrationService aiOrchestrationService;
    @Mock VehicleRepository      vehicleRepository;
    @Mock JobCardRepository      jobCardRepository;

    @InjectMocks DamageDetectionServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private DamageDetectionRequestDTO request() {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setVehicleId(3L);
        req.setDamageDescription("Front-left wing panel is scraped and dented. Bumper cover is cracked.");
        return req;
    }

    private Vehicle vehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setMake("Maruti");
        v.setModel("Swift");
        v.setYear(2019);
        v.setMileage(54000);
        v.setRegistrationNo("MH-12-AB-1234");
        return v;
    }

    private JobCard jobCard(Long id, Vehicle vehicle) {
        JobCard jc = new JobCard();
        jc.setId(id);
        jc.setServiceType("ACCIDENT_REPAIR");
        jc.setStatus("RECEIVED");
        jc.setComplaint("Minor frontal impact reported");
        jc.setVehicle(vehicle);
        return jc;
    }

    private AiResult validResult() {
        return new AiResult(AiFeatureType.DAMAGE_DETECTION,
                "Damage appears concentrated on the front-left corner",
                new BigDecimal("0.62"),
                Map.of("affectedAreas", List.of(
                        Map.of("area", "Front-left wing panel", "severity", "moderate",
                              "description", "Surface scraping and denting",
                              "rationale", "The description states the panel is scraped and dented."),
                        Map.of("area", "Front bumper cover", "description", "Cracked and hanging loose")
                )),
                true);
    }

    private AiResult noopResult() {
        return new AiResult(AiFeatureType.DAMAGE_DETECTION,
                "AI provider not configured.", BigDecimal.ZERO, Map.of(), true);
    }

    // ── T1/T2: Happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — valid request returns a text-based assessment with human review required")
    void t1_validRequest_returnsAssessment() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.getVehicleId()).isEqualTo(3L);
        assertThat(response.getSummary()).isEqualTo("Damage appears concentrated on the front-left corner");
        assertThat(response.getConfidence()).isEqualByComparingTo("0.62");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.isProviderUnavailable()).isFalse();
        assertThat(response.getDisclaimer()).isEqualTo(DamageDetectionServiceImpl.DISCLAIMER);
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("T1b — the disclaimer states no image was analysed and bars insurance/safety use")
    void t1b_disclaimer_disclaimsImageAndInsuranceUse() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionResponseDTO response = service.assess(request());

        String d = response.getDisclaimer();
        assertThat(d).containsIgnoringCase("WRITTEN description");
        assertThat(d).containsIgnoringCase("NO photographs, images or video were analysed");
        assertThat(d).containsIgnoringCase("insurance claim");
        assertThat(d).containsIgnoringCase("safe or unsafe");
    }

    @Test
    @DisplayName("T2 — the correct AiFeatureType is sent to the orchestration layer")
    void t2_usesDamageDetectionFeatureType() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        service.assess(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getFeatureType()).isEqualTo(AiFeatureType.DAMAGE_DETECTION);
        assertThat(captor.getValue().getVehicleId()).isEqualTo(3L);
    }

    // ── T3: Domain context grounding ──────────────────────────────────────

    @Test
    @DisplayName("T3 — real vehicle and job card data are sent to the provider")
    void t3_contextGroundedInRealData() {
        Vehicle v = vehicle(3L);
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(v));
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(7L, v)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionRequestDTO req = request();
        req.setJobCardId(7L);
        service.assess(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();

        assertThat(ctx).containsEntry("vehicleMake", "Maruti")
                       .containsEntry("vehicleModel", "Swift")
                       .containsEntry("serviceType", "ACCIDENT_REPAIR")
                       .containsEntry("complaint", "Minor frontal impact reported");
        // The context declares this is a text assessment with no image analysis.
        assertThat(ctx).containsEntry("imageAnalysisPerformed", false);
    }

    // ── T4..T7: Validation and not-found ──────────────────────────────────

    @Test
    @DisplayName("T4 — null vehicleId is rejected before any AI call")
    void t4_nullVehicleId_rejected() {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setDamageDescription("Front bumper is damaged and cracked.");

        assertThatThrownBy(() -> service.assess(req))
                .isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(aiOrchestrationService, vehicleRepository);
    }

    @Test
    @DisplayName("T4b — description too short → BusinessRuleException")
    void t4b_descriptionTooShort_rejected() {
        DamageDetectionRequestDTO req = new DamageDetectionRequestDTO();
        req.setVehicleId(3L);
        req.setDamageDescription("Bump");

        assertThatThrownBy(() -> service.assess(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("too short");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T5 — vehicle not found → ResourceNotFoundException")
    void t5_vehicleNotFound_throws() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());
        DamageDetectionRequestDTO req = request();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.assess(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T6 — job card not found → ResourceNotFoundException")
    void t6_jobCardNotFound_throws() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(jobCardRepository.findById(88L)).thenReturn(Optional.empty());
        DamageDetectionRequestDTO req = request();
        req.setJobCardId(88L);

        assertThatThrownBy(() -> service.assess(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 88");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T7 — job card belonging to a different vehicle is rejected")
    void t7_jobCardFromAnotherVehicle_rejected() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard(7L, vehicle(99L))));
        DamageDetectionRequestDTO req = request();
        req.setJobCardId(7L);

        assertThatThrownBy(() -> service.assess(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("different vehicle");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T8/T9: Provider unavailable ──────────────────────────────────────

    @Test
    @DisplayName("T8 — provider throws → no damage areas fabricated, no leak")
    void t8_providerThrows_noDamageFabricated() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("timeout; apiKey=sk-live-SUPERSECRET"));

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getAffectedAreas()).isEmpty();
        assertThat(response.getSummary()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDisclaimer()).doesNotContain("SUPERSECRET");
        assertThat(response.getDataLimitations()).noneMatch(l -> l.contains("SUPERSECRET"));
    }

    @Test
    @DisplayName("T9 — noop provider result → providerUnavailable, no damage areas")
    void t9_noopProvider_noDamageFabricated() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getAffectedAreas()).isEmpty();
        assertThat(response.getSummary()).isNull();
    }

    // ── T10: No fabricated severity ──────────────────────────────────────

    @Test
    @DisplayName("T10 — an area with no severity supplied keeps severity null")
    void t10_missingSeverity_notFabricated() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.getAffectedAreas()).hasSize(2);
        // First area had "moderate" — normalised, not invented.
        assertThat(response.getAffectedAreas().get(0).getSeverity()).isEqualTo("MODERATE");
        // Second area had NO severity — it must not be defaulted.
        assertThat(response.getAffectedAreas().get(1).getArea()).isEqualTo("Front bumper cover");
        assertThat(response.getAffectedAreas().get(1).getSeverity()).isNull();
    }

    @Test
    @DisplayName("T11 — low confidence is flagged and manual review is called out")
    void t11_lowConfidence_flagsManualReview() {
        AiResult lowConf = new AiResult(AiFeatureType.DAMAGE_DETECTION, "Guess",
                new BigDecimal("0.18"),
                Map.of("affectedAreas", List.of(Map.of("area", "Front bumper"))), true);

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(lowConf);

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.getConfidence()).isEqualByComparingTo("0.18");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.toLowerCase().contains("low confidence"));
    }

    // ── T12: PII minimisation ────────────────────────────────────────────

    @Test
    @DisplayName("T12 — no customer PII is sent to the AI provider")
    void t12_noCustomerPiiInContext() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        service.assess(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        String ctx = captor.getValue().getContext().toString();

        // Assert on PII keys, not a raw "@" scan, which would false-positive on
        // Lombok's toString output.
        assertThat(ctx).doesNotContain("customerName")
                       .doesNotContain("customerPhone")
                       .doesNotContain("email")
                       .doesNotContain("address");
    }

    // ── T13: The response must never claim an image was analysed ─────────

    @Test
    @DisplayName("T13 — response never claims an image or photo was analysed")
    void t13_neverClaimsImageAnalysis() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionResponseDTO response = service.assess(request());

        // The mandatory limitation is present and explicit.
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No images or photographs were analysed"));
        // Nothing in the payload asserts that an image WAS analysed.
        String everything = response.getDisclaimer() + " "
                + String.join(" ", response.getDataLimitations());
        assertThat(everything).doesNotContain("image analysed by the AI")
                                .doesNotContain("photo analysed by the AI")
                                .doesNotContain("vision analysis")
                                .doesNotContain("analyzed the image");
    }

    @Test
    @DisplayName("T13b — all three mandatory limitations are always present")
    void t13b_mandatoryLimitationsAlwaysPresent() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(3L)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        DamageDetectionResponseDTO response = service.assess(request());

        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No images or photographs were analysed"))
                .anyMatch(l -> l.contains("Inspections are not linked to vehicles or job cards"))
                .anyMatch(l -> l.contains("No damage type taxonomy or severity scale"));
    }
}
