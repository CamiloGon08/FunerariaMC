package com.example.funerariamc.model.enums;

/**
 * Defines system parameter categories.
 */
public enum ParameterCategory {

    GENERAL("General"),
    PLAN("Planes"),
    CEMETERY("Cementerio"),
    TRANSPORT("Transporte"),
    COMMISSION("Comisiones"),
    NOTIFICATION("Notificaciones"),
    SECURITY("Seguridad");

    private final String displayName;

    ParameterCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}