package com.autoservicehub;

import com.autoservicehub.controller.ServicePackageController;
import com.autoservicehub.dto.AppointmentRequestDTO;
import com.autoservicehub.dto.CustomerRequestDTO;
import com.autoservicehub.dto.CustomerResponseDTO;
import com.autoservicehub.dto.EstimateItemRequestDTO;
import com.autoservicehub.dto.EstimateRequestDTO;
import com.autoservicehub.dto.EstimateResponseDTO;
import com.autoservicehub.dto.FollowupRequestDTO;
import com.autoservicehub.dto.FollowupResponseDTO;
import com.autoservicehub.dto.InspectionItemRequestDTO;
import com.autoservicehub.dto.InspectionRequestDTO;
import com.autoservicehub.dto.InspectionResponseDTO;
import com.autoservicehub.dto.PaymentRequestDTO;
import com.autoservicehub.dto.JobCardRequestDTO;
import com.autoservicehub.dto.PaymentResponseDTO;
import com.autoservicehub.dto.VehicleRequestDTO;
import com.autoservicehub.dto.VehicleResponseDTO;
import com.autoservicehub.controller.InvoiceController;
import com.autoservicehub.controller.InspectionController;
import com.autoservicehub.controller.JobCardController;
import com.autoservicehub.controller.EstimateController;
import com.autoservicehub.controller.MechanicController;
import com.autoservicehub.controller.PaymentController;
import com.autoservicehub.controller.ReportController;
import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.entity.Role;
import com.autoservicehub.entity.User;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.EstimateRepository;
import com.autoservicehub.repository.FeedbackRepository;
import com.autoservicehub.repository.InspectionRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.FollowupRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.JobCardStatusHistoryRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.MechanicSkillRepository;
import com.autoservicehub.repository.AuditLogRepository;
import com.autoservicehub.repository.CommunicationRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.impl.AppointmentServiceImpl;
import com.autoservicehub.service.impl.CustomerServiceImpl;
import com.autoservicehub.service.impl.EstimateServiceImpl;
import com.autoservicehub.service.impl.FollowupServiceImpl;
import com.autoservicehub.service.impl.InspectionServiceImpl;
import com.autoservicehub.service.impl.PaymentServiceImpl;
import com.autoservicehub.service.impl.VehicleServiceImpl;
import com.autoservicehub.service.DeliveryGateService;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.impl.JobCardServiceImpl;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceAdvisorModuleTest {

    @Test
    void serviceAdvisorCanViewPackagesButCannotManageThem() throws Exception {
        assertRoleExpression("getById", "hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')");
        assertRoleExpression("list", "hasAnyRole('ADMIN', 'OWNER', 'MANAGER', 'SERVICE_ADVISOR', 'MECHANIC', 'INVENTORY_MANAGER', 'BILLING_USER')");
        assertRoleExpression("create", "hasAnyRole('ADMIN', 'OWNER', 'MANAGER')");
        assertRoleExpression("update", "hasAnyRole('ADMIN', 'OWNER', 'MANAGER')");
        assertRoleExpression("delete", "hasAnyRole('ADMIN', 'OWNER', 'MANAGER')");
    }

    @Test
    void serviceAdvisorCanViewButCannotWriteInvoicesOrPayments() throws Exception {
        assertAdvisorAccess(InvoiceController.class, "getById", true);
        assertAdvisorAccess(InvoiceController.class, "list", true);
        assertAdvisorAccess(InvoiceController.class, "create", false);
        assertAdvisorAccess(InvoiceController.class, "update", false);
        assertAdvisorAccess(InvoiceController.class, "delete", false);
        assertAdvisorAccess(PaymentController.class, "getById", true);
        assertAdvisorAccess(PaymentController.class, "list", true);
        assertAdvisorAccess(PaymentController.class, "create", false);
        assertAdvisorAccess(PaymentController.class, "update", false);
        assertAdvisorAccess(PaymentController.class, "delete", false);
    }

    @Test
    void serviceAdvisorCannotDeleteInspectionJobCardOrEstimate() throws Exception {
        assertAdvisorAccess(InspectionController.class, "create", true);
        assertAdvisorAccess(InspectionController.class, "update", true);
        assertAdvisorAccess(InspectionController.class, "delete", false);
        assertAdvisorAccess(JobCardController.class, "create", true);
        assertAdvisorAccess(JobCardController.class, "update", true);
        assertAdvisorAccess(JobCardController.class, "delete", false);
        assertAdvisorAccess(EstimateController.class, "create", true);
        assertAdvisorAccess(EstimateController.class, "update", true);
        assertAdvisorAccess(EstimateController.class, "delete", false);
    }

    @Test
    void serviceAdvisorCanViewMechanicsButCannotManageThem() throws Exception {
        assertAdvisorAccess(MechanicController.class, "getById", true);
        assertAdvisorAccess(MechanicController.class, "list", true);
        assertAdvisorAccess(MechanicController.class, "skills", true);
        assertAdvisorAccess(MechanicController.class, "workload", true);
        assertAdvisorAccess(MechanicController.class, "create", false);
        assertAdvisorAccess(MechanicController.class, "update", false);
        assertAdvisorAccess(MechanicController.class, "delete", false);
        assertAdvisorAccess(MechanicController.class, "addSkill", false);
        assertAdvisorAccess(MechanicController.class, "updateSkill", false);
        assertAdvisorAccess(MechanicController.class, "deleteSkill", false);
        assertAdvisorAccess(MechanicController.class, "checkIn", false);
        assertAdvisorAccess(MechanicController.class, "checkOut", false);
        assertAdvisorAccess(MechanicController.class, "attendanceHistory", false);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void advisorOperationsReportIsAdvisorOnly() throws Exception {
        assertAdvisorAccess(ReportController.class, "advisorOperations", true);
        assertAdvisorAccess(ReportController.class, "revenue", false);
        assertAdvisorAccess(ReportController.class, "mechanicPerformance", false);
    }

    @Test
    void advisorCannotReadAppointmentAssignedToAnotherAdvisor() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        User currentAdvisor = serviceAdvisor(10L, "advisor-a");
        User otherAdvisor = serviceAdvisor(20L, "advisor-b");
        when(users.findByUsernameIgnoreCase("advisor-a")).thenReturn(Optional.of(currentAdvisor));
        Appointment restricted = new Appointment();
        restricted.setId(5L);
        restricted.setAssignedAdvisor(otherAdvisor);
        when(appointments.findById(5L)).thenReturn(Optional.of(restricted));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "advisor-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ADVISOR"))));

        AppointmentServiceImpl service = new AppointmentServiceImpl(appointments, customers, vehicles, users);

        assertThrows(AccessDeniedException.class, () -> service.getById(5L));
    }

        @Test
        void advisorCannotReadJobCardLinkedToAnotherAdvisorsAppointment() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        User currentAdvisor = serviceAdvisor(10L, "advisor-a");
        User otherAdvisor = serviceAdvisor(20L, "advisor-b");
        when(users.findByUsernameIgnoreCase("advisor-a")).thenReturn(Optional.of(currentAdvisor));
        com.autoservicehub.entity.Appointment appointment = new com.autoservicehub.entity.Appointment();
        appointment.setAssignedAdvisor(otherAdvisor);
        JobCard restricted = new JobCard();
        restricted.setId(7L);
        restricted.setAppointment(appointment);
        when(jobCards.findById(7L)).thenReturn(Optional.of(restricted));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "advisor-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ADVISOR"))));
        JobCardServiceImpl service = new JobCardServiceImpl(jobCards, customers, vehicles,
            mock(MechanicRepository.class), appointments, mock(MechanicSkillRepository.class),
            mock(JobCardStatusHistoryRepository.class), mock(AuditLogRepository.class),
            new MechanicAccessService(users), new ServiceAdvisorAccessService(users),
            mock(DeliveryGateService.class));

        assertThrows(AccessDeniedException.class, () -> service.getById(7L));
        }

    @Test
    void advisorCannotMoveAssignedJobCardToUnassignedAppointment() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        User advisor = serviceAdvisor(10L, "advisor-a");
        when(users.findByUsernameIgnoreCase("advisor-a")).thenReturn(Optional.of(advisor));
        com.autoservicehub.entity.Appointment assignedAppointment = new com.autoservicehub.entity.Appointment();
        assignedAppointment.setId(4L);
        assignedAppointment.setAssignedAdvisor(advisor);
        JobCard jobCard = new JobCard();
        jobCard.setId(8L);
        jobCard.setAppointment(assignedAppointment);
        when(jobCards.findByIdForUpdate(8L)).thenReturn(Optional.of(jobCard));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "advisor-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ADVISOR"))));
        JobCardServiceImpl service = new JobCardServiceImpl(jobCards, customers, vehicles,
                mock(MechanicRepository.class), appointments, mock(MechanicSkillRepository.class),
                mock(JobCardStatusHistoryRepository.class), mock(AuditLogRepository.class),
                new MechanicAccessService(users), new ServiceAdvisorAccessService(users),
                mock(DeliveryGateService.class));
        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setAppointmentId(99L);

        assertThrows(AccessDeniedException.class, () -> service.update(8L, request));
        verify(jobCards, never()).save(any(JobCard.class));
    }

    @Test
    void advisorAppointmentListUsesAssignedAdvisorScope() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        User advisor = serviceAdvisor(10L, "advisor-a");
        when(users.findByUsernameIgnoreCase("advisor-a")).thenReturn(Optional.of(advisor));
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        when(appointments.findVisibleToAdvisor(10L, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty(pageable));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "advisor-a", "not-used", List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ADVISOR"))));

        new AppointmentServiceImpl(appointments, customers, vehicles, users).list(pageable);

        verify(appointments).findVisibleToAdvisor(10L, pageable);
        verify(appointments, never()).findAll(pageable);
    }

   @Test
