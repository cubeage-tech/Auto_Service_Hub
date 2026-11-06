package com.autoservicehub.service;

import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.AppointmentServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppointmentServiceImpl} covering the appointment_type
 * column added in V13. Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * AT1  Create            -> the three supported types are stored
 * AT2  Create            -> a lower-case type is normalised to upper case
 * AT3  Create            -> a type with stray whitespace is trimmed
 * AT4  Create            -> an unsupported type is rejected, nothing saved
 * AT5  Create            -> an omitted type is stored as null
 * AT6  Create            -> a blank type is stored as null (unassigned)
 * AT7  Update            -> the type is changed on an existing appointment
 * AT8  Update            -> a blank type clears a previously stored type
 * AT9  Read              -> a stored type is returned to the caller
 * AT10 Read              -> a legacy row with no type returns null, not an error
 * AT11 supportedTypes    -> the three types the form may offer
 * AT12 Regressions       -> bay/advisor/status still behave as before
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentServiceImpl — appointment type")
class AppointmentServiceImplAppointmentTypeTest {

    @Mock
    private AppointmentRepository repository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AppointmentServiceImpl service;

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 5, 6, 9, 15);

    private AppointmentRequestDTO validRequest() {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setCustomerId(11L);
        request.setVehicleId(22L);
        request.setServiceType("Full Service");
        request.setAppointmentAt(WHEN);
        return request;
    }

    private void stubCustomerAndVehicle() {
        when(customerRepository.findById(11L)).thenReturn(Optional.of(new Customer()));
        when(vehicleRepository.findById(22L)).thenReturn(Optional.of(new Vehicle()));
    }

    private void stubSaveEchoes() {
        when(repository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AT1 — each of the three supported types round-trips on create")
    void createPersistsEverySupportedType() {
        for (String type : new String[] {"WALK_IN", "ONLINE", "PICKUP_DROP"}) {
            stubCustomerAndVehicle();
            stubSaveEchoes();

            AppointmentRequestDTO request = validRequest();
            request.setAppointmentType(type);

            assertThat(service.create(request).getAppointmentType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("AT2 — a lower-case type is normalised to the canonical upper case")
    void createNormalisesCase() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("pickup_drop");

        assertThat(service.create(request).getAppointmentType()).isEqualTo("PICKUP_DROP");
    }

    @Test
    @DisplayName("AT3 — a type with stray whitespace is trimmed before storage")
    void createTrimsType() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("  ONLINE  ");

        assertThat(service.create(request).getAppointmentType()).isEqualTo("ONLINE");
    }

    @Test
    @DisplayName("AT4 — a type outside the supported set is rejected and nothing is saved")
    void createRejectsUnsupportedType() {
        stubCustomerAndVehicle();

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("DRIVE_THROUGH");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("DRIVE_THROUGH");

        verify(repository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("AT5 — an omitted type is stored as null rather than guessed")
    void createWithoutTypeIsAllowed() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        assertThat(service.create(validRequest()).getAppointmentType()).isNull();
    }

    @Test
    @DisplayName("AT6 — a blank type is stored as null (unassigned)")
    void createTreatsBlankTypeAsUnset() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("   ");

        assertThat(service.create(request).getAppointmentType()).isNull();
    }

    // ── update ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AT7 — update changes the type on an existing appointment")
    void updateChangesType() {
        stubCustomerAndVehicle();
        stubSaveEchoes();
        when(repository.findById(100L)).thenReturn(Optional.of(new Appointment()));

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("ONLINE");

        assertThat(service.update(100L, request).getAppointmentType()).isEqualTo("ONLINE");
        verify(repository).save(any(Appointment.class));
    }

    @Test
    @DisplayName("AT8 — clearing the type on update unassigns it")
    void updateClearsType() {
        Appointment existing = new Appointment();
        existing.setAppointmentType("WALK_IN");
        stubCustomerAndVehicle();
        stubSaveEchoes();
        when(repository.findById(100L)).thenReturn(Optional.of(existing));

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("");

        assertThat(service.update(100L, request).getAppointmentType()).isNull();
    }

    // ── read ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AT9 — a stored type is returned to the caller unchanged")
    void getByIdReturnsType() {
        Appointment appointment = new Appointment();
        appointment.setId(100L);
        appointment.setAppointmentType("PICKUP_DROP");
        when(repository.findById(100L)).thenReturn(Optional.of(appointment));

        assertThat(service.getById(100L).getAppointmentType()).isEqualTo("PICKUP_DROP");
    }

    @Test
    @DisplayName("AT10 — an appointment booked before V13 reads back as null, not an error")
    void legacyAppointmentWithoutTypeReadsSafely() {
        Appointment appointment = new Appointment();
        appointment.setId(101L);
        when(repository.findById(101L)).thenReturn(Optional.of(appointment));

        AppointmentResponseDTO response = service.getById(101L);

        assertThat(response.getAppointmentType()).isNull();
        assertThat(response.getBaySlot()).isNull();
        assertThat(response.getAdvisorId()).isNull();
    }

    // ── option source ──────────────────────────────────────────────────────

    @Test
    @DisplayName("AT11 — the form is offered exactly the three supported types")
    void supportedTypesAreTheThreeDefined() {
        assertThat(service.supportedAppointmentTypes())
                .containsExactly("WALK_IN", "ONLINE", "PICKUP_DROP");
    }

    // ── regressions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("AT12 — bay, advisor and status behaviour is unchanged by V13")
    void existingFieldsStillWorkAlongsideType() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setAppointmentType("WALK_IN");
        request.setBaySlot("Bay 2");

        AppointmentResponseDTO response = service.create(request);

        assertThat(response.getAppointmentType()).isEqualTo("WALK_IN");
        assertThat(response.getBaySlot()).isEqualTo("Bay 2");
        assertThat(response.getStatus()).isEqualTo("SCHEDULED");
        assertThat(response.getAdvisorId()).isNull();
    }

    @Test
    @DisplayName("AT12b — a missing customer is still reported, unchanged by V13")
    void missingCustomerStillFails() {
        when(customerRepository.findById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(validRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer not found");
    }
}