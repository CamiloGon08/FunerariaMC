
package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.VaultStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

/**
 * Represents a vault or niche within a cemetery.
 */
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

    @Column(name = "code")
    private String code;

    @Column(name = "lease_start_date")
    private LocalDate leaseStartDate;

    @Column(name = "lease_end_date")
    private LocalDate leaseEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VaultStatus status;
}