void customerOptionalFieldsRoundTripThroughService() {

    CustomerRepository repository = mock(CustomerRepository.class);
    VehicleRepository vehicles = mock(VehicleRepository.class);
    JobCardRepository jobCards = mock(JobCardRepository.class);
    AppointmentRepository appointments = mock(AppointmentRepository.class);
    InvoiceRepository invoices = mock(InvoiceRepository.class);
    FeedbackRepository feedback = mock(FeedbackRepository.class);
    CommunicationRepository communications = mock(CommunicationRepository.class);

    when(repository.save(any(Customer.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

    CustomerRequestDTO request = new CustomerRequestDTO();
    request.setName("Sam Example");
    request.setPhone("5550101");
    request.setCity("Central");
    request.setPincode("00123");
    request.setLoyaltyTier("Gold");
    request.setNotes("Call before visit");
    request.setPreferences("Email contact");

    CustomerServiceImpl customerService = new CustomerServiceImpl(
            repository,
            vehicles,
            jobCards,
            appointments,
            invoices,
            feedback,
            communications
    );

    CustomerResponseDTO response = customerService.create(request);

    assertEquals("Sam Example", response.getName());
    assertEquals("Central", response.getCity());
    assertEquals("00123", response.getPincode());
    assertEquals("Gold", response.getLoyaltyTier());
    assertEquals("Call before visit", response.getNotes());
    assertEquals("Email contact", response.getPreferences());
}

    @Test
    void vehicleFuelTypeAndNotesRoundTripThroughService() {
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        Customer customer = customer(1L);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.save(any(Vehicle.class))).thenAnswer(invocation -> invocation.getArgument(0));
        VehicleRequestDTO request = new VehicleRequestDTO();
        request.setCustomerId(1L);
        request.setRegistrationNo("ABC-123");
        request.setFuelType("Hybrid");
        request.setNotes("Customer reports intermittent noise");

        VehicleResponseDTO response = new VehicleServiceImpl(vehicles, customers).create(request);

        assertEquals("Hybrid", response.getFuelType());
        assertEquals("Customer reports intermittent noise", response.getNotes());
        assertEquals(1L, response.getCustomerId());
    }

    @Test
    void inspectionCanLinkJobCardAndChecklistItems() {
        InspectionRepository inspections = mock(InspectionRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        com.autoservicehub.entity.JobCard card = new com.autoservicehub.entity.JobCard();
        card.setId(3L);
        card.setVehicle(vehicle);
        when(jobCards.findById(3L)).thenReturn(Optional.of(card));
        when(inspections.save(any(Inspection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        InspectionItemRequestDTO checklistItem = new InspectionItemRequestDTO();
        checklistItem.setChecklistItem("Brake condition");
        checklistItem.setFinding("Wear within limit");
        InspectionRequestDTO request = new InspectionRequestDTO();
        request.setJobCardId(3L);
        request.setItems(List.of(checklistItem));

        InspectionResponseDTO response = new InspectionServiceImpl(inspections, vehicles, jobCards,
            new ServiceAdvisorAccessService(mock(UserRepository.class))).create(request);

        assertEquals(2L, response.getVehicleId());
        assertEquals(3L, response.getJobCardId());
        assertEquals("Brake condition", response.getItems().get(0).getChecklistItem());
    }

    @Test
    void paymentMayReferenceInvoiceWithoutBreakingLegacyOptionalAssociation() {
        PaymentRepository payments = mock(PaymentRepository.class);
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        Invoice invoice = new Invoice();
        invoice.setId(17L);
        when(invoices.findById(17L)).thenReturn(Optional.of(invoice));
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentRequestDTO request = new PaymentRequestDTO();
        request.setInvoiceId(17L);

        PaymentResponseDTO response = new PaymentServiceImpl(payments, invoices,
            new ServiceAdvisorAccessService(mock(UserRepository.class))).create(request);

        assertEquals(17L, response.getInvoiceId());
    }

    @Test
    void appointmentRejectsVehicleOwnedByDifferentCustomer() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Customer requestedCustomer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer(3L));
        when(customers.findById(1L)).thenReturn(Optional.of(requestedCustomer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));

        AppointmentServiceImpl service = new AppointmentServiceImpl(appointments, customers, vehicles, users);
        AppointmentRequestDTO request = appointmentRequest(1L, 2L);

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void appointmentRejectsAdvisorAssignmentToNonAdvisorUser() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        Customer customer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer);
        User mechanicUser = new User();
        Role mechanicRole = new Role();
        mechanicRole.setName("MECHANIC");
        mechanicUser.setRole(mechanicRole);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        when(users.findById(9L)).thenReturn(Optional.of(mechanicUser));

        AppointmentRequestDTO request = appointmentRequest(1L, 2L);
        request.setAssignedAdvisorId(9L);
        AppointmentServiceImpl service = new AppointmentServiceImpl(appointments, customers, vehicles, users);

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void appointmentRejectsExactBayAndTimeConflict() {
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        UserRepository users = mock(UserRepository.class);
        Customer customer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        when(appointments.existsBayConflict(any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq("Bay A"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(true);

        AppointmentRequestDTO request = appointmentRequest(1L, 2L);
        request.setBay("Bay A");
        AppointmentServiceImpl service = new AppointmentServiceImpl(appointments, customers, vehicles, users);

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(appointments, never()).save(any(Appointment.class));
    }

    @Test
    void jobCardRejectsVehicleOwnedByDifferentCustomer() {
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Customer customer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer(3L));
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        JobCardServiceImpl service = jobCardService(jobCards, customers, vehicles);
        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setCustomerId(1L);
        request.setVehicleId(2L);
        request.setServiceType("Repair");

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(jobCards, never()).save(any(JobCard.class));
    }

    @Test
    void jobCardUpdateRejectsVehicleOwnedByDifferentCustomer() {
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Customer customer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer(3L));
        JobCard existing = new JobCard();
        existing.setId(12L);
        existing.setStatus("RECEIVED");
        when(jobCards.findByIdForUpdate(12L)).thenReturn(Optional.of(existing));
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        JobCardServiceImpl service = jobCardService(jobCards, customers, vehicles);
        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setCustomerId(1L);
        request.setVehicleId(2L);
        request.setServiceType("Repair");

        assertThrows(BusinessRuleException.class, () -> service.update(12L, request));
        verify(jobCards, never()).save(any(JobCard.class));
    }

    @Test
    void jobCardRejectsCustomerVehiclePairDifferentFromAppointment() {
        CustomerRepository customers = mock(CustomerRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        AppointmentRepository appointments = mock(AppointmentRepository.class);
        Customer customer = customer(1L);
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        vehicle.setCustomer(customer);
        com.autoservicehub.entity.Appointment appointment = new com.autoservicehub.entity.Appointment();
        appointment.setCustomer(customer(3L));
        appointment.setVehicle(vehicle);
        when(customers.findById(1L)).thenReturn(Optional.of(customer));
        when(vehicles.findById(2L)).thenReturn(Optional.of(vehicle));
        when(appointments.findById(4L)).thenReturn(Optional.of(appointment));
        JobCardServiceImpl service = jobCardService(jobCards, customers, vehicles, appointments);
        JobCardRequestDTO request = new JobCardRequestDTO();
        request.setCustomerId(1L);
        request.setVehicleId(2L);
        request.setAppointmentId(4L);
        request.setServiceType("Repair");

        assertThrows(BusinessRuleException.class, () -> service.create(request));
        verify(jobCards, never()).save(any(JobCard.class));
    }

    @Test
    void inspectionUpdateChecksRetainedJobCardWhenOnlyVehicleChanges() {
        InspectionRepository inspections = mock(InspectionRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Vehicle originalVehicle = new Vehicle();
        originalVehicle.setId(2L);
        Vehicle replacementVehicle = new Vehicle();
        replacementVehicle.setId(9L);
        com.autoservicehub.entity.JobCard card = new com.autoservicehub.entity.JobCard();
        card.setId(3L);
        card.setVehicle(originalVehicle);
        Inspection existing = new Inspection();
        existing.setId(6L);
        existing.setVehicle(originalVehicle);
        existing.setJobCard(card);
        when(inspections.findById(6L)).thenReturn(Optional.of(existing));
        when(vehicles.findById(9L)).thenReturn(Optional.of(replacementVehicle));
        InspectionServiceImpl service = new InspectionServiceImpl(inspections, vehicles, jobCards,
                new ServiceAdvisorAccessService(mock(UserRepository.class)));
        InspectionRequestDTO request = new InspectionRequestDTO();
        request.setVehicleId(9L);

        assertThrows(BusinessRuleException.class, () -> service.update(6L, request));
        verify(inspections, never()).save(any(Inspection.class));
    }

    @Test
    void followupUpdateRejectsCustomerDifferentFromRetainedJobCard() {
        FollowupRepository followups = mock(FollowupRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        Customer originalCustomer = customer(1L);
        Customer otherCustomer = customer(2L);
        com.autoservicehub.entity.JobCard card = new com.autoservicehub.entity.JobCard();
        card.setId(3L);
        card.setCustomer(originalCustomer);
        Followup existing = new Followup();
        existing.setId(7L);
        existing.setCustomer(originalCustomer);
        existing.setJobCard(card);
        when(followups.findById(7L)).thenReturn(Optional.of(existing));
        when(customers.findById(2L)).thenReturn(Optional.of(otherCustomer));
        FollowupServiceImpl service = new FollowupServiceImpl(followups, customers, jobCards,
                new ServiceAdvisorAccessService(mock(UserRepository.class)));
        FollowupRequestDTO request = new FollowupRequestDTO();
        request.setCustomerId(2L);

        assertThrows(BusinessRuleException.class, () -> service.update(7L, request));
        verify(followups, never()).save(any(Followup.class));
    }

    @Test
    void inspectionCanRemainUnlinkedAndFollowupCanOmitJobCard() {
        InspectionRepository inspections = mock(InspectionRepository.class);
        VehicleRepository vehicles = mock(VehicleRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        when(inspections.save(any(Inspection.class))).thenAnswer(invocation -> invocation.getArgument(0));
        InspectionResponseDTO inspection = new InspectionServiceImpl(inspections, vehicles, jobCards,
                new ServiceAdvisorAccessService(mock(UserRepository.class))).create(new InspectionRequestDTO());
        assertNull(inspection.getVehicleId());
        assertNull(inspection.getJobCardId());

        FollowupRepository followups = mock(FollowupRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        Customer customer = customer(11L);
        when(customers.findById(11L)).thenReturn(Optional.of(customer));
        when(followups.save(any(Followup.class))).thenAnswer(invocation -> invocation.getArgument(0));
        FollowupRequestDTO request = new FollowupRequestDTO();
        request.setCustomerId(11L);
        FollowupResponseDTO followup = new FollowupServiceImpl(followups, customers, jobCards,
                new ServiceAdvisorAccessService(mock(UserRepository.class))).create(request);
        assertNull(followup.getJobCardId());
    }

    @Test
    void estimateComputesSubtotalAndTotalFromItemsInsteadOfClientTotal() {
        EstimateRepository estimates = mock(EstimateRepository.class);
        JobCardRepository jobCards = mock(JobCardRepository.class);
        EstimateServiceImpl service = new EstimateServiceImpl(estimates, jobCards,
            new ServiceAdvisorAccessService(mock(UserRepository.class)));
        EstimateItemRequestDTO item = new EstimateItemRequestDTO();
        item.setDescription("Brake pads");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("45.00"));
        EstimateRequestDTO request = new EstimateRequestDTO();
        request.setItems(List.of(item));
        request.setDiscount(new BigDecimal("10.00"));
        request.setTax(new BigDecimal("8.00"));
        request.setTotal(new BigDecimal("999.00"));
        when(estimates.save(any(Estimate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EstimateResponseDTO response = service.create(request);

        assertEquals(new BigDecimal("90.00"), response.getSubtotal());
        assertEquals(new BigDecimal("88.00"), response.getTotal());
        assertEquals(new BigDecimal("90.00"), response.getItems().get(0).getLineTotal());
        assertTrue(response.getTotal().compareTo(request.getTotal()) != 0);
    }

    private void assertRoleExpression(String methodName, String expected) throws Exception {
        PreAuthorize annotation = java.util.Arrays.stream(ServicePackageController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst().orElseThrow().getAnnotation(PreAuthorize.class);
        assertEquals(expected, annotation.value());
    }

    private void assertAdvisorAccess(Class<?> controller, String methodName, boolean expected) throws Exception {
        PreAuthorize annotation = java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst().orElseThrow().getAnnotation(PreAuthorize.class);
        assertEquals(expected, annotation.value().contains("SERVICE_ADVISOR"));
    }

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }

    private User serviceAdvisor(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        Role role = new Role();
        role.setName("SERVICE_ADVISOR");
        user.setRole(role);
        return user;
    }

    private AppointmentRequestDTO appointmentRequest(Long customerId, Long vehicleId) {
        AppointmentRequestDTO request = new AppointmentRequestDTO();
        request.setCustomerId(customerId);
        request.setVehicleId(vehicleId);
        request.setServiceType("Service");
        request.setAppointmentAt(LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0));
        return request;
    }

    private JobCardServiceImpl jobCardService(JobCardRepository jobCards, CustomerRepository customers,
                                               VehicleRepository vehicles) {
        return jobCardService(jobCards, customers, vehicles, mock(AppointmentRepository.class));
    }

    private JobCardServiceImpl jobCardService(JobCardRepository jobCards, CustomerRepository customers,
                                               VehicleRepository vehicles, AppointmentRepository appointments) {
        UserRepository users = mock(UserRepository.class);
        return new JobCardServiceImpl(jobCards, customers, vehicles, mock(MechanicRepository.class), appointments,
                mock(MechanicSkillRepository.class), mock(JobCardStatusHistoryRepository.class),
                mock(AuditLogRepository.class), new MechanicAccessService(users),
                new ServiceAdvisorAccessService(users), mock(DeliveryGateService.class));
    }
}