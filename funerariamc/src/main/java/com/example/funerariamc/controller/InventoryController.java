package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.enums.InventoryCategory;
import com.example.funerariamc.model.enums.MovementType;
import com.example.funerariamc.service.InventoryItemData;
import com.example.funerariamc.service.InventoryMovementData;
import com.example.funerariamc.service.InventoryService;
import com.example.funerariamc.service.InventoryService.Filter;
import com.example.funerariamc.service.InventoryService.HistoryEntry;
import com.example.funerariamc.service.InventoryService.ItemRow;
import com.example.funerariamc.service.InventoryService.ItemView;
import com.example.funerariamc.service.InventoryService.Kpis;
import com.example.funerariamc.service.InventoryService.MovementResult;
import com.example.funerariamc.service.InventoryService.Overview;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pantalla de Inventario (pantalla 22 de Figma, paleta verde oscura): indicadores, buscador,
 * vista Tarjetas / Tabla, filtro por categoría o "Bajo mínimo", tarjetas con foto, stock, mínimo y
 * precio, aviso de reposición, panel lateral con el historial y los
 * diálogos Nuevo / Editar artículo y Movimiento (entrada o salida).
 * Todas las consultas corren en otro hilo; el filtro es en memoria, las tarjetas se dibujan por
 * tandas al bajar y las fotos cargan en segundo plano con caché.
 */
