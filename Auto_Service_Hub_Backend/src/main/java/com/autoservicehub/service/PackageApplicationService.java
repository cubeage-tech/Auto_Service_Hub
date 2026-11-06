package com.autoservicehub.service;

import com.autoservicehub.dto.PackageApplicationRequestDTO;
import com.autoservicehub.dto.PackageApplicationResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Applying a service package to a customer's vehicle (SRS 4.8).
 *
 * <p>Separate from {@link ServicePackageService} because the two answer different
 * questions: that one manages the catalogue, this one records what was sold from
 * it. Keeping them apart is what lets the catalogue be edited freely without
 * touching what customers already agreed to.
 */
public interface PackageApplicationService {

    /**
     * Record that a package is being applied to a customer's vehicle.
     *
     * @throws com.autoservicehub.exception.ResourceNotFoundException if the
     *         package, customer or vehicle does not exist (404)
     * @throws com.autoservicehub.exception.BusinessRuleException if the
     *         package is inactive, the vehicle is not this customer's, or the
     *         package is already applied to that vehicle (409)
     */
    PackageApplicationResponseDTO apply(PackageApplicationRequestDTO request);

    /** Every application, newest first. */
    Page<PackageApplicationResponseDTO> list(Pageable pageable);

    /** Applications of a single package, newest first. */
    Page<PackageApplicationResponseDTO> listByPackage(Long servicePackageId, Pageable pageable);

    /** One application by id. */
    PackageApplicationResponseDTO getById(Long id);
}
