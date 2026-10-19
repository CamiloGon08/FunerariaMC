package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleNamesTest {

    @ParameterizedTest
    @CsvSource({
            "MASTER_ADMIN, Administrador Maestro",
            "CEMETERY_ADMIN, Administrador del Cementerio",
            "SALESPERSON, Vendedor",
            "SECRETARY, Secretaría"
    })
    void roleNamesKeepDatabaseValuesAndSpanishLabels(String role, String label) {
        assertEquals(label, RoleNames.displayName(role));
    }

    @Test
    void unknownRoleKeepsItsNameForDisplay() {
        assertEquals("UNKNOWN_ROLE", RoleNames.displayName("UNKNOWN_ROLE"));
    }

    @Test
    void missingRoleHasNoDisplayLabel() {
        assertEquals("", RoleNames.displayName(null));
    }
}
