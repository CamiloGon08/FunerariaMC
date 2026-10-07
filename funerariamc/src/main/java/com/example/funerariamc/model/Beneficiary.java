package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Beneficiario cubierto por un plan funerario. Tabla: beneficiary */
@Entity
@Table(name = "beneficiary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "funeral_plan_id", nullable = false)
    private FuneralPlan funeralPlan;

    @Column(name = "name", length = 25)
    private String name;

    @Column(name = "lastname", length = 25)
    private String lastname;

    @Column(name = "relationship", length = 30)
    private String relationship;

    @Column(name = "document_number", length = 11)
    private String documentNumber;
}
