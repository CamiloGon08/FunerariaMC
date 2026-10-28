package com.example.funerariamc.model.enums;

/**
 * External cause of a non-natural death (column deceased.external_cause, RN-01).
 * It is required when the death is NON_NATURAL and must be empty when it is NATURAL.
 */
public enum ExternalCause {

    ACCIDENT("Accidente"),
    HOMICIDE("Homicidio"),
    SUICIDE("Suicidio"),
    UNDETERMINED("Indeterminada");

    private final String displayName;

    ExternalCause(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the display name shown on screen.
     */
    public String getDisplayName() {
        return displayName;
    }
}
