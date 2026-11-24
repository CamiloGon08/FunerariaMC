package com.example.funerariamc.ui;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;

/**
 * Lista que mide lo que sus filas: así no tiene barra de desplazamiento propia y baja junto con el
 * resto de la pantalla (banda verde, filtros, lista y páginas se desplazan como una sola hoja).
 * Se usa en las tablas hechas con ListView y paginadas con {@link Pager}.
 */
public final class FitRows {

    /** Alto de la lista vacía, para que quepa el aviso de "sin registros". */
    private static final double EMPTY_HEIGHT = 130;
    /** Bordes de la lista, para que la última fila no quede cortada. */
    private static final double BORDER = 4;

    private FitRows() {
    }

    /** Fija el alto de cada fila y ajusta el alto de la lista cada vez que cambian sus filas. */
    public static <T> void apply(ListView<T> list, double rowHeight) {
        list.setFixedCellSize(rowHeight);
        Runnable fit = () -> {
            int rows = list.getItems() == null ? 0 : list.getItems().size();
            double height = Math.max(EMPTY_HEIGHT, rows * rowHeight + BORDER);
            list.setMinHeight(height);
            list.setPrefHeight(height);
        };
        ListChangeListener<T> onRows = change -> fit.run();
        list.itemsProperty().addListener((obs, old, items) -> {
            if (old != null) {
                old.removeListener(onRows);
            }
            if (items != null) {
                items.addListener(onRows);
            }
            fit.run();
        });
        ObservableList<T> items = list.getItems();
        if (items != null) {
            items.addListener(onRows);
        }
        fit.run();
    }
}
