package com.example.funerariamc.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.function.Predicate;

/**Selector de mes y año */
public final class MonthPicker extends HBox {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private final ObjectProperty<YearMonth> value = new SimpleObjectProperty<>();
    private final Label valueLabel = new Label();
    private final Button previous = new Button("‹");
    private final Button next = new Button("›");
    private final Popup calendar = new Popup();
    private final VBox calendarBox = new VBox(10);
    private final Label yearLabel = new Label();
    private final GridPane months = new GridPane();
    private final YearMonth min;
    private final YearMonth max;
    private final Predicate<YearMonth> allowed;
    private final String empty;
    /** Año que muestra el calendario abierto. */
    private int shownYear;

    /**
     * @param caption texto pequeño en mayúsculas sobre el mes (por ejemplo "MES QUE PAGA")
     * @param min     primer mes que se puede elegir
     * @param max     último mes que se puede elegir
     * @param allowed qué meses del rango se pueden elegir
     * @param empty   texto cuando aún no hay mes elegido
     */
    public MonthPicker(String caption, YearMonth min, YearMonth max, Predicate<YearMonth> allowed, String empty) {
        this.min = min;
        this.max = max;
        this.allowed = allowed == null ? month -> true : allowed;
        this.empty = empty;
        getStyleClass().add("month-picker");
        setSpacing(6);
        setAlignment(Pos.CENTER_LEFT);
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("month-picker-caption");
        valueLabel.getStyleClass().add("month-picker-value");
        VBox box = new VBox(captionLabel, valueLabel);
        box.getStyleClass().add("month-picker-box");
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setOnMouseClicked(e -> openCalendar(box));
        for (Button arrow : new Button[]{previous, next}) {
            arrow.getStyleClass().add("month-picker-arrow");
            arrow.setMinWidth(Region.USE_PREF_SIZE);
            arrow.setMaxHeight(Double.MAX_VALUE); // del mismo alto que el recuadro del mes
            arrow.setFocusTraversable(false);
        }
        previous.setOnAction(e -> step(-1));
        next.setOnAction(e -> step(1));
        getChildren().addAll(previous, box, next);
        buildCalendar();
        value.addListener((obs, old, month) -> refresh());
        refresh();
    }

    public ObjectProperty<YearMonth> valueProperty() {
        return value;
    }

    public YearMonth getValue() {
        return value.get();
    }

    public void setValue(YearMonth month) {
        value.set(month);
    }

