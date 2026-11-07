package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class VaultPositionTest {

    @ParameterizedTest
    @CsvSource({"A-07,A,1,7", "A-10,A,1,10", "A-11,A,2,1", "b40,B,4,10",
            "A2-15,A,2,15", "B3-42,B,3,42", "AB02-015,AB,2,15"})
    void readsPhysicalLocationsInBothSupportedCodeFormats(String code, String pavilion, int level, int column) {
        VaultPosition position = VaultPosition.of(code);
        assertNotNull(position);
        assertAll(
                () -> assertEquals(pavilion, position.pavilion()),
                () -> assertEquals(level, position.level()),
                () -> assertEquals(column, position.column())
        );
    }

    @Test
    void legacyAndExplicitCodesCanIdentifyTheSamePhysicalSpot() {
        VaultPosition oldCode = VaultPosition.of("A-11");
        VaultPosition newCode = VaultPosition.of("A2-01");
        assertNotNull(oldCode);
        assertNotNull(newCode);
        assertEquals(oldCode.pavilion(), newCode.pavilion());
        assertEquals(oldCode.level(), newCode.level());
        assertEquals(oldCode.column(), newCode.column());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Lote norte", "A-0", "A0-15", "A1-0", "A-1-2",
            "A999999999999999999999", "A1-999999999999999999999"})
    void unrecognizedOrUnrepresentableCodesDoNotBreakTheWall(String code) {
        assertNull(VaultPosition.of(code));
    }

    @Test
    void vaultCodesDoNotChangeWithTheOperatingSystemLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("I1-05", VaultPosition.code("i", 1, 5));
            VaultPosition position = VaultPosition.of("i1-05");
            assertNotNull(position);
            assertEquals("I", position.pavilion());

            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("A3-42", VaultPosition.code("a", 3, 42));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
