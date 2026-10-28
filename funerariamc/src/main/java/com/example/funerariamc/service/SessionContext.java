package com.example.funerariamc.service;

import com.example.funerariamc.model.AppUser;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Usuario de la sesión actual. Es una aplicación de escritorio de un solo
 * usuario a la vez, así que basta con un bean singleton.
 */
@Component
public class SessionContext {

    private volatile AppUser currentUser;

    public void start(AppUser user) {
        this.currentUser = Objects.requireNonNull(user, "El usuario de la sesión es obligatorio.");
    }

    public void clear() {
        this.currentUser = null;
    }

    public boolean isAuthenticated() {
        return currentUser != null;
    }

    public Optional<AppUser> getCurrentUser() {
        return Optional.ofNullable(currentUser);
    }

    /** Nombre del rol en la BD (MASTER_ADMIN, SALESPERSON...). */
    public Optional<String> getRoleName() {
        return getCurrentUser()
                .map(user -> user.getEmployee())
                .map(employee -> employee.getRole())
                .map(role -> role.getName());
    }

    /** Nombre y apellido del empleado que inició sesión. */
    public String getDisplayName() {
        return getCurrentUser()
                .map(user -> user.getEmployee())
                .map(e -> e.getName() + " " + e.getLastname())
                .orElse("");
    }
}
