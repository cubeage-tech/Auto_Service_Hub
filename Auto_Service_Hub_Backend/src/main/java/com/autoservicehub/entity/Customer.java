package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;


/**
 * Maps to the 'customers' table (SRS section 8.2 High-Level Entities).
 */
@Getter
@Setter
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(name = "name")
    private String name;
    @Column(name = "phone")
    private String phone;
    @Column(name = "email")
    private String email;
    @Column(name = "address")
    private String address;
    @Column(name = "city")
    private String city;
    @Column(name = "pincode")
    private String pincode;
    @Column(name = "loyalty_tier")
    private String loyaltyTier;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
    @Column(name = "preferences", columnDefinition = "TEXT")
    private String preferences;
    @Column(name = "status")
    private String status;
}
