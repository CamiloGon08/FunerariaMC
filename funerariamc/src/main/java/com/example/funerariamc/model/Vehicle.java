package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.VehicleStatus;
import com.example.funerariamc.model.enums.VehicleType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

/**
 * Funeral home vehicle (hearse or bus) and the validity of its documents.
 */
@Entity
@Table(name = "vehicle")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plate", nullable = false, unique = true)
    private String plate;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false)
    private VehicleType vehicleType;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "soat_expiry_date")
    private LocalDate soatExpiryDate;

    @Column(name = "soat_file_url")
    private String soatFileUrl;

    @Column(name = "technical_review_expiry_date")
    private LocalDate technicalReviewExpiryDate;

    @Column(name = "technical_review_file_url")
    private String technicalReviewFileUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VehicleStatus status = VehicleStatus.AVAILABLE;
}
