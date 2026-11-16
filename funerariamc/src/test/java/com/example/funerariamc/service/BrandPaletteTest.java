package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrandPaletteTest {
    @Test
    void defaultPaletteKeepsTheExistingColorsAndIsReadable() {
        BrandPalette palette = BrandPalette.defaults();
        assertEquals(BrandPalette.DEFAULT_PRIMARY, palette.get("-color-primary"));
        assertEquals(BrandPalette.DEFAULT_ACCENT, palette.get("-color-accent"));
        assertEquals(BrandPalette.DEFAULT_DETAIL, palette.get("-color-detail"));
        assertTrue(palette.readabilityProblems().isEmpty());
        assertTrue(palette.inlineStyle().contains("-color-primary: " + BrandPalette.DEFAULT_PRIMARY));
        assertThrows(UnsupportedOperationException.class, () -> palette.values().put("-color-primary", "#FFFFFF"));
    }

    @Test
    void colorsAreValidatedAndContrastIsSymmetric() {
        assertFalse(BrandPalette.isHex("red"));
        assertFalse(BrandPalette.isHex("#FFF"));
        assertThrows(IllegalArgumentException.class, () -> BrandPalette.of("red", "#000000", "#FFFFFF"));
        assertEquals(21.0, BrandPalette.contrast("#000000", "#FFFFFF"), 0.001);
        assertEquals(BrandPalette.contrast("#123456", "#ABCDEF"), BrandPalette.contrast("#ABCDEF", "#123456"), 0.0001);
    }

    @Test
    void unreadablePrimaryIsRejectedByPaletteValidation() {
        assertFalse(BrandPalette.of("#FFFFFF", "#465E47", "#C4AF84").readabilityProblems().isEmpty());
    }
}
