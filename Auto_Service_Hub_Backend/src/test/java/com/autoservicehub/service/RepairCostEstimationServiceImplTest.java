package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.RepairCostEstimationRequestDTO;
import com.autoservicehub.dto.RepairCostEstimationResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.RepairCostEstimationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Unit tests for {@link RepairCostEstimationServiceImpl} (SRS 5.2, FR-AI-05..08).
 *
 * <p>Pure Mockito — no Spring context, no database and no external AI call.
 * The AI provider is always mocked, so these tests never reach the network.
 *
 * <p>Test cases
 * <ul>
 *   <li>T1  valid request, no ids          → advisory response, humanReviewRequired = true</li>
 *   <li>T2  valid request with vehicleId   → vehicle context enriched in the AiRequest</li>
 *   <li>T3  serviceDescription too short   → BusinessRuleException (409)</li>
 *   <li>T4  vehicleId supplied, not found  → ResourceNotFoundException, no AI call</li>
 *   <li>T5  jobCardId supplied, not found  → ResourceNotFoundException</li>
 *   <li>T6  partId supplied, not found     → ResourceNotFoundException</li>
 *   <li>T7  provider throws                → graceful fallback, total null, no leak</li>
 *   <li>T8  Noop provider result (conf = 0)→ providerUnavailable = true, total null</li>
 *   <li>T9  rich provider result           → total/breakdown/parts/labour mapped</li>
 *   <li>T10 low confidence                 → dataLimitations calls out manual review</li>
 *   <li>T11 no customer PII in context     → context carries no name/phone/email</li>
 *   <li>T12 parts grounded in inventory    → real SKU/price/stock in context</li>
 *   <li>T13 breakdown-only result          → total derived by summing the breakdown</li>
 *   <li>T14 non-positive partIds           → BusinessRuleException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RepairCostEstimationServiceImplTest {

    @Mock  AiOrchestrationService aiOrchestrationService;
    @Mock  VehicleRepository      vehicleRepository;
    @Mock  JobCardRepository      jobCardRepository;
    @Mock  PartRepository         partRepository;

    @InjectMocks RepairCostEstimationServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private RepairCostEstimationRequestDTO validRequest() {
        RepairCostEstimationRequestDTO req = new RepairCostEstimationRequestDTO();
        req.setServiceDescription("Front brake disc and pad replacement");
        return req;
    }

    /** Mirrors NoopAiProviderClient: zero confidence + confirmation required. */
    private AiResult noopResult() {
        return new AiResult(
                AiFeatureType.REPAIR_COST_ESTIMATE,
                "AI provider not configured.",
                BigDecimal.ZERO,
                Map.of(),
                true
        );
    }

    private AiResult richResult() {
        return new AiResult(
                AiFeatureType.REPAIR_COST_ESTIMATE,
                "Estimated repair cost",
                new BigDecimal("0.82"),
                Map.of(
                    "estimatedTotalCost", new BigDecimal("8450.00"),
                    "costBreakdown", List.of(
                        Map.of("category", "LABOUR", "description", "Brake disc and pad replacement", "amount", new BigDecimal("3200.00")),
                        Map.of("category", "PARTS",  "description", "Brake pads and disc",             "amount", new BigDecimal("5250.00"))
                    )
                ),
                true
        );
    }

    // ── T1: Happy path ───────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — valid request returns an advisory estimate with human review required")
    void t1_validRequest_returnsAdvisoryResponse() {
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getEstimatedTotalCost()).isEqualByComparingTo("8450.00");
        assertThat(response.getCurrency()).isEqualTo("INR");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.isProviderUnavailable()).isFalse();
        assertThat(response.getDisclaimer()).isEqualTo(RepairCostEstimationServiceImpl.DISCLAIMER);
        // The estimate is explicitly not presented as a guaranteed price.
        assertThat(response.getDisclaimer()).containsIgnoringCase("not a guaranteed price");
    }

    @Test
    @DisplayName("T1b — the correct AiFeatureType is sent to the orchestration layer")
    void t1b_usesRepairCostEstimateFeatureType() {
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        service.estimate(validRequest());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getFeatureType())
                .isEqualTo(AiFeatureType.REPAIR_COST_ESTIMATE);
    }

    // ── T2: Domain context enrichment ────────────────────────────────────

    @Test
    @DisplayName("T2 — vehicleId supplied enriches the AI context from the database")
    void t2_vehicleId_enrichesContext() {
        Vehicle vehicle = new Vehicle();
        vehicle.setMake("Maruti");
        vehicle.setModel("Swift");
        vehicle.setYear(2019);
        vehicle.setMileage(54000);
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle));
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setVehicleId(3L);
        service.estimate(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();
        assertThat(ctx).containsEntry("vehicleMake", "Maruti")
                       .containsEntry("vehicleModel", "Swift")
                       .containsEntry("vehicleMileage", 54000);
        assertThat(captor.getValue().getVehicleId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("T2b — jobCardId supplied adds recorded service context")
    void t2b_jobCardId_addsServiceContext() {
        JobCard jobCard = new JobCard();
        jobCard.setJobCardNumber("JC-20260101120000");
        jobCard.setServiceType("BRAKES");
        jobCard.setComplaint("Squeal under braking");
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard));
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setJobCardId(7L);
        service.estimate(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getContext())
                .containsEntry("serviceType", "BRAKES")
                .containsEntry("complaint", "Squeal under braking");
    }

    @Test
    @DisplayName("T12 — parts are grounded in real inventory pricing and stock")
    void t12_parts_groundedInInventory() {
        Part part = new Part();
        part.setSku("BRK-PAD-01");
        part.setName("Front brake pad set");
        part.setSellingPrice(new BigDecimal("2600.00"));
        part.setStockQty(0);
        part.setMinStock(4);
        when(partRepository.findById(11L)).thenReturn(Optional.of(part));
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setPartIds(List.of(11L));
        service.estimate(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts =
                (List<Map<String, Object>>) captor.getValue().getContext().get("parts");
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).containsEntry("sku", "BRK-PAD-01")
                               .containsEntry("inStock", false);
    }

    // ── T3: Validation ───────────────────────────────────────────────────

    @Test
    @DisplayName("T3 — serviceDescription too short → BusinessRuleException, no AI call")
    void t3_descriptionTooShort_throwsBusinessRule() {
        RepairCostEstimationRequestDTO req = new RepairCostEstimationRequestDTO();
        req.setServiceDescription("Fix");

        assertThatThrownBy(() -> service.estimate(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("too short");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T14 — non-positive partIds → BusinessRuleException")
    void t14_nonPositivePartId_throwsBusinessRule() {
        RepairCostEstimationRequestDTO req = validRequest();
        req.setPartIds(List.of(0L));

        assertThatThrownBy(() -> service.estimate(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("partIds");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T4-T6: Entity not found ──────────────────────────────────────────

    @Test
    @DisplayName("T4 — vehicleId supplied but not found → ResourceNotFoundException")
    void t4_vehicleNotFound_throwsResourceNotFound() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.estimate(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T5 — jobCardId supplied but not found → ResourceNotFoundException")
    void t5_jobCardNotFound_throwsResourceNotFound() {
        when(jobCardRepository.findById(88L)).thenReturn(Optional.empty());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setJobCardId(88L);

        assertThatThrownBy(() -> service.estimate(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 88");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T6 — partId supplied but not found → ResourceNotFoundException")
    void t6_partNotFound_throwsResourceNotFound() {
        when(partRepository.findById(55L)).thenReturn(Optional.empty());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setPartIds(List.of(55L));

        assertThatThrownBy(() -> service.estimate(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 55");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T7/T8: Provider unavailable (fail-safe) ──────────────────────────

    @Test
    @DisplayName("T7 — provider throws → graceful fallback, no figure invented, no leak")
    void t7_providerThrows_gracefulFallback() {
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("Connection timeout; apiKey=sk-live-SUPERSECRET"));

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.isProviderUnavailable()).isTrue();
        // No fabricated figure when the provider failed.
        assertThat(response.getEstimatedTotalCost()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        // Provider internals must never reach the client.
        assertThat(response.getDisclaimer()).doesNotContain("SUPERSECRET");
        assertThat(response.getDataLimitations()).noneMatch(l -> l.contains("SUPERSECRET"));
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("unavailable"));
    }

    @Test
    @DisplayName("T8 — Noop provider result (conf 0) → providerUnavailable, total null")
    void t8_noopProvider_setsProviderUnavailable() {
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getEstimatedTotalCost()).isNull();
        assertThat(response.getCostBreakdown()).isEmpty();
        assertThat(response.isHumanReviewRequired()).isTrue();
    }

    // ── T9/T13: Response mapping ─────────────────────────────────────────

    @Test
    @DisplayName("T9 — provider breakdown is mapped into parts/labour subtotals")
    void t9_richResult_mapsBreakdown() {
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getCostBreakdown()).hasSize(2);
        assertThat(response.getPartsCost()).isEqualByComparingTo("5250.00");
        assertThat(response.getLabourCost()).isEqualByComparingTo("3200.00");
        assertThat(response.getConfidence()).isEqualByComparingTo("0.82");
        assertThat(response.getCostBreakdown())
                .extracting(RepairCostEstimationResponseDTO.CostItemDTO::getCategory)
                .containsExactly("LABOUR", "PARTS");
    }

    @Test
    @DisplayName("T13 — when the provider returns no total, it is derived from the breakdown")
    void t13_totalDerivedFromBreakdown() {
        AiResult noTotal = new AiResult(
                AiFeatureType.REPAIR_COST_ESTIMATE,
                "Estimated repair cost",
                new BigDecimal("0.70"),
                Map.of("costBreakdown", List.of(
                        Map.of("category", "LABOUR", "description", "Labour", "amount", new BigDecimal("1000.00")),
                        Map.of("category", "PARTS",  "description", "Parts",  "amount", new BigDecimal("2000.00"))
                )),
                true
        );
        when(aiOrchestrationService.process(any())).thenReturn(noTotal);

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getEstimatedTotalCost()).isEqualByComparingTo("3000.00");
    }

    @Test
    @DisplayName("T9b — malformed breakdown lines are skipped rather than emitted")
    void t9b_malformedLinesSkipped() {
        AiResult messy = new AiResult(
                AiFeatureType.REPAIR_COST_ESTIMATE,
                "Estimated",
                new BigDecimal("0.90"),
                Map.of("costBreakdown", List.of(
                        Map.of("category", "PARTS", "description", "Good line", "amount", new BigDecimal("500.00")),
                        Map.of("category", "PARTS", "description", "Bad line",  "amount", "not-a-number")
                )),
                true
        );
        when(aiOrchestrationService.process(any())).thenReturn(messy);

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getCostBreakdown()).hasSize(1);
        assertThat(response.getCostBreakdown().get(0).getDescription()).isEqualTo("Good line");
    }

    // ── T10: Low confidence / human review ───────────────────────────────

    @Test
    @DisplayName("T10 — low confidence is flagged and manual review is called out")
    void t10_lowConfidence_flagsManualReview() {
        AiResult lowConf = new AiResult(
                AiFeatureType.REPAIR_COST_ESTIMATE,
                "Rough guess",
                new BigDecimal("0.20"),
                Map.of("estimatedTotalCost", new BigDecimal("1000.00")),
                true
        );
        when(aiOrchestrationService.process(any())).thenReturn(lowConf);

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getConfidence()).isEqualByComparingTo("0.20");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.toLowerCase().contains("low confidence"));
    }

    @Test
    @DisplayName("T10b — missing required data is reported instead of being invented")
    void t10b_missingDataReportedAsLimitations() {
        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationResponseDTO response = service.estimate(validRequest());

        assertThat(response.getDataLimitations()).isNotEmpty();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No vehicle was supplied"));
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No job card was supplied"));
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No parts were supplied"));
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("no labour-rate table"));
    }

    // ── T11: PII minimisation ────────────────────────────────────────────

    @Test
    @DisplayName("T11 — no customer PII is sent to the AI provider")
    void t11_noCustomerPiiInContext() {
        Vehicle vehicle = new Vehicle();
        vehicle.setMake("Maruti");
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle));

        JobCard jobCard = new JobCard();
        jobCard.setServiceType("BRAKES");
        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jobCard));

        when(aiOrchestrationService.process(any())).thenReturn(richResult());

        RepairCostEstimationRequestDTO req = validRequest();
        req.setVehicleId(3L);
        req.setJobCardId(7L);
        service.estimate(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        String ctx = captor.getValue().getContext().toString();

        // Customer identity fields must never be forwarded to an external AI.
        assertThat(ctx).doesNotContain("customerName")
                       .doesNotContain("customerPhone")
                       .doesNotContain("@");
        assertThat(captor.getValue().getContext())
                .doesNotContainKey("customerName")
                .doesNotContainKey("customerPhone")
                .doesNotContainKey("email")
                .doesNotContainKey("address");
    }
}
