package com.autoservicehub.service;

import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobTaskRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.VehicleServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleFuelTypeMappingTest {

    @Test
    void createsVehicleWithCanonicalFuelTypeAndReturnsIt() {
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        VehicleServiceImpl service = new VehicleServiceImpl(
                vehicleRepository,
                mock(CustomerRepository.class),
                mock(JobCardRepository.class),
                mock(JobTaskRepository.class),
                mock(InvoiceRepository.class));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
            Vehicle vehicle = invocation.getArgument(0);
            vehicle.setId(17L);
            return vehicle;
        });

        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-17");
        request.setFuelType("electric");
        request.setStatus("in_service");

        VehicleResponseDTO response = service.create(request);

        assertThat(response.getId()).isEqualTo(17L);
        assertThat(response.getFuelType()).isEqualTo("ELECTRIC");
        assertThat(response.getStatus()).isEqualTo("IN_SERVICE");
    }

    @Test
    void responseIncludesExistingStatusAndFuelType() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(18L);
        vehicle.setRegistrationNo("TEST-18");
        vehicle.setFuelType("HYBRID");
        vehicle.setStatus("WAITING");
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findById(18L)).thenReturn(Optional.of(vehicle));
        VehicleServiceImpl service = new VehicleServiceImpl(
                vehicleRepository,
                mock(CustomerRepository.class),
                mock(JobCardRepository.class),
                mock(JobTaskRepository.class),
                mock(InvoiceRepository.class));

        VehicleResponseDTO response = service.getById(18L);

        assertThat(response.getFuelType()).isEqualTo("HYBRID");
        assertThat(response.getStatus()).isEqualTo("WAITING");
    }

    @Test
    void updateChangesFuelTypeWhenProvidedAndPreservesItWhenOmitted() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(20L);
        vehicle.setRegistrationNo("TEST-20");
        vehicle.setFuelType("PETROL");
        vehicle.setStatus("WAITING");
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findById(20L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        VehicleServiceImpl service = new VehicleServiceImpl(
                vehicleRepository,
                mock(CustomerRepository.class),
                mock(JobCardRepository.class),
                mock(JobTaskRepository.class),
                mock(InvoiceRepository.class));

        VehicleRequestDTO legacyRequest = new VehicleRequestDTO();
        legacyRequest.setRegistrationNo("TEST-20");
        VehicleResponseDTO preserved = service.update(20L, legacyRequest);
        assertThat(preserved.getFuelType()).isEqualTo("PETROL");
        assertThat(preserved.getStatus()).isEqualTo("WAITING");

        VehicleRequestDTO updatedRequest = new VehicleRequestDTO();
        updatedRequest.setRegistrationNo("TEST-20");
        updatedRequest.setFuelType("DIESEL");
        updatedRequest.setStatus("DELIVERED");
        VehicleResponseDTO updated = service.update(20L, updatedRequest);
        assertThat(updated.getFuelType()).isEqualTo("DIESEL");
        assertThat(updated.getStatus()).isEqualTo("DELIVERED");
    }

    @Test
    void listNormalizesAndCombinesOptionalFilters() {
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findFiltered(eq("PETROL"), eq("WAITING"), any()))
                .thenReturn(Page.empty());
        VehicleServiceImpl service = new VehicleServiceImpl(
                vehicleRepository,
                mock(CustomerRepository.class),
                mock(JobCardRepository.class),
                mock(JobTaskRepository.class),
                mock(InvoiceRepository.class));

        service.list(PageRequest.of(0, 10), " petrol ", " waiting ");

        verify(vehicleRepository).findFiltered(
                "PETROL", "WAITING", PageRequest.of(0, 10));
    }

    @Test
    void validatesSupportedVehicleStatusesAndRejectsInvalidValues() {
        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-19");

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();
            assertThat(validator.validate(request)).isEmpty();

            for (String status : new String[] {"IN_SERVICE", "WAITING", "DELIVERED"}) {
                request.setStatus(status);
                assertThat(validator.validate(request)).isEmpty();
            }

            request.setFuelType("NUCLEAR");
            assertThat(validator.validate(request))
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("fuelType"));

            request.setFuelType("PETROL");
            request.setStatus("IN_REPAIR");
            assertThat(validator.validate(request))
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("status"));
        }
    }
}
