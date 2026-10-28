package com.example.funerariamc.model.enums;

public enum DeathType {
    
     NATURAL("Natural"),
    NON_NATURAL("No natural");

    private final String displayName;

    DeathType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the display name shown on screen.
     */
    public String getDisplayName() {
        return displayName;
    }
}
