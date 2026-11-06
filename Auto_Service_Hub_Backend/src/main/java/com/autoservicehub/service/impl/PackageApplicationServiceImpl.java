package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PackageApplicationRequestDTO;
import com.autoservicehub.dto.PackageApplicationResponseDTO;
import com.autoservicehub.entity.Customer;
import com.autoservicehub.entity.PackageApplication;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.entity.Vehicle;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.CustomerRepository;
import com.autoservicehub.repository.PackageApplicationRepository;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.repository.VehicleRepository;
import com.autoservicehub.service.PackageApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the sale of a service package to a customer.
 *
 * <p>Every rule here is checked <em>before</em> the write so the caller gets a
 * specific 409 naming the actual problem. The database constraints are the backstop
 * for anything a check cannot cover — chiefly two advisors pressing Apply at the
 * same instant, which no service-level check can win.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PackageApplicationServiceImpl implements PackageApplicationService {

    private final PackageApplicationRepository repository;
    private final ServicePackageRepository servicePackageRepository;
    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    @Override
    public PackageApplicationResponseDTO apply(PackageApplicationRequestDTO request) {
        ServicePackage servicePackage = servicePackageRepository.findById(request.getServicePackageId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ServicePackage not found: " + request.getServicePackageId()));

        /*
         * An inactive package is withdrawn from sale. Applying one would keep a
         * package in circulation after the workshop decided to stop offering it —
         * exactly what the `active` flag exists to prevent (ServicePackage.java).
         * Re-activating and applying later is the supported route.
         */
        if (!Boolean.TRUE.equals(servicePackage.getActive())) {
            throw new BusinessRuleException(
                    "'" + servicePackage.getName() + "' is inactive and cannot be applied. "
                            + "Activate the package first.");
        }

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found: " + request.getCustomerId()));

        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vehicle not found: " + request.getVehicleId()));

        /*
         * The vehicle must be one of this customer's. The client filters the
         * picker, but that is a convenience, not a guarantee — the same request
         * could name any two ids. Recording a package against a customer who does
         * not own the vehicle would corrupt both the customer's history and the
         * vehicle's, and nothing downstream would notice.
         */
        Long vehicleOwnerId = vehicle.getCustomer() != null ? vehicle.getCustomer().getId() : null;
        if (!customer.getId().equals(vehicleOwnerId)) {
            throw new BusinessRuleException(
                    "That vehicle does not belong to the selected customer.");
        }

        /*
         * Duplicate guard. The unique constraint on (service_package_id, vehicle_id)
         * is what actually guarantees this; the check exists to turn the usual case
         * into an actionable message rather than a generic DATA_CONFLICT.
         */
        if (repository.existsByServicePackageIdAndVehicleId(
                servicePackage.getId(), vehicle.getId())) {
            throw new BusinessRuleException(
                    "'" + servicePackage.getName() + "' has already been applied to "
                            + vehicleLabel(vehicle) + ".");
        }

        PackageApplication application = new PackageApplication();
        application.setServicePackage(servicePackage);
        application.setCustomer(customer);
        application.setVehicle(vehicle);

        // The snapshot. Copied now, not read back later — see PackageApplication.
        application.setPackageName(servicePackage.getName());
        application.setPackageType(servicePackage.getType());
        application.setPackagePrice(servicePackage.getPrice());
        application.setPackageGstRate(servicePackage.getGstRate());
        application.setPackageDiscountPercentage(servicePackage.getDiscountPercentage());
        application.setPackageDuration(servicePackage.getDuration());

        return toResponse(repository.save(application));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PackageApplicationResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PackageApplicationResponseDTO> listByPackage(Long servicePackageId, Pageable pageable) {
        // 404 rather than an empty page: a caller filtering by a package that does
        // not exist has made a mistake worth surfacing, and this also stops the
        // endpoint being used to probe which ids exist.
        if (!servicePackageRepository.existsById(servicePackageId)) {
            throw new ResourceNotFoundException("ServicePackage not found: " + servicePackageId);
        }
        return repository
                .findByServicePackageIdOrderByCreatedAtDescIdDesc(servicePackageId, pageable)
                .map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PackageApplicationResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PackageApplication not found: " + id)));
    }

    /** Best available label for a vehicle in an error message. */
    private String vehicleLabel(Vehicle vehicle) {
        if (vehicle.getRegistrationNo() != null && !vehicle.getRegistrationNo().isBlank()) {
            return vehicle.getRegistrationNo();
        }
        String make = vehicle.getMake() == null ? "" : vehicle.getMake().trim();
        String model = vehicle.getModel() == null ? "" : vehicle.getModel().trim();
        String label = (make + " " + model).trim();
        return label.isEmpty() ? "vehicle #" + vehicle.getId() : label;
    }

    private PackageApplicationResponseDTO toResponse(PackageApplication a) {
        PackageApplicationResponseDTO dto = new PackageApplicationResponseDTO();
        dto.setId(a.getId());
        dto.setServicePackageId(a.getServicePackage().getId());

        // Snapshotted terms — what this customer actually agreed to.
        dto.setPackageName(a.getPackageName());
        dto.setPackageType(a.getPackageType());
        dto.setPackagePrice(a.getPackagePrice());
        dto.setPackageGstRate(a.getPackageGstRate());
        dto.setPackageDiscountPercentage(a.getPackageDiscountPercentage());
        dto.setPackageDuration(a.getPackageDuration());

        // Owner and vehicle read live through the relationships, so a corrected
        // name or registration is reflected without rewriting the snapshot.
        if (a.getCustomer() != null) {
            dto.setCustomerId(a.getCustomer().getId());
            dto.setCustomerName(a.getCustomer().getName());
        }
        if (a.getVehicle() != null) {
            dto.setVehicleId(a.getVehicle().getId());
            dto.setVehicleRegistrationNo(a.getVehicle().getRegistrationNo());
        }

        dto.setAppliedAt(a.getCreatedAt());
        dto.setUpdatedAt(a.getUpdatedAt());
        return dto;
    }
}
