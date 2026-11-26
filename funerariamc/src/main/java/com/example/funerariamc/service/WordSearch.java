package com.example.funerariamc.service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Regla única de los buscadores: lo escrito debe coincidir con el COMIENZO de una palabra (nombre,
 * apellido, documento o código), sin importar mayúsculas ni tildes. Al escribir «j» salen Jesús y
 * José, pero no Cujia ni Alejandra. Primero van los que empiezan por lo escrito (Jesús David antes
 * que Hernán José) y después los que lo tienen en otra palabra.
 * <p>
 * En las consultas a la base de datos el patrón es "% texto%" y se compara con ' ' + nombre sin
 * tildes; para ordenar se usa el mismo patrón sin el primer "%" (" texto%"), que solo coincide
 * cuando el nombre empieza por lo escrito.
 */
public final class WordSearch {

    private WordSearch() {
    }

    /** Minúsculas, sin tildes, con guiones y puntos como espacios y un solo espacio entre palabras. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.replaceAll("[^\\p{L}\\p{N}@]+", " ").trim();
    }

    /** Patrón para las consultas: "% texto%", o "%" (todo) si no se escribió nada. */
    public static String sqlPattern(String text) {
        String clean = clean(text);
        return clean.isEmpty() ? "%" : "% " + clean + "%";
    }

    /** Verdadero si alguna palabra del valor empieza por lo escrito (vacío: siempre). */
    public static boolean matches(String value, String text) {
        String query = clean(text);
        return query.isEmpty() || (" " + clean(value)).contains(" " + query);
    }

    /** Verdadero si alguno de los valores tiene una palabra que empieza por lo escrito. */
    public static boolean matchesAny(String text, String... values) {
        String query = clean(text);
        if (query.isEmpty()) {
            return true;
        }
        for (String value : values) {
            if (value != null && (" " + clean(value)).contains(" " + query)) {
                return true;
            }
        }
        return false;
    }

    /** 0 si el valor empieza por lo escrito, 1 si no: para poner primero a los que empiezan así. */
    public static int rank(String value, String text) {
        String query = clean(text);
        return !query.isEmpty() && clean(value).startsWith(query) ? 0 : 1;
    }

    /**
     * La misma lista con los que empiezan por lo escrito primero; dentro de cada grupo se respeta el
     * orden que traía (más recientes, por saldo, etc.).
     */
    public static <T> List<T> startsFirst(List<T> items, String text, Function<T, String> name) {
        if (clean(text).isEmpty()) {
            return items;
        }
        return items.stream()
                .sorted(Comparator.comparingInt((T item) -> rank(name.apply(item), text)))
                .toList();
    }
}
