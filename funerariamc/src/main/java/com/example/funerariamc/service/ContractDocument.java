package com.example.funerariamc.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Lo que muestra la ventana de un contrato (de un plan exequial o de una venta): encabezado,
 * secciones con filas "dato — valor" y, al final, una lista de viñetas opcional. El PDF lo arma el
 * servicio de cada contrato; esto es solo para la hoja en pantalla.
 *
 * @param number      número que va arriba a la derecha (PL-0004, 0012...)
 * @param issued      fecha en que se ve o se imprime
 * @param title       título en mayúsculas de la hoja
 * @param windowTitle título de la ventana
 * @param windowLine  línea pequeña debajo del título de la ventana
 * @param fileName    nombre que se sugiere al exportar
 */
public record ContractDocument(String number, LocalDate issued, String title, String windowTitle, String windowLine,
                               List<Section> sections, String bulletsTitle, List<String> bullets, String fileName) {

    /** Una sección de la hoja (TITULAR, PLAN, CONCEPTOS...). */
    public record Section(String title, List<Row> rows) {
    }

    /** Fila "dato — valor"; strong pone el valor en negrita de total. */
    public record Row(String label, String value, boolean strong) {

        public Row(String label, String value) {
            this(label, value, false);
        }
    }
}
