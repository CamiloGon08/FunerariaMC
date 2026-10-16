package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ExhumationDestination;
import com.example.funerariamc.model.enums.ExhumationReason;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Exhumation of a deceased person from a vault.
 */
@Entity
@Table(name = "exhumation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Exhumation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deceased_id", nullable = false)
    private Deceased deceased;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vault_id", nullable = false)
    private Vault vault;

    @Column(name = "exhumation_date", nullable = false)
    private LocalDate exhumationDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    private ExhumationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "destination", nullable = false)
    private ExhumationDestination destination;

    @Column(name = "authorization_file_url")
    private String authorizationFileUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_employee_id")
    private Employee registeredByEmployee;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at",
            insertable = false,
            updatable = false)
    private LocalDateTime createdAt;
}
