package com.autoservicehub.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ServicePackageResponseDTO {
    private Long id;
    private String name;
    private String type;
    private BigDecimal price;
    private Boolean active;

    /** How long the package takes or stays valid; null when not set. */
    private String duration;

    /** GST percentage applied on top of the price; null when not set. */
    private BigDecimal gstRate;

    /** Advertised discount percentage; null when not set. */
    private BigDecimal discountPercentage;

    /** Accent colour token; null when not set. */
    private String color;

    /** Whether the card shows a "Popular" badge; never null — defaults to false. */
    private Boolean popular;

    /**
     * The bundled services and parts, in the order they were saved.
     *
     * <p>Always a list, never null, so a client can iterate without a null
     * check even for a package created before this field existed.
     */
    private List<PackageItemResponseDTO> items = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}