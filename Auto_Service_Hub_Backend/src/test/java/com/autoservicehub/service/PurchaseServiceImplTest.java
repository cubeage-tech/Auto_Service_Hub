package com.autoservicehub.service;

import com.autoservicehub.dto.PurchaseItemRequestDTO;
import com.autoservicehub.dto.PurchaseRequestDTO;
import com.autoservicehub.dto.StockMovementRequestDTO;
import com.autoservicehub.entity.Part;
import com.autoservicehub.entity.Purchase;
import com.autoservicehub.entity.PurchaseItem;
import com.autoservicehub.entity.Supplier;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.PurchaseItemRepository;
import com.autoservicehub.repository.PurchaseRepository;
import com.autoservicehub.repository.SupplierRepository;
import com.autoservicehub.service.impl.PurchaseServiceImpl;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PurchaseServiceImpl} - order validation, server-side
 * totals and goods receipt (FR-INV-2).
 * Pure Mockito - no Spring context, no database.
 *
 * <p>The receipt tests assert that stock is moved by delegating to
 * {@link StockMovementService}; the persistence of that delegation (real stock
 * increase, real ledger rows, real rollback) is covered by
 * {@code PurchasingPersistenceTest}.
 *
 * Test cases
 * ----------
 * PU1  Create one item       -> purchase PENDING, one line, server-side total
 * PU2  Create many items     -> every line written, total is their sum
 * PU3  Zero unit price       -> accepted, total is zero
 * PU4  Unknown supplier      -> ResourceNotFoundException, nothing written
 * PU5  Unknown part          -> ResourceNotFoundException, no lines written
 * PU6  Zero quantity         -> BusinessRuleException
 * PU7  Negative quantity     -> BusinessRuleException
 * PU8  Negative unit price   -> BusinessRuleException
 * PU9  No items              -> BusinessRuleException
 * PU10 Receiving raises one IN movement per line
 * PU11 Receiving marks the purchase RECEIVED
 * PU12 Receiving an already-received purchase -> BusinessRuleException
 * PU13 Receiving an unknown purchase -> ResourceNotFoundException
 * PU14 A failure on a later line aborts the receipt; the order stays PENDING
 * PU15 A client total in the JSON body cannot change the stored total
 */
@ExtendWith(MockitoExtension.class)
class PurchaseServiceImplTest {

    @Mock PurchaseRepository     repository;
    @Mock PurchaseItemRepository itemRepository;
    @Mock SupplierRepository     supplierRepository;
    @Mock PartRepository         partRepository;
    @Mock StockMovementService   stockMovementService;
    @Mock AuditService auditService;

    @InjectMocks
    PurchaseServiceImpl service;

    //  Helpers

