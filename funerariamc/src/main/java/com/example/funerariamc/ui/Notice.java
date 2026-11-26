package com.example.funerariamc.ui;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Avisos y confirmaciones con el mismo aspecto de los formularios (ventana redondeada con la banda
 * de color, el nombre del módulo arriba del título y la X para cerrar), en lugar del cuadro de
 * Windows. El tono (información, éxito, advertencia, error) cambia el ícono y su color.
 */
public final class Notice {

    /** Ancho de la ventana del aviso: lo justo para leer dos o tres líneas cómodas. */
    private static final double WIDTH = 500;
    /** Ancho del texto dentro del aviso: el ancho de la ventana menos márgenes e ícono. */
    private static final double TEXT_WIDTH = WIDTH - 60 - 34 - 16 - 4;
    private static final String APP_SHEET = "/css/app.css";

    /** Tipo de aviso: define el ícono y su color. */
    public enum Tone {
        INFO("notice-tone-info", "M12 10.5v6 M12 7.2v.3"),
        SUCCESS("notice-tone-success", "M7.5 12.3l3 3 6-6.6"),
        WARNING("notice-tone-warning", "M12 7.5v5.5 M12 16.2v.3"),
        ERROR("notice-tone-error", "M8.5 8.5l7 7 M15.5 8.5l-7 7");

        private final String styleClass;
        private final String icon;

        Tone(String styleClass, String icon) {
            this.styleClass = styleClass;
            this.icon = icon;
        }
    }

    private Notice() {
    }

    /** Aviso informativo con un solo botón "Entendido". */
    public static void info(Window owner, String module, String title, String text) {
        show(Tone.INFO, owner, module, title, text, "Entendido", null, false);
    }

    /** Aviso de algo que salió bien (por ejemplo, "Contraseña actualizada"). */
    public static void success(Window owner, String module, String title, String text) {
        show(Tone.SUCCESS, owner, module, title, text, "Aceptar", null, false);
    }

    /**
     * Aviso de algo que salió bien con una acción a seguir (por ejemplo, "Ver contrato") y el botón
     * para cerrarlo; true si eligió la acción.
     */
    public static boolean successWithAction(Window owner, String module, String title, String text, String action,
                                            String back) {
        return show(Tone.SUCCESS, owner, module, title, text, action, back, false);
    }

    /** Advertencia: el usuario debe corregir algo o el sistema no permitió la acción. */
    public static void warning(Window owner, String module, String title, String text) {
        show(Tone.WARNING, owner, module, title, text, "Entendido", null, false);
    }

    /** Error: algo falló (conexión, bitácora...). */
    public static void error(Window owner, String module, String title, String text) {
        show(Tone.ERROR, owner, module, title, text, "Entendido", null, false);
    }

    /** Pregunta con Cancelar a la izquierda y la acción con su nombre; true si eligió la acción. */
    public static boolean confirm(Window owner, String module, String question, String detail, String action) {
        return show(Tone.INFO, owner, module, question, detail, action, "Cancelar", false);
    }

    /** Igual que {@link #confirm}, pero la acción va en rojo (anular, desactivar, borrar). */
    public static boolean confirmDanger(Window owner, String module, String question, String detail, String action) {
        return show(Tone.WARNING, owner, module, question, detail, action, "Cancelar", true);
    }

    /** Confirmación con el texto del botón de volver elegido por el módulo (por ejemplo, "Volver"). */
    public static boolean confirm(Window owner, String module, String question, String detail, String action,
                                  String back, boolean danger) {
        return show(danger ? Tone.WARNING : Tone.INFO, owner, module, question, detail, action, back, danger);
    }

    private static boolean show(Tone tone, Window owner, String module, String title, String text,
                                String action, String back, boolean danger) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(title);
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(Notice.class.getResource(APP_SHEET).toExternalForm());
        pane.getStyleClass().add("notice-dialog");
        pane.setContent(message(tone, text));
        pane.setPrefWidth(WIDTH);
        ButtonType ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        if (back == null) {
            pane.getButtonTypes().setAll(ok);
        } else {
            pane.getButtonTypes().setAll(new ButtonType(back, ButtonBar.ButtonData.CANCEL_CLOSE), ok);
        }
        if (pane.lookupButton(ok) instanceof Button button) {
            button.getStyleClass().add(danger ? "danger-button" : "primary-dialog-button");
            button.setDefaultButton(true);
        }
        FormShell.window(dialog, module);
        Optional<ButtonType> answer = dialog.showAndWait();
        return answer.filter(ok::equals).isPresent();
    }

    /** Ícono del tono en un círculo y el texto al lado, con saltos de línea respetados. */
    private static Node message(Tone tone, String text) {
        Circle circle = new Circle(17);
        circle.getStyleClass().add("notice-circle");
        SVGPath mark = new SVGPath();
        mark.setContent(tone.icon);
        mark.getStyleClass().add("notice-mark");
        StackPane icon = new StackPane(circle, mark);
        icon.getStyleClass().addAll("notice-icon-box", tone.styleClass);
        icon.setMinSize(34, 34);
        icon.setMaxSize(34, 34);

        Label body = new Label(text == null ? "" : text);
        body.getStyleClass().add("notice-text");
        body.setWrapText(true);
        body.setMaxWidth(Double.MAX_VALUE);
        // Ancho fijo para partir el texto: así el alto del aviso se calcula bien desde el primer momento
        body.setPrefWidth(TEXT_WIDTH);
        body.setMinHeight(Label.USE_PREF_SIZE);
        HBox.setHgrow(body, Priority.ALWAYS);

        HBox row = new HBox(16, icon, body);
        row.getStyleClass().add("notice-row");
        VBox box = new VBox(row);
        box.getStyleClass().add("notice-body");
        return box;
    }
}
