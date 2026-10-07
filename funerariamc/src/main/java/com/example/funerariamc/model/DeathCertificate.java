package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Documento legal asociado a una defunción. Tabla: death_certificate */
@Entity
@Table(name = "death_certificate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeathCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deceased_id", nullable = false)
    private Deceased deceased;

    // MEDICAL_CERTIFICATE, FORENSIC_REPORT, BODY_RELEASE_ORDER
    @Column(name = "document_type", length = 40)
    private String documentType;

    @Column(name = "issuing_authority", length = 100)
    private String issuingAuthority;

    @Column(name = "document_number", length = 20)
    private String documentNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "file_url", length = 250)
    private String fileUrl;

    @Column(name = "notes", length = 150)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_employee_id")
    private Employee registeredByEmployee;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
