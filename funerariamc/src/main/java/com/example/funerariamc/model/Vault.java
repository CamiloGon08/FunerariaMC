package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Bóveda/nicho dentro de un cementerio, con los datos del arriendo vigente. Tabla: vault */
@Entity
@Table(name = "vault")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Vault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cemetery_id", nullable = false)
    private Cemetery cemetery;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "code", length = 20)
    private String code;

    @Column(name = "lease_start_date")
    private LocalDate leaseStartDate;

    @Column(name = "lease_end_date")
    private LocalDate leaseEndDate;

    // OCCUPIED, AVAILABLE, EXPIRED, OVERDUE
    @Column(name = "status", length = 12)
    private String status;
}
