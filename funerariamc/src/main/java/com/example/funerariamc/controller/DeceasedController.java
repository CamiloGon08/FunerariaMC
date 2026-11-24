package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.utils.enums.DeathType;
import com.example.funerariamc.utils.enums.DocumentType;
import com.example.funerariamc.utils.enums.ExternalCause;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.DeathCertificateService;
import com.example.funerariamc.service.DeathDocumentData;
import com.example.funerariamc.service.DeceasedData;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.service.DeceasedService;
import com.example.funerariamc.service.DestinationService;
import com.example.funerariamc.service.DestinationService.Availability;
import com.example.funerariamc.service.DeceasedService.DeceasedDetail;
import com.example.funerariamc.service.DeceasedService.DeceasedItem;
import com.example.funerariamc.service.DeceasedService.Departure;
import com.example.funerariamc.service.DeceasedService.DocumentInfo;
import com.example.funerariamc.service.DeceasedService.Filter;
import com.example.funerariamc.service.DeceasedService.Kpis;
import com.example.funerariamc.service.DeceasedService.Option;
import com.example.funerariamc.service.DeceasedService.Overview;
import com.example.funerariamc.service.DeceasedService.PendingDocument;
import com.example.funerariamc.service.DeceasedService.PlanCoverage;
import com.example.funerariamc.service.DeceasedService.PlanSuggestion;
import com.example.funerariamc.service.DeceasedService.Segment;
import com.example.funerariamc.service.DeceasedService.Stage;
import com.example.funerariamc.service.DeceasedService.TimelineStep;
import com.example.funerariamc.service.FileStorageService;
import com.example.funerariamc.ui.Pager;
import com.example.funerariamc.ui.DetailShell;
import com.example.funerariamc.ui.ModuleLink;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Pantalla Difuntos: indicadores, tarjetas de los servicios en curso con
 * su etapa, registro de difuntos con la barra visible (sin paginación), documentos pendientes,
 * salidas de hoy y el expediente en el panel lateral. Desde aquí se registra un difunto con su
 * certificado, se sube un documento y se vincula el plan.
 */
