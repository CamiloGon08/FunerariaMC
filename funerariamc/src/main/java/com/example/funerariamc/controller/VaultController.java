package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.utils.enums.ExhumationDestination;
import com.example.funerariamc.utils.enums.ExhumationReason;
import com.example.funerariamc.utils.enums.NotificationChannel;
import com.example.funerariamc.utils.enums.NotificationStatus;
import com.example.funerariamc.utils.enums.NotificationType;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.ui.ModuleLink;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AssignVaultData;
import com.example.funerariamc.service.CemeteryService;
import com.example.funerariamc.service.CemeteryService.VaultBatch;
import com.example.funerariamc.service.CemeteryService.Attention;
import com.example.funerariamc.service.CemeteryService.HistoryEvent;
import com.example.funerariamc.service.CemeteryService.Kpis;
import com.example.funerariamc.service.CemeteryService.Notice;
import com.example.funerariamc.service.CemeteryService.Option;
import com.example.funerariamc.service.CemeteryService.Overview;
import com.example.funerariamc.service.CemeteryService.RecentExhumation;
import com.example.funerariamc.service.CemeteryService.Screen;
import com.example.funerariamc.service.CemeteryService.VaultDetail;
import com.example.funerariamc.service.CemeteryService.WallCell;
import com.example.funerariamc.service.ExhumationData;
import com.example.funerariamc.service.VaultPosition;
import com.example.funerariamc.service.VaultState;
import com.example.funerariamc.ui.Pager;
import com.example.funerariamc.ui.DetailShell;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pantalla Cementerio y bóvedas: una pestaña por cementerio, el muro
 * del pabellón por niveles (o la lista), indicadores, bóvedas que requieren atención, últimas
 * exhumaciones y el panel lateral con el detalle de la bóveda. Desde el panel se
 * asigna, se renueva el arriendo o se registra la exhumación.
 *
 * Rendimiento (regla del proyecto): ninguna consulta corre en el hilo de JavaFX.
 * - La primera carga trae pestañas y muro juntos; el menú guarda la pantalla y al volver solo refresca.
 * - Cada cementerio ya visto queda en memoria: al cambiar de pestaña se pinta al instante y se refresca atrás.
 * - El panel de una bóveda se adelanta al pasar el mouse (DetailCache) y abre sin esperar a la BD.
 * - El muro y la lista de cada pabellón se arman una vez y se reutilizan hasta que llegan datos nuevos.
 * - Los buscadores y los botones Guardar de los diálogos trabajan en otro hilo, sin congelar la ventana.
 */
