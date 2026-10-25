package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDate;

/**
 * Stores information about the deceased person served by the funeral home.
 */
@Entity
@Table(name = "deceased")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Deceased {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 25)
    private String name;

    @Column(name = "lastname", length = 25)
    private String lastname;

    @Column(name = "document_number", length = 11)
    private String documentNumber;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "death_date")
    private LocalDate deathDate;

    // NATURAL or NON_NATURAL
    @Column(name = "death_type", length = 15)
    private String deathType;

    // ACCIDENT, HOMICIDE, SUICIDE, UNDETERMINED (only when death_type = NON_NATURAL)
    @Column(name = "external_cause", length = 15)
    private String externalCause;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    // Funeral plan covering the deceased person as a policyholder or beneficiary; null means no plan.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funeral_plan_id")
    private FuneralPlan funeralPlan;
}