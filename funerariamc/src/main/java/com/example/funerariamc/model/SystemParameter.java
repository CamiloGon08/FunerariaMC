package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/**
 * Parámetros de configuración general del sistema (llave-valor), para no dejar
 * valores fijos ("hardcodeados") en el código, ej. REMINDER_DAYS_BEFORE.
 * Tabla: system_parameter
 */
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

    @Column(name = "param_name", length = 50, nullable = false, unique = true)
    private String paramName;

    @Column(name = "param_value", length = 50, nullable = false)
    private String paramValue;

    @Column(name = "description", length = 150)
    private String description;
}