@Component
@Scope("prototype")
public class InventoryController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final double DRAWER_WIDTH = 480;
    /** Tarjetas que se dibujan de una vez; las demás se agregan al acercarse al final. */
    private static final int BATCH = 40;
    private static final double CARD_MIN_WIDTH = 250;
    private static final double CARD_GAP = 18;
    private static final double PHOTO_HEIGHT = 150;
    /** Fichas que se traen al caché apenas carga la pantalla (las primeras que se ven). */
    private static final int PREFETCH = 8;

    /** Anchos de la vista tabla; ARTÍCULO toma el espacio que sobra. */
    private static final double W_THUMB = 56;
    private static final double W_CATEGORY = 190;
    private static final double W_NUMBER = 96;
    private static final double W_MONEY = 120;
    private static final double W_STATE = 132;
    private static final double W_ACTIONS = 128;

    /** Siluetas por categoría cuando el artículo no tiene foto. */
    private static final String COFFIN_SHAPE = "M14 0 L34 0 L44 22 L38 96 L10 96 L4 22 Z";
    private static final String COFFIN_CROSS = "M24 16 V40 M16 25 H32";
    private static final String SUPPLY_SHAPE = "M16 0 H32 V8 H30 V24 L44 48 V90 Q44 96 38 96 H10 Q4 96 4 90 V48 L18 24 V8 H16 Z";
    private static final String SUPPLY_LINE = "M10 62 H38";
    private static final String PRODUCT_SHAPE = "M8 6 H40 Q44 6 44 10 V18 Q44 22 40 22 H8 Q4 22 4 18 V10 Q4 6 8 6 Z M8 26 H40 L38 90 Q38 96 32 96 H16 Q10 96 10 90 Z";
    private static final String PRODUCT_LINE = "M18 46 H30";

    private final InventoryService inventoryService;
    private final DetailCache<ItemView> details;
    /** Fotos ya pedidas: al volver a dibujar una tarjeta no se lee el archivo otra vez. */
    private final Map<String, Image> photos = new HashMap<>();

    @FXML
    private StackPane root;
    @FXML
    private ToggleGroup viewGroup;
    @FXML
    private ToggleGroup filterGroup;
    @FXML
    private ToggleButton cardsToggle;
    @FXML
    private ToggleButton tableToggle;
    @FXML
    private ToggleButton allToggle;
    @FXML
    private ToggleButton saleToggle;
    @FXML
    private ToggleButton useToggle;
    @FXML
    private ToggleButton lowToggle;
    @FXML
    private Label itemsValue;
    @FXML
    private Label itemsDetail;
    @FXML
    private VBox lowCard;
    @FXML
    private Label lowValue;
    @FXML
    private Label lowDetail;
    @FXML
    private Label valueValue;
    @FXML
    private Label movesCaption;
    @FXML
    private Label movesValue;
    @FXML
    private Label movesDetail;
    @FXML
    private TextField searchField;
    @FXML
    private Button newButton;
    @FXML
    private HBox restockBanner;
    @FXML
    private Label restockText;
    @FXML
    private ScrollPane cardScroll;
    @FXML
    private Label countLabel;
    @FXML
    private FlowPane cardFlow;
    @FXML
    private Label emptyLabel;
    @FXML
    private VBox tableCard;
    @FXML
    private Label tableCountLabel;
    @FXML
    private HBox tableHead;
    @FXML
    private ListView<ItemRow> itemsList;
    @FXML
    private Label tableEmptyLabel;
    @FXML
    private Region veil;
    @FXML
    private VBox drawer;
    @FXML
    private VBox drawerHead;
    @FXML
    private VBox drawerBody;
    @FXML
    private HBox drawerActions;
    @FXML
    private Label toast;

    private final ObservableList<ItemRow> tableRows = FXCollections.observableArrayList();
    private List<ItemRow> allRows = List.of();
    /** Filas del filtro actual; las tarjetas se dibujan de aquí por tandas. */
    private List<ItemRow> filtered = List.of();
    private int drawn;
    private boolean loaded;
    /** Artículo abierto en el panel; null si está cerrado. */
    private Long drawerItemId;
    /** Cada carga lleva un número; si llega una respuesta vieja, se descarta. */
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));
    /** Ancho de cada tarjeta según el espacio disponible (2 a 5 columnas). */
    private final DoubleProperty cardWidth = new SimpleDoubleProperty(CARD_MIN_WIDTH);

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
        this.details = new DetailCache<>(inventoryService::detail, "inventory-detail");
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        loadOverview(drawerItemId);
    }

    @FXML
    private void initialize() {
        // Dos inventarios: lo que vende el vendedor y lo que gasta la funeraria (insumos).
        allToggle.setUserData(Filter.ALL);
        saleToggle.setUserData(Filter.SALE);
        useToggle.setUserData(Filter.FUNERAL_USE);
        lowToggle.setUserData(Filter.LOW);
        keepOneSelected(filterGroup, this::applyFilter);
        keepOneSelected(viewGroup, this::applyView);

        searchDelay.setOnFinished(e -> applyFilter());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        // Columnas de las tarjetas según el ancho: nunca más angostas que CARD_MIN_WIDTH.
        cardFlow.widthProperty().addListener((obs, old, width) -> resizeCards(width.doubleValue()));
        cardScroll.vvalueProperty().addListener((obs, old, value) -> {
            if (value.doubleValue() > 0.85) {
                drawMore();
            }
        });

        tableHead.getChildren().setAll(
                cell(new Label(), W_THUMB),
                grow(label("ARTÍCULO", "head-cell")),
                cell(label("CATEGORÍA", "head-cell"), W_CATEGORY),
                cell(label("DISPONIBLE", "head-cell"), W_NUMBER),
                cell(label("MÍNIMO", "head-cell"), W_NUMBER),
                cell(label("PRECIO", "head-cell"), W_MONEY),
                cell(label("ESTADO", "head-cell"), W_STATE),
                cell(label("ACCIONES", "head-cell"), W_ACTIONS));
        itemsList.setItems(tableRows);
        itemsList.setCellFactory(list -> new ItemCell());
        itemsList.setFixedCellSize(65);
        itemsList.setPlaceholder(new Label(""));
        itemsList.setMinWidth(0);
        itemsList.setPrefWidth(0); // la tabla toma el ancho disponible, no el de su contenido

        drawer.setTranslateX(DRAWER_WIDTH);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);

        loadOverview(null);
    }

    private static void keepOneSelected(ToggleGroup group, Runnable onChange) {
        group.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda una opción elegida
            } else {
                onChange.run();
            }
        });
    }

    // ---------- carga ----------

    /** Indicadores y tarjetas (dos consultas). Si reopenItemId no es null, se vuelve a abrir su panel. */
    private void loadOverview(Long reopenItemId) {
        int request = ++generation;
        if (!loaded) {
            setEmpty("Cargando el inventario…");
        }
        async("inventory-overview", inventoryService::overview, overview -> {
            if (request != generation) {
                return;
            }
            loaded = true;
            details.clear();
            fillKpis(overview);
            allRows = overview.rows();
            applyFilter();
            details.prefetchAll(allRows.stream().limit(PREFETCH).map(ItemRow::id).toList());
            if (reopenItemId != null) {
                openDrawer(reopenItemId);
            }
        }, failure -> {
            if (request == generation) {
                setEmpty("");
                showError(message(failure, "No se pudo cargar el inventario."));
            }
        });
    }

    private void fillKpis(Overview overview) {
        Kpis k = overview.kpis();
        itemsValue.setText(String.valueOf(k.items()));
        itemsDetail.setText(k.items() == 0 ? "registre el primero"
                : k.forSale() + " para la venta · " + k.funeralUse() + " de uso");
        lowValue.setText(String.valueOf(k.low()));
        lowDetail.setText(k.low() == 0 ? "todo con existencias"
                : k.outOfStock() > 0 ? k.outOfStock() + (k.outOfStock() == 1 ? " agotado · reponer" : " agotados · reponer")
                : "pide reposición");
        lowCard.getStyleClass().remove("highlight");
        if (k.low() > 0) {
            lowCard.getStyleClass().add("highlight");
        }
        valueValue.setText(short_(k.value()));
        movesCaption.setText("MOVIMIENTOS DE " + k.monthName().toUpperCase(ES));
        movesValue.setText(String.valueOf(k.movements()));
        movesDetail.setText(k.exits() + k.sold() + (k.exits() + k.sold() == 1 ? " salida · " : " salidas · ")
                + k.entries() + (k.entries() == 1 ? " entrada" : " entradas"));

        // Aviso visible mientras haya artículos en nivel mínimo; no se repite por artículo.
        show(restockBanner, k.low() > 0);
        restockText.setText(k.low() == 1
                ? "1 artículo llegó a su nivel mínimo. Conviene reponerlo."
                : k.low() + " artículos llegaron a su nivel mínimo. Conviene reponerlos.");
    }

    /** Filtro, texto y vista se combinan en memoria (sin volver a la base). */
    private void applyFilter() {
        Filter filter = (Filter) filterGroup.getSelectedToggle().getUserData();
        filtered = InventoryService.filter(allRows, filter, searchField.getText());
        tableRows.setAll(filtered);
        long low = allRows.stream().filter(ItemRow::low).count();
        String count = allRows.isEmpty() ? "Sin artículos todavía"
                : filtered.size() == allRows.size()
                ? allRows.size() + (allRows.size() == 1 ? " artículo · " : " artículos · ") + low + " en nivel mínimo"
                : filtered.size() + " de " + allRows.size() + " artículos";
        countLabel.setText(count);
        tableCountLabel.setText(count);
        String empty = allRows.isEmpty() ? "Todavía no hay artículos. Use «+ Nuevo artículo» para registrar el primero."
                : filtered.isEmpty() ? "Sin resultados." : "";
        setEmpty(empty);
        drawCards();
    }

    private void applyView() {
        boolean table = viewGroup.getSelectedToggle() == tableToggle;
        tableCard.setVisible(table);
        cardScroll.setVisible(!table);
    }

    private void setEmpty(String text) {
        emptyLabel.setText(text);
        show(emptyLabel, !text.isEmpty());
        tableEmptyLabel.setText(text);
        tableEmptyLabel.setVisible(!text.isEmpty());
        if (!text.isEmpty() && !loaded) {
            cardFlow.getChildren().clear();
        }
    }

    // ---------- tarjetas ----------

    private void drawCards() {
        cardFlow.getChildren().clear();
        drawn = 0;
        cardScroll.setVvalue(0);
        drawMore();
    }

    /** Agrega la siguiente tanda de tarjetas (solo las que faltan). */
    private void drawMore() {
        if (drawn >= filtered.size()) {
            return;
        }
        int end = Math.min(filtered.size(), drawn + BATCH);
        List<Node> cards = new ArrayList<>(end - drawn);
        for (int i = drawn; i < end; i++) {
            cards.add(card(filtered.get(i)));
        }
        drawn = end;
        cardFlow.getChildren().addAll(cards);
    }

    private void resizeCards(double width) {
        if (width <= 0) {
            return;
        }
        int columns = Math.max(1, (int) ((width + CARD_GAP) / (CARD_MIN_WIDTH + CARD_GAP)));
        cardWidth.set(Math.floor((width - CARD_GAP * (columns - 1)) / columns) - 0.5);
    }

    private Node card(ItemRow r) {
        StackPane photo = photoPane(r, PHOTO_HEIGHT, cardWidth);
        photo.getStyleClass().add("item-photo");
        Button change = new Button("Cambiar foto");
        change.getStyleClass().add("photo-pill");
        change.setOnAction(e -> {
            e.consume();
            changePhoto(r);
        });
        StackPane.setAlignment(change, Pos.TOP_RIGHT);
        StackPane.setMargin(change, new Insets(12));
        photo.getChildren().add(change);
        if (r.low()) {
            Label chip = label(r.stock() <= 0 ? "Agotado" : "Nivel mínimo", "low-chip");
            StackPane.setAlignment(chip, Pos.TOP_LEFT);
            StackPane.setMargin(chip, new Insets(12));
            photo.getChildren().add(chip);
        }

        Label code = label((r.code() + " · " + r.categoryLabel()).toUpperCase(ES), "item-code");
        Label name = label(r.name(), "item-name");
        name.setMaxWidth(Double.MAX_VALUE);
        Label sub = label(stockState(r), "item-sub");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox stockLine = new HBox(label("Disponible " + r.stock(), "item-stock"), spacer,
                label("mínimo " + r.minimum(), "item-min"));
        stockLine.setAlignment(Pos.CENTER_LEFT);
        ProgressBar bar = stockBar(r);
        Button move = new Button("Movimiento");
        move.getStyleClass().add("move-button");
        move.setOnAction(e -> {
            e.consume();
            openMovementDialog(r);
        });
        Pane spacer2 = new Pane();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        HBox priceLine = new HBox(label(pesos(r.unitPrice()), "item-price"), spacer2, move);
        priceLine.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(stockLine, new Insets(10, 0, 4, 0));
        VBox.setMargin(priceLine, new Insets(10, 0, 0, 0));
        VBox body = new VBox(code, name, sub, stockLine, bar, priceLine);
        body.getStyleClass().add("item-body");

        VBox card = new VBox(photo, body);
        card.getStyleClass().add("item-card");
        if (r.low()) {
            card.getStyleClass().add("low");
        }
        card.prefWidthProperty().bind(cardWidth);
        card.minWidthProperty().bind(cardWidth);
        card.maxWidthProperty().bind(cardWidth);
        card.setOnMouseEntered(e -> details.prefetch(r.id()));
        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                openDrawer(r.id());
            }
        });
        return card;
    }

    /** Foto del artículo recortada para llenar el recuadro, o la silueta de su categoría. */
    private StackPane photoPane(ItemRow r, double height, DoubleProperty width) {
        StackPane pane = new StackPane();
        pane.setMinHeight(height);
        pane.setPrefHeight(height);
        pane.setMaxHeight(height);
        Node silhouette = silhouette(r.category(), height * 0.62);
        pane.getChildren().add(silhouette);
        Image image = photo(r.imageUrl());
        if (image != null) {
            ImageView view = new ImageView(image);
            view.setFitHeight(height);
            view.fitWidthProperty().bind(width);
            view.setSmooth(true);
            Runnable cover = () -> cover(view, image, width.get(), height);
            image.progressProperty().addListener((obs, old, p) -> {
                if (p.doubleValue() >= 1 && !image.isError()) {
                    cover.run();
                    silhouette.setVisible(false);
                }
            });
            width.addListener((obs, old, w) -> cover.run());
            if (image.getProgress() >= 1 && !image.isError()) {
                cover.run();
                silhouette.setVisible(false);
            }
            pane.getChildren().add(view);
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(pane.widthProperty());
        clip.heightProperty().bind(pane.heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        pane.setClip(clip);
        return pane;
    }

    /** Muestra solo la parte de la foto que llena w × h sin deformarla. */
    private static void cover(ImageView view, Image image, double w, double h) {
        double iw = image.getWidth();
        double ih = image.getHeight();
        if (iw <= 0 || ih <= 0 || w <= 0 || h <= 0) {
            return;
        }
        double target = w / h;
        if (iw / ih > target) {
            double cw = ih * target;
            view.setViewport(new Rectangle2D((iw - cw) / 2, 0, cw, ih));
        } else {
            double ch = iw / target;
            view.setViewport(new Rectangle2D(0, (ih - ch) / 2, iw, ch));
        }
    }

    /** La foto se pide una sola vez, reducida y en segundo plano, para no frenar la pantalla. */
    private Image photo(String url) {
        if (url == null) {
            return null;
        }
        return photos.computeIfAbsent(url, u -> new Image(u, 640, 0, true, true, true));
    }

    private static Node silhouette(InventoryCategory category, double height) {
        String shape;
        String detail;
        if (category == InventoryCategory.COFFIN) {
            shape = COFFIN_SHAPE;
            detail = COFFIN_CROSS;
        } else if (category == InventoryCategory.SUPPLY) {
            shape = SUPPLY_SHAPE;
            detail = SUPPLY_LINE;
        } else {
            shape = PRODUCT_SHAPE;
            detail = PRODUCT_LINE;
        }
        SVGPath body = new SVGPath();
        body.setContent(shape);
        body.getStyleClass().add("item-silhouette");
        SVGPath line = new SVGPath();
        line.setContent(detail);
        line.getStyleClass().add("item-silhouette-detail");
        Pane group = new Pane(body, line);
        double scale = height / 96.0;
        group.setScaleX(scale);
        group.setScaleY(scale);
        group.setMaxSize(48, 96);
        group.setPrefSize(48, 96);
        group.setMouseTransparent(true);
        return new StackPane(group);
    }

    private static ProgressBar stockBar(ItemRow r) {
        ProgressBar bar = new ProgressBar(fill(r.stock(), r.minimum()));
        bar.getStyleClass().add("stock-bar");
        if (r.low()) {
            bar.getStyleClass().add("low");
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        return bar;
    }

    /** Proporción de la barra: el stock frente al stock más el mínimo (lleno si no hay mínimo). */
    static double fill(int stock, int minimum) {
        if (stock <= 0) {
            return 0.02;
        }
        if (minimum <= 0) {
            return 1;
        }
        return Math.max(0.06, (double) stock / (stock + minimum));
    }

    private static String stockState(ItemRow r) {
        if (r.stock() <= 0) {
            return "Agotado · reponer";
        }
        if (r.low()) {
            return "Llegó al mínimo · reponer";
        }
        return "Con existencias";
    }

    // ---------- vista tabla ----------

    private final class ItemCell extends ListCell<ItemRow> {
        private final StackPane thumb = new StackPane();
        private final Label name = label("", "item-row-name");
        private final Label code = label("", "item-row-sub");
        private final Label category = label("", "item-row-text");
        private final Label stock = label("", "item-row-text");
        private final Label minimum = label("", "item-row-text");
        private final Label price = label("", "item-row-text");
        private final Label state = label("", "state-chip");
        private final Button move = rowButton("Movimiento");
        private final HBox row;

        ItemCell() {
            thumb.getStyleClass().add("item-row-thumb");
            thumb.setMinSize(42, 42);
            thumb.setMaxSize(42, 42);
            VBox who = new VBox(2, name, code);
            who.setAlignment(Pos.CENTER_LEFT);
            row = new HBox(cell(thumb, W_THUMB), grow(who), cell(category, W_CATEGORY), cell(stock, W_NUMBER),
                    cell(minimum, W_NUMBER), cell(price, W_MONEY), cell(state, W_STATE), cell(move, W_ACTIONS));
            row.getStyleClass().add("item-row");
            row.setMinWidth(0);
            setPrefWidth(0); // la fila se ajusta al ancho de la lista, sin barra horizontal
            move.setOnAction(e -> {
                if (getItem() != null) {
                    openMovementDialog(getItem());
                }
            });
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    openDrawer(getItem().id());
                }
            });
            setOnMouseEntered(e -> {
                if (getItem() != null) {
                    details.prefetch(getItem().id());
                }
            });
        }

        @Override
        protected void updateItem(ItemRow item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            thumb.getChildren().setAll(thumbnail(item));
            name.setText(item.name());
            code.setText(item.code());
            category.setText(item.categoryLabel());
            stock.setText(String.valueOf(item.stock()));
            minimum.setText(String.valueOf(item.minimum()));
            price.setText(pesos(item.unitPrice()));
            stateChip(state, item);
            setGraphic(row);
        }
    }

    /** Miniatura de 42 px: la foto si ya cargó o la silueta de la categoría. */
    private Node thumbnail(ItemRow r) {
        Image image = photo(r.imageUrl());
        if (image != null && !image.isError()) {
            ImageView view = new ImageView(image);
            view.setFitWidth(42);
            view.setFitHeight(42);
            Runnable fit = () -> cover(view, image, 42, 42);
            if (image.getProgress() >= 1) {
                fit.run();
            } else {
                image.progressProperty().addListener((obs, old, p) -> {
                    if (p.doubleValue() >= 1) {
                        fit.run();
                    }
                });
            }
            Rectangle clip = new Rectangle(42, 42);
            clip.setArcWidth(16);
            clip.setArcHeight(16);
            view.setClip(clip);
            return view;
        }
        return silhouette(r.category(), 30);
    }

    private static void stateChip(Label chip, ItemRow r) {
        chip.getStyleClass().removeAll("state-ok", "state-alert");
        if (r.stock() <= 0) {
            chip.setText("●  Agotado");
            chip.getStyleClass().add("state-alert");
        } else if (r.low()) {
            chip.setText("●  Nivel mínimo");
            chip.getStyleClass().add("state-alert");
        } else {
            chip.setText("●  Disponible");
            chip.getStyleClass().add("state-ok");
        }
    }

    private static Button rowButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("row-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Node cell(Node content, double width) {
        HBox box = new HBox(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        return box;
    }

    private static Node grow(Node content) {
        HBox box = new HBox(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(160);
        box.setPrefWidth(160);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    @FXML
    private void onShowLow() {
        lowToggle.setSelected(true);
    }

    // ---------- panel del artículo ----------

    private void openDrawer(Long itemId) {
        drawerItemId = itemId;
        ItemView cached = details.get(itemId);
        if (cached != null) {
            fillDrawer(cached);
        } else {
            drawerHead.getChildren().setAll(label("Cargando el artículo…", "drawer-head-text"));
            drawerBody.getChildren().clear();
            drawerActions.getChildren().clear();
            details.load(itemId, view -> {
                if (itemId.equals(drawerItemId)) {
                    fillDrawer(view);
                }
            }, failure -> {
                if (itemId.equals(drawerItemId)) {
                    closeDrawer();
                    showError(message(failure, "No se pudo cargar el detalle del artículo."));
                }
            });
        }
        slideDrawerIn();
    }

    private void fillDrawer(ItemView v) {
        ItemRow r = v.row();
        Label caption = label((r.code() + " · " + r.categoryLabel()).toUpperCase(ES), "drawer-head-caption");
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close-light");
        close.setOnAction(e -> closeDrawer());
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(caption, spacer, close);
        top.setAlignment(Pos.CENTER_LEFT);
        Label name = wrapped(r.name(), "drawer-head-name");
        Label state = label("", "state-chip");
        stateChip(state, r);
        HBox nameRow = new HBox(12, name, state);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Pane s1 = new Pane();
        HBox.setHgrow(s1, Priority.ALWAYS);
        HBox money = new HBox(label("Disponible " + r.stock() + " · mínimo " + r.minimum(), "drawer-head-strong"), s1,
                label(pesos(r.unitPrice()) + " c/u", "drawer-head-text"));
        drawerHead.getChildren().setAll(top, nameRow, money);

        DoubleProperty photoWidth = new SimpleDoubleProperty(DRAWER_WIDTH - 48);
        StackPane photo = photoPane(r, 210, photoWidth);
        photo.getStyleClass().add("drawer-photo");

        GridPane data = grid();
        field(data, 0, 0, "Cantidad disponible", r.stock() + (r.stock() == 1 ? " unidad" : " unidades"));
        field(data, 1, 0, "Nivel mínimo", String.valueOf(r.minimum()));
        field(data, 0, 1, "Precio unitario", pesos(r.unitPrice()));
        field(data, 1, 1, "Valor en inventario",
                pesos(r.unitPrice().multiply(BigDecimal.valueOf(Math.max(r.stock(), 0)))));
        Node stockNote = r.low()
                ? note("Llegó a su nivel mínimo: registre una entrada cuando lo reponga.")
                : note("");

        VBox history = new VBox();
        if (v.history().isEmpty()) {
            history.getChildren().add(label("Todavía no tiene movimientos.", "field-caption"));
        }
        for (HistoryEntry h : v.history()) {
            history.getChildren().add(historyRow(h));
        }
        drawerBody.getChildren().setAll(photo,
                section("EXISTENCIAS", data, stockNote),
                section("HISTORIAL DE MOVIMIENTOS", history));

        Button move = drawerButton("Registrar movimiento", "drawer-primary");
        move.setOnAction(e -> openMovementDialog(r));
        Button edit = drawerButton("Editar", "drawer-secondary");
        edit.setOnAction(e -> openItemDialog(r));
        Button photoButton = drawerButton("Cambiar foto", "drawer-secondary");
        photoButton.setOnAction(e -> changePhoto(r));
        drawerActions.getChildren().setAll(photoButton, edit, move);
        show(drawerActions, true);
    }

    private static Node historyRow(HistoryEntry h) {
        Label qty = label((h.quantity() > 0 ? "+" : "") + h.quantity(), "history-qty");
        qty.getStyleClass().add(h.quantity() > 0 ? "in" : "out");
        String main = h.kind() + (h.stockAfter() == null ? "" : " · quedó en " + h.stockAfter());
        Label title = label(main, "history-main");
        List<String> parts = new ArrayList<>();
        if (h.date() != null) {
            parts.add(DATE_HOUR.format(h.date()));
        }
        if (h.detail() != null) {
            parts.add(h.detail());
        }
        if (h.by() != null) {
            parts.add(h.by());
        }
        Label sub = wrapped(String.join(" · ", parts), "history-sub");
        VBox text = new VBox(2, title, sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(qty, text);
        row.getStyleClass().add("history-row");
        if (h.voided()) {
            qty.getStyleClass().add("history-voided");
            title.getStyleClass().add("history-voided");
            sub.setText(sub.getText() + " · devuelta al inventario");
        }
        return row;
    }

    private static VBox section(String title, Node... content) {
        VBox box = new VBox(10, label(title, "drawer-section-title"));
        box.getChildren().addAll(content);
        box.getStyleClass().add("drawer-section");
        return box;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            grid.getColumnConstraints().add(column);
        }
        return grid;
    }

    private static void field(GridPane grid, int column, int row, String caption, String value) {
        grid.add(new VBox(2, label(caption, "field-caption"), wrapped(value, "field-value")), column, row);
    }

    private void slideDrawerIn() {
        if (drawer.isVisible() && drawer.getTranslateX() == 0) {
            return;
        }
        veil.setVisible(true);
        drawer.setVisible(true);
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), drawer);
        slide.setToX(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), veil);
        fade.setToValue(1);
        new ParallelTransition(slide, fade).play();
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerItemId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerItemId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    // ---------- Nuevo / Editar artículo ----------

    @FXML
    private void onNewItem() {
        openItemDialog(null);
    }

    /** r null = artículo nuevo; si no, se edita (el stock no se toca aquí, solo con movimientos). */
    private void openItemDialog(ItemRow r) {
        boolean creating = r == null;
        TextField name = new TextField(creating ? "" : r.name());
        name.setPromptText("Ej.: Ataúd roble clásico");
        ComboBox<InventoryCategory> category = new ComboBox<>();
        category.getItems().addAll(InventoryCategory.values());
        category.getStyleClass().add("form-combo");
        category.setPromptText("Elija la categoría");
        // Cada categoría dice a qué inventario va: el del vendedor o el de uso de la funeraria.
        category.setConverter(converter(c -> c.getDisplayName()
                + (c.isForSale() ? " · para la venta" : " · uso de la funeraria"), ""));
        category.setMaxWidth(Double.MAX_VALUE);
        if (!creating) {
            category.setValue(r.category());
        }
        TextField price = new TextField(creating ? "" : plainPesos(r.unitPrice()));
        price.setPromptText("Ej.: 1.200.000");
        TextField stock = new TextField(creating ? "" : String.valueOf(r.stock()));
        stock.setPromptText("Ej.: 10");
        stock.setDisable(!creating);
        TextField minimum = new TextField(creating ? "" : String.valueOf(r.minimum()));
        minimum.setPromptText("Ej.: 3");

        Path[] chosen = new Path[1];
        Label fileName = wrapped(creating || r.imageUrl() == null ? "Sin foto: se mostrará la silueta de la categoría."
                : "Tiene foto. Elija otra solo si quiere cambiarla.", "field-caption");
        StackPane preview = new StackPane();
        preview.getStyleClass().add("photo-preview");
        preview.setMinSize(150, 96);
        preview.setMaxSize(150, 96);
        Button choose = new Button("Elegir foto…");
        choose.getStyleClass().add("small-light-button");
        Form form = new Form();
        choose.setOnAction(e -> {
            Path path = choosePhoto();
            if (path == null) {
                return;
            }
            try {
                InventoryService.validatePhoto(path);
            } catch (BusinessException ex) {
                form.error.setText(ex.getMessage());
                return;
            }
            form.error.setText("");
            chosen[0] = path;
            fileName.setText(path.getFileName().toString());
            ImageView view = new ImageView(new Image(path.toUri().toString(), 300, 0, true, true, true));
            view.setFitWidth(150);
            view.setFitHeight(96);
            view.setPreserveRatio(true);
            preview.getChildren().setAll(view);
        });
        if (!creating && r.imageUrl() != null) {
            Image current = photo(r.imageUrl());
            if (current != null) {
                ImageView view = new ImageView(current);
                view.setFitWidth(150);
                view.setFitHeight(96);
                view.setPreserveRatio(true);
                preview.getChildren().setAll(view);
            }
        }
        VBox photoBox = new VBox(8, new HBox(10, choose), fileName);

        form.section("Datos del artículo", 3)
                .field("NOMBRE *", name)
                .field("CATEGORÍA *", category)
                .field("PRECIO UNITARIO *", price)
                .section("Existencias", 3)
                .field(creating ? "CANTIDAD DISPONIBLE *" : "CANTIDAD DISPONIBLE", stock)
                .field("NIVEL MÍNIMO *", minimum)
                .section("Foto del artículo", 3)
                .field("VISTA PREVIA", preview)
                .field("ARCHIVO (JPG O PNG, MÁX. 2 MB)", photoBox)
                .note(note(creating
                        ? "Cuando la cantidad llegue al nivel mínimo, el artículo se marca «Nivel mínimo» y sale en el aviso de reposición."
                        : "La cantidad solo cambia con «Movimiento» (entrada o salida), así queda en el historial quién y por qué."));

        Dialog<ButtonType> dialog = formDialog(creating ? "Nuevo artículo" : "Editar artículo",
                creating ? "Registre el artículo con su existencia inicial y su nivel mínimo. Los campos con * son obligatorios."
                        : r.code() + " · Los campos con * son obligatorios.", form, 760);
        runForm(dialog, form, creating ? "Registrar artículo" : "Guardar cambios", () -> {
            InventoryItemData data = new InventoryItemData(name.getText(), category.getValue(),
                    creating ? parseInteger(stock.getText(), "La cantidad disponible") : null,
                    parseInteger(minimum.getText(), "El nivel mínimo"),
                    parseAmount(price.getText(), "El precio unitario"), chosen[0]);
            return creating ? inventoryService.register(data) : inventoryService.update(r.id(), data);
        }, id -> {
            showToast(creating ? "Artículo registrado: " + name.getText().trim() : "Cambios guardados.");
            loadOverview(creating ? null : id);
        });
    }

    /** "Cambiar foto" de la tarjeta o del panel: elige el archivo y lo guarda sin abrir otro formulario. */
    private void changePhoto(ItemRow r) {
        Path path = choosePhoto();
        if (path == null) {
            return;
        }
        try {
            InventoryService.validatePhoto(path);
        } catch (BusinessException ex) {
            showError(ex.getMessage());
            return;
        }
        if (r.category() == null) {
            showError("Primero elija la categoría del artículo con «Editar».");
            return;
        }
        InventoryItemData data = new InventoryItemData(r.name(), r.category(), null, r.minimum(), r.unitPrice(), path);
        showToast("Guardando la foto…");
        async("inventory-photo", () -> inventoryService.update(r.id(), data), id -> {
            showToast("Foto actualizada: " + r.name());
            loadOverview(drawerItemId);
        }, failure -> showError(message(failure, "No se pudo guardar la foto. Intente de nuevo.")));
    }

    private Path choosePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Elegir foto del artículo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes JPG o PNG", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(window());
        return file == null ? null : file.toPath();
    }

    // ---------- Movimiento: entrada o salida ----------

    private void openMovementDialog(ItemRow r) {
        ToggleGroup typeGroup = new ToggleGroup();
        HBox types = new HBox();
        types.getStyleClass().addAll("segmented", "form-segmented");
        Map<MovementType, ToggleButton> toggles = new LinkedHashMap<>();
        for (MovementType type : MovementType.values()) {
            ToggleButton toggle = new ToggleButton(type == MovementType.ENTRY ? "Entrada (suma)" : "Salida (resta)");
            toggle.getStyleClass().add("segment");
            toggle.setToggleGroup(typeGroup);
            toggle.setUserData(type);
            types.getChildren().add(toggle);
            toggles.put(type, toggle);
        }
        toggles.get(r.low() ? MovementType.ENTRY : MovementType.EXIT).setSelected(true);
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true);
            }
        });
        TextField quantity = new TextField();
        quantity.setPromptText("Ej.: 2");
        TextField reason = new TextField();
        reason.setPromptText("Ej.: compra al proveedor, consumo en preparación");

        Label preview = label("", "stock-preview-value");
        Label previewSub = label("Actual: " + r.stock() + " · mínimo " + r.minimum(), "field-caption");
        VBox previewBox = new VBox(4, label("QUEDARÁ EN", "field-label"), preview, previewSub);
        previewBox.getStyleClass().add("stock-preview");
        Runnable update = () -> {
            MovementType type = (MovementType) typeGroup.getSelectedToggle().getUserData();
            Integer n;
            try {
                n = parseInteger(quantity.getText(), "La cantidad");
            } catch (InvalidDataException e) {
                n = null;
            }
            preview.getStyleClass().remove("low");
            if (n == null || n <= 0) {
                preview.setText(r.stock() + (r.stock() == 1 ? " unidad" : " unidades"));
                return;
            }
            int after = type == MovementType.ENTRY ? r.stock() + n : r.stock() - n;
            if (after < 0) {
                preview.setText("Solo hay " + r.stock() + " disponibles");
                preview.getStyleClass().add("low");
                return;
            }
            boolean low = InventoryService.isLow(after, r.minimum());
            preview.setText(after + (after == 1 ? " unidad" : " unidades") + (low ? " · nivel mínimo" : ""));
            if (low) {
                preview.getStyleClass().add("low");
            }
        };
        quantity.textProperty().addListener((obs, old, text) -> update.run());
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle != null) {
                update.run();
            }
        });
        update.run();

        Form form = new Form()
                .section("Movimiento", 2)
                .field("TIPO *", types)
                .field("CANTIDAD (UNIDADES) *", quantity)
                .section("Detalle", 2)
                .field("MOTIVO", reason)
                .field("", previewBox)
                .note(note("Las ventas de Contratos y ventas ya descuentan el inventario solas; aquí se registran compras, consumos y ajustes."));
        Dialog<ButtonType> dialog = formDialog("Registrar movimiento", r.code() + " · " + r.name(), form, 640);
        runForm(dialog, form, "Registrar", () -> inventoryService.registerMovement(new InventoryMovementData(r.id(),
                (MovementType) typeGroup.getSelectedToggle().getUserData(),
                parseInteger(quantity.getText(), "La cantidad"), reason.getText())), result -> {
            showToast(movementMessage(result));
            loadOverview(drawerItemId);
        });
    }

    static String movementMessage(MovementResult result) {
        if (result.low()) {
            return result.name() + " quedó en nivel mínimo: " + result.stock() + " de " + result.minimum()
                    + ". Conviene reponerlo.";
        }
        return "Movimiento registrado. " + result.name() + " queda con " + result.stock()
                + (result.stock() == 1 ? " unidad." : " unidades.");
    }

    // ---------- formulario "Personal · El Edén" ----------

    /**
     * Formulario de los diálogos: título de sección en mayúsculas con filete caqui, etiqueta arriba
     * de cada campo y columnas de igual ancho, como la vista interactiva de Personal del 19/10.
     */
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
            if (input instanceof Region region && !(input instanceof Label) && !(input instanceof StackPane)) {
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
        VBox header = new VBox(2, label(title, "form-title"), wrapped(subtitle, "card-subtitle"));
        header.getStyleClass().add("form-header");
        ScrollPane scroll = new ScrollPane(form.body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");
        scroll.prefViewportHeightProperty().bind(form.body.heightProperty());
        scroll.setMaxHeight(javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.72);
        VBox.setMargin(form.error, new Insets(6, 28, 4, 28));
        VBox content = new VBox(header, scroll, form.error);
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
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); // el diálogo se cierra solo cuando el servicio confirma que guardó
            form.error.setText("");
            okButton.setText("Guardando…");
            okButton.setDisable(true);
            cancelButton.setDisable(true);
            form.body.setDisable(true);
            async("inventory-save", save, result -> {
                saved[0] = result;
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
            if (okButton.isDisabled() && saved[0] == null && dialog.getResult() != ok) {
                event.consume();
            }
        });
        if (dialog.showAndWait().filter(ok::equals).isPresent() && saved[0] != null) {
            @SuppressWarnings("unchecked")
            T result = (T) saved[0];
            onSaved.accept(result);
        }
    }

    // ---------- piezas ----------

    private static Integer parseInteger(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim().replace(".", ""));
        } catch (NumberFormatException e) {
            throw new InvalidDataException(what + " debe ser un número entero.");
        }
    }

    /** "1.200.000", "1200000" o "$ 1.200.000" pasan a 1200000. */
    static BigDecimal parseAmount(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.replace("$", "").replace(".", "").replace(" ", "").replace(",", ".");
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new InvalidDataException(what + " debe ser un valor en pesos.");
        }
    }

    private static Label note(String text) {
        Label note = wrapped(text, "note-info");
        note.setGraphic(infoIcon());
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        note.managedProperty().bind(note.textProperty().isNotEmpty());
        note.visibleProperty().bind(note.managedProperty());
        return note;
    }

    private static Node infoIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
        icon.getStyleClass().add("note-icon");
        return icon;
    }

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    /** Las ventanas cargan app.css, personal.css e inventory.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                InventoryController.class.getResource("/css/app.css").toExternalForm(),
                InventoryController.class.getResource("/css/personal.css").toExternalForm(),
                InventoryController.class.getResource("/css/inventory.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "inventory-root");
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
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

    private static Label wrapped(String text, String styleClass) {
        Label label = label(text, styleClass);
        label.setWrapText(true);
        return label;
    }

    private static <T> StringConverter<T> converter(Function<T, String> text, String empty) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? empty : text.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }

    static String pesos(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(ES);
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP));
    }

    /** "1.200.000" para volver a mostrarlo en el campo de precio. */
    private static String plainPesos(BigDecimal value) {
        return NumberFormat.getIntegerInstance(ES).format(value == null ? BigDecimal.ZERO
                : value.setScale(0, RoundingMode.HALF_UP));
    }

    /** "$ 86,2 M" desde un millón; por debajo, el valor completo. */
    static String short_(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.abs().compareTo(BigDecimal.valueOf(1_000_000)) < 0) {
            return pesos(v);
        }
        DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(ES));
        return "$ " + format.format(v.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP).doubleValue()) + " M";
    }
}