    private Supplier supplier(Long id) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName("Bharat Auto");
        return s;
    }

    private Part part(Long id, String sku) {
        Part p = new Part();
        p.setId(id);
        p.setSku(sku);
        p.setName("Part " + sku);
        p.setStockQty(0);
        p.setMinStock(2);
        return p;
    }

    private PurchaseItemRequestDTO line(Long partId, int qty, String unitPrice) {
        PurchaseItemRequestDTO dto = new PurchaseItemRequestDTO();
        dto.setPartId(partId);
        dto.setQuantity(qty);
        dto.setUnitPrice(new BigDecimal(unitPrice));
        return dto;
    }

    private PurchaseRequestDTO request(Long supplierId, PurchaseItemRequestDTO... items) {
        PurchaseRequestDTO dto = new PurchaseRequestDTO();
        dto.setSupplierId(supplierId);
        dto.setItems(List.of(items));
        return dto;
    }

    private Purchase purchase(Long id, String status) {
        Purchase p = new Purchase();
        p.setId(id);
        p.setSupplier(supplier(1L));
        p.setPurchaseDate(LocalDate.of(2026, 1, 15));
        p.setTotalAmount(new BigDecimal("0.00"));
        p.setStatus(status);
        return p;
    }

    /** A stored line, as the receipt loop reads it back from the database. */
    private PurchaseItem storedItem(Long id, Part part, int qty) {
        PurchaseItem item = new PurchaseItem();
        item.setId(id);
        item.setPurchase(purchase(10L, PurchaseServiceImpl.STATUS_PENDING));
        item.setPart(part);
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal("300.00"));
        item.setLineAmount(new BigDecimal("300.00").multiply(BigDecimal.valueOf(qty)));
        return item;
    }

    /**
     * Stubs the save/echo cycle so the service sees the lines it just wrote,
     * the way it would against a real database.
     */
    private void givenSavedPurchaseAndItems() {
        when(repository.save(any(Purchase.class))).thenAnswer(invocation -> {
            Purchase saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(10L);
            }
            return saved;
        });

        List<PurchaseItem> written = new ArrayList<>();
        when(itemRepository.save(any(PurchaseItem.class))).thenAnswer(invocation -> {
            PurchaseItem item = invocation.getArgument(0);
            item.setId((long) written.size() + 1L);
            written.add(item);
            return item;
        });
        when(itemRepository.findByPurchaseIdOrderByIdAsc(anyLong())).thenAnswer(invocation -> written);
    }

    /** Supplier + a part per id are found; anything else is unknown. */
    private void givenSupplierAndParts(Long... partIds) {
        givenSupplierFound();
        for (Long partId : partIds) {
            when(partRepository.findById(partId))
                    .thenReturn(Optional.of(part(partId, "SKU-" + partId)));
        }
    }

    /**
     * Only the supplier is looked up.
     *
     * <p>Used by the cases rejected during line validation: the service resolves
     * the supplier first, so that stub is used, but it must not go on to look up
     * a part - which is the point of those tests.
     */
    private void givenSupplierFound() {
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier(1L)));
    }

    //  PU1..PU3: purchase creation and the calculated total

    @Test
    @DisplayName("PU1 - a purchase is created PENDING with a server-calculated total")
    void pu1_create_singleItem_calculatesTotal() {
        givenSupplierAndParts(1L);
        givenSavedPurchaseAndItems();

        var response = service.create(request(1L, line(1L, 2, "300.00")));

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getStatus()).isEqualTo(PurchaseServiceImpl.STATUS_PENDING);
        assertThat(response.getSupplierId()).isEqualTo(1L);
        assertThat(response.getTotalAmount()).isEqualByComparingTo("600.00");
        assertThat(response.getItems()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getPartId()).isEqualTo(1L);
                    assertThat(item.getQuantity()).isEqualTo(2);
                    assertThat(item.getUnitPrice()).isEqualByComparingTo("300.00");
                    assertThat(item.getLineAmount()).isEqualByComparingTo("600.00");
                });
    }

    @Test
    @DisplayName("PU2 - several items are all stored and the total is their sum")
    void pu2_create_multipleItems_sumsLines() {
        givenSupplierAndParts(1L, 2L, 3L);
        givenSavedPurchaseAndItems();

        var response = service.create(request(1L,
                line(1L, 2, "300.00"),
                line(2L, 4, "125.50"),
                line(3L, 1, "99.99")));

        assertThat(response.getItems()).hasSize(3);
        assertThat(response.getItems())
                .extracting(com.autoservicehub.dto.PurchaseItemResponseDTO::getPartId)
                .containsExactly(1L, 2L, 3L);
        // 600.00 + 502.00 + 99.99
        assertThat(response.getTotalAmount()).isEqualByComparingTo("1201.99");
    }

    @Test
    @DisplayName("PU3 - a zero unit price is accepted; the line still counts for quantity")
    void pu3_zeroUnitPrice_allowed() {
        givenSupplierAndParts(1L);
        givenSavedPurchaseAndItems();

        var response = service.create(request(1L, line(1L, 5, "0.00")));

        assertThat(response.getTotalAmount()).isEqualByComparingTo("0.00");
    }

    //  PU4..PU5: references must exist

    @Test
    @DisplayName("PU4 - an unknown supplier is rejected and nothing is written")
    void pu4_unknownSupplier_throws() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(99L, line(1L, 1, "100.00"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Supplier not found: 99");

        verify(repository, never()).save(any(Purchase.class));
    }

    @Test
    @DisplayName("PU5 - an unknown part is rejected; no purchase or lines are written")
    void pu5_unknownPart_writesNothing() {
        givenSupplierAndParts(1L);
        // Part 2 does not exist.
        when(partRepository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(1L,
                line(1L, 1, "100.00"),
                line(2L, 1, "50.00"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Part not found: 2");

        // Parts are resolved before the order is written, so nothing survives.
        verify(repository, never()).save(any(Purchase.class));
        verify(itemRepository, never()).save(any(PurchaseItem.class));
    }

    //  PU6..PU9: invalid lines are rejected

    @Test
    @DisplayName("PU6 - a zero quantity is rejected and nothing is written")
    void pu6_zeroQuantity_throws() {
        givenSupplierFound();

        assertThatThrownBy(() -> service.create(request(1L, line(1L, 0, "100.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity greater than 0");

        verify(repository, never()).save(any(Purchase.class));
    }

    @Test
    @DisplayName("PU7 - a negative quantity is rejected and nothing is written")
    void pu7_negativeQuantity_throws() {
        givenSupplierFound();

        assertThatThrownBy(() -> service.create(request(1L, line(1L, -5, "100.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("quantity greater than 0");

        verify(repository, never()).save(any(Purchase.class));
    }

    @Test
    @DisplayName("PU8 - a negative unit price is rejected and nothing is written")
    void pu8_negativePrice_throws() {
        givenSupplierFound();

        assertThatThrownBy(() -> service.create(request(1L, line(1L, 1, "-50.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("negative unit price");

        verify(repository, never()).save(any(Purchase.class));
    }

    @Test
    @DisplayName("PU9 - a purchase with no items is rejected")
    void pu9_noItems_throws() {
        givenSupplierFound();

        PurchaseRequestDTO empty = new PurchaseRequestDTO();
        empty.setSupplierId(1L);
        empty.setItems(List.of());

        assertThatThrownBy(() -> service.create(empty))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one item");

        verify(repository, never()).save(any(Purchase.class));
    }

    //  PU10..PU14: goods receipt

    @Test
    @DisplayName("PU10 - receiving raises one IN stock movement per ordered line")
    void pu10_receive_raisesInMovementPerLine() {
        Purchase p = purchase(10L, PurchaseServiceImpl.STATUS_PENDING);
        when(repository.findById(10L)).thenReturn(Optional.of(p));
        when(itemRepository.findByPurchaseIdOrderByIdAsc(10L)).thenReturn(List.of(
                storedItem(1L, part(1L, "SKU-1"), 2),
                storedItem(2L, part(2L, "SKU-2"), 5)));
        when(repository.save(any(Purchase.class))).thenAnswer(inv -> inv.getArgument(0));

        service.receive(10L);

        ArgumentCaptor<StockMovementRequestDTO> captor =
                ArgumentCaptor.forClass(StockMovementRequestDTO.class);
        verify(stockMovementService, times(2)).create(captor.capture());

        assertThat(captor.getAllValues()).extracting(StockMovementRequestDTO::getPartId)
                .containsExactly(1L, 2L);
        assertThat(captor.getAllValues()).extracting(StockMovementRequestDTO::getQuantity)
                .containsExactly(2, 5);
        assertThat(captor.getAllValues()).extracting(StockMovementRequestDTO::getMovementType)
                .containsOnly("IN");
        assertThat(captor.getAllValues()).extracting(StockMovementRequestDTO::getReason)
                .containsOnly(PurchaseServiceImpl.REASON_PURCHASE_RECEIVED);
        assertThat(captor.getAllValues()).extracting(StockMovementRequestDTO::getReference)
                .containsOnly("PURCHASE-10");
    }

    @Test
    @DisplayName("PU11 - a received purchase is marked RECEIVED")
    void pu11_receive_marksReceived() {
        Purchase p = purchase(10L, PurchaseServiceImpl.STATUS_PENDING);
        when(repository.findById(10L)).thenReturn(Optional.of(p));
        when(itemRepository.findByPurchaseIdOrderByIdAsc(10L))
                .thenReturn(List.of(storedItem(1L, part(1L, "SKU-1"), 2)));
        when(repository.save(any(Purchase.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.receive(10L).getStatus())
                .isEqualTo(PurchaseServiceImpl.STATUS_RECEIVED);
        verify(repository).save(p);
    }

    @Test
    @DisplayName("PU12 - receiving twice is rejected and moves no stock the second time")
    void pu12_receiveTwice_rejected() {
        Purchase p = purchase(10L, PurchaseServiceImpl.STATUS_RECEIVED);
        when(repository.findById(10L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.receive(10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been received");

        verify(stockMovementService, never()).create(any(StockMovementRequestDTO.class));
        verify(repository, never()).save(any(Purchase.class));
    }

    @Test
    @DisplayName("PU13 - receiving an unknown purchase is rejected")
    void pu13_receiveUnknown_throws() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.receive(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Purchase not found: 99");

        verify(stockMovementService, never()).create(any(StockMovementRequestDTO.class));
    }

    @Test
    @DisplayName("PU14 - a failure on a later line aborts the receipt; the order stays PENDING")
    void pu14_receiveFailure_leavesPurchasePending() {
        Purchase p = purchase(10L, PurchaseServiceImpl.STATUS_PENDING);
        when(repository.findById(10L)).thenReturn(Optional.of(p));
        when(itemRepository.findByPurchaseIdOrderByIdAsc(10L)).thenReturn(List.of(
                storedItem(1L, part(1L, "SKU-1"), 2),
                storedItem(2L, part(2L, "SKU-2"), 5)));
        when(stockMovementService.create(any(StockMovementRequestDTO.class)))
                .thenAnswer(invocation -> {
                    if (invocation.<StockMovementRequestDTO>getArgument(0).getPartId() == 2L) {
                        throw new BusinessRuleException("Part SKU-2 cannot be received.");
                    }
                    return new com.autoservicehub.dto.StockMovementResponseDTO();
                });

        assertThatThrownBy(() -> service.receive(10L))
                .isInstanceOf(BusinessRuleException.class);

        // The order is never marked received, so the whole receipt is retryable
        // and the rollback undoes the first line's stock movement too.
        verify(repository, never()).save(any(Purchase.class));
        assertThat(p.getStatus()).isEqualTo(PurchaseServiceImpl.STATUS_PENDING);
    }

    //  PU15

    /**
     * A client-supplied total is ignored.
     *
     * <p>Sent through Jackson exactly as an HTTP client would send it, so this
     * exercises the real binding path: the extra {@code totalAmount} has nothing
     * to bind to and the stored total remains the sum of the lines.
     */
    @Test
    @DisplayName("PU15 - a total in the request body is ignored; the stored total is the line sum")
    void pu15_clientTotal_ignored() throws Exception {
        givenSupplierAndParts(1L);
        givenSavedPurchaseAndItems();

        String payload = """
                {
                  "supplierId": 1,
                  "totalAmount": 1.00,
                  "items": [ { "partId": 1, "quantity": 2, "unitPrice": 300.00 } ]
                }
                """;

        PurchaseRequestDTO req = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(payload, PurchaseRequestDTO.class);

        assertThat(service.create(req).getTotalAmount()).isEqualByComparingTo("600.00");
    }
}
