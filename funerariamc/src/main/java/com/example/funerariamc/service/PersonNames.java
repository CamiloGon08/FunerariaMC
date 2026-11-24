package com.example.funerariamc.service;

import java.util.Locale;

/**
 * Nombres y apellidos de personas: se guardan con la primera letra de cada palabra en mayúscula y
 * el resto en minúscula ("andres felipe GOMEZ" queda "Andres Felipe Gomez"). También se respeta la
 * mayúscula después de un guion o un apóstrofo ("garcía-lópez" queda "García-López").
 */
public final class PersonNames {

    private static final Locale SPANISH = Locale.forLanguageTag("es-CO");

    private PersonNames() {
    }

    /** El nombre con cada palabra en mayúscula inicial; los espacios repetidos quedan en uno solo. */
    public static String titleCase(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim().replaceAll("\\s+", " ").toLowerCase(SPANISH);
        StringBuilder result = new StringBuilder(clean.length());
        boolean start = true;
        for (int i = 0; i < clean.length(); ) {
            int code = clean.codePointAt(i);
            result.appendCodePoint(start ? Character.toTitleCase(code) : code);
            start = code == ' ' || code == '-' || code == '\'';
            i += Character.charCount(code);
        }
        return result.toString();
    }
}
