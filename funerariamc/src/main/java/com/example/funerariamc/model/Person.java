package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tabla raíz de la jerarquía de personas (herencia JOINED).
 * Client y Employee heredan de esta clase y comparten el mismo id (PK+FK).
 * Tabla: person
 */
@Entity
@Table(name = "person")
@Inheritance(strategy = InheritanceType.JOINED)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public abstract class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 25, nullable = false)
    private String name;

    @Column(name = "lastname", length = 25, nullable = false)
    private String lastname;

    @Column(name = "document_type", length = 26)
    private String documentType;

    @Column(name = "document_number", length = 11, unique = true)
    private String documentNumber;

    @Column(name = "phone", length = 10)
    private String phone;

    @Column(name = "email", length = 30)
    private String email;

    @Column(name = "address", length = 40)
    private String address;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
