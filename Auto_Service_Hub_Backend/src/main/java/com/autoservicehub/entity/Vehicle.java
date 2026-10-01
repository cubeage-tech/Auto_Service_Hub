package com.autoservicehub.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "vehicles")
public class Vehicle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "registration_no")
    private String registrationNo;
    @Column(name = "make")
    private String make;
    @Column(name = "model")
    private String model;
    @Column(name = "variant")
    private String variant;
    @Column(name = "fuel_type")
    private String fuelType;
    @Column(name = "year")
    private Integer year;
    @Column(name = "engine_no")
    private String engineNo;
    @Column(name = "chassis_no")
    private String chassisNo;
    @Column(name = "mileage")
    private Integer mileage;
    @Column(name = "insurance_expiry")
    private LocalDate insuranceExpiry;
    @Column(name = "warranty_expiry")
    private LocalDate warrantyExpiry;
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
