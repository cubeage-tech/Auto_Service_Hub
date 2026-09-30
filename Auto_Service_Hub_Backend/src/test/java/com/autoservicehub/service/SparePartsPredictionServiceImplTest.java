package com.autoservicehub.service;

import com.autoservicehub.ai.AiFeatureType;
import com.autoservicehub.ai.AiOrchestrationService;
import com.autoservicehub.ai.AiRequest;
import com.autoservicehub.ai.AiResult;
import com.autoservicehub.dto.SparePartsPredictionRequestDTO;
import com.autoservicehub.dto.SparePartsPredictionResponseDTO;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.SparePartsPredictionServiceImpl;
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
 * Unit tests for {@link SparePartsPredictionServiceImpl}
 * (SRS 5.5, FR-AI-21..24).
 *
 * <p>Pure Mockito — no Spring context, no database, no external AI call.
 *
 * <p>Test cases: T1 valid prediction, T2 feature type, T3 catalogue grounding,
 * T4 vehicle/job card not found, T5 part not found, T6 empty catalogue,
 * T7 provider throws, T8 noop provider, T9 hallucinated part id discarded,
 * T10 missing quantity not fabricated, T11 low confidence, T12 PII minimisation,
 * T13 no stock/inventory modification.
 */
@ExtendWith(MockitoExtension.class)
class SparePartsPredictionServiceImplTest {

    @Mock AiOrchestrationService aiOrchestrationService;
    @Mock PartRepository         partRepository;
    @Mock JobCardRepository      jobCardRepository;
    @Mock VehicleRepository      vehicleRepository;

    @InjectMocks SparePartsPredictionServiceImpl service;

    // ── Helpers ──────────────────────────────────────────────────────────

    private SparePartsPredictionRequestDTO request() {
        SparePartsPredictionRequestDTO req = new SparePartsPredictionRequestDTO();
        req.setServiceDescription("Front brake disc and pad replacement");
        return req;
    }

    private Part part(Long id, String sku, String name, Integer stock, Integer minStock) {
        Part p = new Part();
        p.setId(id);
        p.setSku(sku);
        p.setName(name);
        p.setUnit("set");
        p.setSellingPrice(new BigDecimal("2600.00"));
        p.setStockQty(stock);
        p.setMinStock(minStock);
        return p;
    }

    private AiResult validResult() {
        return new AiResult(AiFeatureType.PARTS_PREDICTION,
                "Brake pads and a disc are typically required",
                new BigDecimal("0.69"),
                Map.of("predictedParts", List.of(
                        Map.of("partId", 4L, "partName", "Front brake pad set",
                              "predictedQuantity", 1, "rationale", "Pads are replaced with discs."),
                        Map.of("partId", 5L, "partName", "Brake disc",
                              "rationale", "Disc is scored.")   // no quantity supplied
                )),
                true);
    }

    private AiResult noopResult() {
        return new AiResult(AiFeatureType.PARTS_PREDICTION,
                "AI provider not configured.", BigDecimal.ZERO, Map.of(), true);
    }

