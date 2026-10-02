package com.autoservicehub.service;

import com.autoservicehub.dto.ExportFileDTO;
import com.autoservicehub.dto.VehicleServiceHistoryDTO;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.JobTask;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.ExportRenderer;
import com.autoservicehub.service.impl.VehicleHistoryDocumentService;
import com.autoservicehub.service.impl.VehicleServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the vehicle service history (FR-VEH-6).
 *
 * <p>Pure Mockito. The history is assembled entirely from job cards, tasks and
 * invoices that already exist, so what is pinned is that those three sources are
 * read correctly, and that the two "absent" cases are distinguished: an unknown
 * vehicle is a 404, while a known vehicle with no jobs is an empty history.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VehicleServiceHistoryTest {

    @Mock VehicleRepository  repository;
    @Mock CustomerRepository customerRepository;
    @Mock JobCardRepository  jobCardRepository;
    @Mock JobTaskRepository  jobTaskRepository;
    @Mock InvoiceRepository  invoiceRepository;

    @InjectMocks VehicleServiceImpl service;

    private Vehicle vehicle() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setRegistrationNo("MH-12-AB-1234");
        vehicle.setMake("Maruti");
        vehicle.setModel("Swift VXI");
        return vehicle;
    }

    private JobCard jobCard(Long id, String status, LocalDateTime assigned, LocalDateTime completed) {
        JobCard jobCard = new JobCard();
        jobCard.setId(id);
        jobCard.setJobCardNumber("JC-" + id);
        jobCard.setServiceType("BRAKE_SERVICE");
        jobCard.setStatus(status);
        jobCard.setAssignedDate(assigned);
        jobCard.setCompletedDate(completed);
        return jobCard;
    }

    @Test
    @DisplayName("VH1 a vehicle with service history returns its visits newest first")
    void historyIsReturned() {
        when(repository.findById(2L)).thenReturn(Optional.of(vehicle()));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(2L)).thenReturn(List.of(
                jobCard(20L, "DELIVERED",
                        LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 2, 17, 0)),
                jobCard(10L, "DELIVERED",
                        LocalDateTime.of(2026, 1, 5, 9, 0), LocalDateTime.of(2026, 1, 6, 12, 0))));
        when(jobTaskRepository.findByJobCardIdOrderByIdAsc(any())).thenReturn(List.of());
        when(invoiceRepository.findByJobCardIdOrderByInvoiceDateDescIdDesc(any())).thenReturn(List.of());

        VehicleServiceHistoryDTO history = service.getServiceHistory(2L);

        assertThat(history.getVehicleId()).isEqualTo(2L);
        assertThat(history.getTotalVisits()).isEqualTo(2);
        // Ordering comes from the repository query; the service preserves it.
        assertThat(history.getVisits()).extracting(VehicleServiceHistoryDTO.ServiceVisitDTO::getJobCardId)
                .containsExactly(20L, 10L);
    }

    @Test
    @DisplayName("VH2 an unserviced vehicle returns a valid empty history, not a 404")
    void emptyHistoryIsValid() {
        when(repository.findById(2L)).thenReturn(Optional.of(vehicle()));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(2L)).thenReturn(List.of());

        VehicleServiceHistoryDTO history = service.getServiceHistory(2L);

        assertThat(history.getTotalVisits()).isZero();
        assertThat(history.getVisits()).isNotNull().isEmpty();
        assertThat(history.getTotalInvoiced()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("VH3 an unknown vehicle is a 404")
    void unknownVehicleIsNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getServiceHistory(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Vehicle not found: 999");
    }
    @Test
    @DisplayName("VH4 tasks and invoices are attached to their visit")
    void tasksAndInvoicesAreAttached() {
        Mechanic mechanic = new Mechanic();
        mechanic.setId(7L);
        mechanic.setName("Anil");

        JobTask task = new JobTask();
        task.setId(5L);
        task.setDescription("Replace front brake pads");
        task.setStatus("COMPLETED");
        task.setLabourCost(new BigDecimal("500.00"));
        task.setMechanic(mechanic);

        Invoice invoice = new Invoice();
        invoice.setId(31L);
        invoice.setStatus("PAID");
        invoice.setTotal(new BigDecimal("1180.00"));
        invoice.setInvoiceDate(LocalDate.of(2026, 3, 2));

        when(repository.findById(2L)).thenReturn(Optional.of(vehicle()));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(2L))
                .thenReturn(List.of(jobCard(20L, "DELIVERED",
                        LocalDateTime.of(2026, 3, 1, 9, 0), LocalDateTime.of(2026, 3, 2, 17, 0))));
        when(jobTaskRepository.findByJobCardIdOrderByIdAsc(20L)).thenReturn(List.of(task));
        when(invoiceRepository.findByJobCardIdOrderByInvoiceDateDescIdDesc(20L)).thenReturn(List.of(invoice));

        VehicleServiceHistoryDTO.ServiceVisitDTO visit = service.getServiceHistory(2L).getVisits().get(0);

        assertThat(visit.getTasks()).singleElement().satisfies(t -> {
            assertThat(t.getDescription()).isEqualTo("Replace front brake pads");
            assertThat(t.getMechanicName()).isEqualTo("Anil");
        });
        assertThat(visit.getInvoices()).hasSize(1);
        assertThat(visit.getInvoiceTotal()).isEqualByComparingTo("1180.00");
    }

    @Test
    @DisplayName("VH5 a job that has not been invoiced reports a null total, not zero")
    void uninvoicedJobReportsNull() {
        when(repository.findById(2L)).thenReturn(Optional.of(vehicle()));
        when(jobCardRepository.findByVehicleIdOrderByAssignedDateDesc(2L))
                .thenReturn(List.of(jobCard(20L, "IN_REPAIR", LocalDateTime.of(2026, 3, 1, 9, 0), null)));
        when(jobTaskRepository.findByJobCardIdOrderByIdAsc(20L)).thenReturn(List.of());
        when(invoiceRepository.findByJobCardIdOrderByInvoiceDateDescIdDesc(20L)).thenReturn(List.of());

        VehicleServiceHistoryDTO history = service.getServiceHistory(2L);

        // "Not yet billed" and "billed nothing" are different facts.
        assertThat(history.getVisits().get(0).getInvoiceTotal()).isNull();
        assertThat(history.getTotalInvoiced()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("VH6 the history renders as a non-empty downloadable PDF")
    void historyRendersAsPdf() {
        VehicleServiceHistoryDTO history = new VehicleServiceHistoryDTO();
        history.setVehicleId(2L);
        history.setVehicleInfo("MH-12-AB-1234");
        history.setTotalVisits(0);

        ExportFileDTO file = new VehicleHistoryDocumentService(new ExportRenderer()).toPdf(history);

        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getFilename()).isEqualTo("vehicle-2-service-history.pdf");
        assertThat(file.size()).isGreaterThan(0);
        assertThat(new String(file.getContent(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }
}