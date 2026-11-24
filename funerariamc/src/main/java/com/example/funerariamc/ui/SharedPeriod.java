package com.example.funerariamc.ui;

import com.example.funerariamc.service.FinancePeriod;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * Período que comparten las pestañas de Finanzas y análisis: si se elige "Trimestre" en Movimientos,
 * Ventas y servicios abre en ese mismo trimestre. Guarda el tipo de período y las fechas, y sabe
 * moverse hacia atrás o adelante.
 */
public final class SharedPeriod {

    /** Semana, mes, trimestre, año o un rango libre de fechas. */
    public enum Kind { WEEK, MONTH, QUARTER, YEAR, CUSTOM }

    private static Kind kind = Kind.MONTH;
    private static FinancePeriod period;

    private SharedPeriod() {
    }

    /** Período elegido; null si todavía no se eligió ninguno en esta sesión. */
    public static FinancePeriod period() {
        return period;
    }

    public static Kind kind() {
        return kind;
    }

    public static void set(Kind newKind, FinancePeriod newPeriod) {
        kind = newKind;
        period = newPeriod;
    }

    /** Al cerrar sesión se vuelve al mes actual. */
    public static void clear() {
        kind = Kind.MONTH;
        period = null;
    }

    /** El período de ese tipo que contiene el día (el rango libre no aplica: se queda el mes). */
    public static FinancePeriod of(Kind kind, LocalDate day) {
        return switch (kind) {
            case WEEK -> FinancePeriod.week(day);
            case QUARTER -> FinancePeriod.quarter(day);
            case YEAR -> FinancePeriod.year(day.getYear());
            default -> FinancePeriod.month(YearMonth.from(day));
        };
    }

    /** El período anterior (step -1) o el siguiente (step 1) del mismo tipo y largo. */
    public static FinancePeriod shift(Kind kind, FinancePeriod current, int step) {
        return switch (kind) {
            case WEEK -> FinancePeriod.week(current.from().plusWeeks(step));
            case QUARTER -> FinancePeriod.quarter(current.from().plusMonths(3L * step));
            case YEAR -> FinancePeriod.year(current.from().getYear() + step);
            case CUSTOM -> new FinancePeriod(current.from().plusDays(current.days() * step),
                    current.to().plusDays(current.days() * step));
            default -> FinancePeriod.month(YearMonth.from(current.from()).plusMonths(step));
        };
    }

    /** Diálogo "Elegir rango": desde y hasta; vacío si se cancela o las fechas no sirven. */
    public static Optional<FinancePeriod> askRange(Window owner, FinancePeriod current, String module) {
        DatePicker from = new DatePicker(current.from());
        DatePicker to = new DatePicker(current.to());
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        HBox fields = new HBox(18, field("DESDE *", from), field("HASTA *", to));
        VBox body = new VBox(12, fields, error);
        body.setPrefWidth(460);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Elegir rango");
        dialog.setHeaderText("Los totales incluyen ambos días.");
        dialog.initOwner(owner);
        dialog.getDialogPane().setContent(body);
        ButtonType ok = new ButtonType("Ver rango", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE), ok);
        dialog.getDialogPane().lookupButton(ok).getStyleClass().add("primary-dialog-button");
        // Fechas al revés: se avisa y el diálogo sigue abierto
        dialog.getDialogPane().lookupButton(ok).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (from.getValue() == null || to.getValue() == null || from.getValue().isAfter(to.getValue())) {
                error.setText("Elija las dos fechas; la inicial no puede ser posterior a la final.");
                e.consume();
            }
        });
        FormShell.window(dialog, module);
        return dialog.showAndWait()
                .filter(ok::equals)
                .map(b -> new FinancePeriod(from.getValue(), to.getValue()));
    }

    private static VBox field(String caption, DatePicker picker) {
        Label label = new Label(caption);
        label.getStyleClass().add("field-label");
        picker.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(6, label, picker);
        box.getStyleClass().add("form-field");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }
}