@Component
@Scope("prototype")
public class VaultController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Cementerio y bóvedas";

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Bóvedas de "Requieren atención" cuyo panel se adelanta apenas carga el muro. */
    private static final int PREFETCH_ATTENTION = 3;
    /** Arriendos por página en la columna "Requieren atención". */
    private static final int ATTENTION_PAGE = 8;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    /** Desde cuántas columnas el subtítulo avisa que hay que deslizar el muro. */
    private static final int WIDE_WALL = 14;
    /** Ancho mínimo de una bóveda del muro: con muchas columnas aparece la barra horizontal. */
    private static final double CELL_MIN_WIDTH = 66;
    private static final PseudoClass DIMMED = PseudoClass.getPseudoClass("dimmed");

    private static final Map<ExhumationReason, String> REASONS = Map.of(
            ExhumationReason.UNCLAIMED, "Cuerpo no reclamado",
            ExhumationReason.FAMILY_REQUEST, "Solicitud de la familia");
    private static final Map<ExhumationDestination, String> DESTINATIONS = Map.of(
            ExhumationDestination.COMMON_GRAVE, "fosa común",
            ExhumationDestination.OSSUARY, "osario",
            ExhumationDestination.CREMATION, "cremación",
            ExhumationDestination.FAMILY, "entrega a la familia");

    private final CemeteryService cemeteryService;
    /** Paneles de bóveda ya consultados o adelantados; se vacía cada vez que llegan datos nuevos. */
    private final DetailCache<VaultDetail> details;
    /** Espera a que el usuario deje de escribir antes de resaltar las bóvedas. */
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(250));
    /** Último muro recibido de cada cementerio, para cambiar de pestaña sin esperar. */
    private final Map<Long, Overview> overviews = new HashMap<>();
    /** Muro y lista ya armados por pabellón (clave "A|plano"); se descartan con datos nuevos. */
    private final Map<String, Node> builtViews = new HashMap<>();
    private final Map<String, List<Node>> builtLists = new HashMap<>();
    /** Vista lista: todas las filas del pabellón, la página visible y su barra de páginas. */
    private List<Node> listRows = List.of();
    private final VBox listPage = new VBox(6);
    private Pager<Node> listPager;
    private Pager<Attention> attentionPager;
    private String lastListKey;

    @FXML
    private StackPane root;
    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup cemeteryGroup;
    @FXML
    private ToggleGroup viewGroup;
    @FXML
    private HBox cemeteryTabs;
    @FXML
    private ComboBox<String> pavilionFilter;
    @FXML
    private ToggleButton planToggle;
    @FXML
    private ToggleButton listToggle;
    @FXML
    private ScrollPane pageScroll;
    @FXML
    private Label occupancyCaption;
    @FXML
    private Label occupancyValue;
    @FXML
    private ProgressBar occupancyBar;
    @FXML
    private Label occupancyDetail;
    @FXML
    private Label availableValue;
    @FXML
    private Label overdueValue;
    @FXML
    private Label expiringCaption;
    @FXML
    private Label expiringValue;
    @FXML
    private Label wallTitle;
    @FXML
    private Label wallSubtitle;
    @FXML
    private HBox legend;
    @FXML
    private ScrollPane wallScroll;
    @FXML
    private StackPane wallBox;
    @FXML
    private StackPane listBox;
    @FXML
    private Label emptyLabel;
    @FXML
    private Label selectionLabel;
    @FXML
    private HBox previewBar;
    @FXML
    private Button addCemeteryButton;
    @FXML
    private Button addVaultsButton;
    @FXML
    private Label countLabel;
    @FXML
    private Label attentionCount;
    @FXML
    private VBox attentionBox;
    @FXML
    private VBox exhumationBox;
    @FXML
    private VBox drawer;
    @FXML
    private Region veil;
    @FXML
    private Label toast;

    private Long cemeteryId;
    private String cemeteryName = "";
    private Overview overview;
    private Long selectedVaultId;
    /** Bóveda del panel abierto; null si está cerrado. */
    private Long drawerVaultId;
    private DetailShell detail;
    /** Descarta respuestas viejas si el usuario cambió de cementerio mientras cargaba. */
    private int generation;
    /** Evita que al llenar las pestañas se dispare otra carga. */
    private boolean fillingTabs;
    private SequentialTransition toastMotion;

    public VaultController(CemeteryService cemeteryService) {
        this.cemeteryService = cemeteryService;
        this.details = new DetailCache<>(cemeteryService::detail, "vault-detail");
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        loadScreen(drawerVaultId);
    }

    @FXML
    private void initialize() {
        // Paginación como la bitácora en la vista lista: 20 bóvedas por página, debajo de la lista.
        listPager = Pager.<Node>local(Pager.TABLE_PAGE, "bóveda", "bóvedas",
                page -> listPage.getChildren().setAll(page)).below(listBox);
        // La columna de arriendos que requieren atención es angosta: 8 por página.
        attentionPager = Pager.<Attention>local(ATTENTION_PAGE, "bóveda", "bóvedas", this::drawAttention)
                .below(attentionBox);
        buildLegend();
        // Sin pabellones (cementerio nuevo o sin cementerios) el selector no se muestra: quedaba una caja vacía
        pavilionFilter.setPromptText("Pabellón");
        pavilionFilter.visibleProperty().bind(Bindings.isNotEmpty(pavilionFilter.getItems()));
        pavilionFilter.managedProperty().bind(pavilionFilter.visibleProperty());
        pavilionFilter.setButtonCell(pavilionCell());
        pavilionFilter.setCellFactory(list -> pavilionCell());
        pavilionFilter.valueProperty().addListener((obs, old, value) -> renderPavilion());

        viewGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda Plano o Lista
            } else {
                renderPavilion();
            }
        });
        cemeteryGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true);
            } else if (!fillingTabs) {
                Option cemetery = (Option) toggle.getUserData();
                switchCemetery(cemetery);
            }
        });

        searchDelay.setOnFinished(e -> applySearch());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        // Registrar cementerios y bóvedas es solo del Administrador Maestro.
        boolean canRegister = cemeteryService.canRegister();
        show(addCemeteryButton, canRegister);
        show(addVaultsButton, canRegister);

        detail = DetailShell.install(drawer, veil, DetailShell.Kind.PANEL);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        countLabel.setText("Cargando bóvedas…");
        loadScreen(null);
    }

    // ---------- carga ----------

    /**
     * Pestañas y muro del cementerio actual en una sola consulta al servicio. Lo que ya está en
     * pantalla se queda hasta que llegan los datos nuevos; si {@code reopenVaultId} no es null,
     * se vuelve a abrir su panel con los datos frescos.
     */
    private void loadScreen(Long reopenVaultId) {
        Long requested = cemeteryId;
        int requestGeneration = ++generation;
        run(() -> cemeteryService.screen(requested), screen -> {
            if (requestGeneration != generation) {
                return;
            }
            fillTabs(screen);
            if (screen.overview() == null) {
                overview = null;
                showEmpty(cemeteryService.canRegister()
                        ? "No hay cementerios registrados. Registre el primero con \"+ Cementerio\" y luego agregue sus bóvedas."
                        : "No hay cementerios registrados. El Administrador Maestro los registra con sus bóvedas.");
                renderSide();
                return;
            }
            applyOverview(screen.cemeteryId(), screen.overview(), reopenVaultId);
            // Desde "Esperan destino" en Difuntos: se abre "Asignar bóveda" con ese difunto
            ModuleLink.take(AppModule.CEMENTERIO).ifPresent(payload -> {
                if (payload instanceof DeceasedController.DeceasedLink link) {
                    openAssignDialog(selectedAvailableVault().orElse(null), link);
                }
            });
        }, "No se pudieron cargar las bóvedas.");
    }

    private void fillTabs(Screen screen) {
        fillingTabs = true;
        try {
            cemeteryTabs.getChildren().clear();
            cemeteryGroup.getToggles().clear();
            for (Option cemetery : screen.cemeteries()) {
                ToggleButton tab = new ToggleButton(cemetery.label());
                tab.getStyleClass().add("segment");
                tab.setUserData(cemetery);
                tab.setToggleGroup(cemeteryGroup);
                tab.setMinWidth(Region.USE_PREF_SIZE);
                cemeteryTabs.getChildren().add(tab);
                if (cemetery.id().equals(screen.cemeteryId())) {
                    tab.setSelected(true);
                    cemeteryName = cemetery.label();
                }
            }
        } finally {
            fillingTabs = false;
        }
    }

    /** Cambio de pestaña: si el cementerio ya se vio se pinta al instante y luego se refresca. */
    private void switchCemetery(Option cemetery) {
        cemeteryId = cemetery.id();
        cemeteryName = cemetery.label();
        selectedVaultId = null;
        closeDrawer();
        Overview cached = overviews.get(cemetery.id());
        if (cached != null) {
            showOverview(cached, null);
        } else {
            countLabel.setText("Cargando bóvedas…");
        }
        Long requested = cemetery.id();
        int requestGeneration = ++generation;
        run(() -> cemeteryService.overview(requested), result -> {
            if (requestGeneration == generation) {
                applyOverview(requested, result, null);
            }
        }, "No se pudieron cargar las bóvedas.");
    }

    /** Guarda el muro recibido, olvida lo armado con los datos viejos y lo pinta. */
    private void applyOverview(Long forCemetery, Overview result, Long reopenVaultId) {
        cemeteryId = forCemetery;
        // Si nada cambió desde la última vez, se deja lo pintado y los paneles ya consultados.
        if (reopenVaultId == null && result.equals(overview) && result.equals(overviews.get(forCemetery))) {
            return;
        }
        overviews.put(forCemetery, result);
        details.clear(); // después de guardar o al volver, ningún panel guardado puede quedar viejo
        showOverview(result, reopenVaultId);
        // Los paneles de las bóvedas en mora o por vencer son los que más se abren: se adelantan.
        details.prefetchAll(result.attention().stream().limit(PREFETCH_ATTENTION).map(Attention::vaultId).toList());
    }

    private void showOverview(Overview result, Long reopenVaultId) {
        overview = result;
        builtViews.clear();
        builtLists.clear();
        String previous = pavilionFilter.getValue();
        pavilionFilter.getItems().setAll(result.pavilions().keySet());
        String pavilion = previous != null && result.pavilions().containsKey(previous) ? previous
                : pavilionFilter.getItems().isEmpty() ? null : pavilionFilter.getItems().get(0);
        if (pavilion != null && pavilion.equals(pavilionFilter.getValue())) {
            renderPavilion(); // mismo pabellón: el listener no salta, se pinta a mano
        } else {
            pavilionFilter.setValue(pavilion);
            if (pavilion == null) {
                renderPavilion();
            }
        }
        renderSide();
        if (reopenVaultId != null) {
            openDrawer(reopenVaultId);
        }
    }

    /** La consulta corre en otro hilo para que la ventana no se congele. */
    private <T> void run(Callable<T> work, Consumer<T> onDone, String error) {
        async("cemetery-loader", work, onDone, failure -> {
            countLabel.setText("");
            showError(failure instanceof BusinessException business ? business.getMessage() : error);
        });
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

    // ---------- muro y lista ----------

    private List<WallCell> currentCells() {
        String pavilion = pavilionFilter.getValue();
        if (overview == null || pavilion == null) {
            return List.of();
        }
        return overview.pavilions().getOrDefault(pavilion, List.of());
    }

    private void renderPavilion() {
        List<WallCell> cells = currentCells();
        String pavilion = pavilionFilter.getValue();
        boolean plan = planToggle.isSelected();

        Kpis kpis = Kpis.of(cells);
        String pavilionName = pavilion == null ? "" : pavilionLabel(pavilion);
        occupancyCaption.setText(pavilion == null ? "OCUPACIÓN" : "OCUPACIÓN · " + pavilionName.toUpperCase(Locale.ROOT));
        occupancyValue.setText(kpis.occupancyPercent() + " %");
        occupancyBar.setProgress(kpis.total() == 0 ? 0 : kpis.occupied() / (double) kpis.total());
        occupancyDetail.setText(kpis.occupied() + " de " + count(kpis.total(), "bóveda"));
        availableValue.setText(String.valueOf(kpis.available()));
        overdueValue.setText(String.valueOf(kpis.overdue()));
        expiringCaption.setText("VENCEN EN " + VaultState.EXPIRING_DAYS + " DÍAS");
        expiringValue.setText(String.valueOf(kpis.expiring()));

        int levels = cells.stream().mapToInt(c -> c.position().level()).max().orElse(0);
        int columns = columnsOf(cells);
        wallTitle.setText(pavilion == null ? "Sin bóvedas" : pavilionName);
        wallSubtitle.setText(levels == 0 ? cemeteryName
                : count(levels, "nivel", "niveles") + " × " + columns + " columnas · el nivel 1 es el de abajo"
                + (columns > WIDE_WALL ? " · deslice a los lados con la barra de abajo" : ""));
        countLabel.setText(cells.isEmpty() ? "" : count(cells.size(), "bóveda") + " en el pabellón");

        show(wallScroll, plan && !cells.isEmpty());
        show(listBox, !plan && !cells.isEmpty());
        if (cells.isEmpty()) {
            showEmpty(overview == null || overview.pavilions().isEmpty()
                    ? "Este cementerio aún no tiene bóvedas registradas."
                    : "No hay bóvedas en este pabellón.");
            return;
        }
        show(emptyLabel, false);
        String key = pavilion + (plan ? "|plano" : "|lista");
        if (plan) {
            wallBox.getChildren().setAll(builtViews.computeIfAbsent(key, k -> buildWall(cells, levels, columns)));
            listRows = List.of();
            listPager.setItemsFromStart(List.of()); // el plano es un dibujo del muro: no se pagina
        } else {
            listRows = builtLists.computeIfAbsent(key, k -> buildList(cells));
            listBox.getChildren().setAll(listPage);
        }
        markSelected();
        updatePreview();
        applySearch();
    }

    /** Columnas del pabellón: la más alta que usen sus bóvedas (sin límite; mínimo las 10 de siempre). */
    private static int columnsOf(List<WallCell> cells) {
        int max = cells.stream().mapToInt(c -> c.position().column()).max().orElse(0);
        return Math.max(max, Math.min(VaultPosition.COLUMNS, cells.size()));
    }

    /**
     * Muro: un renglón por nivel, el nivel más alto arriba. Hasta 10 columnas las celdas se estiran
     * a lo ancho de la tarjeta; con más, cada celda guarda un ancho mínimo y aparece la barra de
     * desplazamiento horizontal, con los números de columna arriba para no perderse.
     */
    private Node buildWall(List<WallCell> cells, int levels, int columns) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("wall");
        grid.setHgap(8);
        grid.setVgap(8);
        grid.getColumnConstraints().add(new ColumnConstraints());
        for (int column = 1; column <= columns; column++) {
            ColumnConstraints stretch = new ColumnConstraints();
            stretch.setHgrow(Priority.ALWAYS);
            stretch.setFillWidth(true);
            stretch.setMinWidth(CELL_MIN_WIDTH);
            grid.getColumnConstraints().add(stretch);
        }
        if (columns > VaultPosition.COLUMNS) {
            for (int column = 1; column <= columns; column++) {
                Label axis = new Label(String.format("%02d", column));
                axis.getStyleClass().add("wall-axis");
                axis.setMaxWidth(Double.MAX_VALUE);
                axis.setAlignment(Pos.CENTER);
                grid.add(axis, column, 0);
            }
        }
        for (int level = levels; level >= 1; level--) {
            Label name = new Label("Nivel " + level);
            name.getStyleClass().add("wall-level");
            name.setMinWidth(Region.USE_PREF_SIZE);
            name.setPrefWidth(56);
            grid.add(name, 0, levels - level + 1);
        }
        for (WallCell cell : cells) {
            grid.add(vaultCell(cell), cell.position().column(), levels - cell.position().level() + 1);
        }
        return grid;
    }

    private VBox vaultCell(WallCell cell) {
        Label code = new Label(cell.code());
        code.getStyleClass().add("vault-code");
        Label name = new Label(cell.state() == VaultState.AVAILABLE ? "Libre" : orDash(cell.occupantLastname()));
        name.getStyleClass().add("vault-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setAlignment(Pos.CENTER);
        VBox box = new VBox(code, name);
        box.setMaxWidth(Double.MAX_VALUE);
        box.getStyleClass().addAll("vault-cell", "vault-" + cssName(cell.state()));
        box.setUserData(cell);
        prepareTarget(box, cell);
        return box;
    }

    /** Lista: una fila por bóveda con su nivel, estado, difunto, titular y vencimiento. */
    /** Filas de la vista lista (todas las del pabellón); se muestran por páginas. */
    private List<Node> buildList(List<WallCell> cells) {
        List<Node> list = new ArrayList<>();
        for (WallCell cell : cells) {
            Label code = new Label(cell.code());
            code.getStyleClass().addAll("row-code", "code-" + cssName(cell.state()));
            Label place = new Label("Nivel " + cell.position().level() + " · col. " + cell.position().column());
            place.getStyleClass().add("cell-muted");
            place.setMinWidth(92);
            Label name = new Label(cell.deceasedName() == null ? "Libre" : cell.deceasedName());
            name.getStyleClass().add("cell-strong");
            Label holder = new Label(cell.clientName() == null ? "" : "Titular: " + cell.clientName());
            holder.getStyleClass().add("cell-muted");
            VBox who = new VBox(1, name, holder);
            HBox.setHgrow(who, Priority.ALWAYS);
            Label end = new Label(cell.leaseEnd() == null ? "" : "Vence " + DATE.format(cell.leaseEnd()));
            end.getStyleClass().add("cell-muted");
            end.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(code, place, who, statePill(cell.state()), end);
            row.getStyleClass().add("vault-row");
            row.setUserData(cell);
            prepareTarget(row, cell);
            list.add(row);
        }
        return list;
    }

    /**
     * Un clic muestra el resumen de la bóveda debajo del muro; doble clic abre el panel de una vez.
     * Al pasar el mouse se adelanta la consulta del detalle para que todo abra al instante.
     */
    private void prepareTarget(Node node, WallCell cell) {
        node.setOnMouseClicked(e -> {
            select(cell);
            if (e.getClickCount() >= 2) {
                openDrawer(cell.id());
            }
        });
        node.setOnMouseEntered(e -> details.prefetch(cell.id()));
    }

    /**
     * Resalta las bóvedas que coinciden con el código, el difunto o el titular buscado.
     * Si en este pabellón no hay ninguna pero sí en otro, se cambia a ese pabellón.
     */
    private void applySearch() {
        String text = normalize(searchField.getText());
        if (!text.isEmpty() && overview != null && currentCells().stream().noneMatch(c -> matches(c, text))) {
            Optional<String> other = overview.pavilions().entrySet().stream()
                    .filter(entry -> entry.getValue().stream().anyMatch(c -> matches(c, text)))
                    .map(Map.Entry::getKey)
                    .findFirst();
            if (other.isPresent() && !other.get().equals(pavilionFilter.getValue())) {
                pavilionFilter.setValue(other.get()); // vuelve a llamar a applySearch al dibujar
                return;
            }
        }
        long found = 0;
        List<Node> matching = new ArrayList<>();
        for (Node node : cellNodes()) {
            WallCell cell = (WallCell) node.getUserData();
            boolean match = text.isEmpty() || matches(cell, text);
            found += match ? 1 : 0;
            if (planToggle.isSelected()) {
                node.pseudoClassStateChanged(DIMMED, !match);
            } else if (match) {
                matching.add(node);
            }
        }
        if (!planToggle.isSelected()) {
            // En la lista se pagina solo lo que coincide; otra búsqueda o pabellón vuelve a la página 1.
            String listKey = pavilionFilter.getValue() + "|" + text;
            if (listKey.equals(lastListKey)) {
                listPager.setItems(matching);
            } else {
                listPager.setItemsFromStart(matching);
            }
            lastListKey = listKey;
        }
        if (!text.isEmpty()) {
            selectionLabel.setText(found == 0 ? "Ninguna bóveda coincide con la búsqueda."
                    : count(found, "bóveda") + " coincide" + (found == 1 ? "" : "n") + " con la búsqueda");
        } else {
            updateSelectionLabel();
        }
    }

    /** Celdas del muro o filas de la lista que se están viendo. */
    private List<Node> cellNodes() {
        List<Node> nodes = new ArrayList<>();
        if (planToggle.isSelected()) {
            wallBox.lookupAll(".vault-cell").forEach(nodes::add);
        } else {
            nodes.addAll(listRows);
        }
        return nodes;
    }

    private void select(WallCell cell) {
        selectedVaultId = cell.id();
        markSelected();
        updateSelectionLabel();
        updatePreview();
    }

    // ---------- resumen de la bóveda elegida (barra verde bajo el muro) ----------

    /**
     * Barra verde con el código, quién reposa, el titular y el arriendo de la bóveda elegida.
     * Sale de los datos del muro (sin consultar la base); el teléfono del titular y el año de
     * fallecimiento se completan con el detalle, que casi siempre ya está adelantado por el mouse.
     * Si la bóveda elegida no está en el pabellón que se ve, la barra se oculta.
     */
    private void updatePreview() {
        Optional<WallCell> selected = currentCells().stream()
                .filter(c -> c.id().equals(selectedVaultId)).findFirst();
        if (selected.isEmpty()) {
            show(previewBar, false);
            return;
        }
        WallCell cell = selected.get();
        VaultDetail detail = details.get(cell.id());
        fillPreview(cell, detail);
        show(previewBar, true);
        if (detail == null && cell.state() != VaultState.AVAILABLE) {
            details.load(cell.id(), loaded -> {
                if (cell.id().equals(selectedVaultId)) {
                    fillPreview(cell, loaded);
                }
            }, error -> { }); // sin detalle la barra igual muestra lo del muro
        }
    }

    private void fillPreview(WallCell cell, VaultDetail detail) {
        Label code = new Label(cell.code());
        code.getStyleClass().add("preview-code");
        code.setMinWidth(Region.USE_PREF_SIZE);

        boolean free = cell.state() == VaultState.AVAILABLE;
        String rests = free ? "Nadie · bóveda libre" : orDash(cell.deceasedName());
        if (!free && detail != null && detail.deceasedDeath() != null) {
            rests += " · " + detail.deceasedDeath().getYear();
        }
        String holder = free ? "Sin titular" : orDash(cell.clientName());
        if (!free && detail != null && detail.clientPhone() != null && !detail.clientPhone().isBlank()) {
            holder += " · " + detail.clientPhone();
        }

        Button open = new Button(free ? "Asignar o ver detalle" : "Abrir detalle");
        open.getStyleClass().add("preview-button");
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(e -> openDrawer(cell.id()));
        previewBar.getChildren().setAll(code,
                previewField("REPOSA", rests),
                previewField("TITULAR", holder),
                previewField("ARRIENDO", leaseText(cell)),
                open);
    }

    private static VBox previewField(String caption, String value) {
        Label top = new Label(caption);
        top.getStyleClass().add("preview-caption");
        Label text = new Label(value);
        text.getStyleClass().add("preview-value");
        text.setMinWidth(Region.USE_PREF_SIZE); // el texto completo antes que los puntos suspensivos
        VBox field = new VBox(2, top, text);
        field.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(field, Priority.ALWAYS);
        return field;
    }

    private static String leaseText(WallCell cell) {
        if (cell.state() == VaultState.AVAILABLE) {
            return "Lista para asignar";
        }
        if (cell.leaseEnd() == null) {
            return "Sin fecha de vencimiento";
        }
        return switch (cell.state()) {
            case OVERDUE -> "Venció el " + DATE.format(cell.leaseEnd());
            case EXPIRING -> "Vence el " + DATE.format(cell.leaseEnd());
            default -> "Vigente hasta " + DATE.format(cell.leaseEnd());
        };
    }

    private void markSelected() {
        for (Node node : cellNodes()) {
            WallCell cell = (WallCell) node.getUserData();
            node.pseudoClassStateChanged(SELECTED, cell.id().equals(selectedVaultId));
        }
    }

    private void updateSelectionLabel() {
        Optional<WallCell> selected = currentCells().stream().filter(c -> c.id().equals(selectedVaultId)).findFirst();
        selectionLabel.setText(selected
                .map(c -> c.code() + " seleccionada" + (c.deceasedName() == null ? " · libre" : " · " + c.deceasedName()))
                .orElse("Toque una bóveda para ver quién reposa, el titular y su arriendo."));
    }

    // ---------- columna derecha ----------

    private void renderSide() {
        renderAttention();
        renderExhumations();
    }

    private void renderAttention() {
        attentionBox.getChildren().clear();
        List<Attention> items = overview == null ? List.of() : overview.attention();
        attentionCount.setText(String.valueOf(items.size()));
        attentionCount.setVisible(!items.isEmpty());
        if (items.isEmpty()) {
            attentionPager.setItemsFromStart(List.of());
            attentionBox.getChildren().add(wrapped("Ningún arriendo vencido ni por vencer en los próximos "
                    + VaultState.EXPIRING_DAYS + " días.", "card-subtitle"));
            return;
        }
        attentionPager.setItems(items);
    }

    /** Filas de la página visible de "Requieren atención". */
    private void drawAttention(List<Attention> page) {
        attentionBox.getChildren().clear();
        for (Attention item : page) {
            boolean overdue = item.state() == VaultState.OVERDUE;
            Label code = new Label(item.code());
            code.getStyleClass().addAll("attention-code", overdue ? "code-overdue" : "code-expiring");
            Label name = wrapped(item.holder(), "attention-name");
            String when = item.leaseEnd() == null ? "Arriendo vencido"
                    : (overdue ? "Venció el " : "Vence el ") + DATE.format(item.leaseEnd());
            Label detail = new Label(when);
            detail.getStyleClass().add(overdue ? "attention-detail-overdue" : "attention-detail-expiring");
            VBox text = new VBox(1, name, detail);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button view = new Button("Ver");
            view.getStyleClass().add("text-action");
            view.setMinWidth(Region.USE_PREF_SIZE);
            view.setOnAction(e -> openDrawer(item.vaultId()));
            HBox row = new HBox(code, text, view);
            row.getStyleClass().add("attention-row");
            row.setOnMouseEntered(e -> details.prefetch(item.vaultId()));
            attentionBox.getChildren().add(row);
        }
    }

    private void renderExhumations() {
        exhumationBox.getChildren().clear();
        List<RecentExhumation> items = overview == null ? List.of() : overview.exhumations();
        if (items.isEmpty()) {
            exhumationBox.getChildren().add(wrapped("Aún no hay exhumaciones en este cementerio.", "card-subtitle"));
            return;
        }
        for (RecentExhumation item : items) {
            Label title = wrapped(item.code() + " · " + item.deceasedName() + " · " + DESTINATIONS.get(item.destination()),
                    "exhumation-title");
            Label detail = new Label(DATE.format(item.date()) + " · liberó la bóveda");
            detail.getStyleClass().add("exhumation-detail");
            exhumationBox.getChildren().add(new VBox(2, title, detail));
        }
    }

    // ---------- panel lateral ----------

    /**
     * Abre el panel de inmediato: con el detalle si ya está en la caché, o con un aviso de carga
     * que se reemplaza cuando llega la consulta.
     */
    private void openDrawer(Long vaultId) {
        drawerVaultId = vaultId;
        VaultDetail cached = details.get(vaultId);
        if (cached != null) {
            fillDrawer(cached);
            return;
        }
        showDrawerLoading(vaultId);
        details.load(vaultId, data -> {
            if (vaultId.equals(drawerVaultId)) {
                fillDrawer(data);
            }
        }, failure -> {
            if (vaultId.equals(drawerVaultId)) {
                closeDrawer();
                showError(failure instanceof BusinessException business ? business.getMessage()
                        : "No se pudo cargar la bóveda.");
            }
        });
    }

    /** Mientras llega el detalle se muestra el código de la bóveda y un aviso de carga. */
    private void showDrawerLoading(Long vaultId) {
        String code = overview == null ? "" : overview.pavilions().values().stream().flatMap(List::stream)
                .filter(c -> c.id().equals(vaultId)).map(WallCell::code).findFirst().orElse("");
        DetailShell.Hero hero = new DetailShell.Hero("Bóveda", code).onClose(this::closeDrawer);
        detail.show(vaultId, hero, List.of(DetailShell.empty("Cargando quién reposa, el titular y el arriendo…")),
                List.of(), null);
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerVaultId = null;
        detail.close(null);
    }

    private void fillDrawer(VaultDetail d) {
        LocalDate today = LocalDate.now(AccessLogService.APP_ZONE);
        VaultPosition position = d.position();
        String place = position == null ? d.cemeteryName()
                : d.cemeteryName() + " · nivel " + position.level() + ", columna " + position.column();
        List<WallCell> cells = currentCells();
        DetailShell.Hero hero = new DetailShell.Hero("Bóveda",
                d.code() + (position == null ? "" : " · " + pavilionLabel(position.pavilion())))
                .subtitle(place)
                .leading(DetailShell.badge(d.code(), position == null ? "" : "Nivel " + position.level()))
                .chips(chips(d, today).toArray(Node[]::new))
                .navigation(DetailShell.neighbor(cells, c -> c.id().equals(d.id()), -1, c -> openDrawer(c.id())),
                        DetailShell.neighbor(cells, c -> c.id().equals(d.id()), 1, c -> openDrawer(c.id())))
                .onClose(this::closeDrawer);
        if (position != null && cells.stream().anyMatch(c -> c.id().equals(d.id()))) {
            hero.extra(DetailShell.miniWall(cells.stream().filter(c -> c.position() != null)
                            .map(c -> new int[] {c.position().level(), c.position().column()}).toList(),
                    new int[] {position.level(), position.column()}));
        }
        if (!d.isAvailable() && d.leaseStart() != null && d.leaseEnd() != null) {
            long total = Math.max(1, ChronoUnit.DAYS.between(d.leaseStart(), d.leaseEnd()));
            long passed = Math.max(0, ChronoUnit.DAYS.between(d.leaseStart(), today));
            long years = Math.max(1, Period.between(d.leaseStart(), d.leaseEnd()).getYears());
            hero.extra(DetailShell.progress("Arriendo", duration(d.leaseStart(), today) + " de " + count(years, "año"),
                    passed / (double) total, d.state() == VaultState.OVERDUE ? DetailShell.Tone.ALERT
                            : d.state() == VaultState.EXPIRING ? DetailShell.Tone.GOLD : DetailShell.Tone.NEUTRAL));
        }

        List<Node> sections = new ArrayList<>();
        if (!d.isAvailable()) {
            sections.add(DetailShell.section("Quién reposa", DetailShell.card(deceasedRow(d))));
            sections.add(DetailShell.section("Familia vinculada · titular del arriendo", DetailShell.card(holderRow(d))));
            sections.add(DetailShell.section("Arriendo", leaseBox(d)));
            sections.add(DetailShell.section("Avisos de arriendo", noticesBox(d.notices())));
        }
        sections.add(DetailShell.section("Historial de la bóveda", timeline(d.history())));
        String hint = null;
        List<Button> buttons;
        if (d.isAvailable()) {
            sections.add(DetailShell.nextStep("Bóveda libre: se puede asignar a un titular y un difunto.",
                    DetailShell.Tone.NEUTRAL));
            buttons = List.of(DetailShell.primary("Asignar esta bóveda", () -> openAssignDialog(d.id())));
        } else {
            if (!d.canExhume() && d.exhumationAllowedFrom() != null) {
                sections.add(DetailShell.nextStep("La exhumación procede desde el "
                        + DATE.format(d.exhumationAllowedFrom()) + ", al cumplir 4 años de arriendo.",
                        DetailShell.Tone.NEUTRAL));
                hint = "Registrar exhumación: disponible desde el " + DATE.format(d.exhumationAllowedFrom());
            } else if (d.state() == VaultState.OVERDUE) {
                sections.add(DetailShell.nextStep("El arriendo está vencido: renuévelo o registre la exhumación.",
                        DetailShell.Tone.ALERT));
            }
            Button exhume = DetailShell.secondary("Registrar exhumación", () -> openExhumationDialog(d));
            exhume.setDisable(!d.canExhume());
            buttons = List.of(exhume, DetailShell.primary("Renovar arriendo", () -> onRenew(d)));
        }
        detail.show(d.id(), hero, sections, buttons, hint);
    }

    private List<Label> chips(VaultDetail d, LocalDate today) {
        List<Label> chips = new ArrayList<>();
        chips.add(DetailShell.chip("● " + d.state().getLabel(), switch (d.state()) {
            case AVAILABLE, OCCUPIED -> DetailShell.Tone.OK;
            case EXPIRING -> DetailShell.Tone.WARN;
            case OVERDUE -> DetailShell.Tone.ALERT;
        }));
        if (d.leaseEnd() != null) {
            long days = ChronoUnit.DAYS.between(today, d.leaseEnd());
            switch (d.state()) {
                case OVERDUE -> {
                    chips.add(DetailShell.chip("Arriendo vencido", DetailShell.Tone.ALERT));
                    chips.add(DetailShell.chip("Hace " + duration(d.leaseEnd(), today), DetailShell.Tone.NEUTRAL));
                }
                case EXPIRING -> {
                    chips.add(DetailShell.chip("Por vencer", DetailShell.Tone.WARN));
                    chips.add(DetailShell.chip(days == 0 ? "Vence hoy" : "Faltan " + count(days, "día"), DetailShell.Tone.NEUTRAL));
                }
                default -> {
                    chips.add(DetailShell.chip("Arriendo al día", DetailShell.Tone.OK));
                    chips.add(DetailShell.chip(duration(today, d.leaseEnd()) + " restantes", DetailShell.Tone.NEUTRAL));
                }
            }
        }
        return chips;
    }

    private HBox deceasedRow(VaultDetail d) {
        String buried = d.leaseStart() == null ? "" : "inhumado el " + DATE.format(d.leaseStart());
        String years = lifeYears(d.deceasedBirth(), d.deceasedDeath());
        String line = years.isEmpty() ? buried : buried.isEmpty() ? years : years + " · " + buried;
        return DetailShell.item(DetailShell.icon("M10 3v18M5 8h10"), orDash(d.deceasedName()), line, null);
    }

    /** "1948 – 2026"; sin fecha de nacimiento solo el año de la defunción, sin "?" (como en Difuntos). */
    private static String lifeYears(LocalDate birth, LocalDate death) {
        if (birth != null && death != null) {
            return birth.getYear() + " – " + death.getYear();
        }
        if (death != null) {
            return "Falleció en " + death.getYear();
        }
        return birth == null ? "" : "Nació en " + birth.getYear();
    }

    private HBox holderRow(VaultDetail d) {
        if (d.clientName() == null) {
            return DetailShell.item(null, "La bóveda no tiene titular registrado.", null, null);
        }
        List<String> contact = new ArrayList<>();
        if (d.clientDocument() != null) {
            contact.add(d.clientDocument());
        }
        if (d.clientPhone() != null && !d.clientPhone().isBlank()) {
            contact.add(d.clientPhone());
        }
        if (d.clientEmail() != null && !d.clientEmail().isBlank()) {
            contact.add(d.clientEmail());
        }
        return DetailShell.person(DetailShell.initials(d.clientName()), d.clientName(), String.join(" · ", contact), null);
    }

    private VBox leaseBox(VaultDetail d) {
        return DetailShell.card(DetailShell.rows(
                "Inicio", d.leaseStart() == null ? "—" : DATE.format(d.leaseStart()),
                "Vence", d.leaseEnd() == null ? "—" : DetailShell.value(DATE.format(d.leaseEnd()),
                        d.state() == VaultState.OVERDUE ? DetailShell.Tone.ALERT
                                : d.state() == VaultState.EXPIRING ? DetailShell.Tone.WARN : null),
                "Contrato", d.contractNumber() == null ? "Sin contrato de servicio"
                        : d.contractNumber() + " · incluido en el servicio"));
    }

    private VBox noticesBox(List<Notice> notices) {
        if (notices.isEmpty()) {
            return DetailShell.empty("Aún no se han enviado avisos de vencimiento ni de mora.");
        }
        List<Node> rows = new ArrayList<>();
        for (Notice notice : notices) {
            String type = notice.type() == NotificationType.OVERDUE_NOTICE ? "Aviso de mora" : "Recordatorio";
            String channel = notice.channel() == NotificationChannel.TELEGRAM ? "Telegram" : "correo";
            boolean failed = notice.status() == NotificationStatus.FAILED;
            String status = notice.status() == NotificationStatus.SENT ? "Enviado" : failed ? "Fallido" : "Pendiente";
            rows.add(DetailShell.item(null, type + " por " + channel, DATE.format(notice.scheduledDate()),
                    DetailShell.chip(status, failed ? DetailShell.Tone.ALERT
                            : notice.status() == NotificationStatus.SENT ? DetailShell.Tone.OK : DetailShell.Tone.NEUTRAL)));
        }
        return DetailShell.list(rows.toArray(Node[]::new));
    }

    private Node timeline(List<HistoryEvent> events) {
        if (events.isEmpty()) {
            return DetailShell.empty("Sin inhumaciones ni exhumaciones registradas.");
        }
        return DetailShell.timeline(events.stream().map(e -> new DetailShell.Event(e.text(),
                e.date() == null ? "" : DATE.format(e.date()),
                e.current() ? DetailShell.Tone.OK : DetailShell.Tone.NEUTRAL)).toList());
    }

    // ---------- acciones ----------

    @FXML
    private void onAssign() {
        openAssignDialog(selectedAvailableVault().orElse(null));
    }

    private Optional<Long> selectedAvailableVault() {
        return currentCells().stream()
                .filter(c -> c.id().equals(selectedVaultId) && c.state() == VaultState.AVAILABLE)
                .map(WallCell::id)
                .findFirst();
    }

    /** Bóveda libre, titular, difunto sin bóveda e inicio; el vencimiento se calcula a 4 años. */
    private void openAssignDialog(Long vaultId) {
        openAssignDialog(vaultId, null);
    }

    /** forDeceased no es null cuando se llega desde Difuntos: el difunto ya viene buscado. */
    private void openAssignDialog(Long vaultId, DeceasedController.DeceasedLink forDeceased) {
        List<WallCell> available = overview == null ? List.of() : overview.pavilions().values().stream()
                .flatMap(List::stream)
                .filter(c -> c.state() == VaultState.AVAILABLE)
                .toList();
        if (available.isEmpty()) {
            showError("No hay bóvedas disponibles en " + cemeteryName + ".");
            return;
        }

        ComboBox<WallCell> vaultBox = new ComboBox<>();
        vaultBox.getStyleClass().add("form-combo");
        vaultBox.getItems().setAll(available);
        vaultBox.setConverter(converter(c -> c.code() + " · nivel " + c.position().level()
                + ", col. " + c.position().column(), ""));
        // Si se abrió desde una bóveda del muro queda esa; si no, el usuario la elige.
        FormShell.choose(vaultBox);
        vaultBox.setValue(available.stream().filter(c -> c.id().equals(vaultId)).findFirst().orElse(null));

        DatePicker start = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        TextField end = new TextField();
        end.setDisable(true); // se calcula: no se escribe
        Runnable updateEnd = () -> end.setText(start.getValue() == null ? ""
                : DATE.format(CemeteryService.leaseEnd(start.getValue())));
        start.valueProperty().addListener((obs, old, value) -> updateEnd.run());
        updateEnd.run();

        TextField clientSearch = searchInput("Nombre, razón social o documento");
        ComboBox<Option> clientBox = optionCombo("Seleccione el titular");
        TextField deceasedSearch = searchInput("Nombre o documento del difunto");
        ComboBox<Option> deceasedBox = optionCombo("Seleccione el difunto");
        Label deceasedHint = note("");
        bindSearch(clientSearch, clientBox, cemeteryService::searchClients, null);
        bindSearch(deceasedSearch, deceasedBox, cemeteryService::searchDeceasedWithoutVault, deceasedHint);
        if (forDeceased != null) {
            // Con un solo resultado la búsqueda lo deja elegido
            deceasedSearch.setText(forDeceased.search());
        }

        Form form = new Form()
                .section("Bóveda y arriendo", 3)
                .field("BÓVEDA *", vaultBox)
                .field("INICIO DEL ARRIENDO *", start)
                .field("VENCE", end)
                .section("Titular del arriendo · familia vinculada", 2)
                .field("BUSCAR TITULAR", clientSearch)
                .field("TITULAR *", clientBox)
                .section("Difunto que se inhuma", 2)
                .field("BUSCAR DIFUNTO", deceasedSearch)
                .field("DIFUNTO *", deceasedBox)
                .note(deceasedHint)
                .note(note("El arriendo dura " + CemeteryService.LEASE_YEARS + " años desde el inicio. "
                        + "Solo se listan difuntos que no están en otra bóveda, ni exhumados ni cremados."));

        Dialog<ButtonType> dialog = formDialog("Asignar bóveda",
                "Los campos con * son obligatorios.", form, 720);
        FormShell.page(dialog, "Cementerio y bóvedas");
        FormShell.visual(dialog, pickWall(vaultBox), true, 480);
        runForm(dialog, form, "Asignar bóveda", () -> {
            WallCell vault = vaultBox.getValue();
            return cemeteryService.assign(new AssignVaultData(vault == null ? null : vault.id(),
                    idOf(clientBox.getValue()), idOf(deceasedBox.getValue()), start.getValue()));
        }, assignedId -> {
            selectedVaultId = assignedId;
            WallCell vault = vaultBox.getValue();
            showToast("Bóveda " + (vault == null ? "" : vault.code()) + " asignada.");
            loadScreen(assignedId);
        });
    }

    /**
     * Muro del pabellón de la bóveda elegida: se toca una bóveda libre para elegirla. Las ocupadas se
     * ven en salvia y no se pueden tocar; la elegida, en verde oscuro. Elegir otra bóveda en la lista
     * también la marca aquí (y cambia de pabellón si hace falta).
     */
    private Node pickWall(ComboBox<WallCell> vaultBox) {
        Label title = label("", "form-visual-title");
        Label hint = label("Toque una bóveda libre para elegirla", "form-visual-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(title, spacer, hint);
        head.setAlignment(Pos.CENTER_LEFT);
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(8);
        grid.setMinWidth(0);
        hint.setMinWidth(0);
        HBox legend = new HBox(16, legendItem("free", "Libre"), legendItem("taken", "Ocupada"),
                legendItem("chosen", "Elegida"));
        VBox box = new VBox(14, head, grid, legend);
        box.getStyleClass().add("form-visual-body");
        Runnable draw = () -> {
            grid.getChildren().clear();
            WallCell chosen = vaultBox.getValue();
            // Sin bóveda elegida se muestra el pabellón que se está viendo (o el primero con bóvedas libres).
            String viewed = pavilionFilter.getValue();
            String pavilion = chosen != null ? chosen.position().pavilion()
                    : vaultBox.getItems().stream().anyMatch(c -> c.position().pavilion().equals(viewed)) ? viewed
                    : vaultBox.getItems().isEmpty() ? null : vaultBox.getItems().get(0).position().pavilion();
            if (pavilion == null || overview == null) {
                title.setText("Muro del pabellón");
                return;
            }
            String label = pavilionLabel(pavilion);
            title.setText("Muro del " + label.substring(0, 1).toLowerCase(Locale.ROOT) + label.substring(1));
            List<WallCell> cells = overview.pavilions().getOrDefault(pavilion, List.of());
            int levels = cells.stream().mapToInt(c -> c.position().level()).max().orElse(1);
            shrinkColumns(grid, cells.stream().mapToInt(c -> c.position().column()).max().orElse(1), 26);
            for (int level = levels; level >= 1; level--) {
                grid.add(label("Nivel " + level, "pick-level"), 0, levels - level);
            }
            for (WallCell cell : cells) {
                boolean free = cell.state() == VaultState.AVAILABLE;
                // En la celda va solo el número de la columna; el código completo sale al pasar el ratón
                Label tile = new Label(String.format("%02d", cell.position().column()));
                tile.setTooltip(new javafx.scene.control.Tooltip(cell.code()));
                tile.setMaxWidth(Double.MAX_VALUE);
                boolean isChosen = chosen != null && cell.id().equals(chosen.id());
                tile.getStyleClass().addAll("pick-cell", isChosen ? "chosen" : free ? "free" : "taken");
                if (free) {
                    tile.setOnMouseClicked(e -> vaultBox.getItems().stream()
                            .filter(c -> c.id().equals(cell.id())).findFirst().ifPresent(vaultBox::setValue));
                }
                grid.add(tile, cell.position().column(), levels - cell.position().level());
            }
        };
        vaultBox.valueProperty().addListener((obs, old, value) -> draw.run());
        draw.run();
        return box;
    }

    private static Label legendItem(String state, String text) {
        Region swatch = new Region();
        swatch.getStyleClass().addAll("pick-swatch", state);
        Label item = new Label(text, swatch);
        item.getStyleClass().add("form-legend-item");
        return item;
    }

    // ---------- Registrar cementerios y bóvedas (Administrador Maestro) ----------

    @FXML
    private void onAddCemetery() {
        TextField name = new TextField();
        name.setPromptText("Ej.: Jardines del Ecce Homo");
        TextField address = new TextField();
        address.setPromptText("Ej.: Cl. 21 # 4-60, Valledupar");
        Form form = new Form()
                .section("Datos del cementerio", 1)
                .field("NOMBRE *", name)
                .field("DIRECCIÓN", address)
                .note(note("Después de guardarlo, agregue sus pabellones con \"+ Agregar bóvedas\"."));
        Dialog<ButtonType> dialog = formDialog("Nuevo cementerio", "Los campos con * son obligatorios.", form, 620);
        FormShell.panel(dialog, "Cementerio y bóvedas");
        runForm(dialog, form, "Registrar cementerio",
                () -> cemeteryService.registerCemetery(name.getText(), address.getText()),
                newId -> {
                    cemeteryId = newId;
                    selectedVaultId = null;
                    showToast("Cementerio " + name.getText().trim() + " registrado. Ahora agregue sus bóvedas.");
                    loadScreen(null);
                });
    }

    @FXML
    private void onAddVaults() {
        List<Option> cemeteries = cemeteryGroup.getToggles().stream()
                .map(toggle -> (Option) toggle.getUserData())
                .toList();
        if (cemeteries.isEmpty()) {
            showError("Primero registre un cementerio con \"+ Cementerio\".");
            return;
        }
        ComboBox<Option> cemeteryBox = new ComboBox<>();
        cemeteryBox.getStyleClass().add("form-combo");
        cemeteryBox.getItems().setAll(cemeteries);
        cemeteryBox.setConverter(converter(Option::label, ""));
        // Queda el cementerio que se está viendo; si no hay uno abierto, el usuario lo elige.
        FormShell.choose(cemeteryBox);
        cemeteryBox.setValue(cemeteries.stream().filter(c -> c.id().equals(cemeteryId)).findFirst().orElse(null));

        String currentPavilion = pavilionFilter.getValue();
        TextField pavilion = new TextField(currentPavilion == null || VaultPosition.GENERAL.equals(currentPavilion)
                ? "A" : currentPavilion);
        TextField levels = numberInput("5");
        TextField perLevel = numberInput("30");
        TextField firstLevel = numberInput("1");
        TextField singleCode = new TextField();
        singleCode.setPromptText("Ej.: A2-15");

        ToggleGroup mode = new ToggleGroup();
        ToggleButton whole = new ToggleButton("Pabellón completo");
        ToggleButton single = new ToggleButton("Una bóveda");
        for (ToggleButton option : List.of(whole, single)) {
            option.getStyleClass().add("segment");
            option.setToggleGroup(mode);
        }
        whole.setSelected(true);
        mode.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true);
            }
        });
        HBox modeBox = new HBox(whole, single);
        modeBox.getStyleClass().add("segmented");
        modeBox.setMaxWidth(Region.USE_PREF_SIZE);

        Label preview = note("");
        Runnable updatePreview = () -> preview.setText(single.isSelected()
                ? "Se creará la bóveda " + (singleCode.getText().isBlank() ? "que escriba" : singleCode.getText().trim().toUpperCase(Locale.ROOT))
                  + " en estado Disponible."
                : batchPreview(pavilion.getText(), levels.getText(), perLevel.getText(), firstLevel.getText()));
        for (TextField field : List.of(pavilion, levels, perLevel, firstLevel, singleCode)) {
            field.textProperty().addListener((obs, old, text) -> updatePreview.run());
        }
        mode.selectedToggleProperty().addListener((obs, old, toggle) -> updatePreview.run());
        updatePreview.run();

        Form form = new Form()
                .section("Ubicación", 3)
                .field("CEMENTERIO *", cemeteryBox)
                .field("PABELLÓN *", pavilion)
                .section("Cómo agregar", 1)
                .field("", modeBox);
        form.section("Pabellón completo", 3)
                .field("NIVELES *", levels)
                .field("BÓVEDAS POR NIVEL *", perLevel)
                .field("EMPEZAR EN EL NIVEL", firstLevel);
        Node wholeSection = form.lastSection();
        form.section("Una bóveda", 3)
                .field("CÓDIGO *", singleCode);
        Node singleSection = form.lastSection();
        form.note(preview)
                .note(note("El código dice el nivel y la columna: A2-15 es pabellón A, nivel 2, columna 15. "
                        + "No hay límite de bóvedas por nivel. Si un código ya existe en el cementerio, no se crea otra vez."));
        Runnable showMode = () -> {
            show(wholeSection, whole.isSelected());
            show(singleSection, single.isSelected());
        };
        mode.selectedToggleProperty().addListener((obs, old, toggle) -> showMode.run());
        showMode.run();

        Dialog<ButtonType> dialog = formDialog("Agregar bóvedas", "Los campos con * son obligatorios.", form, 720);
        FormShell.page(dialog, "Cementerio y bóvedas");
        FormShell.visual(dialog, wallPreview(cemeteryBox, pavilion, levels, perLevel, firstLevel, singleCode, single),
                false, 480);
        runForm(dialog, form, "Crear bóvedas", () -> {
            Long target = idOf(cemeteryBox.getValue());
            return single.isSelected()
                    ? cemeteryService.addVault(target, singleCode.getText())
                    : cemeteryService.addPavilion(target, pavilion.getText(), number(levels), number(perLevel),
                            firstLevel.getText().isBlank() ? 1 : number(firstLevel));
        }, batch -> {
            Option target = cemeteryBox.getValue();
            cemeteryId = target.id();
            overviews.remove(target.id());
            showToast(batchMessage(batch));
            loadScreen(null);
        });
    }

    /** Columnas que se dibujan como máximo en la vista previa (el pabellón puede tener más). */
    private static final int PREVIEW_COLUMNS = 24;

    /**
     * Vista previa del muro mientras se escriben niveles y bóvedas por nivel: un renglón por nivel,
     * el más alto arriba, con el código de la primera y la última bóveda de cada nivel.
     */
    private Node wallPreview(ComboBox<Option> cemeteryBox, TextField pavilion, TextField levels, TextField perLevel,
                             TextField firstLevel, TextField singleCode, ToggleButton single) {
        Label title = label("", "form-visual-title");
        Label count = label("", "state-chip");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(title, spacer, count);
        head.setAlignment(Pos.CENTER_LEFT);
        Label where = label("", "form-visual-hint");
        GridPane grid = new GridPane();
        grid.setHgap(4);
        grid.setVgap(8);
        grid.setMinWidth(0);
        where.setMinWidth(0);
        Label more = label("", "form-visual-hint");
        VBox box = new VBox(10, head, where, grid, more);
        box.getStyleClass().add("form-visual-body");
        Runnable draw = () -> {
            grid.getChildren().clear();
            String name = pavilion.getText().isBlank() ? "" : pavilion.getText().trim().toUpperCase(Locale.ROOT);
            title.setText("Vista previa" + (name.isEmpty() ? "" : " · Pabellón " + name));
            where.setText("Así quedará el muro en " + (cemeteryBox.getValue() == null ? "el cementerio"
                    : cemeteryBox.getValue().label()) + ".");
            more.setText("");
            if (single.isSelected()) {
                String code = singleCode.getText().isBlank() ? "?" : singleCode.getText().trim().toUpperCase(Locale.ROOT);
                Label tile = label(code, "pick-cell");
                tile.getStyleClass().add("free");
                grid.add(tile, 0, 0);
                count.setText("+ 1 bóveda");
                return;
            }
            int rows = parse(levels.getText());
            int columns = parse(perLevel.getText());
            int first = firstLevel.getText().isBlank() ? 1 : parse(firstLevel.getText());
            if (rows <= 0 || columns <= 0 || first <= 0 || name.isEmpty()) {
                count.setText("");
                return;
            }
            count.setText("+ " + count(rows * columns, "bóveda"));
            int drawn = Math.min(columns, PREVIEW_COLUMNS);
            shrinkColumns(grid, drawn, 10);
            grid.getColumnConstraints().add(new ColumnConstraints());
            for (int row = 0; row < rows; row++) {
                int level = first + rows - 1 - row;
                grid.add(label("Nivel " + level, "pick-level"), 0, row);
                for (int column = 1; column <= drawn; column++) {
                    Label tile = label("", "pick-cell");
                    tile.setMaxWidth(Double.MAX_VALUE);
                    tile.getStyleClass().add("preview");
                    grid.add(tile, column, row);
                }
                // Primer y último código del nivel al final del renglón
                Label range = label(name + level + "-01 … " + name + level + "-" + String.format("%02d", columns),
                        "form-visual-hint");
                range.setMinWidth(Region.USE_PREF_SIZE);
                grid.add(range, drawn + 1, row);
            }
            if (columns > drawn) {
                more.setText("Se muestran las primeras " + drawn + " columnas de " + columns + ".");
            }
        };
        for (TextField field : List.of(pavilion, levels, perLevel, firstLevel, singleCode)) {
            field.textProperty().addListener((obs, old, text) -> draw.run());
        }
        single.selectedProperty().addListener((obs, old, value) -> draw.run());
        cemeteryBox.valueProperty().addListener((obs, old, value) -> draw.run());
        draw.run();
        return box;
    }

    /** La primera columna (nivel) toma su ancho; las de bóvedas se reparten el resto sin bajar de {@code min} px. */
    private static void shrinkColumns(GridPane grid, int columns, double min) {
        grid.getColumnConstraints().clear();
        grid.getColumnConstraints().add(new ColumnConstraints());
        for (int i = 1; i <= columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            column.setMinWidth(min);
            column.setPrefWidth(min);
            grid.getColumnConstraints().add(column);
        }
    }

    private static int parse(String text) {
        try {
            return text.isBlank() ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static TextField numberInput(String value) {
        TextField field = new TextField(value);
        // Solo dígitos: el número se valida otra vez en el servicio.
        field.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                change -> change.getControlNewText().matches("\\d{0,4}") ? change : null));
        return field;
    }

    private static int number(TextField field) {
        return field.getText().isBlank() ? 0 : Integer.parseInt(field.getText());
    }

    /** "Se crearán 150 bóvedas: A1-01 … A5-30." mientras el administrador escribe. */
    private static String batchPreview(String pavilion, String levels, String perLevel, String firstLevel) {
        String letter = pavilion == null ? "" : pavilion.trim().toUpperCase(Locale.ROOT);
        if (!letter.matches("[A-Z]+") || levels.isBlank() || perLevel.isBlank()) {
            return "Escriba el pabellón (letras), los niveles y las bóvedas por nivel.";
        }
        int total = Integer.parseInt(levels) * Integer.parseInt(perLevel);
        int first = firstLevel.isBlank() ? 1 : Integer.parseInt(firstLevel);
        if (total == 0 || first == 0) {
            return "Los niveles, las bóvedas por nivel y el primer nivel van de 1 en adelante.";
        }
        int last = first + Integer.parseInt(levels) - 1;
        return "Se crearán " + count(total, "bóveda") + ", todas Disponibles: "
                + VaultPosition.code(letter, first, 1) + " … " + VaultPosition.code(letter, last, Integer.parseInt(perLevel)) + ".";
    }

    private static String batchMessage(VaultBatch batch) {
        String created = batch.created() == 0 ? "No se creó ninguna bóveda"
                : (batch.created() == 1 ? "Se creó 1 bóveda" : "Se crearon " + batch.created() + " bóvedas");
        if (batch.skipped().isEmpty()) {
            return created + ".";
        }
        List<String> shown = batch.skipped().stream().limit(6).toList();
        return created + ". Ya existían: " + String.join(", ", shown)
                + (batch.skipped().size() > shown.size() ? " y " + (batch.skipped().size() - shown.size()) + " más" : "") + ".";
    }

    /** Confirmación con la fecha nueva; la renovación se guarda en otro hilo. */
    private void onRenew(VaultDetail d) {
        LocalDate base = d.leaseEnd() == null ? LocalDate.now(AccessLogService.APP_ZONE) : d.leaseEnd();
        String text = "El arriendo de la bóveda " + d.code()
                + (d.leaseEnd() == null ? " no tiene fecha de vencimiento." : " vence el " + DATE.format(d.leaseEnd()) + ".")
                + " Se renueva por " + CemeteryService.RENEWAL_YEARS + " año al valor de este año: vencerá el "
                + DATE.format(CemeteryService.renewalEnd(base)) + " y el pago queda como ingreso en Finanzas.";
        if (!com.example.funerariamc.ui.Notice.confirm(window(), MODULE_NAME, "¿Renovar el arriendo de " + d.code() + "?", text,
                "Renovar arriendo")) {
            return;
        }
        detail.setBusy(true);
        async("vault-renew", () -> cemeteryService.renewLease(d.id()), newEnd -> {
            detail.setBusy(false);
            showToast("Arriendo de " + d.code() + " renovado hasta el " + DATE.format(newEnd) + ".");
            loadScreen(d.id());
        }, failure -> {
            detail.setBusy(false);
            showError(failure instanceof BusinessException business ? business.getMessage()
                    : "No se pudo renovar el arriendo. Intente de nuevo.");
        });
    }

    /** Fecha, motivo, destino y autorización; al guardar la bóveda queda libre. */
    private void openExhumationDialog(VaultDetail d) {
        TextField vault = readOnly(d.code() + (d.position() == null ? "" : " · nivel " + d.position().level()));
        TextField who = readOnly(orDash(d.deceasedName()));
        TextField buried = readOnly(d.leaseStart() == null ? "—" : DATE.format(d.leaseStart()));

        DatePicker date = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        ComboBox<ExhumationReason> reason = new ComboBox<>();
        reason.getItems().setAll(ExhumationReason.values());
        reason.setConverter(converter(REASONS::get, ""));
        reason.setPromptText("Seleccione el motivo");
        reason.getStyleClass().add("form-combo");
        ComboBox<ExhumationDestination> destination = new ComboBox<>();
        destination.getItems().setAll(ExhumationDestination.values());
        destination.setConverter(converter(value -> capitalize(DESTINATIONS.get(value)), ""));
        destination.setPromptText("Seleccione el destino");
        destination.getStyleClass().add("form-combo");

        Label warning = alertNote("Un cuerpo no reclamado va a fosa común u osario. "
                + "Confirme el destino antes de guardar.");
        warning.managedProperty().bind(warning.visibleProperty());
        Runnable updateWarning = () -> warning.setVisible(
                CemeteryService.needsUnclaimedWarning(reason.getValue(), destination.getValue()));
        reason.valueProperty().addListener((obs, old, value) -> updateWarning.run());
        destination.valueProperty().addListener((obs, old, value) -> updateWarning.run());
        updateWarning.run();

        Path[] file = new Path[1];
        Label fileName = new Label("Sin archivo · PDF, PNG o JPG de máximo 10 MB");
        fileName.getStyleClass().add("file-name");
        Button attach = new Button("Adjuntar autorización");
        attach.getStyleClass().add("secondary-button");
        attach.setMinWidth(Region.USE_PREF_SIZE);
        attach.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Autorización de la exhumación");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF o imagen", "*.pdf", "*.png", "*.jpg", "*.jpeg"));
            File chosen = chooser.showOpenDialog(attach.getScene().getWindow());
            if (chosen != null) {
                file[0] = chosen.toPath();
                fileName.setText(chosen.getName());
            }
        });
        HBox fileRow = new HBox(12, attach, fileName);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        TextArea notes = new TextArea();
        notes.setPromptText("Observaciones (opcional)");
        notes.setPrefRowCount(3);
        notes.setWrapText(true);

        Form form = new Form()
                .section("Bóveda y difunto", 3)
                .field("BÓVEDA", vault)
                .field("DIFUNTO", who)
                .field("INHUMADO EL", buried)
                .section("Exhumación", 3)
                .field("FECHA *", date)
                .field("MOTIVO *", reason)
                .field("DESTINO DE LOS RESTOS *", destination)
                .note(warning)
                .section("Soporte", 1)
                .field("AUTORIZACIÓN *", fileRow)
                .field("OBSERVACIONES", notes)
                .note(note("Al guardar, la bóveda " + d.code() + " queda libre para un nuevo arriendo. "
                        + "El archivo se guarda en la carpeta de documentos y queda su enlace."));

        Dialog<ButtonType> dialog = formDialog("Registrar exhumación",
                "Los campos con * son obligatorios.", form, 760);
        FormShell.panel(dialog, "Cementerio y bóvedas");
        runForm(dialog, form, "Registrar exhumación",
                () -> warning.isVisible() && !confirmUnclaimedDestination(destination.getValue()),
                () -> cemeteryService.exhume(new ExhumationData(d.id(), date.getValue(), reason.getValue(),
                        destination.getValue(), file[0], notes.getText())),
                saved -> {
                    showToast("Exhumación registrada. La bóveda " + d.code() + " quedó libre.");
                    loadScreen(d.id());
                });
    }

    /** Confirma un destino distinto de fosa común u osario para un cuerpo no reclamado. */
    private boolean confirmUnclaimedDestination(ExhumationDestination destination) {
        return com.example.funerariamc.ui.Notice.confirm(window(), MODULE_NAME, "¿Confirma el destino de los restos?",
                "El motivo es «cuerpo no reclamado» y el destino es " + DESTINATIONS.get(destination)
                        + ". Lo usual es la fosa común o el osario.", "Guardar así");
    }

    // ---------- formularios con el estilo de "Personal · El Edén" ----------

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
            // Su renglón queda reservado: al aparecer el error el diálogo no cambia de alto.
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
            if (input instanceof Region region && !(input instanceof Label)) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            grid.add(box, column, row);
            column++;
            return this;
        }

        /** Leyenda y campos de la última sección, para mostrarla u ocultarla según la opción elegida. */
        Node lastSection() {
            int size = body.getChildren().size();
            List<Node> parts = new ArrayList<>(body.getChildren().subList(size - 2, size));
            body.getChildren().remove(size - 2, size);
            VBox section = new VBox(12);
            section.getChildren().setAll(parts);
            body.getChildren().add(section);
            return section;
        }

        Form note(Node note) {
            body.getChildren().add(note);
            return this;
        }
    }

    /** Diálogo de formulario: título musgo, subtítulo, el formulario y abajo Cancelar y la acción. */
    private Dialog<ButtonType> formDialog(String title, String subtitle, Form form, double width) {
        VBox header = new VBox(2, label(title, "form-title"), wrapped(subtitle, "card-subtitle"));
        header.getStyleClass().add("form-header");
        ScrollPane scroll = new ScrollPane(form.body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");
        // Muestra el formulario completo; solo se desplaza si la pantalla es baja.
        scroll.prefViewportHeightProperty().bind(form.body.heightProperty());
        scroll.setMaxHeight(javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.72);
        // El error va fuera del desplazamiento para que se vea aunque el formulario sea largo.
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

    private <T> void runForm(Dialog<ButtonType> dialog, Form form, String okText, Callable<T> save, Consumer<T> onSaved) {
        runForm(dialog, form, okText, () -> false, save, onSaved);
    }

    /**
     * Muestra el formulario. Guardar corre en otro hilo con el botón en "Guardando…", así la ventana
     * no se congela mientras va y vuelve la BD. Si el servicio rechaza los datos, el mensaje sale
     * abajo y el diálogo sigue abierto; si guarda, el diálogo se cierra y se llama a onSaved.
     * {@code cancelSave} permite pedir una confirmación antes (devuelve true si no se guarda).
     */
    private <T> void runForm(Dialog<ButtonType> dialog, Form form, String okText,
                             java.util.function.BooleanSupplier cancelSave, Callable<T> save, Consumer<T> onSaved) {
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
            if (cancelSave.getAsBoolean()) {
                return;
            }
            form.error.setText("");
            okButton.setText("Guardando…");
            okButton.setDisable(true);
            cancelButton.setDisable(true);
            form.body.setDisable(true);
            async("vault-save", save, result -> {
                saved[0] = result;
                dialog.setResult(ok);
                dialog.close();
            }, failure -> {
                okButton.setText(okText);
                okButton.setDisable(false);
                cancelButton.setDisable(false);
                form.body.setDisable(false);
                form.error.setText(failure instanceof BusinessException business ? business.getMessage()
                        : "No se pudo guardar. Revise la conexión e intente de nuevo.");
            });
        });
        // Mientras guarda no se puede cerrar con la X ni con Escape.
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

    /**
     * Buscador del diálogo: espera a que el usuario deje de escribir y consulta en otro hilo.
     * Si llega una respuesta vieja (el texto ya cambió) se descarta. Si queda un solo resultado, se elige.
     */
    private void bindSearch(TextField field, ComboBox<Option> box, Function<String, List<Option>> search, Label emptyHint) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        int[] requestNumber = new int[1];
        Runnable refresh = () -> {
            String text = field.getText();
            int request = ++requestNumber[0];
            box.setPromptText("Buscando…");
            async("vault-dialog-search", () -> search.apply(text), options -> {
                if (request != requestNumber[0]) {
                    return;
                }
                box.setPromptText(options.isEmpty() ? "Sin resultados" : "Seleccione de la lista");
                box.getItems().setAll(options);
                // Se elige sola solo si la búsqueda deja un único resultado; si no, la elige el usuario.
                box.setValue(options.size() == 1 ? options.get(0) : null);
                if (emptyHint != null) {
                    emptyHint.setText(options.isEmpty() && (text == null || text.isBlank())
                            ? "No hay difuntos sin bóveda. Regístrelos primero en Difuntos." : "");
                }
            }, failure -> {
                if (request == requestNumber[0]) {
                    box.setPromptText("No se pudo buscar");
                }
            });
        };
        delay.setOnFinished(e -> refresh.run());
        field.textProperty().addListener((obs, old, text) -> delay.playFromStart());
        refresh.run();
    }

    /** Recuadro salvia de aviso con su ícono; se oculta mientras no tenga texto. */
    private static Label note(String text) {
        Label note = wrapped(text, "note-info");
        note.setGraphic(infoIcon());
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        note.managedProperty().bind(note.textProperty().isNotEmpty());
        note.visibleProperty().bind(note.managedProperty());
        return note;
    }

    /** Aviso en terracota, también con ícono. */
    private static Label alertNote(String text) {
        Label note = wrapped(text, "note-alert");
        note.setGraphic(infoIcon());
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        return note;
    }

    /** Círculo con una "i", como el aviso de la vista de Personal. */
    private static Node infoIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
        icon.getStyleClass().add("note-icon");
        return icon;
    }

    private static TextField readOnly(String value) {
        TextField field = new TextField(value);
        field.setDisable(true);
        return field;
    }

    // ---------- piezas ----------

    private void buildLegend() {
        for (VaultState state : VaultState.values()) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("legend-swatch", "vault-" + cssName(state));
            Label text = new Label(state.getLabel());
            text.getStyleClass().add("legend-text");
            HBox item = new HBox(swatch, text);
            item.getStyleClass().add("legend-item");
            legend.getChildren().add(item);
        }
    }

    private void showEmpty(String text) {
        emptyLabel.setText(text);
        show(emptyLabel, true);
        show(wallScroll, false);
        show(listBox, false);
        wallBox.getChildren().clear();
        listBox.getChildren().clear();
        listRows = List.of();
        listPager.setItemsFromStart(List.of());
        selectionLabel.setText("");
        countLabel.setText("");
    }

    /** Aviso corto abajo de la pantalla que se desvanece solo (no bloquea como una alerta). */
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

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static Label statePill(VaultState state) {
        String style = switch (state) {
            case AVAILABLE -> "pill-neutral";
            case OCCUPIED -> "status-active";
            case EXPIRING -> "pill-warning";
            case OVERDUE -> "pill-alert";
        };
        return pill("●  " + state.getLabel(), style);
    }

    private static Label pill(String text, String styleClass) {
        Label pill = new Label(text);
        pill.getStyleClass().addAll("pill", styleClass);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        return pill;
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

    private static TextField searchInput(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        return field;
    }

    private static ComboBox<Option> optionCombo(String prompt) {
        ComboBox<Option> box = new ComboBox<>();
        box.setPromptText(prompt);
        box.getStyleClass().add("form-combo");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(converter(o -> o.detail() == null ? o.label() : o.label() + " · " + o.detail(), ""));
        return box;
    }

    /** Las ventanas cargan app.css, personal.css (encabezado y botones) y cemetery.css (formulario El Edén). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                VaultController.class.getResource("/css/app.css").toExternalForm(),
                VaultController.class.getResource("/css/personal.css").toExternalForm(),
                VaultController.class.getResource("/css/cemetery.css").toExternalForm());
        pane.getStyleClass().add("personal-dialog");
        // Cancelar a la izquierda y la acción a la derecha también en Windows, como en Personal.
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showError(String message) {
        com.example.funerariamc.ui.Notice.warning(window(), MODULE_NAME, "Atención", message);
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static ListCell<String> pavilionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : pavilionLabel(item));
            }
        };
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

    // ---------- utilidades ----------

    /** "A" se muestra "Pabellón A"; el pabellón General se deja tal cual. */
    static String pavilionLabel(String pavilion) {
        return VaultPosition.GENERAL.equals(pavilion) ? "Pabellón general" : "Pabellón " + pavilion;
    }

    private static boolean matches(WallCell cell, String text) {
        return normalize(cell.code()).contains(text)
                || normalize(cell.deceasedName()).contains(text)
                || normalize(cell.clientName()).contains(text);
    }

    /** Minúsculas y sin tildes, para que "duran" encuentre "Durán". */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String cssName(VaultState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    /** "20 días", "8 meses", "3 años y 2 meses" entre dos fechas. */
    static String duration(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from)) {
            return "0 días";
        }
        Period period = Period.between(from, to);
        if (period.getYears() == 0 && period.getMonths() == 0) {
            return count(ChronoUnit.DAYS.between(from, to), "día");
        }
        if (period.getYears() == 0) {
            return count(period.getMonths(), "mes", "meses");
        }
        return count(period.getYears(), "año") + (period.getMonths() == 0 ? "" : " y " + count(period.getMonths(), "mes", "meses"));
    }

    private static String count(long amount, String word) {
        return count(amount, word, word + "s");
    }

    private static String count(long amount, String singular, String plural) {
        return amount + " " + (amount == 1 ? singular : plural);
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Long idOf(Option option) {
        return option == null ? null : option.id();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
