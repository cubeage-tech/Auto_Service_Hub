package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.DiagnosisRequestDTO;
import com.autoservicehub.dto.DiagnosisResponseDTO;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.VehicleDiagnosisServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VehicleDiagnosisServiceImpl}.
 * Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * T1  Valid request without vehicleId     → success, humanReviewRequired = true
 * T2  Valid request with vehicleId        → vehicle context enriched in AiRequest
 * T3  symptoms too short                 → BusinessRuleException
 * T4  symptoms null / blank              → BusinessRuleException (via @NotBlank guard)
 * T5  vehicleId supplied but not found   → ResourceNotFoundException
 * T6  AI provider throws RuntimeException → graceful fallback, providerUnavailable = true
 * T7  NoopAiProviderClient result (conf=0)→ providerUnavailable = true, humanReviewRequired always true
 * T8  Real provider result with details  → possibleIssue / recommendation extracted from details map
 */
@ExtendWith(MockitoExtension.class)
class VehicleDiagnosisServiceImplTest {

    @Mock  AiOrchestrationService aiOrchestrationService;
    @Mock  VehicleRepository      vehicleRepository;

    @InjectMocks
    VehicleDiagnosisServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private DiagnosisRequestDTO validRequest() {
        DiagnosisRequestDTO req = new DiagnosisRequestDTO();
        req.setSymptoms("Engine knocking noise above 3000 RPM");
        return req;
    }

    private AiResult noopResult() {
        return new AiResult(
                AiFeatureType.VEHICLE_DIAGNOSIS,
                "AI provider not configured.",
                BigDecimal.ZERO,
                Map.of(),
                true
        );
    }

    private AiResult richResult() {
        return new AiResult(
                AiFeatureType.VEHICLE_DIAGNOSIS,
                "Possible engine bearing wear",
                new BigDecimal("0.82"),
                Map.of(
                    "possibleIssue",  "Engine bearing wear / loose timing chain",
                    "recommendation", "Perform compression test and inspect timing chain tension"
                ),
                true
        );
    }

    // ── T1: Valid request, no vehicleId → success ─────────────────────────

    @Test
    @DisplayName("T1 — valid request without vehicleId returns advisory diagnosis")
    void t1_validRequestNoVehicleId_returnsAdvisoryResponse() {
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        DiagnosisResponseDTO response = service.diagnose(validRequest());

        assertThat(response).isNotNull();
        assertThat(response.isHumanReviewRequired()).isTrue();                  // SRS BR-09
        assertThat(response.getDisclaimer()).isEqualTo(VehicleDiagnosisServiceImpl.DISCLAIMER);
        assertThat(response.getGeneratedAt()).isNotNull();
        assertThat(response.getVehicleId()).isNull();
        verifyNoInteractions(vehicleRepository);
    }

    // ── T2: Valid request with vehicleId → vehicle context added ─────────

    @Test
    @DisplayName("T2 — valid request with vehicleId enriches AI context with vehicle data")
    void t2_validRequestWithVehicleId_vehicleDataAddedToContext() {
        Vehicle vehicle = new Vehicle();
        vehicle.setMake("Toyota");
        vehicle.setModel("Fortuner");
        vehicle.setYear(2020);
        vehicle.setMileage(55000);
        vehicle.setRegistrationNo("UP-32-GH-1190");

        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        DiagnosisRequestDTO req = validRequest();
        req.setVehicleId(5L);

        DiagnosisResponseDTO response = service.diagnose(req);

        assertThat(response.getVehicleId()).isEqualTo(5L);
        assertThat(response.isHumanReviewRequired()).isTrue();

        // Verify the AiRequest context included the vehicle fields
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();
        assertThat(ctx).containsEntry("vehicleMake",  "Toyota");
        assertThat(ctx).containsEntry("vehicleModel", "Fortuner");
        assertThat(ctx).containsEntry("vehicleYear",  2020);
        assertThat(ctx).containsEntry("vehicleMileage", 55000);
        assertThat(ctx).containsEntry("registrationNo", "UP-32-GH-1190");
        assertThat(ctx).containsKey("symptoms");
    }

