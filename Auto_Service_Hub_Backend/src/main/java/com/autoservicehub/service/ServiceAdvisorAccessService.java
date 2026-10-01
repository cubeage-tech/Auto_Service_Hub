package com.autoservicehub.service;

import com.autoservicehub.entity.Appointment;
import com.autoservicehub.entity.Estimate;
import com.autoservicehub.entity.Followup;
import com.autoservicehub.entity.Inspection;
import com.autoservicehub.entity.Invoice;
import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Payment;
import com.autoservicehub.entity.User;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ServiceAdvisorAccessService {

    private final UserRepository userRepository;

    public boolean isAdvisorUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE_ADVISOR".equals(authority.getAuthority()));
    }

    public User currentAdvisor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }
        return userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated Service Advisor not found."));
    }

    public void assertCanAccess(Appointment appointment) {
        if (isAdvisorUser() && appointment != null && appointment.getAssignedAdvisor() != null
                && !Objects.equals(currentAdvisor().getId(), appointment.getAssignedAdvisor().getId())) {
            throw new AccessDeniedException("Service Advisors can access only their assigned appointments.");
        }
    }

    public void assertCanAccess(JobCard jobCard) {
        if (jobCard != null) assertCanAccess(jobCard.getAppointment());
    }

    public void assertCanAccess(Inspection inspection) {
        if (inspection != null) assertCanAccess(inspection.getJobCard());
    }

    public void assertCanAccess(Estimate estimate) {
        if (estimate != null) assertCanAccess(estimate.getJobCard());
    }

    public void assertCanAccess(Followup followup) {
        if (followup != null) assertCanAccess(followup.getJobCard());
    }

    public void assertCanAccess(Invoice invoice) {
        if (invoice != null) assertCanAccess(invoice.getJobCard());
    }

    public void assertCanAccess(Payment payment) {
        if (payment != null && payment.getInvoice() != null) assertCanAccess(payment.getInvoice());
    }
}
