package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.enums.ParameterCategory;
import com.example.funerariamc.service.ParameterCatalog;
import com.example.funerariamc.service.ParameterData;
import com.example.funerariamc.service.SystemParameterService;
import com.example.funerariamc.service.SystemParameterService.ParameterView;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Pantalla Parámetros del sistema (solo el Administrador Maestro): indicadores, buscador,
 * filtro por categoría y una tarjeta por parámetro. "Editar" cambia la tarjeta a un campo con
 * Cancelar / Guardar cambio; antes de guardar se pide confirmación con el valor anterior y el nuevo.
 * Los parámetros que el sistema necesita y todavía no tienen valor aparecen como "Sin definir".
 * La consulta corre en otro hilo; el filtro y la búsqueda son en memoria.
 */
@Component
@Scope("prototype")
public class ParametersController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Ancho mínimo de una tarjeta; con espacio suficiente se ponen dos por fila. */
    private static final double TWO_COLUMNS_FROM = 820;

    private final SystemParameterService parameterService;

    @FXML
    private StackPane root;
    @FXML
    private Label definedValue;
    @FXML
    private Label definedDetail;
    @FXML
    private VBox undefinedCard;
    @FXML
    private Label undefinedValue;
    @FXML
    private Label undefinedDetail;
    @FXML
    private Label lastValue;
    @FXML
    private Label lastDetail;
    @FXML
    private TextField searchField;
    @FXML
    private HBox categoryBar;
    @FXML
    private HBox notice;
    @FXML
    private SVGPath noticeIcon;
    @FXML
    private Label noticeText;
    @FXML
    private Button noticeLink;
    @FXML
    private ScrollPane scroll;
    @FXML
    private VBox sections;
    @FXML
    private Label toast;

    private final ToggleGroup categoryGroup = new ToggleGroup();
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));
    private List<ParameterView> all = List.of();
    /** Parámetro que se está editando en su tarjeta; null si ninguno. */
    private String editing;
    /** Solo se muestran los que no tienen valor (enlace "Ver cuáles"). */
    private boolean onlyUndefined;
    private boolean loaded;
    private int generation;
    private SequentialTransition toastMotion;

    public ParametersController(SystemParameterService parameterService) {
        this.parameterService = parameterService;
    }

    @Override
    public void refresh() {
        load(null);
    }

    @FXML
    private void initialize() {
        categoryGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null && old != null) {
                old.setSelected(true); // siempre queda una categoría elegida
            } else {
                draw();
            }
        });
        searchDelay.setOnFinished(e -> draw());
        searchField.textProperty().addListener((obs, old, text) -> {
            onlyUndefined = false;
            searchDelay.playFromStart();
        });
        sections.widthProperty().addListener((obs, old, width) -> {
            boolean wasTwo = old.doubleValue() >= TWO_COLUMNS_FROM;
            boolean isTwo = width.doubleValue() >= TWO_COLUMNS_FROM;
            if (wasTwo != isTwo) {
                draw();
            }
        });
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        load(null);
    }

    // ---------- carga ----------

    /** Trae todos los parámetros; si toastText no es null, lo muestra al terminar. */
    private void load(String toastText) {
        int request = ++generation;
        if (!loaded) {
            sections.getChildren().setAll(emptyLabel("Cargando los parámetros…"));
        }
        async("parameters-overview", parameterService::overview, views -> {
            if (request != generation) {
                return;
            }
            loaded = true;
            all = views;
            editing = null;
            fillKpis();
            buildCategories();
            draw();
            if (toastText != null) {
                showToast(toastText);
            }
        }, failure -> {
            if (request == generation) {
                sections.getChildren().setAll(emptyLabel(""));
                showError(message(failure, "No se pudieron cargar los parámetros."));
            }
        });
    }

    private void fillKpis() {
        long defined = all.stream().filter(ParameterView::defined).count();
        long undefined = all.size() - defined;
        definedValue.setText(String.valueOf(defined));
        definedDetail.setText("de " + all.size() + " parámetros");
        undefinedValue.setText(String.valueOf(undefined));
        undefinedDetail.setText(undefined == 0 ? "todo configurado" : "los cálculos que los usan no se pueden hacer");
        undefinedCard.getStyleClass().remove("highlight");
        if (undefined > 0) {
            undefinedCard.getStyleClass().add("highlight");
        }
        Optional<ParameterView> last = all.stream().filter(v -> v.updatedAt() != null)
                .max(Comparator.comparing(ParameterView::updatedAt));
        lastValue.setText(last.map(v -> v.updatedAt().format(DATE)).orElse("—"));
        lastDetail.setText(last.map(v -> v.title() + (v.updatedBy() == null ? "" : " · " + v.updatedBy()))
                .orElse("sin cambios registrados"));

        boolean warn = undefined > 0;
        notice.getStyleClass().remove("warn");
        if (warn) {
            notice.getStyleClass().add("warn");
            noticeIcon.setContent("M8 1.5 L15 14 L1 14 Z M8 6v3.6 M8 11.6v.2");
            noticeText.setText(undefined == 1
                    ? "Hay 1 parámetro sin definir. Mientras no tenga valor, el cálculo que lo usa muestra un aviso."
                    : "Hay " + undefined + " parámetros sin definir. Mientras no tengan valor, los cálculos que los usan muestran un aviso.");
        } else {
            noticeIcon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
            noticeText.setText("Cada cambio pide confirmación, se aplica a los cálculos siguientes y queda en la bitácora con quién lo hizo y cuándo.");
        }
        show(noticeLink, warn);
    }

    /** "Todos" y una opción por cada categoría que tiene parámetros. */
    private void buildCategories() {
        Object selected = categoryGroup.getSelectedToggle() == null ? null : categoryGroup.getSelectedToggle().getUserData();
        categoryBar.getChildren().clear();
        categoryGroup.getToggles().clear();
        categoryBar.getChildren().add(categoryToggle("Todos", null));
        for (ParameterCategory category : ParameterCategory.values()) {
            if (all.stream().anyMatch(v -> v.category() == category)) {
                categoryBar.getChildren().add(categoryToggle(category.getDisplayName(), category));
            }
        }
        categoryGroup.getToggles().stream()
                .filter(t -> Objects.equals(t.getUserData(), selected))
                .findFirst()
                .orElse(categoryGroup.getToggles().get(0))
                .setSelected(true);
    }

    private ToggleButton categoryToggle(String text, ParameterCategory category) {
        ToggleButton toggle = new ToggleButton(text);
        toggle.getStyleClass().add("segment");
        toggle.setUserData(category);
        toggle.setToggleGroup(categoryGroup);
        return toggle;
    }

    // ---------- dibujo ----------

    private void draw() {
        ParameterCategory category = categoryGroup.getSelectedToggle() == null ? null
                : (ParameterCategory) categoryGroup.getSelectedToggle().getUserData();
        String query = normalize(searchField.getText());
        Map<ParameterCategory, List<ParameterView>> groups = new EnumMap<>(ParameterCategory.class);
        for (ParameterView view : all) {
            if (category != null && view.category() != category) {
                continue;
            }
            if (onlyUndefined && view.defined()) {
                continue;
            }
            if (!query.isEmpty() && !normalize(view.title() + " " + view.name() + " "
                    + Objects.toString(view.description(), "")).contains(query)) {
                continue;
            }
            groups.computeIfAbsent(view.category(), c -> new ArrayList<>()).add(view);
        }
        List<Node> children = new ArrayList<>();
        boolean twoColumns = sections.getWidth() == 0 || sections.getWidth() >= TWO_COLUMNS_FROM;
        for (Map.Entry<ParameterCategory, List<ParameterView>> group : groups.entrySet()) {
            int count = group.getValue().size();
            HBox head = new HBox(label(group.getKey().getDisplayName(), "section-name"),
                    label(count + (count == 1 ? " parámetro" : " parámetros"), "section-count"));
            head.getStyleClass().add("section-head");
            children.add(head);
            children.add(cardGrid(group.getValue(), twoColumns));
        }
        if (children.isEmpty()) {
            children.add(emptyLabel(all.isEmpty() ? "Todavía no hay parámetros registrados."
                    : "No hay parámetros que coincidan con la búsqueda."));
        }
        sections.getChildren().setAll(children);
    }

    private GridPane cardGrid(List<ParameterView> views, boolean twoColumns) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        int columns = twoColumns ? 2 : 1;
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(constraints);
        }
        for (int i = 0; i < views.size(); i++) {
            Node card = editing != null && editing.equals(views.get(i).name()) ? editCard(views.get(i)) : card(views.get(i));
            GridPane.setValignment(card, javafx.geometry.VPos.TOP);
            grid.add(card, i % columns, i / columns);
        }
        return grid;
    }

    /** Tarjeta en modo lectura: nombre, descripción, valor con su unidad y quién lo cambió. */
    private Node card(ParameterView v) {
        VBox card = cardShell(v);
        Node valueRow;
        Button edit = new Button(v.defined() ? "Editar" : "Definir valor");
        edit.getStyleClass().add("edit-button");
        if (!v.defined()) {
            edit.getStyleClass().add("define");
            card.getStyleClass().add("undefined");
        }
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.setOnAction(e -> startEditing(v.name()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        if (v.defined()) {
            Label value = label(display(v), "param-value");
            Label unit = label(unitText(v), "param-unit");
            HBox amount = new HBox(8, value, unit);
            amount.setAlignment(Pos.BASELINE_LEFT);
            valueRow = new HBox(amount, gap, edit);
        } else {
            Label value = label("Sin definir", "param-value");
            value.getStyleClass().add("undefined");
            valueRow = new HBox(value, gap, edit);
        }
        ((HBox) valueRow).setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(valueRow, new Insets(6, 0, 2, 0));
        card.getChildren().addAll(valueRow, label(meta(v), "param-meta"));
        return card;
    }

    /** Tarjeta en modo edición: campo con la unidad, aviso de error y Cancelar / Guardar cambio. */
    private Node editCard(ParameterView v) {
        VBox card = cardShell(v);
        card.getStyleClass().add("editing");
        TextField input = new TextField(v.defined() ? editableValue(v) : "");
        input.getStyleClass().add("param-input");
        input.setPromptText(v.kind() == ParameterCatalog.Kind.AMOUNT ? "Valor en pesos" : "Valor");
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox box = new HBox(input, label(unitText(v), "param-unit"));
        box.getStyleClass().add("param-input-box");
        Label error = label("", "param-error");
        error.setWrapText(true);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());
        Button cancel = new Button("Cancelar");
        cancel.getStyleClass().add("edit-button");
        cancel.setOnAction(e -> {
            editing = null;
            draw();
        });
        Button save = new Button("Guardar cambio");
        save.getStyleClass().add("save-button");
        save.setDefaultButton(true);
        save.setOnAction(e -> confirmAndSave(v, input.getText(), save, cancel, input, error));
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancel.fire();
            }
        });
        HBox actions = new HBox(10, cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox.setMargin(box, new Insets(8, 0, 0, 0));
        card.getChildren().addAll(box, error, actions, label(meta(v), "param-meta"));
        Platform.runLater(() -> {
            input.requestFocus();
            input.selectAll();
        });
        return card;
    }

    private VBox cardShell(ParameterView v) {
        Label title = label(v.title(), "param-title");
        title.setWrapText(true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(8, title, gap);
        top.setAlignment(Pos.TOP_LEFT);
        if (!v.known()) {
            top.getChildren().add(chip("Creado a mano", "state-ok"));
        }
        VBox card = new VBox(top);
        card.getStyleClass().add("param-card");
        if (v.description() != null && !v.description().isBlank()) {
            Label description = label(v.description(), "param-desc");
            description.setWrapText(true);
            card.getChildren().add(description);
        }
        card.getChildren().add(label(v.name(), "param-code"));
        return card;
    }

    private void startEditing(String name) {
        editing = name;
        draw();
    }

    @FXML
    private void onShowUndefined() {
        onlyUndefined = true;
        categoryGroup.getToggles().get(0).setSelected(true);
        draw();
    }

    // ---------- guardar ----------

    /** Pide confirmación con el valor anterior y el nuevo; guarda en otro hilo y recarga la pantalla. */
    private void confirmAndSave(ParameterView v, String typed, Button save, Button cancel, TextField input, Label error) {
        error.setText("");
        if (typed == null || typed.isBlank()) {
            error.setText("Escriba el valor del parámetro.");
            return;
        }
        String newText = typed.trim();
        String question = v.defined()
                ? "¿Cambiar " + v.title() + " de " + display(v) + " a " + newText + " " + unitText(v) + "?"
                : "¿Definir " + v.title() + " en " + newText + " " + unitText(v) + "?";
        if (!confirm(question.replace("  ", " ").replace(" ?", "?"),
                "El nuevo valor se usa desde el siguiente cálculo y el cambio queda en la bitácora.", "Guardar cambio")) {
            return;
        }
        save.setText("Guardando…");
        save.setDisable(true);
        cancel.setDisable(true);
        input.setDisable(true);
        async("parameters-save", () -> parameterService.updateValue(v.name(), newText), saved -> {
            editing = null;
            load(v.title() + " guardado.");
        }, failure -> {
            save.setText("Guardar cambio");
            save.setDisable(false);
            cancel.setDisable(false);
            input.setDisable(false);
            error.setText(message(failure, "No se pudo guardar. Revise la conexión e intente de nuevo."));
        });
    }

    /** Nuevo parámetro: si el nombre ya existe, ofrece cambiar el valor del existente. */
    @FXML
    private void onNewParameter() {
        Form form = new Form();
        TextField name = new TextField();
        name.setPromptText("Ej.: TARIFA_BUS");
        TextField value = new TextField();
        TextField unit = new TextField();
        unit.setPromptText("Ej.: días, COP, %");
        TextField description = new TextField();
        description.setPromptText("Para qué sirve");
        ComboBox<ParameterCategory> category = new ComboBox<>();
        category.getItems().setAll(ParameterCategory.values());
        category.setValue(ParameterCategory.GENERAL);
        category.getStyleClass().add("form-combo");
        category.setConverter(new StringConverter<>() {
            @Override
            public String toString(ParameterCategory c) {
                return c == null ? "" : c.getDisplayName();
            }

            @Override
            public ParameterCategory fromString(String s) {
                return null;
            }
        });
        form.section("Parámetro", 2)
                .field("NOMBRE *", name)
                .field("CATEGORÍA *", category)
                .field("VALOR *", value)
                .field("UNIDAD", unit);
        form.section("Descripción", 1)
                .field("DESCRIPCIÓN", description);
        form.note(note("El nombre va en mayúsculas y sin espacios. Un parámetro nuevo no cambia ningún cálculo"
                + " hasta que el sistema lo use; los que el sistema ya usa aparecen en la lista como \"Sin definir\"."));
        Dialog<ButtonType> dialog = formDialog("Nuevo parámetro", "Los campos con * son obligatorios.", form, 640);
        runForm(dialog, form, "Crear parámetro", () -> {
            String key = name.getText() == null ? "" : name.getText().trim().toUpperCase(Locale.ROOT);
            if (!key.isEmpty() && parameterService.exists(key)) {
                return key; // ya existe: se ofrece cambiar su valor
            }
            parameterService.create(new ParameterData(name.getText(), value.getText(), description.getText(),
                    category.getValue(), unit.getText()));
            return null;
        }, existing -> {
            if (existing == null) {
                load("Parámetro creado.");
                return;
            }
            boolean update = confirm("Ya existe un parámetro llamado " + existing + ".",
                    "No se crea un duplicado. ¿Quiere cambiar el valor del que ya existe?", "Cambiar su valor");
            if (update) {
                searchField.setText("");
                onlyUndefined = false;
                categoryGroup.getToggles().get(0).setSelected(true);
                startEditing(existing);
            }
        });
    }

    // ---------- textos ----------

    /** Valor como se lee: pesos con puntos de miles, enteros con puntos de miles, texto tal cual. */
    static String display(ParameterView v) {
        if (v.value() == null) {
            return "Sin definir";
        }
        try {
            return switch (v.kind()) {
                case AMOUNT -> "$ " + NumberFormat.getNumberInstance(ES).format(new BigDecimal(v.value()));
                case WHOLE -> NumberFormat.getIntegerInstance(ES).format(Long.parseLong(v.value()));
                case TEXT -> v.value();
            };
        } catch (NumberFormatException e) {
            return v.value();
        }
    }

    /** Valor para el campo de edición: en pesos sin el signo, para que se pueda escribir encima. */
    private static String editableValue(ParameterView v) {
        String shown = display(v);
        return v.kind() == ParameterCatalog.Kind.AMOUNT ? shown.replace("$ ", "") : shown;
    }

    /** La unidad COP ya va en el signo de pesos; las demás se muestran tal cual. */
    private static String unitText(ParameterView v) {
        if (v.unit() == null || v.kind() == ParameterCatalog.Kind.AMOUNT && "COP".equalsIgnoreCase(v.unit())) {
            return "";
        }
        return v.unit();
    }

    private static String meta(ParameterView v) {
        if (v.updatedAt() == null) {
            return v.defined() ? "Sin cambios registrados" : "Todavía no tiene valor";
        }
        return "Modificado " + v.updatedAt().format(DATE_HOUR) + (v.updatedBy() == null ? "" : " · " + v.updatedBy());
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text.trim().toLowerCase(ES), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    // ---------- formulario "Personal · El Edén" ----------

    /** Formulario con sección en mayúsculas y filete, etiqueta arriba del campo y columnas de igual ancho. */
    private static final class Form {
        private final VBox body = new VBox(14);
        private final Label error = new Label();
        private GridPane grid;
        private int columns;
        private int column;
        private int row;

        Form() {
            body.getStyleClass().add("form-body");
            error.getStyleClass().add("error-label");
            error.setWrapText(true);
            error.setMinHeight(Region.USE_PREF_SIZE);
            error.visibleProperty().bind(error.textProperty().isNotEmpty());
        }

        Form section(String title, int columns) {
            Label caption = new Label(title.toUpperCase(Locale.ROOT));
            caption.getStyleClass().add("section-title");
            caption.setMinWidth(Region.USE_PREF_SIZE);
            Region rule = new Region();
            rule.getStyleClass().add("section-rule");
            HBox.setHgrow(rule, Priority.ALWAYS);
            HBox legend = new HBox(caption, rule);
            legend.getStyleClass().add("section-legend");
            grid = new GridPane();
            grid.setHgap(18);
            grid.setVgap(12);
            for (int i = 0; i < columns; i++) {
                ColumnConstraints constraints = new ColumnConstraints();
                constraints.setPercentWidth(100.0 / columns);
                grid.getColumnConstraints().add(constraints);
            }
            this.columns = columns;
            column = 0;
            row = 0;
            body.getChildren().addAll(legend, grid);
            return this;
        }

        Form field(String label, Node input) {
            if (column >= columns) {
                column = 0;
                row++;
            }
            Label caption = new Label(label);
            caption.getStyleClass().add("field-label");
            VBox box = new VBox(caption, input);
            box.getStyleClass().add("form-field");
            if (input instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            grid.add(box, column, row);
            column++;
            return this;
        }

        Form note(Node note) {
            body.getChildren().add(note);
            return this;
        }
    }

    private Dialog<ButtonType> formDialog(String title, String subtitle, Form form, double width) {
        VBox header = new VBox(2, label(title, "form-title"), label(subtitle, "card-subtitle"));
        header.getStyleClass().add("form-header");
        VBox.setMargin(form.error, new Insets(6, 28, 4, 28));
        VBox content = new VBox(header, form.body, form.error);
        content.getStyleClass().add("form-root");
        content.setPrefWidth(width);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initOwner(window());
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        styleDialog(pane);
        pane.getStyleClass().add("form-dialog");
        dialog.setOnShown(e -> pane.getScene().getWindow().sizeToScene());
        return dialog;
    }

    /**
     * Muestra el formulario. Guardar corre en otro hilo con el botón en "Guardando…"; si el servicio
     * rechaza los datos, el mensaje sale abajo y el diálogo sigue abierto.
     */
    private <T> void runForm(Dialog<ButtonType> dialog, Form form, String okText, Callable<T> save, Consumer<T> onSaved) {
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, ok);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(cancel);
        okButton.getStyleClass().add("primary-dialog-button");
        okButton.setMinWidth(Region.USE_PREF_SIZE);
        Object[] saved = new Object[1];
        boolean[] done = new boolean[1];
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); // el diálogo se cierra solo cuando el servicio responde
            form.error.setText("");
            okButton.setText("Guardando…");
            okButton.setDisable(true);
            cancelButton.setDisable(true);
            form.body.setDisable(true);
            async("parameters-create", save, result -> {
                saved[0] = result;
                done[0] = true;
                dialog.setResult(ok);
                dialog.close();
            }, failure -> {
                okButton.setText(okText);
                okButton.setDisable(false);
                cancelButton.setDisable(false);
                form.body.setDisable(false);
                form.error.setText(message(failure, "No se pudo guardar. Revise la conexión e intente de nuevo."));
            });
        });
        dialog.setOnCloseRequest(event -> {
            if (okButton.isDisabled() && !done[0]) {
                event.consume();
            }
        });
        if (dialog.showAndWait().filter(ok::equals).isPresent() && done[0]) {
            @SuppressWarnings("unchecked")
            T result = (T) saved[0];
            onSaved.accept(result);
        }
    }

    /** Confirmación con la pregunta arriba y el botón con el nombre de la acción; Cancelar a la izquierda. */
    private boolean confirm(String question, String detail, String actionText) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(window());
        alert.setTitle("Parámetros del sistema");
        alert.setHeaderText(question);
        alert.setContentText(detail);
        ButtonType action = new ButtonType(actionText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, action);
        styleDialog(alert.getDialogPane());
        ((Button) alert.getDialogPane().lookupButton(action)).getStyleClass().add("primary-dialog-button");
        return alert.showAndWait().filter(action::equals).isPresent();
    }

    // ---------- piezas ----------

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("note-info");
        note.setWrapText(true);
        SVGPath icon = new SVGPath();
        icon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
        icon.getStyleClass().add("note-icon");
        note.setGraphic(icon);
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        return note;
    }

    private static Label chip(String text, String tone) {
        Label chip = label(text, "state-chip");
        chip.getStyleClass().add(tone);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    private static Label emptyLabel(String text) {
        Label label = label(text, "empty-board");
        label.setWrapText(true);
        return label;
    }

    /** Las ventanas cargan app.css, personal.css y parameters.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                ParametersController.class.getResource("/css/app.css").toExternalForm(),
                ParametersController.class.getResource("/css/personal.css").toExternalForm(),
                ParametersController.class.getResource("/css/parameters.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "parameters-root");
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showError(String text) {
        Alert alert = new Alert(Alert.AlertType.WARNING, text);
        alert.initOwner(window());
        alert.setHeaderText(null);
        styleDialog(alert.getDialogPane());
        alert.showAndWait();
    }

    private void showToast(String text) {
        toast.setText(text);
        if (toastMotion != null) {
            toastMotion.stop();
        }
        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(Duration.millis(400), toast);
        out.setToValue(0);
        toastMotion = new SequentialTransition(in, new PauseTransition(Duration.seconds(3)), out);
        toastMotion.play();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static <T> void async(String threadName, Callable<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private static String message(Throwable failure, String fallback) {
        return failure instanceof BusinessException business ? business.getMessage() : fallback;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
