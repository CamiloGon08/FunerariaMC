package com.example.funerariamc.ui;

import com.example.funerariamc.service.ContractDocument;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ventana con un contrato (de afiliación a un plan exequial o de una venta), con el mismo aspecto de
 * la vista previa del formulario del plan, y los botones "Ver PDF" y "Exportar PDF".
 */
public final class ContractSheet {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ContractSheet() {
    }

    /**
     * Muestra el contrato. exporter guarda el PDF en el archivo elegido y pdfToOpen lo deja en un
     * archivo temporal para "Ver PDF"; los dos corren fuera del hilo de la pantalla y, si fallan, el
     * aviso sale debajo del contrato.
     */
    public static void show(Window owner, String module, ContractDocument contract, Consumer<Path> exporter,
                            Supplier<Path> pdfToOpen) {
        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(contract.windowTitle());
        dialog.setHeaderText(contract.windowLine());
        VBox paper = paper(contract);
        Label status = new Label();
        status.getStyleClass().add("contract-sheet-status");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        VBox content = new VBox(10, paper, status);
        content.getStyleClass().add("contract-sheet");
        DialogPane pane = dialog.getDialogPane();
        // Los colores de la marca (-color-primary...) vienen de app.css, igual que en los avisos
        pane.getStylesheets().add(ContractSheet.class.getResource("/css/app.css").toExternalForm());
        pane.setContent(content);
        ButtonType export = new ButtonType("Exportar PDF", ButtonBar.ButtonData.OK_DONE);
        ButtonType open = new ButtonType("Ver PDF", ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(close, open, export);
        pane.setPrefWidth(640);
        FormShell.window(dialog, module);
        if (pane.lookupButton(export) instanceof Button button) {
            button.getStyleClass().add("primary-dialog-button");
            // Exportar no cierra la ventana: se elige dónde guardar y se avisa debajo del contrato
            button.addEventFilter(ActionEvent.ACTION, e -> {
                e.consume();
                exportPdf(pane, button, status, contract, exporter);
            });
        }
        if (pane.lookupButton(open) instanceof Button button) {
            // Abre el PDF en el visor del sistema sin pedir dónde guardarlo
            button.addEventFilter(ActionEvent.ACTION, e -> {
                e.consume();
                openPdf(button, status, pdfToOpen);
            });
        }
        dialog.showAndWait();
    }

    private static void openPdf(Button button, Label status, Supplier<Path> pdfToOpen) {
        button.setDisable(true);
        status.getStyleClass().remove("contract-sheet-error");
        status.setText("Abriendo el PDF…");
        Thread worker = new Thread(() -> {
            String error = null;
            try {
                Path file = pdfToOpen.get();
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException();
                }
                Desktop.getDesktop().open(file.toFile());
            } catch (Exception ex) {
                error = "No se pudo abrir el PDF. Use «Exportar PDF» para guardarlo y abrirlo desde la carpeta.";
            }
            String failure = error;
            Platform.runLater(() -> {
                button.setDisable(false);
                if (failure != null) {
                    status.getStyleClass().add("contract-sheet-error");
                    status.setText(failure);
                } else {
                    status.setText("");
                }
            });
        }, "contract-open");
        worker.setDaemon(true);
        worker.start();
    }

    private static void exportPdf(DialogPane pane, Button button, Label status, ContractDocument contract,
                                  Consumer<Path> exporter) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar contrato");
        chooser.setInitialFileName(contract.fileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File file = chooser.showSaveDialog(pane.getScene().getWindow());
        if (file == null) {
            return;
        }
        button.setDisable(true);
        button.setText("Exportando…");
        status.getStyleClass().remove("contract-sheet-error");
        status.setText("");
        Thread worker = new Thread(() -> {
            String error = null;
            try {
                exporter.accept(file.toPath());
            } catch (RuntimeException ex) {
                error = ex.getMessage() == null ? "No se pudo guardar el contrato." : ex.getMessage();
            }
            String failure = error;
            Platform.runLater(() -> {
                button.setDisable(false);
                button.setText("Exportar PDF");
                if (failure != null) {
                    status.getStyleClass().add("contract-sheet-error");
                    status.setText(failure);
                } else {
                    status.setText("Contrato guardado: " + file.getName());
                }
            });
        }, "contract-pdf");
        worker.setDaemon(true);
        worker.start();
    }

    /** La hoja del contrato, con las mismas clases de la vista previa del formulario del plan. */
    static VBox paper(ContractDocument k) {
        VBox box = new VBox(4);
        box.getStyleClass().addAll("contract-preview", "contract-sheet-paper");
        Label brand = label(BrandTheme.brand() == null ? "" : BrandTheme.brand().commercialName(), "contract-preview-brand");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(brand, gap, label("N.° " + k.number() + " · " + date(k.issued()), "contract-preview-meta"));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("contract-preview-head");
        Label title = label(k.title(), "contract-preview-title");
        title.setWrapText(true);
        box.getChildren().addAll(head, title);
        for (ContractDocument.Section section : k.sections()) {
            box.getChildren().add(label(section.title(), "contract-preview-section"));
            for (ContractDocument.Row r : section.rows()) {
                HBox line = row(r.label(), r.value());
                if (r.strong()) {
                    line.getStyleClass().add("contract-sheet-total");
                }
                box.getChildren().add(line);
            }
        }
        if (k.bullets() != null && !k.bullets().isEmpty()) {
            box.getChildren().add(label(k.bulletsTitle(), "contract-preview-section"));
            for (String text : k.bullets()) {
                Label item = label("•  " + text, "contract-preview-value");
                item.getStyleClass().add("contract-sheet-include");
                item.setWrapText(true);
                box.getChildren().add(item);
            }
        }
        return box;
    }

    private static HBox row(String caption, String value) {
        boolean empty = value == null || value.isBlank();
        Label left = label(caption, "contract-preview-label");
        Label right = label(empty ? "—" : value, empty ? "contract-preview-blank" : "contract-preview-value");
        for (Label side : java.util.List.of(left, right)) {
            side.setWrapText(true);
            side.setMinWidth(Region.USE_PREF_SIZE);
        }
        // El valor puede bajar de línea si es largo, pero la etiqueta no se parte
        right.setMinWidth(0);
        right.setTextAlignment(TextAlignment.RIGHT);
        right.setAlignment(Pos.CENTER_RIGHT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(12, left, gap, right);
        row.getStyleClass().add("contract-preview-row");
        return row;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add(style);
        return label;
    }

    private static String date(LocalDate value) {
        return value == null ? null : DATE.format(value);
    }

}