    // ── T3: symptoms too short → BusinessRuleException ───────────────────

    @Test
    @DisplayName("T3 — symptoms shorter than 5 characters throws BusinessRuleException")
    void t3_symptomsToShort_throwsBusinessRuleException() {
        DiagnosisRequestDTO req = new DiagnosisRequestDTO();
        req.setSymptoms("Hi");   // only 2 chars

        assertThatThrownBy(() -> service.diagnose(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("symptoms is too short");

        verifyNoInteractions(vehicleRepository, aiOrchestrationService);
    }

    // ── T4: symptoms null → NPE-safe guard ───────────────────────────────

    @Test
    @DisplayName("T4 — null symptoms handled safely as BusinessRuleException (service-level guard)")
    void t4_nullSymptoms_treatedAsTooShort() {
        DiagnosisRequestDTO req = new DiagnosisRequestDTO();
        req.setSymptoms(null);   // @NotBlank would catch this before service, but service is also safe

        assertThatThrownBy(() -> service.diagnose(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("symptoms is too short");
    }

    // ── T5: vehicleId supplied but vehicle not found → 404 ───────────────

    @Test
    @DisplayName("T5 — vehicleId supplied but not found in DB throws ResourceNotFoundException")
    void t5_vehicleNotFound_throwsResourceNotFoundException() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        DiagnosisRequestDTO req = validRequest();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.diagnose(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T6: Provider throws RuntimeException → graceful fallback ─────────

    @Test
    @DisplayName("T6 — AI provider throws RuntimeException → providerUnavailable=true, no exception propagated")
    void t6_providerThrowsException_gracefulFallback() {
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("Connection timeout to AI provider"));

        DiagnosisResponseDTO response = service.diagnose(validRequest());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.isHumanReviewRequired()).isTrue();              // still true
        assertThat(response.getDisclaimer()).isEqualTo(VehicleDiagnosisServiceImpl.DISCLAIMER);
        // Provider error details must NOT be exposed to the client
        assertThat(response.getPossibleIssue()).doesNotContain("Connection timeout");
    }

    // ── T7: NoopAiProviderClient result (confidence=0) → providerUnavailable ──

    @Test
    @DisplayName("T7 — NoopAiProviderClient result (conf=0, requiresHumanConfirmation=true) sets providerUnavailable")
    void t7_noopProviderResult_setsProviderUnavailableFlag() {
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        DiagnosisResponseDTO response = service.diagnose(validRequest());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getConfidence()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.isHumanReviewRequired()).isTrue();
    }

    // ── T8: Rich provider result → details map used for possibleIssue/recommendation ──

    @Test
    @DisplayName("T8 — real provider result with details map populates possibleIssue and recommendation")
    void t8_richProviderResult_extractsDetailsFromMap() {
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        DiagnosisResponseDTO response = service.diagnose(validRequest());

        assertThat(response.isProviderUnavailable()).isFalse();
        assertThat(response.getConfidence()).isEqualByComparingTo(new BigDecimal("0.82"));
        assertThat(response.getPossibleIssue())
                .isEqualTo("Engine bearing wear / loose timing chain");
        assertThat(response.getRecommendation())
                .contains("compression test");
        assertThat(response.isHumanReviewRequired()).isTrue();              // always true
        assertThat(response.getDisclaimer()).isEqualTo(VehicleDiagnosisServiceImpl.DISCLAIMER);
    }

    // ── T9: inspectionFindings and technicianNotes included in context ────

    @Test
    @DisplayName("T9 — inspectionFindings and technicianNotes are forwarded to AI context when present")
    void t9_optionalFields_addedToContext() {
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        DiagnosisRequestDTO req = validRequest();
        req.setInspectionFindings("Low engine oil. Belt appears loose.");
        req.setTechnicianNotes("Vehicle has not been serviced in 18 months.");

        service.diagnose(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();
        assertThat(ctx).containsKey("inspectionFindings");
        assertThat(ctx).containsKey("technicianNotes");
    }
}
