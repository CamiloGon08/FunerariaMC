package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class BrandPaletteTest {

    /** Variables definidas en el bloque .root de app.css. */
    private static Map<String, String> appCssRoot() throws IOException {
        try (InputStream in = BrandPaletteTest.class.getResourceAsStream("/css/app.css")) {
            String css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String root = css.substring(css.indexOf(".root {"), css.indexOf('}', css.indexOf(".root {")));
            Map<String, String> values = new LinkedHashMap<>();
            Matcher m = Pattern.compile("(-color-[a-z0-9-]+):\\s*([^;]+);").matcher(root);
            while (m.find()) {
                values.put(m.group(1), m.group(2).trim());
            }
            return values;
        }
    }

    @Test
    void defaultPaletteIsExactlyTheOneInAppCss() throws IOException {
        assertEquals(appCssRoot(), BrandPalette.defaults().values());
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
        assertEquals(3, light.readabilityProblems().size());
        assertTrue(BrandPalette.defaults().readabilityProblems().isEmpty());
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
