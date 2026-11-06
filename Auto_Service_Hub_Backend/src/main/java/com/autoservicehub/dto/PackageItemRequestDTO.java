package com.autoservicehub.dto;

import com.autoservicehub.entity.PackageItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * One line in a service package's contents — a service performed or a part
 * supplied.
 *
 * <p>A structured object rather than a delimited string, so the two kinds stay
 * distinguishable on the way back out: the package card renders services as a
 * ticked list and parts as tags, and a flat string list could not say which was
 * which.
 */
@Getter
@Setter
public class PackageItemRequestDTO {

    @NotBlank
    private String itemName;

    /** SERVICE or PART. An unrecognised value is rejected with a 400. */
    @NotNull
    private PackageItemType itemType;

    public PackageItemRequestDTO() {
    }

    public PackageItemRequestDTO(String itemName, PackageItemType itemType) {
        this.itemName = itemName;
        this.itemType = itemType;
    }
}