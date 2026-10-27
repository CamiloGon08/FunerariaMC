package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.ParameterCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/** Guarda los parámetros de configuración del sistema.*/
@Entity
@Table(name = "system_parameter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SystemParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "param_name", unique = true, nullable = false)
    private String paramName;

    @Column(name = "param_value", nullable = false)
    private String paramValue;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 15)
    private ParameterCategory category = ParameterCategory.GENERAL;

    // Unidad que se muestra junto al valor: días, años, %, COP
    @Column(name = "unit", length = 10)
    private String unit;

    // La pone la app en hora de Bogotá en cada cambio
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;
}