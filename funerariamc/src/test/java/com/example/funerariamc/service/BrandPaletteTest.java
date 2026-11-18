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
    void chosenColorsAreUsedAsTheyAreAndTonesFollowThem() {
        BrandPalette blue = BrandPalette.of("#1B2A3D", "#3D5A80", "#C9A96E");
        assertEquals("#1B2A3D", blue.get("-color-primary"));
        assertEquals("#3D5A80", blue.get("-color-accent"));
        assertEquals("#C9A96E", blue.get("-color-detail"));
        // Los tonos del principal toman su matiz (azul) y los fondos siguen claros
        double[] forest = BrandPalette.hsl(blue.get("-color-forest"));
        assertTrue(forest[0] > 190 && forest[0] < 230, "matiz " + forest[0]);
        assertTrue(BrandPalette.hsl(blue.get("-color-paper"))[2] > 0.9);
        // Los colores de estado no cambian
        assertEquals("#9E3B32", blue.get("-color-error"));
        assertEquals("#6E6A45", blue.get("-color-warning"));
        assertTrue(blue.get("-color-shadow-22").startsWith("rgba("));
        assertTrue(blue.readabilityProblems().isEmpty());
    }

    @Test
    void lowercaseDefaultsGiveTheSamePalette() {
        assertEquals(BrandPalette.defaults().values(), BrandPalette.of("#182f25", "#465e47", "#c4af84").values());
    }

    @Test
    void lightColorsAreRejectedBecauseTextWouldNotBeReadable() {
        BrandPalette light = BrandPalette.of("#E8E4D8", "#F0E6C8", "#FFFFFF");
        assertEquals(2, light.readabilityProblems().size());
        assertTrue(BrandPalette.defaults().readabilityProblems().isEmpty());
        assertTrue(BrandPalette.defaults().readabilityWarnings().isEmpty());
    }

    @Test
    void lightButReadableColorsOnlyWarn() {
        // Azul claro con acento gris: se lee con algo de esfuerzo, se avisa pero se puede guardar
        BrandPalette lightBlue = BrandPalette.of("#6B86E0", "#9AA0A6", "#E0B860");
        assertTrue(lightBlue.readabilityProblems().isEmpty());
        assertTrue(lightBlue.readabilityWarnings().size() >= 2);
    }

    @Test
    void goldOnTealIsAcceptedBecauseTheHueIsDifferent() {
        // Claridad cercana pero tono muy distinto: se distingue y no se avisa ni se bloquea
        BrandPalette teal = BrandPalette.of("#336666", "#3D5A80", "#CC9933");
        assertTrue(teal.readabilityProblems().isEmpty());
        assertTrue(teal.readabilityWarnings().isEmpty());
    }

    @Test
    void detailAlmostEqualToPrimaryOnlyWarns() {
        BrandPalette same = BrandPalette.of("#1E3A2E", "#465E47", "#2A4A3A");
        assertTrue(same.readabilityProblems().isEmpty());
        assertEquals(1, same.readabilityWarnings().size());
    }

    @Test
    void inlineStyleHasEveryVariable() {
        String style = BrandPalette.defaults().inlineStyle();
        BrandPalette.defaults().values().keySet().forEach(name -> assertTrue(style.contains(name + ": ")));
        assertTrue(style.startsWith("-color-forest-deep: #0F1F17;"));
    }

    @Test
    void contrastAndHexChecks() {
        assertEquals(21, BrandPalette.contrast("#000000", "#FFFFFF"), 0.01);
        assertTrue(BrandPalette.isHex("#A1b2C3"));
        assertFalse(BrandPalette.isHex("A1B2C3"));
        assertFalse(BrandPalette.isHex("#A1B2C"));
        assertThrows(IllegalArgumentException.class, () -> BrandPalette.of("rojo", "#000000", "#000000"));
    }
}
