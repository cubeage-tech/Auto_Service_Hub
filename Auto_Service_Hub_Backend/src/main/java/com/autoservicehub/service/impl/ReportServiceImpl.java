package com.autoservicehub.service.impl;

import com.autoservicehub.dto.CustomerGrowthReportDTO;
import com.autoservicehub.dto.DashboardSummaryDTO;
import com.autoservicehub.dto.MechanicPerformanceReportDTO;
import com.autoservicehub.dto.RevenueReportDTO;
import com.autoservicehub.dto.ServiceAdvisorOperationsReportDTO;
import com.autoservicehub.entity.Mechanic;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.AppointmentRepository;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.FeedbackRepository;
import com.autoservicehub.repository.InvoiceRepository;
import com.autoservicehub.repository.JobCardRepository;
import com.autoservicehub.repository.MechanicRepository;
import com.autoservicehub.repository.PartRepository;
import com.autoservicehub.repository.PaymentRepository;
import com.autoservicehub.repository.UserRepository;
import com.autoservicehub.service.ReportService;
import com.autoservicehub.service.MechanicAccessService;
import com.autoservicehub.service.ServiceAdvisorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final JobCardRepository jobCardRepository;
    private final InvoiceRepository invoiceRepository;
    private final PartRepository partRepository;
    private final AppointmentRepository appointmentRepository;
    private final CustomerRepository customerRepository;
    private final MechanicRepository mechanicRepository;
    private final FeedbackRepository feedbackRepository;
    private final PaymentRepository paymentRepository;
    private final MechanicAccessService accessService;
    private final UserRepository userRepository;
    private final ServiceAdvisorAccessService advisorAccessService;

    @Override
    public DashboardSummaryDTO getDashboardSummary() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        if (advisorAccessService.isAdvisorUser()) {
            Long advisorId = advisorAccessService.currentAdvisor().getId();
            long assignedJobsToday = jobCardRepository.countAssignedForAdvisorBetween(advisorId, startOfDay, endOfDay);
            long activeAssignedJobs = jobCardRepository.countActiveAssignmentsForAdvisor(advisorId);
            long mechanicWorkload = jobCardRepository.countActiveMechanicWorkloadForAdvisor(advisorId);
            long lowStockParts = partRepository.countLowStockParts();
            LocalDateTime now = LocalDateTime.now();
                long upcomingAppointments = appointmentRepository.countForAdvisorBetween(advisorId, now, now.plusDays(7));
            return new DashboardSummaryDTO(
                assignedJobsToday,
                jobCardRepository.countCompletedForAdvisor(advisorId),
                activeAssignedJobs,
                paymentRepository.sumRecordedPaymentsForAdvisorBetween(advisorId, startOfDay, endOfDay),
                lowStockParts,
                upcomingAppointments,
                invoiceRepository.countPendingForAdvisor(advisorId),
                    mechanicWorkload);
        }

        long todaysJobs    = jobCardRepository.countByAssignedDateBetween(startOfDay, endOfDay);
        long completedJobs = jobCardRepository.countByStatus("DELIVERED");
        long pendingJobs   = jobCardRepository.countByStatus("RECEIVED")
                           + jobCardRepository.countByStatus("INSPECTION")
                           + jobCardRepository.countByStatus("IN_REPAIR")
                           + jobCardRepository.countByStatus("QUALITY_CHECK");
        long lowStockParts = partRepository.countLowStockParts();
        long upcomingAppts = appointmentRepository.countByAppointmentAtBetween(
                LocalDateTime.now(), LocalDateTime.now().plusDays(7));

        return new DashboardSummaryDTO(
                todaysJobs, completedJobs, pendingJobs,
                paymentRepository.sumRecordedPaymentsBetween(startOfDay, endOfDay),
            lowStockParts, upcomingAppts,
            invoiceRepository.countByStatusIgnoreCase("PENDING"),
            jobCardRepository.countActiveAssignedJobs());
    }

    @Override
    public RevenueReportDTO getRevenueReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException("Revenue report requires both from and to dates.");
        }
        if (from.isAfter(to)) {
            throw new BusinessRuleException("Revenue report date range is invalid: from date cannot be after to date.");
        }

        BigDecimal totalRevenue = invoiceRepository.sumTotalByInvoiceDateBetween(from, to);
        if (totalRevenue == null) {
            totalRevenue = BigDecimal.ZERO;
        }

        long invoiceCount = invoiceRepository.countByInvoiceDateBetween(from, to);
        BigDecimal averageInvoiceValue = invoiceCount == 0
                ? BigDecimal.ZERO
                : totalRevenue.divide(BigDecimal.valueOf(invoiceCount), 2, RoundingMode.HALF_UP);

        return new RevenueReportDTO(from, to, totalRevenue, invoiceCount, averageInvoiceValue);
    }

    @Override
    public ServiceAdvisorOperationsReportDTO getAdvisorOperationsReport(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessRuleException("Advisor report requires a valid date range.");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }
        User advisor = userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated Service Advisor not found."));
        boolean serviceAdvisorAuthority = authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_SERVICE_ADVISOR".equals(authority.getAuthority()));
        if (!serviceAdvisorAuthority) {
            throw new AccessDeniedException("This report is available only to Service Advisors.");
        }

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();
        return new ServiceAdvisorOperationsReportDTO(
                from,
                to,
                advisor.getId(),
                advisor.getFullName(),
                appointmentRepository.countForAdvisorBetween(advisor.getId(), fromTime, toTime),
                jobCardRepository.countActiveForAdvisor(advisor.getId(), fromTime, toTime),
                jobCardRepository.countCompletedForAdvisor(advisor.getId(), fromTime, toTime),
                invoiceRepository.countPendingForAdvisorBetween(advisor.getId(), from, to.plusDays(1)));
    }

    @Override
    public MechanicPerformanceReportDTO getMechanicPerformanceReport(LocalDate from, LocalDate to, Long mechanicId) {
        if (from == null || to == null) {
            throw new BusinessRuleException("Mechanic performance report requires both from and to dates.");
        }
        if (from.isAfter(to)) {
            throw new BusinessRuleException("Mechanic performance report date range is invalid: from date cannot be after to date.");
        }

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.plusDays(1).atStartOfDay();

        if (accessService.isMechanicUser()) {
            Long authenticatedMechanicId = accessService.currentMechanic().getId();
            if (mechanicId != null && !mechanicId.equals(authenticatedMechanicId)) {
                throw new AccessDeniedException("Mechanics can view only their own performance report.");
            }
            mechanicId = authenticatedMechanicId;
        }

        String mechanicName = "ALL MECHANICS";
        if (mechanicId != null) {
            Mechanic mechanic = mechanicRepository.findById(mechanicId).orElse(null);
            if (mechanic == null) throw new ResourceNotFoundException("Mechanic not found: " + mechanicId);
            mechanicName = mechanic.getName();
        }

        List<com.autoservicehub.entity.JobCard> completedCards = mechanicId == null
            ? jobCardRepository.findCompletedBetween(fromDateTime, toDateTime)
            : jobCardRepository.findCompletedForMechanicBetween(mechanicId, fromDateTime, toDateTime);
        long completedJobs = completedCards.size();

        long turnaroundSeconds = 0;
        long turnaroundSamples = 0;
        for (com.autoservicehub.entity.JobCard jobCard : completedCards) {
                LocalDateTime startedAt = jobCard.getStartedDate() != null ? jobCard.getStartedDate()
                    : jobCard.getAssignedDate() != null ? jobCard.getAssignedDate() : jobCard.getCreatedAt();
            if (startedAt != null && jobCard.getCompletedDate() != null && !jobCard.getCompletedDate().isBefore(startedAt)) {
            turnaroundSeconds += Duration.between(startedAt, jobCard.getCompletedDate()).getSeconds();
            turnaroundSamples++;
            }
        }
        BigDecimal averageTurnaroundHours = turnaroundSamples == 0 ? null
            : BigDecimal.valueOf(turnaroundSeconds).divide(BigDecimal.valueOf(turnaroundSamples * 3600), 2, RoundingMode.HALF_UP);

        BigDecimal totalRevenue = invoiceRepository.sumTotalByMechanicAndJobStatus(mechanicId, "DELIVERED", fromDateTime, toDateTime);
        if (totalRevenue == null) {
            totalRevenue = BigDecimal.ZERO;
        }

        BigDecimal averageRevenuePerJob = completedJobs == 0
                ? BigDecimal.ZERO
                : totalRevenue.divide(BigDecimal.valueOf(completedJobs), 2, RoundingMode.HALF_UP);

        long activeAssignedJobs = mechanicId == null
            ? jobCardRepository.countActiveAssignedJobs()
            : jobCardRepository.countActiveAssignments(mechanicId);
        Double averageFeedbackRating = feedbackRepository.averageRatingBetween(mechanicId, fromDateTime, toDateTime);
        long feedbackCount = feedbackRepository.countBetween(mechanicId, fromDateTime, toDateTime);

        return new MechanicPerformanceReportDTO(from, to, mechanicId, mechanicName, completedJobs, totalRevenue,
            averageRevenuePerJob, averageTurnaroundHours, activeAssignedJobs, averageFeedbackRating, feedbackCount);
    }

    @Override
    public Object getPartsUsageReport(LocalDate from, LocalDate to) {
        return null;
    }

    @Override
    public CustomerGrowthReportDTO getCustomerGrowthReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException("Customer growth report requires both from and to dates.");
        }
        if (from.isAfter(to)) {
            throw new BusinessRuleException("Customer growth report date range is invalid: from date cannot be after to date.");
        }

        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.plusDays(1).atStartOfDay();

        long newCustomerCount = customerRepository.countByCreatedAtBetween(fromDateTime, toDateTime);
        return new CustomerGrowthReportDTO(from, to, newCustomerCount);
    }

    @Override
    public Object getProfitAnalysisReport(LocalDate from, LocalDate to) {
        return null;
    }
}
