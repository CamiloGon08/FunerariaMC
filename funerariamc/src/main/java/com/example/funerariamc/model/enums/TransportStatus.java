package com.example.funerariamc.model.enums;

/**
 * Defines transport statuses.
 */
public enum TransportStatus {

    SCHEDULED("Programado"),
    IN_PROGRESS("En ruta"),
    COMPLETED("Completado"),
    CANCELLED("Cancelado");

    private final String displayName;

    TransportStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}