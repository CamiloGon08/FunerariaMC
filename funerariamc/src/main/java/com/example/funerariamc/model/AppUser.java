package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Credenciales de acceso al sistema para un empleado. Tabla: app_user */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false, unique = true)
    private Employee employee;

    @Column(name = "username", length = 25, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 70, nullable = false)
    private String passwordHash;

    @Column(name = "is_enabled")
    private Boolean isEnabled = true;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;
}
