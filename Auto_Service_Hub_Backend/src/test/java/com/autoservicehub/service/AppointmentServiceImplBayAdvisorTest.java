package com.autoservicehub.service;

import com.autoservicehub.dto.AdvisorResponseDTO;
import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.AppointmentResponseDTO;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AppointmentServiceImpl} covering the bay / slot and
 * assigned-advisor fields added in V12, on top of the pre-existing behaviour
 * that must not regress. Pure Mockito — no Spring context, no database.
 *
 * Test cases
 * ----------
 * BA1  Create            -> bay and advisor persisted on the entity
 * BA2  Create            -> bay trimmed before it is stored
 * BA3  Create            -> unknown bay is rejected, nothing saved
 * BA4  Create            -> blank bay is stored as null (unassigned)
 * BA5  Create            -> inactive advisor is rejected
 * BA6  Create            -> advisor with the wrong role is rejected
 * BA7  Create            -> unknown advisor id is rejected
 * BA8  Create            -> omitted advisor leaves the appointment unassigned
 * BA9  Update            -> bay and advisor changed on an existing appointment
 * BA10 Update            -> blank bay clears a previously assigned bay
 * BA11 Read              -> bay and advisor returned to the caller
 * BA12 Read              -> legacy row with no bay/advisor returns nulls, not errors
 * BA13 listAdvisors      -> only active SERVICE_ADVISOR users, named
 * BA14 availableBaySlots -> the four garage bays
 * BA15 Regressions       -> status still defaults, customer/vehicle still required
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentServiceImpl — bay slot and assigned advisor")
class AppointmentServiceImplBayAdvisorTest {

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

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 4, 2, 10, 30);

    // ── helpers ────────────────────────────────────────────────────────────

    private AppointmentRequestDTO validRequest() {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setCustomerId(11L);
        request.setVehicleId(22L);
        request.setServiceType("Basic Service");
        request.setAppointmentAt(WHEN);
        request.setPickupDrop(Boolean.FALSE);
        return request;
    }

    private void stubCustomerAndVehicle() {
        when(customerRepository.findById(11L)).thenReturn(Optional.of(new Customer()));
        when(vehicleRepository.findById(22L)).thenReturn(Optional.of(new Vehicle()));
    }

    /** A saved appointment is echoed back as the same instance, as JpaRepository does. */
    private void stubSaveEchoes() {
        when(repository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User advisor(Long id, String role, boolean active, String fullName) {
        Role r = new Role();
        r.setName(role);
        User user = new User();
        user.setId(id);
        user.setRole(r);
        user.setActive(active);
        user.setFullName(fullName);
        user.setUsername("advisor" + id);
        return user;
    }

    // ── create ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BA1 — create persists both the bay and the advisor")
    void createPersistsBayAndAdvisor() {
        stubCustomerAndVehicle();
        stubSaveEchoes();
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(advisor(7L, "SERVICE_ADVISOR", true, "Ramesh K.")));

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("Bay 3");
        request.setAdvisorId(7L);

        AppointmentResponseDTO response = service.create(request);

        assertThat(response.getBaySlot()).isEqualTo("Bay 3");
        assertThat(response.getAdvisorId()).isEqualTo(7L);
        assertThat(response.getAdvisorName()).isEqualTo("Ramesh K.");
    }

    @Test
    @DisplayName("BA2 — a bay sent with stray whitespace is trimmed before storage")
    void createTrimsBay() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("  Bay 2  ");

        assertThat(service.create(request).getBaySlot()).isEqualTo("Bay 2");
    }

    @Test
    @DisplayName("BA3 — a bay outside the garage's list is rejected and nothing is saved")
    void createRejectsUnknownBay() {
        stubCustomerAndVehicle();

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("Bay 9");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Bay 9");

        verify(repository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("BA4 — a blank bay is stored as null rather than as an empty string")
    void createTreatsBlankBayAsUnassigned() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("   ");

        assertThat(service.create(request).getBaySlot()).isNull();
    }

    @Test
    @DisplayName("BA5 — an inactive advisor cannot be assigned")
    void createRejectsInactiveAdvisor() {
        stubCustomerAndVehicle();
        when(userRepository.findById(9L))
                .thenReturn(Optional.of(advisor(9L, "SERVICE_ADVISOR", false, "Former Staff")));

        AppointmentRequestDTO request = validRequest();
        request.setAdvisorId(9L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inactive");

        verify(repository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("BA6 — a user holding a different role cannot be assigned")
    void createRejectsWrongRoleAdvisor() {
        stubCustomerAndVehicle();
        when(userRepository.findById(5L))
                .thenReturn(Optional.of(advisor(5L, "CUSTOMER", true, "Walk-in Customer")));

        AppointmentRequestDTO request = validRequest();
        request.setAdvisorId(5L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not an eligible service advisor");

        verify(repository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("BA7 — an unknown advisor id is reported as not found")
    void createRejectsUnknownAdvisor() {
        stubCustomerAndVehicle();
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        AppointmentRequestDTO request = validRequest();
        request.setAdvisorId(404L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Advisor not found");

        verify(repository, never()).save(any(Appointment.class));
    }

    @Test
    @DisplayName("BA8 — an omitted advisor leaves the appointment unassigned")
    void createWithoutAdvisorIsAllowed() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentResponseDTO response = service.create(validRequest());

        assertThat(response.getAdvisorId()).isNull();
        assertThat(response.getAdvisorName()).isNull();
        verify(userRepository, never()).findById(any());
    }

    // ── update ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BA9 — update changes the bay and advisor on an existing appointment")
    void updateChangesBayAndAdvisor() {
        stubCustomerAndVehicle();
        stubSaveEchoes();
        when(repository.findById(100L)).thenReturn(Optional.of(new Appointment()));
        when(userRepository.findById(8L))
                .thenReturn(Optional.of(advisor(8L, "SERVICE_ADVISOR", true, "Anita S.")));

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("Bay 4");
        request.setAdvisorId(8L);

        AppointmentResponseDTO response = service.update(100L, request);

        assertThat(response.getBaySlot()).isEqualTo("Bay 4");
        assertThat(response.getAdvisorId()).isEqualTo(8L);
        assertThat(response.getAdvisorName()).isEqualTo("Anita S.");
        verify(repository).save(any(Appointment.class));
    }

    @Test
    @DisplayName("BA10 — clearing the bay on update unassigns it")
    void updateClearsBay() {
        stubCustomerAndVehicle();
        stubSaveEchoes();
        when(repository.findById(100L)).thenReturn(Optional.of(new Appointment()));

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("");

        assertThat(service.update(100L, request).getBaySlot()).isNull();
    }

    // ── read ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BA11 — a stored bay and advisor are returned to the caller")
    void getByIdReturnsBayAndAdvisor() {
        Appointment appointment = new Appointment();
        appointment.setId(100L);
        appointment.setBaySlot("Bay 1");
        appointment.setAdvisor(advisor(3L, "SERVICE_ADVISOR", true, "Deepak Rao"));
        when(repository.findById(100L)).thenReturn(Optional.of(appointment));

        AppointmentResponseDTO response = service.getById(100L);

        assertThat(response.getBaySlot()).isEqualTo("Bay 1");
        assertThat(response.getAdvisorId()).isEqualTo(3L);
        assertThat(response.getAdvisorName()).isEqualTo("Deepak Rao");
    }

    @Test
    @DisplayName("BA12 — an appointment booked before V12 reads back as null, not an error")
    void legacyAppointmentWithoutBayOrAdvisorReadsSafely() {
        Appointment appointment = new Appointment();
        appointment.setId(101L);
        when(repository.findById(101L)).thenReturn(Optional.of(appointment));

        AppointmentResponseDTO response = service.getById(101L);

        assertThat(response.getBaySlot()).isNull();
        assertThat(response.getAdvisorId()).isNull();
        assertThat(response.getAdvisorName()).isNull();
    }

    // ── option sources ─────────────────────────────────────────────────────

    @Test
    @DisplayName("BA13 — advisors are the active SERVICE_ADVISOR users, named for display")
    void listAdvisorsReturnsEligibleUsers() {
        when(userRepository.findActiveByRoleNameIn(List.of("SERVICE_ADVISOR")))
                .thenReturn(List.of(
                        advisor(1L, "SERVICE_ADVISOR", true, "Ramesh K."),
                        advisor(2L, "SERVICE_ADVISOR", true, null)));

        List<AdvisorResponseDTO> advisors = service.listAdvisors();

        assertThat(advisors).hasSize(2);
        assertThat(advisors.get(0).getName()).isEqualTo("Ramesh K.");
        // A user with no full name still gets a usable label.
        assertThat(advisors.get(1).getName()).isEqualTo("advisor2");
    }

    @Test
    @DisplayName("BA14 — the bay list is the garage's four bays")
    void availableBaySlotsReturnsFourBays() {
        assertThat(service.availableBaySlots())
                .containsExactly("Bay 1", "Bay 2", "Bay 3", "Bay 4");
    }

    // ── regressions on existing behaviour ───────────────────────────────────

    @Test
    @DisplayName("BA15 — status still defaults to SCHEDULED and the core fields are untouched")
    void existingBehaviourIsUnchanged() {
        stubCustomerAndVehicle();
        stubSaveEchoes();

        AppointmentRequestDTO request = validRequest();
        request.setBaySlot("Bay 1");

        AppointmentResponseDTO response = service.create(request);

        assertThat(response.getStatus()).isEqualTo("SCHEDULED");
        assertThat(response.getServiceType()).isEqualTo("Basic Service");
        assertThat(response.getAppointmentAt()).isEqualTo(WHEN);
        assertThat(response.getPickupDrop()).isFalse();
    }

    @Test
    @DisplayName("BA15b — a missing customer is still reported before anything is written")
    void missingCustomerStillFails() {
        when(customerRepository.findById(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(validRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer not found");

        verify(repository, never()).save(any(Appointment.class));
    }
}