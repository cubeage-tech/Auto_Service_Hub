package com.autoservicehub.service;

import com.autoservicehub.entity.JobCard;
import com.autoservicehub.entity.Mechanic;
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
public class MechanicAccessService {

    private final UserRepository userRepository;

    public boolean isManagementUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(authority ->
                Objects.equals(authority.getAuthority(), "ROLE_ADMIN")
                        || Objects.equals(authority.getAuthority(), "ROLE_OWNER")
                        || Objects.equals(authority.getAuthority(), "ROLE_MANAGER")
                        || Objects.equals(authority.getAuthority(), "ROLE_SERVICE_ADVISOR"));
    }

    public boolean isMechanicUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && !isManagementUser() && authentication.getAuthorities().stream()
                .anyMatch(authority -> Objects.equals(authority.getAuthority(), "ROLE_MECHANIC"));
    }

    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required.");
        }
        return userRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found."));
    }

    public Mechanic currentMechanic() {
        Mechanic mechanic = currentUser().getMechanic();
        if (mechanic == null) {
            throw new AccessDeniedException("No mechanic profile is linked to the authenticated user.");
        }
        return mechanic;
    }

    public void assertCanAccess(Mechanic mechanic) {
        if (isMechanicUser() && !Objects.equals(currentMechanic().getId(), mechanic.getId())) {
            throw new AccessDeniedException("Mechanics can access only their own records.");
        }
    }

    public void assertCanAccess(JobCard jobCard) {
        if (isMechanicUser() && !isAssignedTo(jobCard, currentMechanic())) {
            throw new AccessDeniedException("Mechanics can access only assigned job cards.");
        }
    }

    public boolean isAssignedTo(JobCard jobCard, Mechanic mechanic) {
        return mechanic != null && (Objects.equals(jobCard.getMechanic() == null ? null : jobCard.getMechanic().getId(), mechanic.getId())
                || jobCard.getAssignedMechanics().stream().anyMatch(assigned -> Objects.equals(assigned.getId(), mechanic.getId())));
    }
}