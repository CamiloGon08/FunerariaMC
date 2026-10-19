package com.example.funerariamc.service;

import java.util.Map;

/**
 * Nombres de los roles tal como están en la tabla {@code role}.
 * Deben coincidir con el CHECK {@code role_name_check} de la BD.
 */
public final class RoleNames {

    public static final String MASTER_ADMIN = "MASTER_ADMIN";
    public static final String CEMETERY_ADMIN = "CEMETERY_ADMIN";
    public static final String SALESPERSON = "SALESPERSON";
    public static final String SECRETARY = "SECRETARY";

    private static final Map<String, String> DISPLAY = Map.of(
            MASTER_ADMIN, "Administrador Maestro",
            CEMETERY_ADMIN, "Administrador del Cementerio",
            SALESPERSON, "Vendedor",
            SECRETARY, "Secretaría");

    private RoleNames() {
    }

    /** Nombre del rol para mostrar en pantalla (en español). */
    public static String displayName(String roleName) {
        return roleName == null ? "" : DISPLAY.getOrDefault(roleName, roleName);
    }
}
