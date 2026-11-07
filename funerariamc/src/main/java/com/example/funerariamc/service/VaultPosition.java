package com.example.funerariamc.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ubicación de una bóveda en el muro, extraída de su código sin columnas nuevas en la BD.
 * Admite el formato explícito A2-15 (nivel 2, columna 15) y el anterior A-11
 * (nivel 2, columna 1, con diez columnas por nivel). El nivel 1 es el de abajo.
 */
public record VaultPosition(String pavilion, int number, int level, int column) {

    /** Columnas por nivel de los códigos del formato anterior. */
    public static final int COLUMNS = 10;
    public static final String GENERAL = "General";

    private static final Pattern LEVEL_CODE = Pattern.compile("^\\s*([A-Za-z]+)\\s*0*(\\d+)\\s*-\\s*0*(\\d+)\\s*$");
    private static final Pattern NUMBER_CODE = Pattern.compile("^\\s*([A-Za-z]+)\\s*-?\\s*0*(\\d+)\\s*$");

    /** Un código desconocido no tiene posición calculable; la pantalla puede agruparlo en General. */
    public static VaultPosition of(String code) {
        if (code == null) {
            return null;
        }
        try {
            Matcher withLevel = LEVEL_CODE.matcher(code);
            if (withLevel.matches()) {
                int level = Integer.parseInt(withLevel.group(2));
                int column = Integer.parseInt(withLevel.group(3));
                return level < 1 || column < 1 ? null
                        : atLevel(withLevel.group(1).toUpperCase(Locale.ROOT), level, column);
            }
            Matcher numbered = NUMBER_CODE.matcher(code);
            if (!numbered.matches()) {
                return null;
            }
            int number = Integer.parseInt(numbered.group(2));
            return number < 1 ? null : at(numbered.group(1).toUpperCase(Locale.ROOT), number);
        } catch (NumberFormatException invalidCode) {
            return null;
        }
    }

    /** Posición de una bóveda del formato anterior, numerada desde 1. */
    public static VaultPosition at(String pavilion, int number) {
        return new VaultPosition(pavilion, number, (number - 1) / COLUMNS + 1, (number - 1) % COLUMNS + 1);
    }

    /** Posición con nivel y columna explícitos, sin el límite de diez columnas del formato anterior. */
    public static VaultPosition atLevel(String pavilion, int level, int column) {
        return new VaultPosition(pavilion, level * 100_000 + column, level, column);
    }

    /** Código para una bóveda nueva; no depende del idioma del equipo. */
    public static String code(String pavilion, int level, int column) {
        return pavilion.toUpperCase(Locale.ROOT) + level + "-" + String.format(Locale.ROOT, "%02d", column);
    }
}
