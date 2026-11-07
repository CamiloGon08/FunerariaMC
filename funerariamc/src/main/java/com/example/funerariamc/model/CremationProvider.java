package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Proveedor externo de cremaciones.*/
@Entity
@Table(name = "cremation_provider")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CremationProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mismo largo que cremation_batch.furnace_code.
    @Column(name = "code", length = 15, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    // Cupos disponibles en los que se pide reponer.
    @Column(name = "minimum_available", nullable = false)
    private Integer minimumAvailable = 0;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
