
package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Stores the system configuration parameters.
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

    @Column(name = "param_name", unique = true, nullable = false)
    private String paramName;

    @Column(name = "param_value", nullable = false)
    private String paramValue;

    @Column(name = "description")
    private String description;
}
