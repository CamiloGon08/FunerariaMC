package com.example.funerariamc.model.enums;

public enum MovementType {
    ENTRY("Entrada"),
    EXIT("Salida");

    private final String displayName;

    MovementType(String displayName) {
        this.displayName = displayName;
    }

    /** Texto en español para la pantalla. */
    public String getDisplayName() {
        return displayName;
    }
}
