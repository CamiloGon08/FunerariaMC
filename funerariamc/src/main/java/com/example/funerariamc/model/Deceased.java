package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Datos del difunto atendido por la funeraria. Tabla: deceased */
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

    @Column(name = "name", length = 25, nullable = false)
    private String name;

    @Column(name = "lastname", length = 25, nullable = false)
    private String lastname;

    @Column(name = "document_number", length = 11)
    private String documentNumber;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "death_date")
    private LocalDate deathDate;

    // NATURAL o NON_NATURAL
    @Column(name = "death_type", length = 15)
    private String deathType;

    // ACCIDENT, HOMICIDE, SUICIDE, UNDETERMINED (solo si death_type = NON_NATURAL)
    @Column(name = "external_cause", length = 15)
    private String externalCause;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;
}
