package com.example.funerariamc.utils.enums;

/**
 * Defines transport stop types.
 */
public enum StopType {

    ORIGIN("Salida"),
    PICKUP("Recogida"),
    CEREMONY("Ceremonia"),
    DESTINATION("Destino");

    private final String displayName;

    StopType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}