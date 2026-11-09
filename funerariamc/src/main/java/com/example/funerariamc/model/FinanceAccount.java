package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** cuenta a la que se carga un egreso: administración, cementerio o cementerio nuevo de la diócesis. */
@Entity
@Table(name = "finance_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FinanceAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 50, nullable = false, unique = true)
    private String name;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}