package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.MaintenancePredictionRequestDTO;
import com.autoservicehub.dto.MaintenancePredictionResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.MaintenancePredictionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MaintenancePredictionServiceImpl} (SRS 5.3, FR-AI-09..12).
 *
 * <p>Pure Mockito — no Spring context, no database and no external AI call.
 * The AI provider is always mocked, so these tests never reach the network.
 *
 * <p>Test cases: T1 valid request, T2 correct feature type, T3 vehicle context,
 * T4 service history grounding, T5 vehicle not found, T6 no service history,
 * T7 thin history, T8 provider throws, T9 noop provider, T10 rich result,
 * T11 low confidence, T12 missing due date not fabricated, T13 unparseable date,
 * T14 no PII in context, T15 priority normalisation.
 */
@ExtendWith(MockitoExtension.class)
class MaintenancePredictionServiceImplTest {

    @Mock AiOrchestrationService aiOrchestrationService;
    @Mock VehicleRepository      vehicleRepository;
    @Mock JobCardRepository      jobCardRepository;
    @Mock AppointmentRepository  appointmentRepository;

    @InjectMocks MaintenancePredictionServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private MaintenancePredictionRequestDTO request() {
        MaintenancePredictionRequestDTO req = new MaintenancePredictionRequestDTO();
        req.setVehicleId(3L);
        return req;
    }

    private Vehicle vehicle(Integer mileage) {
        Vehicle v = new Vehicle();
        v.setMake("Maruti");
        v.setModel("Swift");
        v.setYear(2019);
        v.setMileage(mileage);
        return v;
    }

    private JobCard deliveredJob(String serviceType, LocalDateTime completed, Integer odo) {
        JobCard jc = new JobCard();
        jc.setStatus("DELIVERED");
        jc.setServiceType(serviceType);
        jc.setCompletedDate(completed);
        jc.setOdometerReading(odo);
        return jc;
    }

    /** Mirrors NoopAiProviderClient: zero confidence + confirmation required. */
    private AiResult noopResult() {
        return new AiResult(AiFeatureType.MAINTENANCE_PREDICTION,
                "AI provider not configured.", BigDecimal.ZERO, Map.of(), true);
    }

    private AiResult richResult() {
        return new AiResult(AiFeatureType.MAINTENANCE_PREDICTION,
                "Routine service due soon",
                new BigDecimal("0.78"),
                Map.of("predictedItems", List.of(
                        Map.of("item", "Engine oil and filter change", "priority", "MEDIUM",
                              "dueByMileage", 55000, "dueByDate", "2026-01-15T00:00:00",
                              "rationale", "Last oil change was 12000 km ago"),
                        Map.of("item", "Brake fluid flush", "priority", "HIGH",
                              "dueByMileage", 50000)
                )),
                true);
    }

