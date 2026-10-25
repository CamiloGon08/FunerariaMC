package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.Sex;

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

    /** Femenino de cada rol: "Secretaria", "Administradora Maestra"... */
    private static final Map<String, String> FEMALE = Map.of(
            MASTER_ADMIN, "Administradora Maestra",
            CEMETERY_ADMIN, "Administradora del Cementerio",
            SALESPERSON, "Vendedora",
            SECRETARY, "Secretaria");

    /** Masculino de cada rol: "Secretario", "Administrador Maestro"... */
    private static final Map<String, String> MALE = Map.of(
            MASTER_ADMIN, "Administrador Maestro",
            CEMETERY_ADMIN, "Administrador del Cementerio",
            SALESPERSON, "Vendedor",
            SECRETARY, "Secretario");

    private RoleNames() {
    }

    /** Nombre del rol para mostrar en pantalla (en español). */
    public static String displayName(String roleName) {
        return roleName == null ? "" : DISPLAY.getOrDefault(roleName, roleName);
    }

    /** Nombre del rol según el sexo del empleado ("Secretario" / "Secretaria"); sin sexo, el genérico. */
    public static String displayName(String roleName, Sex sex) {
        if (roleName == null || sex == null) {
            return displayName(roleName);
        }
        return (sex == Sex.FEMALE ? FEMALE : MALE).getOrDefault(roleName, displayName(roleName));
    }
}
