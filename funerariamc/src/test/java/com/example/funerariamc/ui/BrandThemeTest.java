package com.example.funerariamc.ui;

import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.service.BrandingService.BrandView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrandThemeTest {

    @Test
    void templateTakesTheBrandNameAndTagline() {
        BrandView eden = BrandingService.defaults();
        assertEquals("El Edén", BrandTheme.fill("{name}", eden));
        assertEquals("Inicia sesión para acceder al sistema de El Edén.",
                BrandTheme.fill("Inicia sesión para acceder al sistema de {name}.", eden));
        assertEquals("“Donde el recuerdo florece”", BrandTheme.fill("“{tagline}”", eden));
        assertEquals("Funeraria El Edén", BrandTheme.fill("{fullName}", eden));
    }

    @Test
    void taglineLabelHidesWhenThereIsNoTagline() {
        BrandView noTagline = new BrandView("Otra", null, null, null, "#182F25", "#465E47", "#C4AF84", null, null);
        assertTrue(BrandTheme.hidesTagline("“{tagline}”", noTagline));
        assertFalse(BrandTheme.hidesTagline("{name}", noTagline));
        assertFalse(BrandTheme.hidesTagline("“{tagline}”", BrandingService.defaults()));
    }
}