    /** "Diciembre 2026". */
    public static String label(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL, ES);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " " + month.getYear();
    }

    private boolean selectable(YearMonth month) {
        return month != null && !month.isBefore(min) && !month.isAfter(max) && allowed.test(month);
    }

    /** Pasa al mes elegible anterior o siguiente; sin mes elegido, empieza por el primero o el último. */
    private void step(int direction) {
        YearMonth current = value.get();
        YearMonth month = current == null ? (direction > 0 ? min.minusMonths(1) : max.plusMonths(1)) : current;
        do {
            month = month.plusMonths(direction);
        } while (!month.isBefore(min) && !month.isAfter(max) && !allowed.test(month));
        if (selectable(month)) {
            value.set(month);
        }
    }

    private YearMonth neighbour(int direction) {
        YearMonth month = value.get();
        if (month == null) {
            return null;
        }
        do {
            month = month.plusMonths(direction);
        } while (!month.isBefore(min) && !month.isAfter(max) && !allowed.test(month));
        return selectable(month) ? month : null;
    }

    private void refresh() {
        YearMonth month = value.get();
        valueLabel.setText(month == null ? empty : label(month));
        valueLabel.getStyleClass().remove("month-picker-empty");
        if (month == null) {
            valueLabel.getStyleClass().add("month-picker-empty");
        }
        previous.setDisable(month != null && neighbour(-1) == null);
        next.setDisable(month != null && neighbour(1) == null);
    }

    private void buildCalendar() {
        Button prevYear = new Button("‹");
        Button nextYear = new Button("›");
        for (Button arrow : new Button[]{prevYear, nextYear}) {
            arrow.getStyleClass().add("month-picker-arrow");
            arrow.setFocusTraversable(false);
        }
        prevYear.setOnAction(e -> showYear(shownYear - 1));
        nextYear.setOnAction(e -> showYear(shownYear + 1));
        yearLabel.getStyleClass().add("month-picker-year");
        yearLabel.setMaxWidth(Double.MAX_VALUE);
        yearLabel.setAlignment(Pos.CENTER);
        HBox.setHgrow(yearLabel, Priority.ALWAYS);
        HBox head = new HBox(8, prevYear, yearLabel, nextYear);
        head.setAlignment(Pos.CENTER);
        months.setHgap(6);
        months.setVgap(6);
        for (int i = 0; i < 4; i++) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(25);
            months.getColumnConstraints().add(column);
        }
        VBox content = calendarBox;
        content.getChildren().setAll(head, months);
        content.getStyleClass().addAll("month-picker-calendar");
        content.setMinWidth(300);
        content.setPrefWidth(300);
        calendar.getContent().setAll(content);
        calendar.setAutoHide(true);
    }

    private void openCalendar(Region anchor) {
        YearMonth month = value.get() != null ? value.get() : firstSelectable();
        showYear(month == null ? YearMonth.now().getYear() : month.getYear());
        // La ventanita no hereda los estilos de la pantalla: se le pasan las hojas de estilo del formulario
        // (y la general) y los colores de la marca
        java.util.LinkedHashSet<String> sheets = new java.util.LinkedHashSet<>();
        java.net.URL app = MonthPicker.class.getResource("/css/app.css");
        if (app != null) {
            sheets.add(app.toExternalForm());
        }
        String brand = "";
        for (javafx.scene.Parent p = anchor; p != null; p = p.getParent()) {
            sheets.addAll(p.getStylesheets());
            if (brand.isEmpty() && p.getStyle() != null && p.getStyle().contains("-color-")) {
                brand = p.getStyle();
            }
        }
        if (anchor.getScene() != null) {
            sheets.addAll(anchor.getScene().getStylesheets());
        }
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            javafx.scene.Parent root = window.getScene() == null ? null : window.getScene().getRoot();
            if (brand.isEmpty() && root != null && root.getStyle() != null && root.getStyle().contains("-color-")) {
                brand = root.getStyle();
            }
        }
        calendarBox.getStylesheets().setAll(sheets);
        calendarBox.setStyle(brand);
        if (!calendarBox.getStyleClass().contains("root")) {
            calendarBox.getStyleClass().add("root");
        }
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        calendar.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    private YearMonth firstSelectable() {
        for (YearMonth month = min; !month.isAfter(max); month = month.plusMonths(1)) {
            if (allowed.test(month)) {
                return month;
            }
        }
        return null;
    }

    /** Dibuja los 12 meses del año: los que no se pueden elegir quedan apagados. */
    private void showYear(int year) {
        shownYear = year;
        yearLabel.setText(String.valueOf(year));
        months.getChildren().clear();
        for (Month m : Month.values()) {
            YearMonth month = YearMonth.of(year, m);
            String name = m.getDisplayName(TextStyle.SHORT, ES).replace(".", "");
            Button cell = new Button(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            cell.getStyleClass().add("month-picker-cell");
            if (month.equals(value.get())) {
                cell.getStyleClass().add("month-picker-selected");
            }
            cell.setMaxWidth(Double.MAX_VALUE);
            cell.setDisable(!selectable(month));
            cell.setOnAction(e -> {
                value.set(month);
                calendar.hide();
            });
            GridPane.setHgrow(cell, Priority.ALWAYS);
            months.add(cell, (m.getValue() - 1) % 4, (m.getValue() - 1) / 4);
        }
    }
}