    // ── T1/T2: Happy path ────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — valid request returns parts matched to the real catalogue")
    void t1_validRequest_returnsMatchedParts() {
        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4),
                part(5L, "BRK-DISC-01", "Brake disc", 0, 2)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.getPredictedParts()).hasSize(2);
        SparePartsPredictionResponseDTO.PredictedPartDTO pads = response.getPredictedParts().get(0);
        // Part identity is resolved from the real catalogue record.
        assertThat(pads.getPartId()).isEqualTo(4L);
        assertThat(pads.getPartSku()).isEqualTo("BRK-PAD-01");
        assertThat(pads.getPartName()).isEqualTo("Front brake pad set");
        assertThat(pads.getPredictedQuantity()).isEqualTo(1);
        assertThat(pads.getUnit()).isEqualTo("set");
        assertThat(pads.getCurrentStockQty()).isEqualTo(12);
        assertThat(pads.getInStock()).isTrue();
        assertThat(pads.getRationale()).isEqualTo("Pads are replaced with discs.");

        assertThat(response.getConfidence()).isEqualByComparingTo("0.69");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.isInventoryModified()).isFalse();
        assertThat(response.getDisclaimer()).isEqualTo(SparePartsPredictionServiceImpl.DISCLAIMER);
    }

    @Test
    @DisplayName("T1b — an out-of-stock predicted part is flagged, not hidden")
    void t1b_outOfStockPartIsFlagged() {
        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4),
                part(5L, "BRK-DISC-01", "Brake disc", 0, 2)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        SparePartsPredictionResponseDTO response = service.predict(request());

        SparePartsPredictionResponseDTO.PredictedPartDTO disc = response.getPredictedParts().get(1);
        assertThat(disc.getCurrentStockQty()).isZero();
        assertThat(disc.getInStock()).isFalse();
        assertThat(response.getInventorySnapshot().getOutOfStockCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("T2 — the correct AiFeatureType is sent to the orchestration layer")
    void t2_usesPartsPredictionFeatureType() {
        when(partRepository.findAll()).thenReturn(List.of(part(4L, "BRK-PAD-01", "Pads", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        service.predict(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        assertThat(captor.getValue().getFeatureType()).isEqualTo(AiFeatureType.PARTS_PREDICTION);
    }

    // ── T3: Domain context grounding ──────────────────────────────────────

    @Test
    @DisplayName("T3 — real catalogue, job card and vehicle data are sent to the provider")
    void t3_contextGroundedInRealData() {
        Vehicle v = new Vehicle();
        v.setMake("Maruti");
        v.setModel("Swift");
        JobCard jc = new JobCard();
        jc.setServiceType("BRAKES");
        jc.setStatus("RECEIVED");

        when(jobCardRepository.findById(7L)).thenReturn(Optional.of(jc));
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(v));
        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        SparePartsPredictionRequestDTO req = request();
        req.setJobCardId(7L);
        req.setVehicleId(3L);
        service.predict(req);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiOrchestrationService).process(captor.capture());
        Map<String, Object> ctx = captor.getValue().getContext();

        assertThat(ctx).containsEntry("serviceType", "BRAKES")
                       .containsEntry("vehicleModel", "Swift")
                       .containsEntry("availablePartCount", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) ctx.get("availableParts");
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0)).containsEntry("sku", "BRK-PAD-01")
                                .containsEntry("stockQty", 12);
    }

    // ── T4/T5: Not found and validation ──────────────────────────────────

    @Test
    @DisplayName("T4 — job card not found → ResourceNotFoundException")
    void t4_jobCardNotFound_throws() {
        when(jobCardRepository.findById(99L)).thenReturn(Optional.empty());
        SparePartsPredictionRequestDTO req = request();
        req.setJobCardId(99L);

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("JobCard not found: 99");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T4b — vehicle not found → ResourceNotFoundException")
    void t4b_vehicleNotFound_throws() {
        when(vehicleRepository.findById(99L)).thenReturn(Optional.empty());
        SparePartsPredictionRequestDTO req = request();
        req.setVehicleId(99L);

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 99");
    }

    @Test
    @DisplayName("T5 — requested part not found → ResourceNotFoundException, no AI call")
    void t5_partNotFound_throws() {
        when(partRepository.findById(77L)).thenReturn(Optional.empty());
        SparePartsPredictionRequestDTO req = request();
        req.setPartIds(List.of(77L));

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 77");

        verifyNoInteractions(aiOrchestrationService);
    }

    @Test
    @DisplayName("T5b — description too short → BusinessRuleException")
    void t5b_descriptionTooShort_rejected() {
        SparePartsPredictionRequestDTO req = new SparePartsPredictionRequestDTO();
        req.setServiceDescription("Fix");

        assertThatThrownBy(() -> service.predict(req))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("too short");

        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T6: Empty catalogue ──────────────────────────────────────────────

    @Test
    @DisplayName("T6 — empty catalogue → no parts predicted, no fabrication")
    void t6_emptyCatalogue_noPrediction() {
        when(partRepository.findAll()).thenReturn(List.of());

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.getPredictedParts()).isEmpty();
        assertThat(response.getSummary()).isNull();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.isInventoryModified()).isFalse();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("No parts exist in the catalogue"));
        verifyNoInteractions(aiOrchestrationService);
    }

    // ── T7/T8: Provider unavailable ──────────────────────────────────────

    @Test
    @DisplayName("T7 — provider throws → no parts predicted, no leak")
    void t7_providerThrows_noPrediction() {
        when(partRepository.findAll()).thenReturn(List.of(part(4L, "BRK-PAD-01", "Pads", 12, 4)));
        when(aiOrchestrationService.process(any()))
                .thenThrow(new RuntimeException("timeout; apiKey=sk-live-SUPERSECRET"));

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getPredictedParts()).isEmpty();
        assertThat(response.getConfidence()).isNull();
        assertThat(response.getSummary()).isNull();
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDisclaimer()).doesNotContain("SUPERSECRET");
        assertThat(response.getDataLimitations()).noneMatch(l -> l.contains("SUPERSECRET"));
    }

    @Test
    @DisplayName("T8 — noop provider result → providerUnavailable, empty parts")
    void t8_noopProvider_noPrediction() {
        when(partRepository.findAll()).thenReturn(List.of(part(4L, "BRK-PAD-01", "Pads", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(noopResult());

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.isProviderUnavailable()).isTrue();
        assertThat(response.getPredictedParts()).isEmpty();
        // The factual inventory snapshot is still available without a provider.
        assertThat(response.getInventorySnapshot().getCatalogueSize()).isEqualTo(1);
    }

    // ── T9: Hallucinated part id is discarded ───────────────────────────

    @Test
    @DisplayName("T9 — provider names a nonexistent part id → discarded, not substituted")
    void t9_hallucinatedPartId_discarded() {
        AiResult hallucinated = new AiResult(AiFeatureType.PARTS_PREDICTION,
                "Needs pads",
                new BigDecimal("0.80"),
                Map.of("predictedParts", List.of(
                        Map.of("partId", 4L, "partName", "Front brake pad set"),
                        Map.of("partId", 9999L, "partName", "Magical turbo widget"))),
                true);

        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(hallucinated);

        SparePartsPredictionResponseDTO response = service.predict(request());

        // Only the real catalogue part survives.
        assertThat(response.getPredictedParts()).hasSize(1);
        assertThat(response.getPredictedParts().get(0).getPartId()).isEqualTo(4L);
        // The invented part is reported, and no substitute was chosen.
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("could not be matched to a real catalogue record"));
        assertThat(response.isHumanReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("T9b — a part suggested by name with no id is not invented into the catalogue")
    void t9b_partWithoutId_notFabricated() {
        AiResult byNameOnly = new AiResult(AiFeatureType.PARTS_PREDICTION,
                "Needs something",
                new BigDecimal("0.75"),
                Map.of("predictedParts", List.of(
                        Map.of("partName", "Some part that is not in the catalogue"))),
                true);

        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(byNameOnly);

        SparePartsPredictionResponseDTO response = service.predict(request());

        // No part id is invented to match the name.
        assertThat(response.getPredictedParts()).isEmpty();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.contains("could not be matched to a real catalogue record"));
    }

    // ── T10: No fabricated quantity ──────────────────────────────────────

    @Test
    @DisplayName("T10 — a quantity the provider omits stays null, never 0 or 1")
    void t10_missingQuantity_notFabricated() {
        when(partRepository.findAll()).thenReturn(List.of(
                part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4),
                part(5L, "BRK-DISC-01", "Brake disc", 0, 2)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        SparePartsPredictionResponseDTO response = service.predict(request());

        // The second predicted part had no quantity in the provider output.
        assertThat(response.getPredictedParts().get(1).getPredictedQuantity()).isNull();
        assertThat(response.getPredictedParts().get(0).getPredictedQuantity()).isEqualTo(1);
    }

    // ── T11: Low confidence ──────────────────────────────────────────────

    @Test
    @DisplayName("T11 — low confidence is flagged and manual review is called out")
    void t11_lowConfidence_flagsManualReview() {
        AiResult lowConf = new AiResult(AiFeatureType.PARTS_PREDICTION, "Guess",
                new BigDecimal("0.15"),
                Map.of("predictedParts", List.of(Map.of("partId", 4L))), true);

        when(partRepository.findAll()).thenReturn(List.of(part(4L, "BRK-PAD-01", "Pads", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(lowConf);

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.getConfidence()).isEqualByComparingTo("0.15");
        assertThat(response.isHumanReviewRequired()).isTrue();
        assertThat(response.getDataLimitations())
                .anyMatch(l -> l.toLowerCase().contains("low confidence"));
    }

    // ── T12: PII minimisation ────────────────────────────────────────────

    @Test
    @DisplayName("T12 — no customer PII is sent to the AI provider")
    void t12_noCustomerPiiInContext() {
        when(partRepository.findAll()).thenReturn(List.of(part(4L, "BRK-PAD-01", "Pads", 12, 4)));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        service.predict(request());

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

    // ── T13: The prediction must NOT modify inventory ───────────────────

    @Test
    @DisplayName("T13 — a prediction never modifies stock, movements or job cards")
    void t13_predictionDoesNotModifyInventory() {
        Part tracked = part(4L, "BRK-PAD-01", "Front brake pad set", 12, 4);
        when(partRepository.findAll()).thenReturn(List.of(tracked));
        when(aiOrchestrationService.process(any())).thenReturn(validResult());

        SparePartsPredictionResponseDTO response = service.predict(request());

        assertThat(response.isInventoryModified()).isFalse();

        // Stock quantity must be untouched — no deduction, no reservation.
        assertThat(tracked.getStockQty()).isEqualTo(12);
        // Nothing may be written to parts or job cards.
        verify(partRepository, never()).save(any(Part.class));
        verify(partRepository, never()).saveAll(any());
        verify(partRepository, never()).deleteById(anyLong());
        verify(jobCardRepository, never()).save(any(JobCard.class));
        verify(jobCardRepository, never()).deleteById(anyLong());
    }
}