@Component
@Scope("prototype")
public class DeceasedController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Difuntos";

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM · HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", ES);
    /** Tarjetas de "En servicio ahora" visibles a la vez; el resto se ve en el registro. */
    private static final int MAX_SERVICE_CARDS = 4;
    private static final String[] STEPS = {"Ingreso", "Prep.", "Velación", "Destino"};

    private static final Map<ExternalCause, String> CAUSES = Map.of(
            ExternalCause.ACCIDENT, "Accidente",
            ExternalCause.HOMICIDE, "Homicidio",
            ExternalCause.SUICIDE, "Suicidio",
            ExternalCause.UNDETERMINED, "Indeterminada");

    private final DeceasedService deceasedService;
    private final DeathCertificateService certificateService;
    private final DestinationService destinationService;
    /** Expedientes ya consultados o adelantados; se vacía en cada recarga. */
    private final DetailCache<DeceasedDetail> details;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(250));

    @FXML
    private StackPane root;
    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup segmentGroup;
    @FXML
    private ToggleButton allToggle;
    @FXML
    private ToggleButton serviceToggle;
    @FXML
    private ToggleButton planToggle;
    @FXML
    private ToggleButton noPlanToggle;
    @FXML
    private ComboBox<DeathType> deathTypeFilter;
    @FXML
    private ComboBox<YearMonth> monthFilter;
    @FXML
    private ScrollPane pageScroll;
    @FXML
    private Label monthCaption;
    @FXML
    private Label monthValue;
    @FXML
    private Label monthDetail;
    @FXML
    private Label serviceValue;
    @FXML
    private Label serviceDetail;
    @FXML
    private Label pendingValue;
    @FXML
    private Label pendingDetail;
    @FXML
    private Label planValue;
    @FXML
    private ProgressBar planBar;
    @FXML
    private Label planDetail;
    @FXML
    private HBox legend;
    @FXML
    private HBox serviceCards;
    @FXML
    private Label serviceEmpty;
    @FXML
    private Label countLabel;
    @FXML
    private VBox registryBox;
    @FXML
    private Label emptyLabel;
    @FXML
    private Label pendingCount;
    @FXML
    private VBox pendingBox;
    @FXML
    private VBox departureBox;
    @FXML
    private Label waitingCount;
    @FXML
    private VBox waitingBox;
    @FXML
    private VBox availabilityBox;
    @FXML
    private VBox drawer;
    @FXML
    private Region veil;

    private Overview overview;
    /** Difunto del panel abierto; null si está cerrado. */
    private Long drawerDeceasedId;
    /** Difuntos que se ven en el registro, en su orden: las flechas del expediente pasan por ellos. */
    private List<DeceasedItem> shownItems = List.of();
    private Pager<DeceasedItem> registryPager;
    /** Filtros con que se armó la página actual (para saber si hay que volver a la página 1). */
    private String lastFilter;
    private DetailShell detail;

    public DeceasedController(DeceasedService deceasedService, DeathCertificateService certificateService,
                              DestinationService destinationService) {
        this.deceasedService = deceasedService;
        this.certificateService = certificateService;
        this.destinationService = destinationService;
        this.details = new DetailCache<>(deceasedService::detail, "deceased-detail");
    }

    /** Al volver al módulo se recargan los datos sin limpiar la pantalla; el expediente abierto sigue abierto. */
    @Override
    public void refresh() {
        loadOverview(drawerDeceasedId);
    }

    @FXML
    private void initialize() {
        // Paginación como la bitácora: 20 filas por página, debajo de la tabla del registro.
        registryPager = Pager.<DeceasedItem>local(Pager.TABLE_PAGE, "difunto", "difuntos",
                page -> registryBox.getChildren().setAll(page.stream().map(this::registryRow).toList()))
                .below(registryBox);
        allToggle.setUserData(Segment.ALL);
        serviceToggle.setUserData(Segment.IN_SERVICE);
        planToggle.setUserData(Segment.WITH_PLAN);
        noPlanToggle.setUserData(Segment.WITHOUT_PLAN);
        segmentGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un segmento elegido
            } else {
                renderRegistry();
            }
        });

        deathTypeFilter.getItems().setAll(null, DeathType.NATURAL, DeathType.NON_NATURAL);
        deathTypeFilter.setConverter(converter(t -> t.getDisplayName(), "Muerte: todas"));
        deathTypeFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(DeathType item, boolean empty) {
                super.updateItem(item, empty);
                setText(item == null ? "Muerte: todas" : "Muerte: " + item.getDisplayName().toLowerCase());
            }
        });
        deathTypeFilter.valueProperty().addListener((obs, old, value) -> renderRegistry());

        monthFilter.setConverter(converter(m -> capitalize(MONTH.format(m)), "Todos los meses"));
        monthFilter.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(YearMonth item, boolean empty) {
                super.updateItem(item, empty);
                setText(item == null ? "Todos los meses" : capitalize(MONTH.format(item)));
            }
        });
        monthFilter.valueProperty().addListener((obs, old, value) -> renderRegistry());

        searchDelay.setOnFinished(e -> renderRegistry());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        buildLegend();
        detail = DetailShell.install(drawer, veil, DetailShell.Kind.MODAL);
        loadOverview(null);
    }

    private void loadOverview(Long reopenId) {
        details.clear(); // después de guardar o al volver, nada de lo guardado puede quedar viejo
        if (overview == null) {
            countLabel.setText("Cargando difuntos…");
        }
        run(deceasedService::overview, result -> {
            overview = result;
            fillMonths(result.items());
            renderKpis(result.kpis());
            renderServiceCards();
            renderRegistry();
            renderPending(result.pendingDocuments());
            renderDepartures(result.departures());
            renderWaiting(result.items(), false);
            if (reopenId != null) {
                openDrawer(reopenId);
            }
            // Los expedientes de "En servicio ahora" son los que más se abren: se adelantan en segundo plano.
            details.prefetchAll(inServiceCards().stream().map(DeceasedItem::id).toList());
        }, "No se pudieron cargar los difuntos.");
        run(destinationService::availability, this::renderAvailability, "No se pudo cargar lo disponible para destino.");
    }

    /** Igual que Cementerio: la consulta corre en otro hilo para que la ventana no se congele. */
    private <T> void run(java.util.concurrent.Callable<T> work, java.util.function.Consumer<T> onDone, String error) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> {
            countLabel.setText("");
            showError(task.getException() instanceof BusinessException business ? business.getMessage() : error);
        });
        Thread thread = new Thread(task, "deceased-loader");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------- indicadores y tarjetas ----------

    private void fillMonths(List<DeceasedItem> items) {
        YearMonth selected = monthFilter.getValue();
        List<YearMonth> months = new ArrayList<>();
        months.add(null);
        items.stream().map(DeceasedItem::deathDate).filter(java.util.Objects::nonNull).map(YearMonth::from)
                .distinct().sorted(java.util.Comparator.reverseOrder()).forEach(months::add);
        monthFilter.getItems().setAll(months);
        monthFilter.setValue(months.contains(selected) ? selected : null);
    }

    private void renderKpis(Kpis k) {
        YearMonth now = YearMonth.now(AccessLogService.APP_ZONE);
        monthCaption.setText("INGRESOS EN " + MONTH.format(now).split(" ")[0].toUpperCase(ES));
        monthValue.setText(String.valueOf(k.thisMonth()));
        long diff = k.thisMonth() - k.lastMonth();
        String last = MONTH.format(now.minusMonths(1)).split(" ")[0];
        monthDetail.setText(diff == 0 ? "Igual que en " + last
                : (diff > 0 ? "▲ " + diff + " más" : "▼ " + (-diff) + " menos") + " que en " + last);
        monthDetail.getStyleClass().setAll("kpi-detail", diff > 0 ? "kpi-up" : "kpi-detail");

        serviceValue.setText(String.valueOf(k.inService()));
        List<String> parts = new ArrayList<>();
        k.byStage().forEach((stage, count) -> parts.add(count + " " + stage.getLabel().toLowerCase()));
        serviceDetail.setText(parts.isEmpty() ? "Ningún servicio en curso" : String.join(" · ", parts));

        pendingValue.setText(String.valueOf(k.pendingDocuments()));
        pendingDetail.setText(k.blockingCremation() > 0
                ? count(k.blockingCremation(), "bloquea una cremación", "bloquean una cremación")
                : k.pendingDocuments() == 0 ? "Todos los expedientes completos" : "difuntos con documentos por subir");
        pendingDetail.getStyleClass().setAll("kpi-detail", k.blockingCremation() > 0 ? "kpi-alert" : "kpi-detail");

        planValue.setText(k.planPercent() + " %");
        planBar.setProgress(k.total() == 0 ? 0 : (double) k.withPlan() / k.total());
        planDetail.setText(k.withPlan() + " de " + count(k.total(), "difunto"));

        allToggle.setText("Todos · " + k.total());
        serviceToggle.setText("En servicio · " + k.inService());
    }

    private List<DeceasedItem> inServiceCards() {
        return overview.items().stream().filter(DeceasedItem::inService)
                .sorted(java.util.Comparator.comparing((DeceasedItem i) -> i.stage().getStep()).reversed())
                .limit(MAX_SERVICE_CARDS)
                .toList();
    }

    private void renderServiceCards() {
        List<DeceasedItem> inService = inServiceCards();
        serviceCards.getChildren().setAll(inService.stream().map(this::serviceCard).toList());
        // Rellena con espacios para que las tarjetas no se estiren cuando hay pocas.
        for (int i = inService.size(); i < MAX_SERVICE_CARDS && !inService.isEmpty(); i++) {
            Pane spacer = new Pane();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            spacer.setPrefWidth(0);
            serviceCards.getChildren().add(spacer);
        }
        boolean empty = inService.isEmpty();
        serviceEmpty.setVisible(empty);
        serviceEmpty.setManaged(empty);
    }

    private VBox serviceCard(DeceasedItem item) {
        boolean missing = !item.missing().isEmpty();
        Label stagePill = pill(item.stage().getLabel(), switch (item.stage()) {
            case PREPARATION, READY -> "pill-warning";
            case TRANSFER -> "pill-dark";
            default -> "status-active";
        });
        Label code = new Label(item.contractNumber());
        code.getStyleClass().add("service-code");
        Pane gap = new Pane();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(stagePill, gap, code);
        top.setAlignment(Pos.CENTER_LEFT);

        Label name = wrapped(item.fullName(), "service-name");
        Label meta = wrapped(meta(item), "service-meta");

        VBox box;
        if (missing) {
            DocumentType first = item.missing().get(0);
            box = new VBox(label("FALTA DOCUMENTO", "service-box-caption"),
                    wrapped(first.getDisplayName() + (item.blocksCremation() ? " · bloquea cremación" : ""),
                            "service-box-text"));
            box.getStyleClass().addAll("service-box", "missing");
        } else {
            box = new VBox(label(boxCaption(item), "service-box-caption"), wrapped(boxText(item), "service-box-text"));
            box.getStyleClass().add("service-box");
        }

        Label destination = new Label("Destino: " + orDash(item.destination()));
        destination.getStyleClass().add("service-meta");
        Pane gap2 = new Pane();
        HBox.setHgrow(gap2, Priority.ALWAYS);
        Label see = new Label("Ver ›");
        see.getStyleClass().add("service-link");
        HBox footer = new HBox(destination, gap2, see);
        footer.getStyleClass().add("service-footer");

        VBox card = new VBox(top, new VBox(2, name, meta), stepper(item.stage()), box, footer);
        card.getStyleClass().add("service-card");
        card.getStyleClass().add(missing ? "stage-missing" : switch (item.stage()) {
            case PREPARATION, READY -> "stage-preparation";
            case TRANSFER -> "stage-transfer";
            default -> "stage-wake";
        });
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setPrefWidth(0);
        // 4 tarjetas de 160 caben en la ventana más pequeña (1000 px) sin empujar la pantalla a la derecha
        card.setMinWidth(160);
        card.setOnMouseEntered(e -> details.prefetch(item.id()));
        card.setOnMouseClicked(e -> openDrawer(item.id()));
        return card;
    }

    /** Barra Ingreso / Preparación / Velación / Destino con el paso actual resaltado. */
    private static VBox stepper(Stage stage) {
        GridPane dots = new GridPane();
        dots.setHgap(0);
        HBox labels = new HBox();
        for (int i = 0; i < STEPS.length; i++) {
            Circle dot = new Circle(5);
            dot.getStyleClass().add("stepper-dot");
            if (i < stage.getStep()) {
                dot.getStyleClass().add("done");
            } else if (i == stage.getStep()) {
                dot.getStyleClass().addAll("current", stage.getStep() == 1 ? "preparation" : "wake");
            }
            HBox cell = new HBox(dot);
            cell.setAlignment(Pos.CENTER_LEFT);
            if (i < STEPS.length - 1) {
                Line line = new Line(0, 0, 1, 0);
                line.getStyleClass().add("stepper-line");
                if (i < stage.getStep()) {
                    line.getStyleClass().add("done");
                }
                StackPane lineBox = new StackPane(line);
                HBox.setHgrow(lineBox, Priority.ALWAYS);
                line.endXProperty().bind(lineBox.widthProperty().subtract(2));
                cell.getChildren().add(lineBox);
                HBox.setHgrow(cell, Priority.ALWAYS);
            }
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setHgrow(i < STEPS.length - 1 ? Priority.ALWAYS : Priority.NEVER);
            dots.getColumnConstraints().add(column);
            dots.add(cell, i, 0);

            Label text = new Label(STEPS[i]);
            text.getStyleClass().add("stepper-label");
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            text.setAlignment(i == 0 ? Pos.CENTER_LEFT : i == STEPS.length - 1 ? Pos.CENTER_RIGHT : Pos.CENTER);
            labels.getChildren().add(text);
        }
        return new VBox(4, dots, labels);
    }

    private static String meta(DeceasedItem item) {
        List<String> parts = new ArrayList<>();
        if (item.age() != null) {
            parts.add(item.age() + " años");
        }
        if (item.deathType() != null) {
            parts.add(item.deathType().getDisplayName());
        }
        parts.add(item.hasPlan() ? orDash(item.planName()) : "Particular");
        return String.join(" · ", parts);
    }

    private static String boxCaption(DeceasedItem item) {
        return switch (item.stage()) {
            case WAKE, READY -> item.wakeRoom() == null ? "VELACIÓN" : "SALA " + item.wakeRoom().toUpperCase(ES);
            case TRANSFER -> "TRASLADO EN RUTA";
            case CREMATION_SCHEDULED -> "CREMACIÓN";
            case AWAITING_DESTINATION -> "VELACIÓN TERMINADA";
            default -> "PREPARACIÓN";
        };
    }

    private static String boxText(DeceasedItem item) {
        return switch (item.stage()) {
            case WAKE -> item.wakeEndAt() == null ? "En curso" : "Hasta " + TIME.format(item.wakeEndAt());
            case READY -> item.wakeStartAt() == null ? "Sala por asignar" : "Desde " + DAY_TIME.format(item.wakeStartAt());
            case TRANSFER, AWAITING_DESTINATION, CREMATION_SCHEDULED -> item.nextTransportAt() == null
                    ? "Sin traslado programado" : "Salida " + DAY_TIME.format(item.nextTransportAt());
            default -> "Tanatopraxia en curso";
        };
    }

    // ---------- registro ----------

    private void renderRegistry() {
        if (overview == null) {
            return;
        }
        Segment segment = segmentGroup.getSelectedToggle() == null ? Segment.ALL
                : (Segment) segmentGroup.getSelectedToggle().getUserData();
        List<DeceasedItem> items = DeceasedService.filter(overview.items(),
                new Filter(segment, deathTypeFilter.getValue(), monthFilter.getValue(), searchField.getText()));
        shownItems = items;
        // Un filtro nuevo vuelve a la página 1; recargar tras guardar se queda en la misma página.
        String filterKey = segment + "|" + deathTypeFilter.getValue() + "|" + monthFilter.getValue() + "|" + searchField.getText();
        if (filterKey.equals(lastFilter)) {
            registryPager.setItems(items);
        } else {
            registryPager.setItemsFromStart(items);
        }
        lastFilter = filterKey;
        countLabel.setText(count(items.size(), "difunto"));
        boolean empty = items.isEmpty();
        emptyLabel.setText(overview.items().isEmpty()
                ? "Todavía no hay difuntos registrados. Use «Registrar difunto»."
                : "Ningún difunto coincide con los filtros.");
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
    }

    private HBox registryRow(DeceasedItem item) {
        String responsible = item.responsible() == null ? "" : " · resp. " + item.responsible();
        String document = item.unidentified() ? "Sin identificar" : "CC " + orDash(item.documentNumber());
        VBox name = new VBox(1, label(item.fullName(), "row-title"), label(document + responsible, "row-detail"));
        name.getStyleClass().add("col-name");
        HBox.setHgrow(name, Priority.ALWAYS);

        Label date = label(item.deathDate() == null ? "—" : DATE.format(item.deathDate()), "row-text");
        date.getStyleClass().add("col-date");

        Label type = item.deathType() == null ? label("—", "row-text")
                : pill(item.deathType().getDisplayName(),
                item.deathType() == DeathType.NON_NATURAL ? "pill-alert" : "status-active");
        HBox typeBox = new HBox(type);
        typeBox.getStyleClass().add("col-type");
        typeBox.setAlignment(Pos.CENTER_LEFT);

        VBox plan = item.hasPlan()
                ? new VBox(1, label(orDash(item.planName()), "row-text"),
                label(item.planCode() + " · " + orDash(item.coverage()).toLowerCase(ES), "row-detail"))
                : new VBox(label("Sin plan", "row-detail"));
        plan.getStyleClass().add("col-plan");
        plan.setAlignment(Pos.CENTER_LEFT);

        Label destination = label(item.destination() == null ? item.stage().getLabel() : item.destination(), "row-text");
        destination.getStyleClass().add("col-destination");

        Label docs = item.missing().isEmpty() ? label("Completos", "docs-ok")
                : label("Falta " + item.missing().size(), "docs-missing");
        docs.getStyleClass().add("col-docs");

        HBox row = new HBox(name, date, typeBox, plan, destination, docs);
        row.getStyleClass().add("registry-row");
        row.setOnMouseEntered(e -> details.prefetch(item.id()));
        row.setOnMouseClicked(e -> openDrawer(item.id()));
        return row;
    }

    // ---------- columna derecha ----------

    private void renderPending(List<PendingDocument> pending) {
        pendingCount.setText(String.valueOf(pending.size()));
        pendingCount.setVisible(!pending.isEmpty());
        pendingBox.getChildren().clear();
        if (pending.isEmpty()) {
            pendingBox.getChildren().add(wrapped("Todos los expedientes tienen sus documentos.", "card-subtitle"));
            return;
        }
        for (PendingDocument p : pending.stream().limit(8).toList()) {
            DeceasedItem item = find(p.deceasedId());
            String code = item == null || item.contractNumber() == null
                    ? (item != null && item.unidentified() ? "NN" : DeceasedService.code(p.deceasedId()))
                    : item.contractNumber();
            Label badge = new Label(code);
            badge.getStyleClass().addAll("attention-code", "doc-code",
                    p.blocksCremation() ? "code-overdue" : "code-expiring");
            String detail = p.type().getDisplayName() + (p.blocksCremation() ? " · bloquea cremación" : "");
            VBox text = new VBox(1, wrapped(p.fullName(), "attention-name"),
                    wrapped(detail, p.blocksCremation() ? "attention-detail-overdue" : "attention-detail-expiring"));
            HBox.setHgrow(text, Priority.ALWAYS);
            Button upload = new Button("Subir");
            upload.getStyleClass().add("text-action");
            upload.setMinWidth(Region.USE_PREF_SIZE);
            upload.setOnAction(e -> openDocumentDialog(p.deceasedId(), p.fullName(), p.type()));
            HBox row = new HBox(badge, text, upload);
            row.getStyleClass().add("attention-row");
            pendingBox.getChildren().add(row);
        }
        if (pending.size() > 8) {
            pendingBox.getChildren().add(wrapped("y " + (pending.size() - 8) + " más en el registro.", "card-subtitle"));
        }
    }

    private void renderDepartures(List<Departure> departures) {
        departureBox.getChildren().clear();
        if (departures.isEmpty()) {
            departureBox.getChildren().add(wrapped("No hay traslados programados para hoy.", "card-subtitle"));
            return;
        }
        for (Departure d : departures) {
            Label time = label(TIME.format(d.scheduledAt()), "departure-time");
            VBox text = new VBox(1, wrapped(d.fullName(), "exhumation-title"),
                    wrapped(d.vehicle() + " → " + d.destination() + " · " + d.status().getDisplayName(),
                            "exhumation-detail"));
            HBox row = new HBox(10, time, text);
            row.setAlignment(Pos.TOP_LEFT);
            row.setOnMouseClicked(e -> openDrawer(d.deceasedId()));
            row.setStyle("-fx-cursor: hand;");
            departureBox.getChildren().add(row);
        }
    }

    /** Cuántos de "Esperan destino" se ven antes de "Ver los N". */
    private static final int WAITING_SHOWN = 3;

    /**
     * Difuntos que ya terminaron la velación y no tienen bóveda ni cremación. Con muerte no natural se
     * avisa que no se pueden cremar hasta que la Fiscalía cierre el caso.
     */
    private void renderWaiting(List<DeceasedItem> items, boolean all) {
        List<DeceasedItem> waiting = items.stream()
                .filter(i -> i.stage() == Stage.AWAITING_DESTINATION)
                .sorted(java.util.Comparator.comparing(DeceasedItem::wakeEndAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        waitingCount.setText(String.valueOf(waiting.size()));
        waitingCount.setVisible(!waiting.isEmpty());
        waitingBox.getChildren().clear();
        if (waiting.isEmpty()) {
            waitingBox.getChildren().add(wrapped("Ningún difunto espera bóveda o cremación.", "card-subtitle"));
            return;
        }
        LocalDateTime now = LocalDateTime.now(AccessLogService.APP_ZONE);
        for (DeceasedItem item : all ? waiting : waiting.stream().limit(WAITING_SHOWN).toList()) {
            Label badge = new Label(item.unidentified() ? "NN" : item.code());
            badge.getStyleClass().addAll("attention-code", "doc-code", "code-waiting");
            boolean nonNatural = item.deathType() == DeathType.NON_NATURAL;
            String detail = nonNatural ? "No natural · no se puede cremar hasta que la Fiscalía cierre el caso"
                    : (item.hasPlan() ? item.planName() : "Particular") + since(item.wakeEndAt(), now);
            VBox text = new VBox(1, wrapped(item.fullName(), "attention-name"),
                    wrapped(detail, nonNatural ? "attention-detail-overdue" : "waiting-detail"));
            text.setOnMouseClicked(e -> openDrawer(item.id()));
            text.setStyle("-fx-cursor: hand;");
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(badge, text);
            row.getStyleClass().add("attention-row");
            assignButton(item, nonNatural).ifPresent(row.getChildren()::add);
            waitingBox.getChildren().add(row);
        }
        if (waiting.size() > WAITING_SHOWN) {
            Button more = new Button(all ? "Ver menos" : "Ver los " + waiting.size() + " ›");
            more.getStyleClass().add("text-action");
            more.setOnAction(e -> renderWaiting(items, !all));
            HBox footer = new HBox(more);
            footer.setAlignment(Pos.CENTER_RIGHT);
            waitingBox.getChildren().add(footer);
        }
    }

    private static String since(LocalDateTime end, LocalDateTime now) {
        if (end == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(end.toLocalDate(), now.toLocalDate());
        return days <= 0 ? " · terminó hoy" : days == 1 ? " · hace 1 día" : " · hace " + days + " días";
    }

    /**
     * "Asignar" abre la asignación en Cementerio y bóvedas o en Cremaciones, según lo que el rol
     * pueda abrir. Sin ninguno de los dos módulos el botón no aparece.
     */
    private java.util.Optional<Button> assignButton(DeceasedItem item, boolean nonNatural) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        if (ModuleLink.canOpen(AppModule.CEMENTERIO)) {
            javafx.scene.control.MenuItem vault = new javafx.scene.control.MenuItem("Asignar bóveda");
            vault.setOnAction(e -> ModuleLink.open(AppModule.CEMENTERIO, link(item)));
            menu.getItems().add(vault);
        }
        if (ModuleLink.canOpen(AppModule.CREMACIONES)) {
            javafx.scene.control.MenuItem cremation = new javafx.scene.control.MenuItem(nonNatural
                    ? "Cremación (espera a la Fiscalía)" : "Agregar a un lote de cremación");
            cremation.setDisable(nonNatural);
            cremation.setOnAction(e -> ModuleLink.open(AppModule.CREMACIONES, link(item)));
            menu.getItems().add(cremation);
        }
        if (menu.getItems().isEmpty()) {
            return java.util.Optional.empty();
        }
        Button assign = new Button("Asignar");
        assign.getStyleClass().add("text-action");
        assign.setMinWidth(Region.USE_PREF_SIZE);
        assign.setOnAction(e -> menu.show(assign, javafx.geometry.Side.LEFT, 0, 0));
        return java.util.Optional.of(assign);
    }

    private static DeceasedLink link(DeceasedItem item) {
        String search = item.documentNumber() == null || item.documentNumber().isBlank()
                ? item.fullName() : item.documentNumber();
        return new DeceasedLink(item.id(), item.fullName(), search);
    }

    /** Lo que se lleva "Asignar" a Cementerio y bóvedas o a Cremaciones: el difunto y con qué buscarlo. */
    public record DeceasedLink(Long deceasedId, String fullName, String search) {
    }

    /** Bóvedas, cremaciones prepagadas y salas libres, cada una con su barra. */
    private void renderAvailability(Availability a) {
        availabilityBox.getChildren().setAll(
                availabilityRow("Bóvedas libres", a.freeVaults() + " de " + a.vaults(),
                        a.vaults() == 0 ? 0 : (double) a.freeVaults() / a.vaults(), false),
                availabilityRow("Cremaciones prepagadas", a.prepaidCremations() == 1 ? "1 queda"
                                : a.prepaidCremations() + " quedan",
                        a.receivedCremations() == 0 ? 0 : (double) a.prepaidCremations() / a.receivedCremations(), true),
                availabilityRow("Salas de velación libres ahora", a.freeRooms() + " de " + a.rooms(),
                        a.rooms() == 0 ? 0 : (double) a.freeRooms() / a.rooms(), false));
    }

    private static VBox availabilityRow(String name, String value, double fraction, boolean gold) {
        Label nameLabel = label(name, "availability-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(nameLabel, spacer, label(value, "availability-value"));
        top.setAlignment(Pos.CENTER_LEFT);
        ProgressBar bar = new ProgressBar(Math.min(1, fraction));
        bar.getStyleClass().add("occupancy-bar");
        if (gold) {
            bar.getStyleClass().add("bar-gold");
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        return new VBox(6, top, bar);
    }

    // ---------- expediente ----------

    /**
     * Abre el expediente al instante: con los datos si ya están guardados, o con el nombre de la fila y
     * "Cargando expediente…" mientras llegan. Si ya muestra ese difunto (recarga después de guardar),
     * conserva lo que se ve hasta que llegue lo nuevo.
     */
    private void openDrawer(Long deceasedId) {
        boolean sameOpen = detail.isOpen() && deceasedId.equals(drawerDeceasedId);
        drawerDeceasedId = deceasedId;
        DeceasedDetail cached = details.get(deceasedId);
        if (cached != null) {
            fillDrawer(cached);
            return;
        }
        if (!sameOpen) {
            fillDrawerLoading(deceasedId);
        }
        details.load(deceasedId, data -> {
            if (deceasedId.equals(drawerDeceasedId)) {
                fillDrawer(data);
            }
        }, error -> {
            if (deceasedId.equals(drawerDeceasedId)) {
                closeDrawer();
                showError(error instanceof BusinessException business ? business.getMessage()
                        : "No se pudo cargar el expediente.");
            }
        });
    }

    private void fillDrawerLoading(Long deceasedId) {
        DeceasedItem item = find(deceasedId);
        DetailShell.Hero hero = new DetailShell.Hero("Expediente" + (item == null ? "" : " · "
                + (item.contractNumber() == null ? item.code() : item.contractNumber())),
                item == null ? "Difunto" : item.fullName())
                .leading(crossBadge())
                .onClose(this::closeDrawer);
        detail.show(deceasedId, hero, List.of(DetailShell.empty("Cargando expediente…")), List.of(), null);
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerDeceasedId = null;
        detail.close(null);
    }

    /** Ícono de la cruz en el recuadro de la banda. */
    private static StackPane crossBadge() {
        SVGPath cross = new SVGPath();
        cross.setContent("M10 2v20M4 8h12");
        cross.getStyleClass().add("detail-badge-icon");
        StackPane badge = new StackPane(cross);
        badge.getStyleClass().add("detail-icon-badge");
        badge.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return badge;
    }

    private void fillDrawer(DeceasedDetail d) {
        DeceasedItem item = d.item();
        List<String> facts = new ArrayList<>();
        facts.add(item.unidentified() ? "Sin identificar" : "CC " + orDash(item.documentNumber()));
        // Sin fecha de nacimiento solo se muestra la de defunción (no un «?»)
        String died = item.deathDate() == null ? "Sin fecha de defunción" : "Falleció el " + DATE.format(item.deathDate());
        facts.add(d.deceased().getBirthDate() == null ? died
                : DATE.format(d.deceased().getBirthDate()) + " – " + orDash(item.deathDate() == null ? null : DATE.format(item.deathDate())));
        if (item.age() != null) {
            facts.add(item.age() + " años");
        }
        PlanCoverage plan = d.plan();
        DetailShell.Hero hero = new DetailShell.Hero("Expediente · "
                + (item.contractNumber() == null ? item.code() : item.contractNumber()), item.fullName())
                .subtitle(String.join(" · ", facts))
                .leading(crossBadge())
                .chips(DetailShell.chip(item.stage().getLabel(), DetailShell.Tone.NEUTRAL))
                .navigation(DetailShell.neighbor(shownItems, x -> x.id().equals(item.id()), -1, x -> openDrawer(x.id())),
                        DetailShell.neighbor(shownItems, x -> x.id().equals(item.id()), 1, x -> openDrawer(x.id())))
                .onClose(this::closeDrawer);
        if (item.deathType() != null) {
            String type = "Muerte " + item.deathType().getDisplayName().toLowerCase(ES)
                    + (item.externalCause() == null ? "" : " · " + CAUSES.get(item.externalCause()).toLowerCase(ES));
            hero.chips(DetailShell.chip(type, item.deathType() == DeathType.NON_NATURAL
                    ? DetailShell.Tone.ALERT : DetailShell.Tone.NEUTRAL));
        }
        hero.chips(plan == null ? DetailShell.chip("Particular", DetailShell.Tone.NEUTRAL)
                : DetailShell.chip(plan.planName() + (plan.covered() ? " · cubierto" : " · en carencia"),
                        plan.covered() ? DetailShell.Tone.OK : DetailShell.Tone.WARN));
        hero.extra(steps(d.timeline()));

        List<Node> left = new ArrayList<>();
        left.add(DetailShell.section("Responsable y plan", responsibleBox(d)));
        if (plan != null && !d.otherBeneficiaries().isEmpty()) {
            FlowPane chips = new FlowPane(6, 6);
            d.otherBeneficiaries().forEach(name -> chips.getChildren().add(DetailShell.chip(name, DetailShell.Tone.NEUTRAL)));
            left.add(DetailShell.section("Otros beneficiarios del plan " + plan.code(), DetailShell.card(chips)));
        }
        List<Node> right = new ArrayList<>(documentsSection(d));
        List<Node> sections = new ArrayList<>();
        if (item.blocksCremation()) {
            sections.add(DetailShell.nextStep("Falta la orden de entrega del cuerpo: la cremación no puede hacerse "
                    + "hasta subirla.", DetailShell.Tone.ALERT));
        }
        sections.add(DetailShell.columns(left, right));

        Button link = DetailShell.secondary(plan == null ? "Vincular plan" : "Cambiar plan", () -> openLinkPlanDialog(d));
        link.setDisable(item.contractNumber() != null || item.unidentified());
        Button upload = DetailShell.primary("Subir documento", () -> openDocumentDialog(item.id(), item.fullName(),
                item.missing().isEmpty() ? null : item.missing().get(0)));
        detail.show(item.id(), hero, sections, List.of(link, upload), null);
    }

    /** Recorrido del servicio en la banda: lo hecho con ✓, el primer paso sin hacer es el actual. */
    private static HBox steps(List<TimelineStep> timeline) {
        List<DetailShell.Step> steps = new ArrayList<>();
        boolean currentSet = false;
        for (TimelineStep step : timeline) {
            DetailShell.StepState state;
            if (step.done()) {
                state = DetailShell.StepState.DONE;
            } else if (!currentSet) {
                state = DetailShell.StepState.CURRENT;
                currentSet = true;
            } else {
                state = DetailShell.StepState.PENDING;
            }
            String when = step.at() == null ? "" : DAY_TIME.format(step.at()) + " · ";
            steps.add(new DetailShell.Step(step.title(), when + orDash(step.detail()), state));
        }
        return DetailShell.steps(steps);
    }

    private VBox responsibleBox(DeceasedDetail d) {
        String responsible = d.item().responsible();
        Node person;
        if (responsible == null) {
            person = DetailShell.item(null, "Sin cliente responsable registrado", null, null);
        } else {
            List<String> contact = new ArrayList<>();
            if (d.responsibleDocument() != null) {
                contact.add("CC " + d.responsibleDocument());
            }
            if (d.responsiblePhone() != null && !d.responsiblePhone().isBlank()) {
                contact.add(d.responsiblePhone());
            }
            person = DetailShell.person(DetailShell.initials(responsible), responsible, String.join(" · ", contact), null);
        }
        PlanCoverage plan = d.plan();
        String contract = d.item().contractNumber() == null ? "Pendiente en Contratos y ventas" : d.item().contractNumber();
        GridPane rows = plan == null
                ? DetailShell.rows("Plan exequial", "Particular (sin plan)", "Contrato de servicio", contract)
                : DetailShell.rows(
                        "Plan exequial", plan.code() + " · " + plan.planName() + " · " + plan.relationship().toLowerCase(ES),
                        "Titular del plan", plan.holder(),
                        "Carencia", plan.waitingEnds() == null ? "Sin fecha de inicio"
                                : (plan.covered() ? "Cumplida el " : "Termina el ") + DATE.format(plan.waitingEnds()),
                        "Contrato de servicio", contract);
        return DetailShell.list(person, rows);
    }

    /** Documentos del expediente con su estado; el título lleva la cuenta (completos, no aplica, pendientes). */
    private List<Node> documentsSection(DeceasedDetail d) {
        List<DocumentType> required = DeathCertificateService.requiredDocuments(d.item().deathType());
        List<Node> rows = new ArrayList<>();
        int complete = 0;
        int notApplicable = 0;
        int pending = 0;
        for (DocumentType type : DocumentType.values()) {
            DocumentInfo doc = d.documents().stream().filter(x -> x.type() == type).findFirst().orElse(null);
            boolean needed = required.contains(type);
            // El registro en la notaría lo tramita la funeraria después; no bloquea, pero se puede subir.
            boolean notary = type == DocumentType.CIVIL_DEATH_REGISTRY;
            DetailShell.Mark mark;
            String line;
            Node aside = null;
            if (doc != null) {
                complete++;
                mark = DetailShell.Mark.DONE;
                line = "N.º " + orDash(doc.documentNumber()) + " · " + orDash(doc.issuingAuthority())
                        + (doc.issueDate() == null ? "" : " · " + DATE.format(doc.issueDate()));
                aside = DetailShell.link("Ver", () -> openFile(doc.fileUrl()));
            } else if (needed || notary) {
                pending++;
                mark = needed && d.item().blocksCremation() && type == DocumentType.BODY_RELEASE_ORDER
                        ? DetailShell.Mark.MISSING : DetailShell.Mark.PENDING;
                line = notary ? "Por registrar la muerte en la notaría" : "Pendiente";
                aside = DetailShell.link("Subir", () -> openDocumentDialog(d.item().id(), d.item().fullName(), type));
            } else {
                notApplicable++;
                mark = DetailShell.Mark.NOT_APPLICABLE;
                line = "No aplica · muerte natural";
            }
            rows.add(DetailShell.item(DetailShell.mark(mark), type.getDisplayName(), line, aside));
        }
        List<String> counts = new ArrayList<>();
        if (complete > 0) {
            counts.add(count(complete, "completo"));
        }
        if (notApplicable > 0) {
            counts.add(notApplicable + " no aplica");
        }
        if (pending > 0) {
            counts.add(count(pending, "pendiente"));
        }
        return List.of(DetailShell.section("Documentos", String.join(" · ", counts),
                DetailShell.list(rows.toArray(Node[]::new))));
    }

    /** Abre el archivo guardado con el programa del sistema (visor de PDF o imágenes). */
    private void openFile(String url) {
        Thread thread = new Thread(() -> {
            try {
                File file = Paths.get(URI.create(url)).toFile();
                if (!file.isFile() || !Desktop.isDesktopSupported()) {
                    throw new IllegalStateException();
                }
                Desktop.getDesktop().open(file);
            } catch (Exception e) {
                javafx.application.Platform.runLater(() ->
                        showError("No se pudo abrir el archivo. Puede que se haya movido o borrado de la carpeta de documentos."));
            }
        }, "open-document");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------- diálogos ----------

    @FXML
    private void onRegister() {
        CheckBox unidentified = new CheckBox("Cuerpo no identificado (NN)");
        TextField name = new TextField();
        name.setPromptText("Nombres");
        TextField lastname = new TextField();
        lastname.setPromptText("Apellidos");
        TextField document = new TextField();
        document.setPromptText("Solo números, 5 a 10 dígitos");
        FormShell.document(document, null, 10);
        // Nombres y apellidos sin números ni símbolos
        FormShell.personName(name, lastname);
        DatePicker birth = new DatePicker();
        DatePicker death = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));

        ComboBox<DeathType> type = new ComboBox<>();
        type.getItems().setAll(DeathType.values());
        type.setConverter(converter(DeathType::getDisplayName, ""));
        FormShell.choose(type); // sin tipo de muerte puesto: el usuario lo elige
        type.getStyleClass().add("form-combo");
        ComboBox<ExternalCause> cause = new ComboBox<>();
        cause.getItems().setAll(ExternalCause.values());
        cause.setConverter(converter(CAUSES::get, ""));
        cause.setPromptText("Causa externa");
        cause.getStyleClass().add("form-combo");
        cause.disableProperty().bind(type.valueProperty().isNotEqualTo(DeathType.NON_NATURAL));
        Label typeHint = wrapped("", "dialog-hint");
        typeHint.setPrefWidth(300);
        type.valueProperty().addListener((obs, old, value) -> typeHint.setText(value == DeathType.NON_NATURAL
                ? "Muerte no natural: después suba el informe forense y la orden de entrega." : ""));

        // Aquí no hay cliente ni plan: quien paga se elige al hacer el contrato. La persona de un plan
        // exequial se registra desde Nuevo contrato › «Registrar difunto del plan».
        for (Node node : List.of(name, document)) {
            node.disableProperty().bind(unidentified.selectedProperty());
        }
        // El NN se registra solo con el nombre «NN», sin apellidos ni documento
        Label nnNote = note("Se registrará con el nombre «NN» y sin número de documento.");
        Label planNote = note("Si la persona estaba en un plan exequial, regístrela desde Clientes › Contratos y ventas "
                + "› Nuevo contrato › «Registrar difunto del plan»: ahí se escoge del plan y su contrato queda hecho.");

        // Certificado de defunción
        // Lo expide Medicina Legal si la muerte es violenta, o el médico tratante o la clínica si es natural.
        // La entidad se sugiere cuando el usuario elige el tipo de muerte (antes queda vacía).
        ComboBox<String> authority = authorityBox(MEDICAL_AUTHORITIES);
        type.valueProperty().addListener((obs, old, value) -> authority.getEditor().setText(value == null ? ""
                : value == DeathType.NON_NATURAL ? FORENSIC_AUTHORITY : DOCTOR_AUTHORITY));
        TextField number = new TextField();
        number.setPromptText("Número del certificado");
        DatePicker issued = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        Path[] file = new Path[1];
        HBox fileBox = fileChooser(file);

        unidentified.getStyleClass().add("form-check");
        Label causeHint = typeHint;
        causeHint.setPrefWidth(Region.USE_COMPUTED_SIZE);
        Form form = new Form()
                .section("Datos del difunto", 3)
                .wide(null, unidentified)
                .wide(null, nnNote).hideWhen(unidentified.selectedProperty().not())
                .field("NOMBRES *", name).hideWhen(unidentified.selectedProperty())
                .field("APELLIDOS *", lastname).hideWhen(unidentified.selectedProperty())
                .field("NÚMERO DE DOCUMENTO *", document).hideWhen(unidentified.selectedProperty())
                .field("FECHA DE NACIMIENTO", birth)
                .field("FECHA DE DEFUNCIÓN *", death)
                .field("TIPO DE MUERTE *", type)
                .field("CAUSA EXTERNA", cause)
                .span(null, causeHint, 2)
                .wide(null, planNote)
                .section("Certificado de defunción", 3)
                .field("ENTIDAD QUE LO EXPIDE *", authority)
                .field("NÚMERO DEL CERTIFICADO *", number)
                .field("FECHA DE EXPEDICIÓN *", issued)
                .wide("ARCHIVO *", fileBox);

        Dialog<ButtonType> dialog = formDialog("Registrar difunto",
                "Los campos con * son obligatorios. Sin el certificado de defunción no se registra el difunto.",
                form, 760, true);
        FormShell.page(dialog, "Difuntos");
        FormShell.steps(dialog, null,
                new FormShell.Step("Datos del difunto", "Datos del difunto"),
                new FormShell.Step("Certificado de defunción", "Certificado de defunción"));
        Long[] savedId = new Long[1];
        boolean saved = runForm(dialog, form, "Registrar", () -> {
            boolean nn = unidentified.isSelected();
            DeceasedData data = new DeceasedData(nn, nn ? DeceasedService.UNIDENTIFIED_NAME : name.getText(),
                    nn ? null : lastname.getText(), nn ? null : document.getText(), birth.getValue(), death.getValue(),
                    type.getValue(), cause.getValue(), null, null);
            DeathDocumentData certificate = new DeathDocumentData(DocumentType.MEDICAL_CERTIFICATE,
                    authority.getEditor().getText(), number.getText(), issued.getValue(), file[0], null);
            savedId[0] = deceasedService.register(data, certificate).getId();
        });
        if (saved) {
            loadOverview(savedId[0]);
        }
    }

    private void refreshPlans(String document, LocalDate deathDate, ComboBox<PlanSuggestion> box, Label hint) {
        String text = document == null ? "" : document.trim();
        if (!text.matches("\\d{5,10}")) {
            box.getItems().setAll((PlanSuggestion) null);
            box.setValue(null);
            hint.setText("Escriba el documento para buscar planes donde sea titular o beneficiario.");
            return;
        }
        run(() -> deceasedService.suggestPlans(text, deathDate), plans -> {
            List<PlanSuggestion> items = new ArrayList<>();
            items.add(null);
            items.addAll(plans);
            box.getItems().setAll(items);
            PlanSuggestion covered = plans.stream().filter(PlanSuggestion::covered).findFirst().orElse(null);
            box.setValue(covered);
            if (plans.isEmpty()) {
                hint.setText("El documento no está en ningún plan activo: el servicio será particular.");
            } else if (covered != null) {
                hint.setText("Cubierto por " + covered.code() + " (" + covered.relationship().toLowerCase(ES) + ").");
            } else {
                PlanSuggestion first = plans.get(0);
                hint.setText(first.code() + " sigue en carencia: faltan " + count(first.waitingDaysLeft(), "día")
                        + ". El servicio será particular.");
            }
        }, "No se pudieron buscar los planes.");
    }

    private void openDocumentDialog(Long deceasedId, String fullName, DocumentType suggested) {
        ComboBox<DocumentType> type = new ComboBox<>();
        type.getItems().setAll(DocumentType.values());
        type.setConverter(converter(DocumentType::getDisplayName, ""));
        // Si se abrió desde un documento pendiente ya viene ese tipo; si no, el usuario lo elige.
        FormShell.choose(type);
        type.setValue(suggested);
        type.getStyleClass().add("form-combo");
        ComboBox<String> authority = authorityBox(authoritiesFor(type.getValue()));
        if (!authority.getItems().isEmpty()) {
            authority.getEditor().setText(authority.getItems().get(0)); // la del documento pendiente
        }
        type.valueProperty().addListener((obs, old, value) -> {
            authority.getItems().setAll(authoritiesFor(value));
            authority.getEditor().setText(authority.getItems().isEmpty() ? "" : authority.getItems().get(0));
        });
        TextField number = new TextField();
        number.setPromptText("Número del documento");
        DatePicker issued = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        TextField notes = new TextField();
        notes.setPromptText("Opcional, máximo 150 caracteres");
        Path[] file = new Path[1];

        Form form = new Form()
                .section(null, 2)
                .wide("TIPO DE DOCUMENTO *", type)
                .field("ENTIDAD QUE LO EXPIDE *", authority)
                .field("NÚMERO DEL DOCUMENTO *", number)
                .field("FECHA DE EXPEDICIÓN *", issued)
                .field("OBSERVACIONES", notes)
                .wide("ARCHIVO *", fileChooser(file));

        Dialog<ButtonType> dialog = formDialog("Subir documento",
                fullName + ". Los campos con * son obligatorios.", form, 640, false);
        FormShell.panel(dialog, "Difuntos");
        boolean saved = runForm(dialog, form, "Guardar", () -> certificateService.addDocument(deceasedId,
                new DeathDocumentData(type.getValue(), authority.getEditor().getText(), number.getText(), issued.getValue(),
                        file[0], notes.getText())));
        if (saved) {
            loadOverview(drawerDeceasedId != null ? deceasedId : null);
        }
    }

    /** Entidades que expiden el certificado de defunción. */
    private static final String FORENSIC_AUTHORITY = "Medicina Legal";
    private static final String DOCTOR_AUTHORITY = "Médico tratante o clínica";
    private static final List<String> MEDICAL_AUTHORITIES = List.of(DOCTOR_AUTHORITY, FORENSIC_AUTHORITY);

    /** Sugerencias de "quién lo expide" según el documento; el campo deja escribir otra entidad. */
    private static List<String> authoritiesFor(DocumentType type) {
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case MEDICAL_CERTIFICATE -> MEDICAL_AUTHORITIES;
            case FORENSIC_REPORT -> List.of(FORENSIC_AUTHORITY);
            case BODY_RELEASE_ORDER -> List.of("Fiscalía", FORENSIC_AUTHORITY);
            case CIVIL_DEATH_REGISTRY -> List.of("Notaría");
        };
    }

    private static ComboBox<String> authorityBox(List<String> suggestions) {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.getItems().setAll(suggestions);
        box.setPromptText("Entidad que lo expide");
        box.setMaxWidth(Double.MAX_VALUE);
        box.getStyleClass().add("form-combo");
        return box;
    }

    private void openLinkPlanDialog(DeceasedDetail d) {
        ComboBox<PlanSuggestion> planBox = new ComboBox<>();
        planBox.getStyleClass().add("form-combo");
        planBox.setMaxWidth(Double.MAX_VALUE);
        planBox.setConverter(converter(DeceasedController::suggestionText, "Particular (sin plan)"));
        Label hint = wrapped("", "dialog-hint");
        refreshPlans(d.deceased().getDocumentNumber(), d.deceased().getDeathDate(), planBox, hint);

        Form form = new Form()
                .section(null, 1)
                .field("PLAN EXEQUIAL", new VBox(4, planBox, hint));

        Dialog<ButtonType> dialog = formDialog("Vincular plan exequial", d.item().fullName()
                + ". Se buscan los planes donde su documento es titular o beneficiario.", form, 540, false);
        FormShell.window(dialog, "Difuntos");
        boolean saved = runForm(dialog, form, "Guardar", () -> {
            PlanSuggestion plan = planBox.getValue();
            deceasedService.linkPlan(d.item().id(), plan == null ? null : plan.planId());
        });
        if (saved) {
            loadOverview(d.item().id());
        }
    }

    /** Botón "Adjuntar" con el nombre del archivo elegido (PDF o imagen, hasta 10 MB). */
    private HBox fileChooser(Path[] selected) {
        Label fileName = label("Ningún archivo (PDF, PNG o JPG, máx. 10 MB)", "file-name");
        fileName.setWrapText(true);
        Button attach = new Button("Adjuntar…");
        attach.getStyleClass().add("secondary-button");
        attach.setMinWidth(Region.USE_PREF_SIZE);
        attach.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Seleccionar documento");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF o imagen",
                    FileStorageService.EXTENSIONS.stream().map(x -> "*." + x).toList()));
            File file = chooser.showOpenDialog(window());
            if (file != null) {
                selected[0] = file.toPath();
                fileName.setText(file.getName());
            }
        });
        HBox box = new HBox(10, attach, fileName);
        box.getStyleClass().add("file-box");
        return box;
    }

    private static String suggestionText(PlanSuggestion s) {
        return s.code() + " · " + s.planName() + " · " + s.relationship().toLowerCase(ES)
                + (s.covered() ? "" : " · carencia " + s.waitingDaysLeft() + " d");
    }

    // ---------- utilidades de interfaz ----------

    private void buildLegend() {
        String[][] items = {{"dot-preparation", "Preparación"}, {"dot-wake", "Velación"},
                {"dot-transfer", "Traslado"}, {"dot-missing", "Falta documento"}};
        for (String[] item : items) {
            Region dot = new Region();
            dot.getStyleClass().addAll("legend-dot", item[0]);
            Label text = new Label(item[1]);
            text.getStyleClass().add("legend-text");
            HBox entry = new HBox(dot, text);
            entry.getStyleClass().add("legend-item");
            legend.getChildren().add(entry);
        }
    }

    private DeceasedItem find(Long id) {
        return overview == null ? null
                : overview.items().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
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

    /**
     * Formulario de diálogo con el estilo de Registrar empleado (Personal): secciones con título y
     * campos en columnas con la etiqueta en mayúsculas arriba.
     */
    private static final class Form {
        private final VBox body = new VBox(14);
        private final Label error = new Label();
        private GridPane grid;
        private int columns;
        private int column;
        private int row;
        private VBox last;

        Form() {
            body.getStyleClass().add("form-body");
            error.getStyleClass().add("error-label");
            error.setWrapText(true);
            error.managedProperty().bind(error.textProperty().isNotEmpty());
            error.visibleProperty().bind(error.managedProperty());
        }

        Form section(String title, int columns) {
            if (title != null) {
                body.getChildren().add(legend(title));
            }
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
            body.getChildren().add(grid);
            return this;
        }

        Form field(String label, Node input) {
            return span(label, input, 1);
        }

        Form wide(String label, Node input) {
            return span(label, input, columns);
        }

        Form span(String label, Node input, int span) {
            if (column + span > columns) {
                column = 0;
                row++;
            }
            VBox box = new VBox(2);
            box.getStyleClass().add("form-field");
            if (label != null) {
                Label caption = new Label(label);
                caption.getStyleClass().add("field-label");
                box.getChildren().add(caption);
            }
            box.getChildren().add(input);
            stretch(input);
            grid.add(box, column, row, span, 1);
            last = box;
            column += span;
            if (column >= columns) {
                column = 0;
                row++;
            }
            return this;
        }

        /** Oculta el último campo agregado mientras la condición sea verdadera (no deja hueco). */
        Form hideWhen(ObservableBooleanValue condition) {
            last.managedProperty().bind(Bindings.not(condition));
            last.visibleProperty().bind(Bindings.not(condition));
            return this;
        }

        /** Cambia la etiqueta del último campo mientras la condición sea verdadera. */
        Form captionWhen(ObservableBooleanValue condition, String caption) {
            Label label = (Label) last.getChildren().get(0);
            label.textProperty().bind(Bindings.when(condition).then(caption).otherwise(label.getText()));
            return this;
        }

        Form note(Node note) {
            body.getChildren().add(note);
            return this;
        }

        /** Los campos ocupan todo el ancho de su columna, también dentro de un VBox. */
        private static void stretch(Node node) {
            if (node instanceof Region region && !(node instanceof Label) && !(node instanceof CheckBox)) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            if (node instanceof VBox box) {
                box.getChildren().forEach(Form::stretch);
            }
        }
    }

    /** Recuadro salvia de aviso (como en Crear cuenta); se oculta mientras no tenga texto. */
    private static Label note(String text) {
        Label note = wrapped(text, "note-info");
        note.setGraphic(infoIcon());
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        note.managedProperty().bind(note.textProperty().isNotEmpty());
        note.visibleProperty().bind(note.managedProperty());
        return note;
    }

    /** Título de sección en mayúsculas con línea caqui a la derecha (estilo de registro aprobado). */
    private static HBox legend(String title) {
        Label caption = new Label(title.toUpperCase(Locale.ROOT));
        caption.getStyleClass().add("section-title");
        caption.setMinWidth(Region.USE_PREF_SIZE);
        Region rule = new Region();
        rule.getStyleClass().add("section-rule");
        HBox.setHgrow(rule, Priority.ALWAYS);
        HBox legend = new HBox(caption, rule);
        legend.getStyleClass().add("section-legend");
        return legend;
    }

    private static Node infoIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
        icon.getStyleClass().add("note-icon");
        return icon;
    }

    private static ComboBox<Option> optionCombo(String prompt) {
        ComboBox<Option> box = new ComboBox<>();
        box.setPromptText(prompt);
        box.getStyleClass().add("form-combo");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(converter(o -> o.detail() == null ? o.label() : o.label() + " · " + o.detail(), ""));
        return box;
    }

    /** Diálogo de formulario: título, subtítulo, el formulario y abajo Cancelar y la acción. */
    private Dialog<ButtonType> formDialog(String title, String subtitle, Form form, double width, boolean scroll) {
        VBox header = new VBox(2, label(title, "form-title"), wrapped(subtitle, "card-subtitle"));
        header.getStyleClass().add("form-header");
        Node center = form.body;
        if (scroll) {
            ScrollPane pane = new ScrollPane(form.body);
            pane.setFitToWidth(true);
            pane.setPrefViewportHeight(520);
            pane.getStyleClass().add("form-scroll");
            center = pane;
        }
        // El error va fuera del desplazamiento para que se vea aunque el formulario sea largo.
        VBox.setMargin(form.error, new Insets(6, 28, 4, 28));
        VBox content = new VBox(header, center, form.error);
        content.getStyleClass().add("form-root");
        content.setPrefWidth(width);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.initOwner(window());
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        styleDialog(pane);
        pane.getStyleClass().add("form-dialog");
        return dialog;
    }

    /** Muestra el formulario; si el servicio rechaza los datos, el mensaje sale abajo y el diálogo sigue abierto. */
    private boolean runForm(Dialog<ButtonType> dialog, Form form, String okText, Runnable save) {
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(cancel, ok);
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ok);
        okButton.getStyleClass().add("primary-dialog-button");
        okButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                form.error.setText("");
                save.run();
            } catch (BusinessException | NumberFormatException e) {
                event.consume();
                form.error.setText(e.getMessage());
            }
        });
        return dialog.showAndWait().filter(ok::equals).isPresent();
    }

    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                DeceasedController.class.getResource("/css/app.css").toExternalForm(),
                DeceasedController.class.getResource("/css/personal.css").toExternalForm(),
                DeceasedController.class.getResource("/css/deceased.css").toExternalForm());
        pane.getStyleClass().add("personal-dialog");
        // Cancelar a la izquierda y la acción a la derecha también en Windows, como en Personal.
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showError(String message) {
        Notice.warning(window(), MODULE_NAME, "Atención", message);
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
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
