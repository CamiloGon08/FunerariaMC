package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.utils.enums.ParameterCategory;
import com.example.funerariamc.service.BrandPalette;
import com.example.funerariamc.service.BrandingData;
import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.service.BrandingService.BrandView;
import com.example.funerariamc.service.ParameterCatalog;
import com.example.funerariamc.service.SystemParameterService;
import com.example.funerariamc.service.SystemParameterService.ParameterView;
import com.example.funerariamc.ui.BrandTheme;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.LilyLogo;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
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
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
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
 * Arriba va la tarjeta "Identidad de la funeraria" (nombre, frase, logo y colores de la interfaz).
 */
@Component
@Scope("prototype")
public class ParametersController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Parámetros del sistema";

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Ancho mínimo de una tarjeta; con espacio suficiente se ponen dos por fila. */
    private static final double TWO_COLUMNS_FROM = 820;
    /** Palabras que muestran la tarjeta de identidad al buscar. */
    private static final String BRAND_WORDS = "identidad marca logo nombre colores frase funeraria";
    /** Combinaciones de ejemplo para empezar; la funeraria puede elegir cualquier color. */
    private static final String[][] BRAND_PRESETS = {
            {"El Edén", BrandPalette.DEFAULT_PRIMARY, BrandPalette.DEFAULT_ACCENT, BrandPalette.DEFAULT_DETAIL},
            {"Azul sereno", "#1B2A3D", "#3D5A80", "#C9A96E"},
            {"Vino", "#3A1F24", "#6B2E3A", "#B08D57"}};

    private final SystemParameterService parameterService;
    private final BrandingService brandingService;

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

    public ParametersController(SystemParameterService parameterService, BrandingService brandingService) {
        this.parameterService = parameterService;
        this.brandingService = brandingService;
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
        // Si la marca cambia (desde aquí o al restaurarla) la tarjeta se vuelve a dibujar
        BrandTheme.brandProperty().addListener((obs, old, brand) -> {
            if (loaded) {
                draw();
            }
        });
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
        if (category == null && !onlyUndefined && (query.isEmpty() || BRAND_WORDS.contains(query)
                || normalize(BrandTheme.brand().fullName()).contains(query))) {
            HBox head = new HBox(label("Identidad de la funeraria", "section-name"),
                    label("nombre, logo y colores", "section-count"));
            head.getStyleClass().add("section-head");
            children.add(head);
            children.add(brandCard());
        }
        boolean twoColumns = sections.getWidth() == 0 || sections.getWidth() >= TWO_COLUMNS_FROM;
        for (Map.Entry<ParameterCategory, List<ParameterView>> group : groups.entrySet()) {
            int count = group.getValue().size();
            HBox head = new HBox(label(group.getKey().getDisplayName(), "section-name"),
                    label(count + (count == 1 ? " parámetro" : " parámetros"), "section-count"));
            head.getStyleClass().add("section-head");
            children.add(head);
            children.add(cardGrid(group.getValue(), twoColumns));
        }
        if (children.isEmpty() || (children.size() == 2 && all.isEmpty())) {
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

    // ---------- identidad de la funeraria ----------

    /** Tarjeta con el logo, el nombre, la frase y los 3 colores; "Editar identidad" abre el formulario. */
    private Node brandCard() {
        BrandView brand = BrandTheme.brand();
        LilyLogo logo = new LilyLogo();
        logo.setSize(64);
        Label name = label(brand.fullName(), "param-value");
        name.setWrapText(true);
        Label tagline = label(brand.tagline() == null ? "Sin frase" : "“" + brand.tagline() + "”", "param-desc");
        tagline.setWrapText(true);
        HBox swatches = new HBox(14, swatch("Principal", brand.primaryColor()), swatch("Acento", brand.accentColor()),
                swatch("Detalle", brand.detailColor()));
        swatches.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(4, name, tagline, swatches);
        VBox.setMargin(swatches, new Insets(8, 0, 0, 0));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button edit = new Button("Editar identidad");
        edit.getStyleClass().add("edit-button");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.setOnAction(e -> onEditBrand());
        Button restore = new Button("Restaurar El Edén");
        restore.getStyleClass().add("edit-button");
        restore.setMinWidth(Region.USE_PREF_SIZE);
        restore.setDisable(brand.edenLook() && brand.showBandImage() && brand.showMenuImage()
                && brand.bandImage() == null && brand.menuImage() == null
                && BrandingService.DEFAULT_NAME.equals(brand.commercialName())
                && BrandingService.DEFAULT_TAGLINE.equals(brand.tagline()));
        restore.setOnAction(e -> onRestoreBrand(restore));
        VBox buttons = new VBox(8, edit, restore);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        edit.setMaxWidth(Double.MAX_VALUE);
        restore.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(18, logo, text, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        String meta = brand.updatedAt() == null ? "Marca con la que viene el sistema"
                : "Modificado " + DATE_HOUR.format(brand.updatedAt())
                + (brand.updatedBy() == null ? "" : " · " + brand.updatedBy());
        Label description = label("Se ve en el inicio de sesión, el menú, las bandas de cada módulo, los correos, los"
                + " reportes y el bot de Telegram. Queda guardada en la base de datos: se ve igual en cada ingreso y en"
                + " todos los equipos. Solo el Administrador Maestro la puede cambiar.", "param-desc");
        description.setWrapText(true);
        VBox card = new VBox(10, row, description, label(meta, "param-meta"));
        card.getStyleClass().addAll("param-card", "brand-card");
        return card;
    }

    /** Vuelve a la marca El Edén (nombre, frase, emblema, lirios y colores) después de confirmar. */
    private void onRestoreBrand(Button restore) {
        boolean sure = confirm("¿Restaurar la identidad de El Edén?",
                "El nombre, la frase, el logo, las imágenes de las bandas y del menú y los colores vuelven a los de"
                        + " Funeraria El Edén. El cambio queda en la bitácora.", "Restaurar El Edén");
        if (!sure) {
            return;
        }
        restore.setDisable(true);
        async("branding-restore", brandingService::restoreDefaults,
                brand -> showToast("Se restauró la identidad de El Edén."),
                failure -> {
                    restore.setDisable(false);
                    showError(message(failure, "No se pudo restaurar. Revise la conexión e intente de nuevo."));
                });
    }

    /**
     * Fila para elegir la imagen de las bandas o del menú: el lirio de El Edén, una imagen propia o ninguna.
     * Muestra cómo queda y guarda la imagen nueva elegida del disco.
     */
    private static final class ImageChoiceRow {
        private final ComboBox<BrandingData.ImageChoice> choice = new ComboBox<>();
        private final ImageView preview = new ImageView();
        private final Label info = new Label();
        private final Image eden;
        private final byte[] stored;
        private byte[] picked;
        final HBox node;

        ImageChoiceRow(Image eden, byte[] stored, boolean show) {
            this.eden = eden;
            this.stored = stored;
            choice.getItems().setAll(BrandingData.ImageChoice.values());
            choice.getStyleClass().add("form-combo");
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setConverter(new StringConverter<>() {
                @Override
                public String toString(BrandingData.ImageChoice c) {
                    if (c == null) {
                        return "";
                    }
                    return switch (c) {
                        case EDEN -> "Lirio de El Edén";
                        case CUSTOM -> "Imagen propia";
                        case NONE -> "Sin imagen";
                    };
                }

                @Override
                public BrandingData.ImageChoice fromString(String s) {
                    return null;
                }
            });
            choice.setValue(!show ? BrandingData.ImageChoice.NONE
                    : stored != null && stored.length > 0 ? BrandingData.ImageChoice.CUSTOM : BrandingData.ImageChoice.EDEN);
            preview.setFitWidth(120);
            preview.setFitHeight(56);
            preview.setPreserveRatio(true);
            info.getStyleClass().add("param-meta");
            info.setWrapText(true);
            Button choose = new Button("Elegir imagen…");
            choose.getStyleClass().add("edit-button");
            choose.setGraphic(pictureIcon());
            choose.setMinWidth(Region.USE_PREF_SIZE);
            choose.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Elegir imagen");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
                File file = chooser.showOpenDialog(choose.getScene().getWindow());
                if (file == null) {
                    return;
                }
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    if (bytes.length > BrandingService.MAX_LOGO_BYTES) {
                        info.setText("Esa imagen pesa más de 1 MB. Elija una más liviana.");
                        return;
                    }
                    picked = bytes;
                    choice.setValue(BrandingData.ImageChoice.CUSTOM);
                    refresh();
                    info.setText("Imagen nueva: " + file.getName());
                } catch (IOException failure) {
                    info.setText("No se pudo leer el archivo.");
                }
            });
            choice.valueProperty().addListener((obs, old, value) -> refresh());
            VBox text = new VBox(6, choice, info);
            HBox.setHgrow(text, Priority.ALWAYS);
            StackPane frame = new StackPane(preview);
            frame.getStyleClass().add("brand-image-frame");
            frame.setMinSize(132, 64);
            frame.setMaxSize(132, 64);
            node = new HBox(14, frame, text, choose);
            node.setAlignment(Pos.CENTER_LEFT);
            refresh();
        }

        private void refresh() {
            BrandingData.ImageChoice value = choice.getValue();
            byte[] custom = picked != null ? picked : stored;
            if (value == BrandingData.ImageChoice.NONE) {
                preview.setImage(null);
                info.setText("No se pone ninguna imagen.");
            } else if (value == BrandingData.ImageChoice.CUSTOM && custom != null && custom.length > 0) {
                preview.setImage(new Image(new ByteArrayInputStream(custom)));
                if (picked == null) {
                    info.setText("Imagen propia guardada.");
                }
            } else if (value == BrandingData.ImageChoice.CUSTOM) {
                preview.setImage(null);
                info.setText("Elija la imagen con el botón. PNG o JPG de hasta 1 MB.");
            } else {
                preview.setImage(eden);
                info.setText("El lirio con el que viene el sistema.");
            }
        }

        BrandingData.ImageChoice value() {
            return choice.getValue();
        }

        byte[] picked() {
            return choice.getValue() == BrandingData.ImageChoice.CUSTOM ? picked : null;
        }
    }

    /** Dibujo de una foto (marco, sol y montaña) para los botones "Elegir imagen…". */
    private static SVGPath pictureIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M1.5 2.5h11v9h-11z M4.5 5.2a1 1 0 1 0 0.01 0 M1.5 10l3.5-3.2 2.5 2.2 2-1.6 3 2.6");
        icon.getStyleClass().add("brand-pick-icon");
        return icon;
    }

    private static HBox swatch(String caption, String hex) {
        Region color = new Region();
        color.getStyleClass().add("brand-swatch");
        color.setStyle("-fx-background-color: " + hex + ";");
        color.setMinSize(22, 22);
        color.setMaxSize(22, 22);
        VBox text = new VBox(label(caption, "param-code"), label(hex, "param-meta"));
        HBox box = new HBox(8, color, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Formulario de la identidad: nombre, frase, logo y los 3 colores con una vista previa que cambia al escribir. */
    private void onEditBrand() {
        BrandView brand = BrandTheme.brand();
        TextField name = new TextField(brand.commercialName());
        name.setPromptText("Ej.: El Edén");
        TextField tagline = new TextField(brand.tagline() == null ? "" : brand.tagline());
        tagline.setPromptText("Opcional");
        ColorPicker primary = colorPicker(brand.primaryColor());
        ColorPicker accent = colorPicker(brand.accentColor());
        ColorPicker detail = colorPicker(brand.detailColor());

        // Logo: el actual, uno nuevo elegido del disco o el emblema del lirio
        byte[][] newLogo = new byte[1][];
        boolean[] removeLogo = new boolean[1];
        ImageView logoView = new ImageView(BrandTheme.logoProperty().get() != null ? BrandTheme.logoProperty().get()
                : LilyLogo.emblemImage());
        logoView.setFitWidth(64);
        logoView.setFitHeight(64);
        logoView.setPreserveRatio(true);
        Label logoInfo = label(brand.hasCustomLogo() ? "Logo propio de la funeraria." : "Emblema del lirio (El Edén).",
                "param-desc");
        logoInfo.setWrapText(true);
        logoInfo.getStyleClass().add("brand-logo-title");
        Label logoHint = label("PNG o JPG de hasta 1 MB. Mejor cuadrado y con fondo transparente.", "param-meta");
        logoHint.setWrapText(true);
        Button choose = new Button("Elegir imagen…");
        choose.getStyleClass().add("edit-button");
        Button emblem = new Button("Usar el emblema");
        emblem.getStyleClass().add("edit-button");
        choose.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Elegir el logo de la funeraria");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
            File file = chooser.showOpenDialog(choose.getScene().getWindow());
            if (file == null) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length > BrandingService.MAX_LOGO_BYTES) {
                    logoInfo.setText("Esa imagen pesa más de 1 MB. Elija una más liviana.");
                    return;
                }
                newLogo[0] = bytes;
                removeLogo[0] = false;
                logoView.setImage(new Image(new ByteArrayInputStream(bytes)));
                logoInfo.setText("Logo nuevo: " + file.getName());
            } catch (IOException failure) {
                logoInfo.setText("No se pudo leer el archivo.");
            }
        });
        emblem.setOnAction(e -> {
            newLogo[0] = null;
            removeLogo[0] = true;
            logoView.setImage(LilyLogo.emblemImage());
            logoInfo.setText("Se usará el emblema del lirio (El Edén).");
        });
        VBox logoText = new VBox(4, logoInfo, logoHint);
        HBox.setHgrow(logoText, Priority.ALWAYS);
        choose.setMinWidth(Region.USE_PREF_SIZE);
        emblem.setMinWidth(Region.USE_PREF_SIZE);
        HBox logoButtons = new HBox(8, choose, emblem);
        logoButtons.setAlignment(Pos.CENTER_RIGHT);
        HBox logoRow = new HBox(16, logoView, logoText, logoButtons);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        logoRow.getStyleClass().add("brand-logo-row");

        // Combinaciones de ejemplo y vuelta a El Edén
        HBox presets = new HBox(8);
        presets.setAlignment(Pos.CENTER_LEFT);
        for (String[] preset : BRAND_PRESETS) {
            Button button = new Button(preset[0]);
            button.getStyleClass().add("brand-preset");
            HBox dots = new HBox(-6);
            for (int i = 1; i <= 3; i++) {
                Region dot = new Region();
                dot.getStyleClass().add("brand-preset-dot");
                dot.setStyle("-fx-background-color: " + preset[i] + ";");
                dots.getChildren().add(dot);
            }
            button.setGraphic(dots);
            button.setOnAction(e -> {
                primary.setValue(Color.web(preset[1]));
                accent.setValue(Color.web(preset[2]));
                detail.setValue(Color.web(preset[3]));
            });
            presets.getChildren().add(button);
        }

        // Vista previa: menú con el nombre y un botón, con los colores que se están eligiendo
        Label previewSub = label("F U N E R A R I A", "brand-preview-sub");
        Label previewName = label("", "brand-preview-name");
        Label previewTagline = label("", "brand-preview-tagline");
        previewTagline.setWrapText(true);
        previewTagline.managedProperty().bind(previewTagline.textProperty().isNotEmpty());
        previewTagline.visibleProperty().bind(previewTagline.managedProperty());
        Label previewButton = label("Guardar", "brand-preview-button");
        Label previewLink = label("¿Olvidó su contraseña?", "brand-preview-link");
        Region previewRule = new Region();
        previewRule.getStyleClass().add("brand-preview-rule");
        // El logo de la vista previa sigue al logo elegido arriba
        ImageView previewLogo = new ImageView();
        previewLogo.imageProperty().bind(logoView.imageProperty());
        previewLogo.setFitWidth(34);
        previewLogo.setFitHeight(34);
        previewLogo.setPreserveRatio(true);
        HBox previewTitle = new HBox(10, previewLogo, new VBox(1, previewSub, previewName));
        previewTitle.setAlignment(Pos.CENTER_LEFT);
        VBox previewMenu = new VBox(14, previewTitle, previewTagline);
        previewMenu.getStyleClass().add("brand-preview-menu");
        previewMenu.setMinWidth(210);
        previewMenu.setPrefWidth(235);
        VBox previewBody = new VBox(12, previewRule, previewButton, previewLink);
        previewBody.getStyleClass().add("brand-preview-body");
        previewBody.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(previewBody, Priority.ALWAYS);
        HBox preview = new HBox(previewMenu, previewBody);
        preview.getStyleClass().add("brand-preview");
        Label readability = label("", "param-error");
        readability.setWrapText(true);
        readability.managedProperty().bind(readability.textProperty().isNotEmpty());
        readability.visibleProperty().bind(readability.managedProperty());
        Runnable updatePreview = () -> {
            BrandPalette palette = BrandPalette.of(hex(primary.getValue()), hex(accent.getValue()), hex(detail.getValue()));
            String typed = name.getText() == null || name.getText().isBlank() ? "Nombre" : name.getText().strip();
            previewName.setText(typed);
            previewTagline.setText(tagline.getText() == null || tagline.getText().isBlank() ? ""
                    : "\u201C" + tagline.getText().strip() + "\u201D");
            previewTagline.setStyle("-fx-text-fill: " + palette.get("-color-background") + ";");
            previewRule.setStyle("-fx-background-color: " + palette.get("-color-detail") + ";");
            previewMenu.setStyle("-fx-background-color: linear-gradient(to bottom, " + palette.get("-color-forest")
                    + ", " + palette.get("-color-forest-deep") + ");");
            previewSub.setStyle("-fx-text-fill: " + palette.get("-color-detail") + ";");
            previewName.setStyle("-fx-text-fill: " + palette.get("-color-background") + ";");
            previewBody.setStyle("-fx-background-color: " + palette.get("-color-content") + ";");
            previewButton.setStyle("-fx-background-color: " + palette.get("-color-accent") + "; -fx-text-fill: "
                    + palette.get("-color-background") + ";");
            previewLink.setStyle("-fx-text-fill: " + palette.get("-color-gold-deep") + ";");
            // Los problemas impiden guardar y van en rojo; si solo hay avisos van en color de aviso
            List<String> problems = palette.readabilityProblems();
            List<String> notes = new ArrayList<>(problems);
            notes.addAll(palette.readabilityWarnings());
            readability.getStyleClass().removeAll("param-error", "param-warning");
            readability.getStyleClass().add(problems.isEmpty() ? "param-warning" : "param-error");
            readability.setText(String.join(" ", notes));
        };
        name.textProperty().addListener((obs, old, text) -> updatePreview.run());
        tagline.textProperty().addListener((obs, old, text) -> updatePreview.run());
        primary.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        accent.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        detail.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        updatePreview.run();

        Form form = new Form();
        form.section("Nombre", 2)
                .field("NOMBRE COMERCIAL *", name)
                .field("FRASE", tagline);
        form.note(note("Escriba el nombre sin la palabra \"Funeraria\": el sistema la pone delante en los correos,"
                + " los reportes y el título de la ventana."));
        form.section("Logo", 1)
                .field("LOGO", logoRow);
        ImageChoiceRow band = new ImageChoiceRow(new Image(Objects.requireNonNull(
                ParametersController.class.getResource("/images/lirio-banda.png")).toExternalForm()),
                brand.bandImage(), brand.showBandImage());
        ImageChoiceRow menu = new ImageChoiceRow(new Image(Objects.requireNonNull(
                ParametersController.class.getResource("/images/lirio-fondo.png")).toExternalForm()),
                brand.menuImage(), brand.showMenuImage());
        form.section("Imágenes", 1)
                .field("BANDA SUPERIOR DE CADA MÓDULO", band.node)
                .field("FONDO DEL MENÚ LATERAL", menu.node);
        form.note(note("La imagen de las bandas va a la derecha, sobre el color principal: funciona mejor una imagen"
                + " horizontal con fondo transparente o que se desvanezca en los bordes."));
        form.section("Colores", 3)
                .field("COLOR PRINCIPAL *", primary)
                .field("COLOR DE ACENTO *", accent)
                .field("COLOR DE DETALLE *", detail);
        form.note(new VBox(8, label("Empezar desde una combinación (puede elegir cualquier color):", "param-meta"), presets));
        form.section("Vista previa", 1)
                .field("ASÍ SE VERÁ", new VBox(8, preview, readability));
        form.note(note("El principal pinta el menú y el panel del login; el de acento, los botones y los títulos;"
                + " el de detalle, los filetes y los textos dorados. Los demás tonos se calculan solos. Los colores de"
                + " error, aviso y éxito no cambian. Si un color deja un texto difícil de leer sale un aviso, pero se"
                + " puede guardar; solo se rechaza un color casi blanco, con el que el texto no se vería."
                + " Cada cambio queda en la bitácora."));
        // Dos columnas: a la izquierda nombre, logo e imágenes; a la derecha colores y vista previa
        twoColumns(form, "Colores");
        Dialog<ButtonType> dialog = formDialog("Identidad de la funeraria", "Los campos con * son obligatorios.", form, 1180);
        FormShell.window(dialog, "Parámetros del sistema");
        runForm(dialog, form, "Guardar identidad",
                () -> brandingService.save(new BrandingData(name.getText(), tagline.getText(), newLogo[0], removeLogo[0],
                        hex(primary.getValue()), hex(accent.getValue()), hex(detail.getValue()),
                        band.value(), band.picked(), menu.value(), menu.picked())),
                saved -> showToast("Identidad guardada. Los colores ya cambiaron en todas las ventanas."));
    }

    /**
     * Reparte el formulario en dos columnas del mismo ancho: desde la sección {@code rightFrom}
     * (con sus avisos) todo pasa a la columna derecha.
     */
    private static void twoColumns(Form form, String rightFrom) {
        List<Node> nodes = new ArrayList<>(form.body.getChildren());
        int split = nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).lookup(".section-title") instanceof Label title
                    && title.getText().equalsIgnoreCase(rightFrom)) {
                split = i;
                break;
            }
        }
        VBox left = new VBox(form.body.getSpacing());
        left.getChildren().setAll(nodes.subList(0, split));
        VBox right = new VBox(form.body.getSpacing());
        right.getChildren().setAll(nodes.subList(split, nodes.size()));
        for (VBox column : List.of(left, right)) {
            column.setPrefWidth(0);
            column.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
        }
        HBox columns = new HBox(34, left, right);
        form.body.getChildren().setAll(columns);
    }

    private static ColorPicker colorPicker(String hex) {
        ColorPicker picker = new ColorPicker(Color.web(hex));
        picker.getStyleClass().add("brand-color-picker");
        return picker;
    }

    /** Color de JavaFX a #RRGGBB. */
    static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255));
    }


    // ---------- identidad de la funeraria ----------

    /** Tarjeta con el logo, el nombre, la frase y los 3 colores; "Editar identidad" abre el formulario. */
    private Node brandCard() {
        BrandView brand = BrandTheme.brand();
        LilyLogo logo = new LilyLogo();
        logo.setSize(64);
        Label name = label(brand.fullName(), "param-value");
        name.setWrapText(true);
        Label tagline = label(brand.tagline() == null ? "Sin frase" : "“" + brand.tagline() + "”", "param-desc");
        tagline.setWrapText(true);
        HBox swatches = new HBox(14, swatch("Principal", brand.primaryColor()), swatch("Acento", brand.accentColor()),
                swatch("Detalle", brand.detailColor()));
        swatches.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(4, name, tagline, swatches);
        VBox.setMargin(swatches, new Insets(8, 0, 0, 0));
        HBox.setHgrow(text, Priority.ALWAYS);
        Button edit = new Button("Editar identidad");
        edit.getStyleClass().add("edit-button");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.setOnAction(e -> onEditBrand());
        Button restore = new Button("Restaurar El Edén");
        restore.getStyleClass().add("edit-button");
        restore.setMinWidth(Region.USE_PREF_SIZE);
        restore.setDisable(brand.edenLook() && brand.showBandImage() && brand.showMenuImage()
                && brand.bandImage() == null && brand.menuImage() == null
                && BrandingService.DEFAULT_NAME.equals(brand.commercialName())
                && BrandingService.DEFAULT_TAGLINE.equals(brand.tagline()));
        restore.setOnAction(e -> onRestoreBrand(restore));
        VBox buttons = new VBox(8, edit, restore);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        edit.setMaxWidth(Double.MAX_VALUE);
        restore.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(18, logo, text, buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        String meta = brand.updatedAt() == null ? "Marca con la que viene el sistema"
                : "Modificado " + DATE_HOUR.format(brand.updatedAt())
                + (brand.updatedBy() == null ? "" : " · " + brand.updatedBy());
        Label description = label("Se ve en el inicio de sesión, el menú, las bandas de cada módulo, los correos, los"
                + " reportes y el bot de Telegram. Queda guardada en la base de datos: se ve igual en cada ingreso y en"
                + " todos los equipos. Solo el Administrador Maestro la puede cambiar.", "param-desc");
        description.setWrapText(true);
        VBox card = new VBox(10, row, description, label(meta, "param-meta"));
        card.getStyleClass().addAll("param-card", "brand-card");
        return card;
    }

    /** Vuelve a la marca El Edén (nombre, frase, emblema, lirios y colores) después de confirmar. */
    private void onRestoreBrand(Button restore) {
        boolean sure = confirm("¿Restaurar la identidad de El Edén?",
                "El nombre, la frase, el logo, las imágenes de las bandas y del menú y los colores vuelven a los de"
                        + " Funeraria El Edén. El cambio queda en la bitácora.", "Restaurar El Edén");
        if (!sure) {
            return;
        }
        restore.setDisable(true);
        async("branding-restore", brandingService::restoreDefaults,
                brand -> showToast("Se restauró la identidad de El Edén."),
                failure -> {
                    restore.setDisable(false);
                    showError(message(failure, "No se pudo restaurar. Revise la conexión e intente de nuevo."));
                });
    }

    /**
     * Fila para elegir la imagen de las bandas o del menú: el lirio de El Edén, una imagen propia o ninguna.
     * Muestra cómo queda y guarda la imagen nueva elegida del disco.
     */
    private static final class ImageChoiceRow {
        private final ComboBox<BrandingData.ImageChoice> choice = new ComboBox<>();
        private final ImageView preview = new ImageView();
        private final Label info = new Label();
        private final Image eden;
        private final byte[] stored;
        private byte[] picked;
        final HBox node;

        ImageChoiceRow(Image eden, byte[] stored, boolean show) {
            this.eden = eden;
            this.stored = stored;
            choice.getItems().setAll(BrandingData.ImageChoice.values());
            choice.getStyleClass().add("form-combo");
            choice.setMaxWidth(Double.MAX_VALUE);
            choice.setConverter(new StringConverter<>() {
                @Override
                public String toString(BrandingData.ImageChoice c) {
                    if (c == null) {
                        return "";
                    }
                    return switch (c) {
                        case EDEN -> "Lirio de El Edén";
                        case CUSTOM -> "Imagen propia";
                        case NONE -> "Sin imagen";
                    };
                }

                @Override
                public BrandingData.ImageChoice fromString(String s) {
                    return null;
                }
            });
            choice.setValue(!show ? BrandingData.ImageChoice.NONE
                    : stored != null && stored.length > 0 ? BrandingData.ImageChoice.CUSTOM : BrandingData.ImageChoice.EDEN);
            preview.setFitWidth(120);
            preview.setFitHeight(56);
            preview.setPreserveRatio(true);
            info.getStyleClass().add("param-meta");
            info.setWrapText(true);
            Button choose = new Button("Elegir imagen…");
            choose.getStyleClass().add("edit-button");
            choose.setGraphic(pictureIcon());
            choose.setMinWidth(Region.USE_PREF_SIZE);
            choose.setOnAction(e -> {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Elegir imagen");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
                File file = chooser.showOpenDialog(choose.getScene().getWindow());
                if (file == null) {
                    return;
                }
                try {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    if (bytes.length > BrandingService.MAX_LOGO_BYTES) {
                        info.setText("Esa imagen pesa más de 1 MB. Elija una más liviana.");
                        return;
                    }
                    picked = bytes;
                    choice.setValue(BrandingData.ImageChoice.CUSTOM);
                    refresh();
                    info.setText("Imagen nueva: " + file.getName());
                } catch (IOException failure) {
                    info.setText("No se pudo leer el archivo.");
                }
            });
            choice.valueProperty().addListener((obs, old, value) -> refresh());
            VBox text = new VBox(6, choice, info);
            HBox.setHgrow(text, Priority.ALWAYS);
            StackPane frame = new StackPane(preview);
            frame.getStyleClass().add("brand-image-frame");
            frame.setMinSize(132, 64);
            frame.setMaxSize(132, 64);
            node = new HBox(14, frame, text, choose);
            node.setAlignment(Pos.CENTER_LEFT);
            refresh();
        }

        private void refresh() {
            BrandingData.ImageChoice value = choice.getValue();
            byte[] custom = picked != null ? picked : stored;
            if (value == BrandingData.ImageChoice.NONE) {
                preview.setImage(null);
                info.setText("No se pone ninguna imagen.");
            } else if (value == BrandingData.ImageChoice.CUSTOM && custom != null && custom.length > 0) {
                preview.setImage(new Image(new ByteArrayInputStream(custom)));
                if (picked == null) {
                    info.setText("Imagen propia guardada.");
                }
            } else if (value == BrandingData.ImageChoice.CUSTOM) {
                preview.setImage(null);
                info.setText("Elija la imagen con el botón. PNG o JPG de hasta 1 MB.");
            } else {
                preview.setImage(eden);
                info.setText("El lirio con el que viene el sistema.");
            }
        }

        BrandingData.ImageChoice value() {
            return choice.getValue();
        }

        byte[] picked() {
            return choice.getValue() == BrandingData.ImageChoice.CUSTOM ? picked : null;
        }
    }

    /** Dibujo de una foto (marco, sol y montaña) para los botones "Elegir imagen…". */
    private static SVGPath pictureIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M1.5 2.5h11v9h-11z M4.5 5.2a1 1 0 1 0 0.01 0 M1.5 10l3.5-3.2 2.5 2.2 2-1.6 3 2.6");
        icon.getStyleClass().add("brand-pick-icon");
        return icon;
    }

    private static HBox swatch(String caption, String hex) {
        Region color = new Region();
        color.getStyleClass().add("brand-swatch");
        color.setStyle("-fx-background-color: " + hex + ";");
        color.setMinSize(22, 22);
        color.setMaxSize(22, 22);
        VBox text = new VBox(label(caption, "param-code"), label(hex, "param-meta"));
        HBox box = new HBox(8, color, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Formulario de la identidad: nombre, frase, logo y los 3 colores con una vista previa que cambia al escribir. */
    private void onEditBrand() {
        BrandView brand = BrandTheme.brand();
        TextField name = new TextField(brand.commercialName());
        name.setPromptText("Ej.: El Edén");
        TextField tagline = new TextField(brand.tagline() == null ? "" : brand.tagline());
        tagline.setPromptText("Opcional");
        ColorPicker primary = colorPicker(brand.primaryColor());
        ColorPicker accent = colorPicker(brand.accentColor());
        ColorPicker detail = colorPicker(brand.detailColor());

        // Logo: el actual, uno nuevo elegido del disco o el emblema del lirio
        byte[][] newLogo = new byte[1][];
        boolean[] removeLogo = new boolean[1];
        ImageView logoView = new ImageView(BrandTheme.logoProperty().get() != null ? BrandTheme.logoProperty().get()
                : LilyLogo.emblemImage());
        logoView.setFitWidth(64);
        logoView.setFitHeight(64);
        logoView.setPreserveRatio(true);
        Label logoInfo = label(brand.hasCustomLogo() ? "Logo propio de la funeraria." : "Emblema del lirio (El Edén).",
                "param-desc");
        logoInfo.setWrapText(true);
        logoInfo.getStyleClass().add("brand-logo-title");
        Label logoHint = label("PNG o JPG de hasta 1 MB. Mejor cuadrado y con fondo transparente.", "param-meta");
        logoHint.setWrapText(true);
        Button choose = new Button("Elegir imagen…");
        choose.getStyleClass().add("edit-button");
        Button emblem = new Button("Usar el emblema");
        emblem.getStyleClass().add("edit-button");
        choose.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Elegir el logo de la funeraria");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
            File file = chooser.showOpenDialog(choose.getScene().getWindow());
            if (file == null) {
                return;
            }
            try {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (bytes.length > BrandingService.MAX_LOGO_BYTES) {
                    logoInfo.setText("Esa imagen pesa más de 1 MB. Elija una más liviana.");
                    return;
                }
                newLogo[0] = bytes;
                removeLogo[0] = false;
                logoView.setImage(new Image(new ByteArrayInputStream(bytes)));
                logoInfo.setText("Logo nuevo: " + file.getName());
            } catch (IOException failure) {
                logoInfo.setText("No se pudo leer el archivo.");
            }
        });
        emblem.setOnAction(e -> {
            newLogo[0] = null;
            removeLogo[0] = true;
            logoView.setImage(LilyLogo.emblemImage());
            logoInfo.setText("Se usará el emblema del lirio (El Edén).");
        });
        VBox logoText = new VBox(4, logoInfo, logoHint);
        HBox.setHgrow(logoText, Priority.ALWAYS);
        choose.setMinWidth(Region.USE_PREF_SIZE);
        emblem.setMinWidth(Region.USE_PREF_SIZE);
        HBox logoButtons = new HBox(8, choose, emblem);
        logoButtons.setAlignment(Pos.CENTER_RIGHT);
        HBox logoRow = new HBox(16, logoView, logoText, logoButtons);
        logoRow.setAlignment(Pos.CENTER_LEFT);
        logoRow.getStyleClass().add("brand-logo-row");

        // Combinaciones de ejemplo y vuelta a El Edén
        HBox presets = new HBox(8);
        presets.setAlignment(Pos.CENTER_LEFT);
        for (String[] preset : BRAND_PRESETS) {
            Button button = new Button(preset[0]);
            button.getStyleClass().add("brand-preset");
            HBox dots = new HBox(-6);
            for (int i = 1; i <= 3; i++) {
                Region dot = new Region();
                dot.getStyleClass().add("brand-preset-dot");
                dot.setStyle("-fx-background-color: " + preset[i] + ";");
                dots.getChildren().add(dot);
            }
            button.setGraphic(dots);
            button.setOnAction(e -> {
                primary.setValue(Color.web(preset[1]));
                accent.setValue(Color.web(preset[2]));
                detail.setValue(Color.web(preset[3]));
            });
            presets.getChildren().add(button);
        }

        // Vista previa: menú con el nombre y un botón, con los colores que se están eligiendo
        Label previewSub = label("F U N E R A R I A", "brand-preview-sub");
        Label previewName = label("", "brand-preview-name");
        Label previewTagline = label("", "brand-preview-tagline");
        previewTagline.setWrapText(true);
        previewTagline.managedProperty().bind(previewTagline.textProperty().isNotEmpty());
        previewTagline.visibleProperty().bind(previewTagline.managedProperty());
        Label previewButton = label("Guardar", "brand-preview-button");
        Label previewLink = label("¿Olvidó su contraseña?", "brand-preview-link");
        Region previewRule = new Region();
        previewRule.getStyleClass().add("brand-preview-rule");
        // El logo de la vista previa sigue al logo elegido arriba
        ImageView previewLogo = new ImageView();
        previewLogo.imageProperty().bind(logoView.imageProperty());
        previewLogo.setFitWidth(34);
        previewLogo.setFitHeight(34);
        previewLogo.setPreserveRatio(true);
        HBox previewTitle = new HBox(10, previewLogo, new VBox(1, previewSub, previewName));
        previewTitle.setAlignment(Pos.CENTER_LEFT);
        VBox previewMenu = new VBox(14, previewTitle, previewTagline);
        previewMenu.getStyleClass().add("brand-preview-menu");
        previewMenu.setMinWidth(210);
        previewMenu.setPrefWidth(235);
        VBox previewBody = new VBox(12, previewRule, previewButton, previewLink);
        previewBody.getStyleClass().add("brand-preview-body");
        previewBody.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(previewBody, Priority.ALWAYS);
        HBox preview = new HBox(previewMenu, previewBody);
        preview.getStyleClass().add("brand-preview");
        Label readability = label("", "param-error");
        readability.setWrapText(true);
        readability.managedProperty().bind(readability.textProperty().isNotEmpty());
        readability.visibleProperty().bind(readability.managedProperty());
        Runnable updatePreview = () -> {
            BrandPalette palette = BrandPalette.of(hex(primary.getValue()), hex(accent.getValue()), hex(detail.getValue()));
            String typed = name.getText() == null || name.getText().isBlank() ? "Nombre" : name.getText().strip();
            previewName.setText(typed);
            previewTagline.setText(tagline.getText() == null || tagline.getText().isBlank() ? ""
                    : "\u201C" + tagline.getText().strip() + "\u201D");
            previewTagline.setStyle("-fx-text-fill: " + palette.get("-color-background") + ";");
            previewRule.setStyle("-fx-background-color: " + palette.get("-color-detail") + ";");
            previewMenu.setStyle("-fx-background-color: linear-gradient(to bottom, " + palette.get("-color-forest")
                    + ", " + palette.get("-color-forest-deep") + ");");
            previewSub.setStyle("-fx-text-fill: " + palette.get("-color-detail") + ";");
            previewName.setStyle("-fx-text-fill: " + palette.get("-color-background") + ";");
            previewBody.setStyle("-fx-background-color: " + palette.get("-color-content") + ";");
            previewButton.setStyle("-fx-background-color: " + palette.get("-color-accent") + "; -fx-text-fill: "
                    + palette.get("-color-background") + ";");
            previewLink.setStyle("-fx-text-fill: " + palette.get("-color-gold-deep") + ";");
            // Los problemas impiden guardar; los avisos solo informan
            List<String> notes = new ArrayList<>(palette.readabilityProblems());
            notes.addAll(palette.readabilityWarnings());
            readability.setText(String.join(" ", notes));
        };
        name.textProperty().addListener((obs, old, text) -> updatePreview.run());
        tagline.textProperty().addListener((obs, old, text) -> updatePreview.run());
        primary.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        accent.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        detail.valueProperty().addListener((obs, old, value) -> updatePreview.run());
        updatePreview.run();

        Form form = new Form();
        form.section("Nombre", 2)
                .field("NOMBRE COMERCIAL *", name)
                .field("FRASE", tagline);
        form.note(note("Escriba el nombre sin la palabra \"Funeraria\": el sistema la pone delante en los correos,"
                + " los reportes y el título de la ventana."));
        form.section("Logo", 1)
                .field("LOGO", logoRow);
        ImageChoiceRow band = new ImageChoiceRow(new Image(Objects.requireNonNull(
                ParametersController.class.getResource("/images/lirio-banda.png")).toExternalForm()),
                brand.bandImage(), brand.showBandImage());
        ImageChoiceRow menu = new ImageChoiceRow(new Image(Objects.requireNonNull(
                ParametersController.class.getResource("/images/lirio-fondo.png")).toExternalForm()),
                brand.menuImage(), brand.showMenuImage());
        form.section("Imágenes", 1)
                .field("BANDA SUPERIOR DE CADA MÓDULO", band.node)
                .field("FONDO DEL MENÚ LATERAL", menu.node);
        form.note(note("La imagen de las bandas va a la derecha, sobre el color principal: funciona mejor una imagen"
                + " horizontal con fondo transparente o que se desvanezca en los bordes."));
        form.section("Colores", 3)
                .field("COLOR PRINCIPAL *", primary)
                .field("COLOR DE ACENTO *", accent)
                .field("COLOR DE DETALLE *", detail);
        form.note(new VBox(8, label("Empezar desde una combinación (puede elegir cualquier color):", "param-meta"), presets));
        form.section("Vista previa", 1)
                .field("ASÍ SE VERÁ", new VBox(8, preview, readability));
        form.note(note("El principal pinta el menú y el panel del login; el de acento, los botones y los títulos;"
                + " el de detalle, los filetes y los textos dorados. Los demás tonos se calculan solos. Los colores de"
                + " error, aviso y éxito no cambian. Si un color deja un texto ilegible no se puede guardar."
                + " Cada cambio queda en la bitácora."));
        // Dos columnas: a la izquierda nombre, logo e imágenes; a la derecha colores y vista previa
        twoColumns(form, "Colores");
        Dialog<ButtonType> dialog = formDialog("Identidad de la funeraria", "Los campos con * son obligatorios.", form, 1180);
        FormShell.window(dialog, "Parámetros del sistema");
        runForm(dialog, form, "Guardar identidad",
                () -> brandingService.save(new BrandingData(name.getText(), tagline.getText(), newLogo[0], removeLogo[0],
                        hex(primary.getValue()), hex(accent.getValue()), hex(detail.getValue()),
                        band.value(), band.picked(), menu.value(), menu.picked())),
                saved -> showToast("Identidad guardada. Los colores ya cambiaron en todas las ventanas."));
    }

    /**
     * Reparte el formulario en dos columnas del mismo ancho: desde la sección {@code rightFrom}
     * (con sus avisos) todo pasa a la columna derecha.
     */
    private static void twoColumns(Form form, String rightFrom) {
        List<Node> nodes = new ArrayList<>(form.body.getChildren());
        int split = nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).lookup(".section-title") instanceof Label title
                    && title.getText().equalsIgnoreCase(rightFrom)) {
                split = i;
                break;
            }
        }
        VBox left = new VBox(form.body.getSpacing());
        left.getChildren().setAll(nodes.subList(0, split));
        VBox right = new VBox(form.body.getSpacing());
        right.getChildren().setAll(nodes.subList(split, nodes.size()));
        for (VBox column : List.of(left, right)) {
            column.setPrefWidth(0);
            column.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
        }
        HBox columns = new HBox(34, left, right);
        form.body.getChildren().setAll(columns);
    }

    private static ColorPicker colorPicker(String hex) {
        ColorPicker picker = new ColorPicker(Color.web(hex));
        picker.getStyleClass().add("brand-color-picker");
        return picker;
    }

    /** Color de JavaFX a #RRGGBB. */
    static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255));
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
        return Notice.confirm(window(), MODULE_NAME, question, detail, actionText);
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
        // Alto según el texto: así el aviso se lee completo y no termina en "…"
        note.setMinHeight(Region.USE_PREF_SIZE);
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
        Notice.warning(window(), MODULE_NAME, "Atención", text);
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
