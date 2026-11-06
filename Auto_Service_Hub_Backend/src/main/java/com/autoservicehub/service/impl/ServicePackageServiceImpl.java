package com.autoservicehub.service.impl;

import com.autoservicehub.dto.PackageItemRequestDTO;
import com.autoservicehub.dto.PackageItemResponseDTO;
import com.autoservicehub.dto.ServicePackageRequestDTO;
import com.autoservicehub.dto.ServicePackageResponseDTO;
import com.autoservicehub.entity.PackageItem;
import com.autoservicehub.entity.PackageItemType;
import com.autoservicehub.entity.ServicePackage;
import com.autoservicehub.exception.BusinessRuleException;
import com.autoservicehub.exception.ResourceNotFoundException;
import com.autoservicehub.repository.ServicePackageRepository;
import com.autoservicehub.service.ServicePackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ServicePackageServiceImpl implements ServicePackageService {

    /**
     * Accent colour tokens the package card stylesheet defines. A colour outside
     * this set would render as an unstyled card, so it is rejected up front
     * rather than stored and hoped for.
     */
    static final Set<String> ALLOWED_COLORS =
            Set.of("lime", "blue", "purple", "orange", "green", "red");

    private final ServicePackageRepository repository;

    @Override
    public ServicePackageResponseDTO create(ServicePackageRequestDTO request) {
        ServicePackage entity = new ServicePackage();
        mapToEntity(request, entity);
        // A new package is offered to customers unless explicitly withdrawn.
        if (entity.getActive() == null) {
            entity.setActive(true);
        }
        if (entity.getPopular() == null) {
            entity.setPopular(false);
        }
        replaceItems(request.getItems(), entity);
        return toResponse(repository.save(entity));
    }

    @Override
    public ServicePackageResponseDTO update(Long id, ServicePackageRequestDTO request) {
        ServicePackage existing = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePackage not found: " + id));
        // mapToEntity copies the request verbatim, so capture the stored flags
        // first: honour `active`/`popular` only when the caller actually sent
        // them, and otherwise put back what was already there. Defaulting them to
        // true/false here, as create does, would silently reactivate a package
        // that any partial update forgot to mention.
        Boolean storedActive = existing.getActive();
        Boolean storedPopular = existing.getPopular();
        mapToEntity(request, existing);
        if (existing.getActive() == null) {
            existing.setActive(storedActive);
        }
        if (existing.getPopular() == null) {
            existing.setPopular(storedPopular);
        }
        replaceItems(request.getItems(), existing);
        return toResponse(repository.save(existing));
    }

    @Override
    @Transactional(readOnly = true)
    public ServicePackageResponseDTO getById(Long id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ServicePackage not found: " + id)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ServicePackageResponseDTO> list(Pageable pageable) {
        return repository.findAll(pageable).map(this::toResponse);
    }

    @Override
    public void delete(Long id) {
        if (!repository.existsById(id)) throw new ResourceNotFoundException("ServicePackage not found: " + id);
        // Cascade + orphanRemoval on ServicePackage.items removes the package's
        // item rows with it.
        repository.deleteById(id);
    }

    private void mapToEntity(ServicePackageRequestDTO r, ServicePackage e) {
        e.setName(r.getName());
        e.setType(r.getType());
        e.setPrice(r.getPrice());
        e.setDuration(trimToNull(r.getDuration()));
        e.setGstRate(r.getGstRate());
        e.setDiscountPercentage(r.getDiscountPercentage());
        e.setColor(normalizeColor(r.getColor()));
        // Copied verbatim, null included; the caller decides what a null means.
        e.setActive(r.getActive());
        e.setPopular(r.getPopular());
    }

    /**
     * Replaces the package's items with the supplied list.
     *
     * <p>Full replacement rather than a merge: a package's contents are an
     * ordered checklist that the user edits as a whole, so re-sending a shorter
     * list must remove the dropped lines rather than keep them.
     *
     * <p>A null list means "not mentioned" and leaves the existing items alone,
     * so an older client that knows only about name/type/price does not wipe a
     * package's contents on save. An empty list genuinely clears them.
     */
    private void replaceItems(List<PackageItemRequestDTO> requested, ServicePackage target) {
        if (requested == null) {
            return;
        }
        target.getItems().clear();
        for (PackageItemRequestDTO item : requested) {
            if (item == null || item.getItemName() == null || item.getItemName().isBlank()) {
                continue;
            }
            PackageItem entity = new PackageItem();
            entity.setItemName(item.getItemName().trim());
            entity.setItemType(item.getItemType() != null ? item.getItemType().name() : null);
            entity.setServicePackage(target);
            target.getItems().add(entity);
        }
    }

    /**
     * Validates the colour token and lower-cases it, so a client sending
     * "Lime" gets the same stored value as one sending "lime".
     *
     * @throws BusinessRuleException when the token is not one the card stylesheet
     *         defines. Blank is treated as "not set" rather than an error.
     */
    private String normalizeColor(String color) {
        String trimmed = trimToNull(color);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toLowerCase();
        if (!ALLOWED_COLORS.contains(normalized)) {
            throw new BusinessRuleException(
                    "Unsupported package colour '" + trimmed + "'. Allowed colours: "
                            + String.join(", ", ALLOWED_COLORS) + ".");
        }
        return normalized;
    }

    /** Blank optional fields are stored as null rather than as an empty string. */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ServicePackageResponseDTO toResponse(ServicePackage e) {
        ServicePackageResponseDTO dto = new ServicePackageResponseDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setType(e.getType());
        dto.setPrice(e.getPrice());
        dto.setActive(e.getActive());
        dto.setDuration(e.getDuration());
        dto.setGstRate(e.getGstRate());
        dto.setDiscountPercentage(e.getDiscountPercentage());
        dto.setColor(e.getColor());
        dto.setPopular(e.getPopular() != null ? e.getPopular() : Boolean.FALSE);
        // Read inside the caller's transaction, so the lazy item collection
        // loads here rather than after the session has closed.
        for (PackageItem item : e.getItems()) {
            dto.getItems().add(new PackageItemResponseDTO(
                    item.getId(), item.getItemName(), PackageItemType.fromStored(item.getItemType())));
        }
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }
}