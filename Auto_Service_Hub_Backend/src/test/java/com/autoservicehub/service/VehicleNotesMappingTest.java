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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VehicleNotesMappingTest {

    private VehicleServiceImpl serviceUsing(VehicleRepository vehicleRepository) {
        return new VehicleServiceImpl(
                vehicleRepository,
                mock(CustomerRepository.class),
                mock(JobCardRepository.class),
                mock(JobTaskRepository.class),
                mock(InvoiceRepository.class));
    }

    @Test
    void createsVehicleWithNotesAndReturnsThem() {
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
            Vehicle vehicle = invocation.getArgument(0);
            vehicle.setId(31L);
            return vehicle;
        });
        VehicleServiceImpl service = serviceUsing(vehicleRepository);

        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-31");
        request.setNotes("  Battery replaced in 2024. Check charging before next service.  ");

        VehicleResponseDTO response = service.create(request);

        assertThat(response.getId()).isEqualTo(31L);
        // Surrounding whitespace is trimmed on the way in.
        assertThat(response.getNotes())
                .isEqualTo("Battery replaced in 2024. Check charging before next service.");
    }

    @Test
    void readsExistingNotesBackFromTheDatabase() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(32L);
        vehicle.setRegistrationNo("TEST-32");
        vehicle.setNotes("Owner reports a slight pull to the left.");
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findById(32L)).thenReturn(Optional.of(vehicle));
        VehicleServiceImpl service = serviceUsing(vehicleRepository);

        VehicleResponseDTO response = service.getById(32L);

        assertThat(response.getNotes()).isEqualTo("Owner reports a slight pull to the left.");
    }

    @Test
    void notesAreOptionalAndAbsentOnCreateWhenNotSupplied() {
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
            Vehicle vehicle = invocation.getArgument(0);
            vehicle.setId(33L);
            return vehicle;
        });
        VehicleServiceImpl service = serviceUsing(vehicleRepository);

        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-33");

        assertThat(service.create(request).getNotes()).isNull();
    }

    @Test
    void whitespaceOnlyNotesAreStoredAsNull() {
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> {
            Vehicle vehicle = invocation.getArgument(0);
            vehicle.setId(34L);
            return vehicle;
        });
        VehicleServiceImpl service = serviceUsing(vehicleRepository);

        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-34");
        request.setNotes("   \n  ");

        assertThat(service.create(request).getNotes()).isNull();
    }

    @Test
    void updateChangesNotesWhenProvidedAndPreservesThemWhenOmitted() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(35L);
        vehicle.setRegistrationNo("TEST-35");
        vehicle.setNotes("Original note that must survive an unrelated update.");
        VehicleRepository vehicleRepository = mock(VehicleRepository.class);
        when(vehicleRepository.findById(35L)).thenReturn(Optional.of(vehicle));
        when(vehicleRepository.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        VehicleServiceImpl service = serviceUsing(vehicleRepository);

        VehicleRequestDTO omitted = new VehicleRequestDTO();
        omitted.setRegistrationNo("TEST-35");
        assertThat(service.update(35L, omitted).getNotes())
                .isEqualTo("Original note that must survive an unrelated update.");

        VehicleRequestDTO replaced = new VehicleRequestDTO();
        replaced.setRegistrationNo("TEST-35");
        replaced.setNotes("Replaced note.");
        assertThat(service.update(35L, replaced).getNotes()).isEqualTo("Replaced note.");

        VehicleRequestDTO cleared = new VehicleRequestDTO();
        cleared.setRegistrationNo("TEST-35");
        cleared.setNotes("   ");
        assertThat(service.update(35L, cleared).getNotes()).isNull();
    }

    @Test
    void notesAreOptionalButCappedAtTwoThousandCharacters() {
        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setRegistrationNo("TEST-36");

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            assertThat(validator.validate(request)).isEmpty();

            request.setNotes("a".repeat(2000));
            assertThat(validator.validate(request)).isEmpty();

            request.setNotes("a".repeat(2001));
            assertThat(validator.validate(request))
                    .anySatisfy(violation ->
                            assertThat(violation.getPropertyPath().toString()).isEqualTo("notes"));
        }
    }
}
