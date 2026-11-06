package com.autoservicehub.dto;

import com.autoservicehub.entity.PackageItemType;
import lombok.Getter;
import lombok.Setter;

/** Outbound payload for one line of a service package's contents. */
@Getter
@Setter
public class PackageItemResponseDTO {

    private Long id;
    private String itemName;
    private PackageItemType itemType;

    public PackageItemResponseDTO() {
    }

    public PackageItemResponseDTO(Long id, String itemName, PackageItemType itemType) {
        this.id = id;
        this.itemName = itemName;
        this.itemType = itemType;
    }
}