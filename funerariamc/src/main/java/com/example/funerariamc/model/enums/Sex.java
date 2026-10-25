package com.example.funerariamc.model.enums;

/**
 * Sex of an employee (column employee.sex).
 */
public enum Sex {

    MALE("Masculino"),
    FEMALE("Femenino");

    private final String displayName;

    Sex(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the sex's display name.
     */
    public String getDisplayName() {
        return displayName;
    }
}