    // ── T1/T2: Happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — valid request returns an advisory prediction with human review required")
    void t1_validRequest_returnsAdvisoryPrediction() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 42000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getVehicleId()).isEqualTo(3L);
        assertThat(response.getSummary()).isEqualTo("Routine service due soon");
        assertThat(response.getConfidence()).isEqualByComparingTo("0.78");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.isProviderUnavailable()).isFalse();
        assertThat(response.getDisclaimer()).isEqualTo(MaintenancePredictionServiceImpl.DISCLAIMER);
        assertThat(response.getDisclaimer()).containsIgnoringCase("not a service schedule");
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("T2 — the correct AiFeatureType is sent to the orchestration layer")
    void t2_usesMaintenancePredictionFeatureType() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        service.predict(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getFeatureType())
                .isEqualTo(AiFeatureType.MAINTENANCE_PREDICTION);
        assertThat(captor.getValue().getVehicleId()).isEqualTo(3L);
    }

    // ── T3/T4: Domain context grounding ──────────────────────────────────

    @Test
    @DisplayName("T3 — vehicle facts and real service history are sent to the provider")
    void t3_contextGroundedInRealData() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 42000),
                deliveredJob("BRAKES", LocalDateTime.of(2025, 6, 1, 9, 0), 38000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        service.predict(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();

        assertThat(ctx).containsEntry("vehicleMake", "Maruti")
                       .containsEntry("vehicleModel", "Swift")
                       .containsEntry("currentMileage", 54000)
                       .containsEntry("totalJobCards", 2)
                       .containsEntry("completedJobCards", 2)
                       .containsEntry("lastRecordedOdometer", 42000);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history =
                (List<Map<String, Object>>) ctx.get("recentCompletedServices");
        assertThat(history).hasSize(2);
        assertThat(history.get(0)).containsEntry("serviceType", "PERIODIC");
    }

    @Test
    @DisplayName("T4 — appointment history is forwarded when present")
    void t4_appointmentHistoryForwarded() {
        Appointment appt = new Appointment();
        appt.setServiceType("SERVICE");
        appt.setStatus("COMPLETED");
        appt.setAppointmentAt(LocalDateTime.of(2025, 10, 1, 11, 0));

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L))
                .thenReturn(List.of(appt));
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        service.predict(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getContext()).containsEntry("appointmentCount", 1);
    }

    // ── T5: Vehicle not found ────────────────────────────────────────────

    @Test
    @DisplayName("T5 — vehicleId supplied but not found → ResourceNotFoundException")
    void t5_vehicleNotFound_throwsResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        MaintenancePredictionRequestDTO req = new MaintenancePredictionRequestDTO();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T5b — null vehicleId is rejected before any AI call")
    void t5b_nullVehicleId_rejected() {
        MaintenancePredictionRequestDTO req = new MaintenancePredictionRequestDTO();

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(aiOrchestrationService, vehicleRepository);
    }

    // ── T6/T7: Insufficient history ──────────────────────────────────────

    @Test
    @DisplayName("T6 — no service history → no history fabricated, gap reported")
    void t6_noServiceHistory_reportsLimitation() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(12000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        MaintenancePredictionResponseDTO response = service.predict(request());

        // Real counts only — no invented history.
        assertThat(response.getServiceHistorySummary().getTotalJobCards()).isZero();
        assertThat(response.getServiceHistorySummary().getCompletedJobCards()).isZero();
        assertThat(response.getServiceHistorySummary().getLastServicedAt()).isNull();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No completed service is recorded"));
    }

    @Test
    @DisplayName("T7 — thin history is flagged as unreliable and triggers review")
    void t7_thinHistory_flaggedUnreliable() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(20000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 18000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("Only 1 completed service"));
        assertThat(response.isHumanReviewRequired()).isTrue();
    }

    // ── T8/T9: Provider unavailable (fail-safe) ──────────────────────────

    @Test
    @DisplayName("T8 — provider throws → no prediction fabricated, no leak")
    void t8_providerThrows_noFabrication() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 42000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("timeout; apiKey=sk-live-SUPERSECRET"));

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getPredictedItems()).isEmpty();
        assertThat(response.getSummary()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        // No invented dates.
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("unavailable"));
        assertThat(response.getDisclaimer()).doesNotContain("SUPERSECRET");
        assertThat(response.getDataLimitations()).noneMatch(l -> l.contains("SUPERSECRET"));
    }

    @Test
    @DisplayName("T9 — noop provider result → providerUnavailable, no items")
    void t9_noopProvider_setsProviderUnavailable() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getPredictedItems()).isEmpty();
        assertThat(response.getSummary()).isNull();
        // History summary is still factual and available even without a provider.
        assertThat(response.getServiceHistorySummary()).isNotNull();
    }

    // ── T10: Response mapping ────────────────────────────────────────────

    @Test
    @DisplayName("T10 — predicted items are mapped with priority, mileage and date")
    void t10_richResult_mapsItems() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 42000),
                deliveredJob("BRAKES", LocalDateTime.of(2025, 6, 1, 9, 0), 38000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getPredictedItems()).hasSize(2);
        MaintenancePredictionResponseDTO.MaintenanceItemDTO first = response.getPredictedItems().get(0);
        assertThat(first.getItem()).isEqualTo("Engine oil and filter change");
        assertThat(first.getPriority()).isEqualTo("MEDIUM");
        assertThat(first.getDueByMileage()).isEqualTo(55000);
        assertThat(first.getDueByDate()).isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 0));
        assertThat(first.getRationale()).isEqualTo("Last oil change was 12000 km ago");
    }

    @Test
    @DisplayName("T11 — low confidence is flagged and human review is called out")
    void t11_lowConfidence_flagsManualReview() {
        AiResult lowConf = new AiResult(AiFeatureType.MAINTENANCE_PREDICTION, "Rough",
                new BigDecimal("0.20"),
                Map.of("predictedItems", List.of(Map.of("item", "Oil change"))), true);

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(lowConf);

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getConfidence()).isEqualByComparingTo("0.20");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.toLowerCase().contains("low confidence"));
    }

    // ── T12/T13: Nothing fabricated ──────────────────────────────────────

    @Test
    @DisplayName("T12 — fields the provider omits stay null (no invented date or priority)")
    void t12_missingFieldsNotFabricated() {
        AiResult sparse = new AiResult(AiFeatureType.MAINTENANCE_PREDICTION, "Something",
                new BigDecimal("0.60"),
                Map.of("predictedItems", List.of(
                        Map.of("item", "Transmission oil change"))),   // nothing else supplied
                true);

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(sparse);

        MaintenancePredictionResponseDTO response = service.predict(request());

        MaintenancePredictionResponseDTO.MaintenanceItemDTO item = response.getPredictedItems().get(0);
        assertThat(item.getItem()).isEqualTo("Transmission oil change");
        assertThat(item.getPriority()).isNull();      // not defaulted
        assertThat(item.getDueByMileage()).isNull();  // not defaulted
        assertThat(item.getDueByDate()).isNull();     // not defaulted to today
    }

    @Test
    @DisplayName("T13 — an unparseable due date is dropped, not guessed")
    void t13_unparseableDateDropped() {
        AiResult messy = new AiResult(AiFeatureType.MAINTENANCE_PREDICTION, "Something",
                new BigDecimal("0.70"),
                Map.of("predictedItems", List.of(
                        Map.of("item", "Coolant flush", "dueByDate", "sometime next year"))), true);

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(messy);

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getPredictedItems().get(0).getDueByDate()).isNull();
    }

    @Test
    @DisplayName("T15 — provider priority values are normalised")
    void t15_priorityNormalised() {
        AiResult varied = new AiResult(AiFeatureType.MAINTENANCE_PREDICTION, "Mixed",
                new BigDecimal("0.90"),
                Map.of("predictedItems", List.of(
                        Map.of("item", "A", "priority", "critical"),
                        Map.of("item", "B", "priority", "minor"))), true);

        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of());
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(varied);

        MaintenancePredictionResponseDTO response = service.predict(request());

        assertThat(response.getPredictedItems().get(0).getPriority()).isEqualTo("URGENT");
        assertThat(response.getPredictedItems().get(1).getPriority()).isEqualTo("LOW");
    }

    // ── T14: PII minimisation ────────────────────────────────────────────

    @Test
    @DisplayName("T14 — no customer PII is sent to the AI provider")
    void t14_noCustomerPiiInContext() {
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle(54000)));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(3L)).thenReturn(List.of(
                deliveredJob("PERIODIC", LocalDateTime.of(2025, 11, 2, 10, 0), 42000)));
        when(appointmentRepository.findByVehicleIdOrderByAppointmentAtDesc(3L)).thenReturn(List.of());
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        service.predict(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        String ctx = captor.getValue().getContext().toString();

        assertThat(ctx).doesNotContain("customerName")
                       .doesNotContain("customerPhone")
                       .doesNotContain("@")
                       .doesNotContain("email");
    }
}
