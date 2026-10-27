package com.example.funerariamc.service;

import java.util.Set;

import static com.example.funerariamc.service.RoleNames.CEMETERY_ADMIN;
import static com.example.funerariamc.service.RoleNames.MASTER_ADMIN;
import static com.example.funerariamc.service.RoleNames.SALESPERSON;
import static com.example.funerariamc.service.RoleNames.SECRETARY;

/**
 * Módulos del sistema y los roles que pueden abrirlos (RN-21).
 * El Administrador Maestro tiene acceso a todos (RN-22).
 * Los nombres de las constantes los usan MainController (BITACORA) y ModuleIcons; no renombrarlas.
 */
public enum AppModule {

    SEGURIDAD("Seguridad: personal y cuentas"),
    BITACORA("Bitácora de accesos"),
    CLIENTES("Clientes", SALESPERSON, SECRETARY),
    DIFUNTOS("Difuntos", SECRETARY, CEMETERY_ADMIN),
    PLANES("Planes exequiales", SALESPERSON, SECRETARY),
    CEMENTERIO("Cementerio y bóvedas", CEMETERY_ADMIN),
    SALAS("Salas de velación", SECRETARY),
    CONTRATOS("Contratos y ventas", SALESPERSON, SECRETARY),
    INVENTARIO("Inventario", CEMETERY_ADMIN),
    TRANSPORTE("Transporte", SECRETARY),
    CREMACIONES("Cremaciones", CEMETERY_ADMIN),
    FINANZAS("Finanzas", SECRETARY),
    /** Panel de análisis de solo lectura: solo el Administrador Maestro. */
    ANALISIS("Panel de análisis"),
    RED_ALIADAS("Red de funerarias aliadas"),
    PARAMETROS("Parámetros del sistema");

    private final String label;
    private final Set<String> roles;

    AppModule(String label, String... roles) {
        this.label = label;
        this.roles = Set.of(roles);
    }

    public String getLabel() {
        return label;
    }

    /** RN-21 y RN-22: sin rol no hay acceso; el Administrador Maestro accede a todo. */
    public boolean isAllowedFor(String roleName) {
        if (roleName == null) {
            return false;
        }
        return MASTER_ADMIN.equals(roleName) || roles.contains(roleName);
    }
}
