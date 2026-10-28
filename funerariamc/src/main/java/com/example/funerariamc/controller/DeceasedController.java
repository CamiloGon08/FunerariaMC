package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.ExternalCause;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.DeathCertificateService;
import com.example.funerariamc.service.DeathDocumentData;
import com.example.funerariamc.service.DeceasedData;
import com.example.funerariamc.service.DeceasedService;
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
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Pantalla Difuntos (propuesta P-13 / P-13.1): indicadores, tarjetas de los servicios en curso con
 * su etapa, registro de difuntos con la barra visible (sin paginación), documentos pendientes,
 * salidas de hoy y el expediente en el panel lateral. Desde aquí se registra un difunto con su
 * certificado (RF-DIF-01, RF-DIF-02), se sube un documento (RF-DIF-03) y se vincula el plan (HU-56).
 */
@Component
@Scope("prototype")
public class DeceasedController {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM · HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", ES);
    private static final double DRAWER_WIDTH = 460;
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
    private VBox drawer;
    @FXML
    private Region veil;
    @FXML
    private VBox drawerBody;
    @FXML
    private HBox drawerActions;

    private Overview overview;
    /** Difunto del panel abierto; null si está cerrado. */
    private Long drawerDeceasedId;

    public DeceasedController(DeceasedService deceasedService, DeathCertificateService certificateService) {
        this.deceasedService = deceasedService;
        this.certificateService = certificateService;
    }

    @FXML
    private void initialize() {
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
        drawer.setTranslateX(DRAWER_WIDTH);
        loadOverview(null);
    }

    private void loadOverview(Long reopenId) {
        countLabel.setText("Cargando difuntos…");
        run(deceasedService::overview, result -> {
            overview = result;
            fillMonths(result.items());
            renderKpis(result.kpis());
            renderServiceCards();
            renderRegistry();
            renderPending(result.pendingDocuments());
            renderDepartures(result.departures());
            if (reopenId != null) {
                openDrawer(reopenId);
            }
        }, "No se pudieron cargar los difuntos.");
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

    private void renderServiceCards() {
        List<DeceasedItem> inService = overview.items().stream().filter(DeceasedItem::inService)
                .sorted(java.util.Comparator.comparing((DeceasedItem i) -> i.stage().getStep()).reversed())
                .limit(MAX_SERVICE_CARDS)
                .toList();
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
        card.setMinWidth(200);
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
        registryBox.getChildren().setAll(items.stream().map(this::registryRow).toList());
        countLabel.setText(count(items.size(), "difunto") + (items.size() > 8 ? " · desplace para ver más" : ""));
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

        Label destination = label(item.destination() == null ? item.stage().getLabel() : item.destination(), "row-text");
        destination.getStyleClass().add("col-destination");

        Label docs = item.missing().isEmpty() ? label("Completos", "docs-ok")
                : label("Falta " + item.missing().size(), "docs-missing");
        docs.getStyleClass().add("col-docs");

        HBox row = new HBox(name, date, typeBox, plan, destination, docs);
        row.getStyleClass().add("registry-row");
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

    // ---------- expediente (P-13.1) ----------

    private void openDrawer(Long deceasedId) {
        drawerDeceasedId = deceasedId;
        run(() -> deceasedService.detail(deceasedId), detail -> {
            if (!deceasedId.equals(drawerDeceasedId)) {
                return;
            }
            fillDrawer(detail);
            if (drawer.isVisible()) {
                return;
            }
            veil.setVisible(true);
            drawer.setVisible(true);
            TranslateTransition slide = new TranslateTransition(Duration.millis(220), drawer);
            slide.setToX(0);
            FadeTransition fade = new FadeTransition(Duration.millis(220), veil);
            fade.setFromValue(0);
            fade.setToValue(1);
            new ParallelTransition(slide, fade).play();
        }, "No se pudo cargar el expediente.");
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerDeceasedId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerDeceasedId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    private void fillDrawer(DeceasedDetail d) {
        DeceasedItem item = d.item();
        SVGPath cross = new SVGPath();
        cross.setContent("M10 2v20M4 8h12");
        cross.getStyleClass().add("cross");
        StackPane badge = new StackPane(cross);
        badge.getStyleClass().add("file-badge");

        Label caption = new Label("EXPEDIENTE · " + (item.contractNumber() == null ? item.code() : item.contractNumber()));
        caption.getStyleClass().add("kpi-caption");
        List<String> facts = new ArrayList<>();
        facts.add(item.unidentified() ? "Sin identificar" : "CC " + orDash(item.documentNumber()));
        String born = d.deceased().getBirthDate() == null ? "?" : DATE.format(d.deceased().getBirthDate());
        String died = item.deathDate() == null ? "?" : DATE.format(item.deathDate());
        facts.add(born + " – " + died);
        if (item.age() != null) {
            facts.add(item.age() + " años");
        }
        VBox titles = new VBox(2, caption, wrapped(item.fullName(), "drawer-name"),
                wrapped(String.join(" · ", facts), "card-subtitle"));
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(event -> closeDrawer());
        HBox header = new HBox(14, badge, titles, close);
        header.setAlignment(Pos.TOP_LEFT);

        FlowPane pills = new FlowPane(pill(item.stage().getLabel(), "status-active"));
        pills.getStyleClass().add("chip-flow");
        if (item.deathType() != null) {
            String type = "Muerte " + item.deathType().getDisplayName().toLowerCase(ES)
                    + (item.externalCause() == null ? "" : " · " + CAUSES.get(item.externalCause()).toLowerCase(ES));
            pills.getChildren().add(pill(type, item.deathType() == DeathType.NON_NATURAL ? "pill-alert" : "pill-warning"));
        }
        PlanCoverage plan = d.plan();
        pills.getChildren().add(plan == null ? pill("Particular", "pill-neutral")
                : pill(plan.planName() + (plan.covered() ? " · cubierto" : " · en carencia"), "pill-neutral"));

        drawerBody.getChildren().setAll(header, pills);
        if (item.blocksCremation()) {
            drawerBody.getChildren().add(band("Falta la orden de entrega del cuerpo: la cremación no puede hacerse "
                    + "hasta subirla (RN-03).", "band-alert"));
        }
        drawerBody.getChildren().addAll(
                titled("RECORRIDO DEL SERVICIO", timeline(d.timeline())),
                titled("RESPONSABLE Y PLAN", responsibleBox(d)),
                titled("DOCUMENTOS", documentsBox(d)));
        if (plan != null && !d.otherBeneficiaries().isEmpty()) {
            FlowPane chips = new FlowPane();
            chips.getStyleClass().add("chip-flow");
            d.otherBeneficiaries().forEach(name -> chips.getChildren().add(pill(name, "status-active")));
            drawerBody.getChildren().add(titled("OTROS BENEFICIARIOS DEL PLAN " + plan.code(), chips));
        }

        drawerActions.getChildren().clear();
        Button link = drawerButton(plan == null ? "Vincular plan" : "Cambiar plan", "drawer-secondary");
        link.setDisable(item.contractNumber() != null || item.unidentified());
        link.setOnAction(e -> openLinkPlanDialog(d));
        Button upload = drawerButton("Subir documento", "drawer-primary");
        upload.setOnAction(e -> openDocumentDialog(item.id(), item.fullName(),
                item.missing().isEmpty() ? null : item.missing().get(0)));
        drawerActions.getChildren().addAll(link, upload);
    }

    private VBox timeline(List<TimelineStep> steps) {
        VBox box = new VBox(12);
        box.getStyleClass().add("timeline-box");
        for (TimelineStep step : steps) {
            Circle dot = new Circle(5);
            dot.getStyleClass().add("timeline-dot");
            Label title = wrapped(step.title(), "timeline-title");
            if (!step.done()) {
                dot.getStyleClass().add("pending");
                title.getStyleClass().add("pending");
            }
            String when = step.at() == null ? "" : DAY_TIME.format(step.at()) + " · ";
            Label detail = wrapped(when + orDash(step.detail()), "timeline-date");
            StackPane dotBox = new StackPane(dot);
            dotBox.setPadding(new Insets(4, 0, 0, 2));
            HBox row = new HBox(dotBox, new VBox(1, title, detail));
            row.getStyleClass().add("timeline-row");
            box.getChildren().add(row);
        }
        return box;
    }

    private VBox responsibleBox(DeceasedDetail d) {
        VBox box = new VBox(10);
        box.getStyleClass().add("timeline-box");
        String responsible = d.item().responsible();
        if (responsible == null) {
            box.getChildren().add(wrapped("Sin cliente responsable registrado.", "card-subtitle"));
        } else {
            Label initials = new Label(initials(responsible));
            initials.getStyleClass().add("avatar-text");
            StackPane avatar = new StackPane(initials);
            avatar.getStyleClass().add("avatar");
            avatar.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            List<String> contact = new ArrayList<>();
            if (d.responsibleDocument() != null) {
                contact.add("CC " + d.responsibleDocument());
            }
            if (d.responsiblePhone() != null && !d.responsiblePhone().isBlank()) {
                contact.add(d.responsiblePhone());
            }
            HBox person = new HBox(12, avatar, new VBox(2, wrapped(responsible, "attention-name"),
                    wrapped(String.join(" · ", contact), "card-subtitle")));
            person.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(person);
        }
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        PlanCoverage plan = d.plan();
        if (plan == null) {
            infoRow(grid, 0, "Plan exequial", "Particular (sin plan)");
        } else {
            infoRow(grid, 0, "Plan exequial", plan.code() + " · " + plan.planName() + " · "
                    + plan.relationship().toLowerCase(ES));
            infoRow(grid, 1, "Titular del plan", plan.holder());
            infoRow(grid, 2, "Carencia", plan.waitingEnds() == null ? "Sin fecha de inicio"
                    : (plan.covered() ? "Cumplida el " : "Termina el ") + DATE.format(plan.waitingEnds()));
        }
        infoRow(grid, plan == null ? 1 : 3, "Contrato de servicio",
                d.item().contractNumber() == null ? "Pendiente en Contratos y ventas" : d.item().contractNumber());
        box.getChildren().add(grid);
        return box;
    }

    private VBox documentsBox(DeceasedDetail d) {
        VBox box = new VBox(10);
        box.getStyleClass().add("timeline-box");
        List<DocumentType> required = DeathCertificateService.requiredDocuments(d.item().deathType());
        for (DocumentType type : DocumentType.values()) {
            DocumentInfo doc = d.documents().stream().filter(x -> x.type() == type).findFirst().orElse(null);
            boolean needed = required.contains(type);
            Label check = new Label(doc != null ? "✓" : needed ? "!" : "–");
            check.getStyleClass().addAll("doc-check", doc != null ? "ok" : needed ? "missing" : "na");
            String detail;
            if (doc != null) {
                detail = "N.º " + orDash(doc.documentNumber()) + " · " + orDash(doc.issuingAuthority())
                        + (doc.issueDate() == null ? "" : " · " + DATE.format(doc.issueDate()));
            } else {
                detail = needed ? "Pendiente de subir" : "No aplica · muerte natural";
            }
            VBox text = new VBox(1, wrapped(type.getDisplayName(), "attention-name"),
                    wrapped(detail, doc == null && needed ? "attention-detail-overdue" : "card-subtitle"));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(check, text);
            row.getStyleClass().add("doc-row");
            if (doc != null) {
                Button see = new Button("Ver");
                see.getStyleClass().add("text-action");
                see.setOnAction(e -> openFile(doc.fileUrl()));
                row.getChildren().add(see);
            } else if (needed) {
                Button upload = new Button("Subir");
                upload.getStyleClass().add("text-action");
                upload.setOnAction(e -> openDocumentDialog(d.item().id(), d.item().fullName(), type));
                row.getChildren().add(upload);
            }
            box.getChildren().add(row);
        }
        return box;
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
        document.setPromptText("Solo números, 5 a 11 dígitos");
        DatePicker birth = new DatePicker();
        DatePicker death = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));

        ComboBox<DeathType> type = new ComboBox<>();
        type.getItems().setAll(DeathType.values());
        type.setConverter(converter(DeathType::getDisplayName, ""));
        type.setValue(DeathType.NATURAL);
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
                ? "Muerte no natural: después suba el informe forense y la orden de entrega (RN-03)." : ""));

        TextField clientSearch = searchInput("Buscar cliente por nombre o documento");
        ComboBox<Option> clientBox = optionCombo("Seleccione el responsable");
        bindSearch(clientSearch, clientBox, deceasedService::searchClients);

        ComboBox<PlanSuggestion> planBox = new ComboBox<>();
        planBox.getStyleClass().add("form-combo");
        planBox.setMaxWidth(Double.MAX_VALUE);
        planBox.setConverter(converter(DeceasedController::suggestionText, "Particular (sin plan)"));
        Label planHint = wrapped("Escriba el documento para buscar planes donde sea titular o beneficiario.",
                "dialog-hint");
        planHint.setPrefWidth(300);
        PauseTransition planDelay = new PauseTransition(Duration.millis(300));
        planDelay.setOnFinished(e -> refreshPlans(document.getText(), death.getValue(), planBox, planHint));
        document.textProperty().addListener((obs, old, text) -> planDelay.playFromStart());
        death.valueProperty().addListener((obs, old, value) -> planDelay.playFromStart());

        for (Node node : List.of(name, document, planBox)) {
            node.disableProperty().bind(unidentified.selectedProperty());
        }
        unidentified.selectedProperty().addListener((obs, old, nn) -> {
            lastname.setPromptText(nn ? "Descripción corta (ej. hombre adulto)" : "Apellidos");
            if (nn) {
                planBox.setValue(null);
            }
        });

        // Certificado de defunción (RN-02)
        TextField authority = new TextField();
        authority.setPromptText("Entidad que lo expide (clínica, Medicina Legal…)");
        TextField number = new TextField();
        number.setPromptText("Número del certificado");
        DatePicker issued = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        Path[] file = new Path[1];
        HBox fileBox = fileChooser(file);

        GridPane form = dialogForm();
        int row = 0;
        addField(form, row++, "", unidentified);
        addField(form, row++, "NOMBRES", name);
        addField(form, row++, "APELLIDOS", lastname);
        addField(form, row++, "DOCUMENTO", document);
        addField(form, row++, "NACIMIENTO", birth);
        addField(form, row++, "DEFUNCIÓN", death);
        addField(form, row++, "TIPO DE MUERTE", new VBox(6, type, cause, typeHint));
        addField(form, row++, "RESPONSABLE", new VBox(6, clientSearch, clientBox));
        addField(form, row++, "PLAN EXEQUIAL", new VBox(4, planBox, planHint));
        Label certTitle = new Label("CERTIFICADO DE DEFUNCIÓN (obligatorio)");
        certTitle.getStyleClass().add("kpi-caption");
        form.add(certTitle, 0, row++, 2, 1);
        addField(form, row++, "ENTIDAD", authority);
        addField(form, row++, "NÚMERO", number);
        addField(form, row++, "EXPEDICIÓN", issued);
        addField(form, row, "ARCHIVO", fileBox);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(560);
        scroll.getStyleClass().add("card-scroll");
        Dialog<ButtonType> dialog = dialog("Registrar difunto", scroll);
        ButtonType save = new ButtonType("Registrar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary-dialog-button");
        Long[] savedId = new Long[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                boolean nn = unidentified.isSelected();
                PlanSuggestion plan = planBox.getValue();
                DeceasedData data = new DeceasedData(nn, nn ? DeceasedService.UNIDENTIFIED_NAME : name.getText(),
                        lastname.getText(), nn ? null : document.getText(), birth.getValue(), death.getValue(),
                        type.getValue(), cause.getValue(), idOf(clientBox.getValue()),
                        nn || plan == null ? null : plan.planId());
                DeathDocumentData certificate = new DeathDocumentData(DocumentType.MEDICAL_CERTIFICATE,
                        authority.getText(), number.getText(), issued.getValue(), file[0], null);
                savedId[0] = deceasedService.register(data, certificate).getId();
            } catch (BusinessException e) {
                event.consume(); // el diálogo sigue abierto para corregir
                showError(e.getMessage());
            }
        });
        if (dialog.showAndWait().filter(save::equals).isPresent()) {
            loadOverview(savedId[0]);
        }
    }

    private void refreshPlans(String document, LocalDate deathDate, ComboBox<PlanSuggestion> box, Label hint) {
        String text = document == null ? "" : document.trim();
        if (!text.matches("\\d{5,11}")) {
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
                        + " (RN-08). El servicio será particular.");
            }
        }, "No se pudieron buscar los planes.");
    }

    private void openDocumentDialog(Long deceasedId, String fullName, DocumentType suggested) {
        ComboBox<DocumentType> type = new ComboBox<>();
        type.getItems().setAll(DocumentType.values());
        type.setConverter(converter(DocumentType::getDisplayName, ""));
        type.setValue(suggested == null ? DocumentType.FORENSIC_REPORT : suggested);
        type.getStyleClass().add("form-combo");
        TextField authority = new TextField();
        authority.setPromptText("Entidad que lo expide");
        TextField number = new TextField();
        number.setPromptText("Número del documento");
        DatePicker issued = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        TextField notes = new TextField();
        notes.setPromptText("Opcional, máximo 150 caracteres");
        Path[] file = new Path[1];

        GridPane form = dialogForm();
        addField(form, 0, "DIFUNTO", label(fullName, "cell-strong"));
        addField(form, 1, "DOCUMENTO", type);
        addField(form, 2, "ENTIDAD", authority);
        addField(form, 3, "NÚMERO", number);
        addField(form, 4, "EXPEDICIÓN", issued);
        addField(form, 5, "ARCHIVO", fileChooser(file));
        addField(form, 6, "OBSERVACIONES", notes);

        Dialog<ButtonType> dialog = dialog("Subir documento", form);
        ButtonType save = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary-dialog-button");
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                certificateService.addDocument(deceasedId, new DeathDocumentData(type.getValue(), authority.getText(),
                        number.getText(), issued.getValue(), file[0], notes.getText()));
            } catch (BusinessException e) {
                event.consume();
                showError(e.getMessage());
            }
        });
        if (dialog.showAndWait().filter(save::equals).isPresent()) {
            loadOverview(drawerDeceasedId != null ? deceasedId : null);
        }
    }

    private void openLinkPlanDialog(DeceasedDetail d) {
        ComboBox<PlanSuggestion> planBox = new ComboBox<>();
        planBox.getStyleClass().add("form-combo");
        planBox.setMaxWidth(Double.MAX_VALUE);
        planBox.setConverter(converter(DeceasedController::suggestionText, "Particular (sin plan)"));
        Label hint = wrapped("", "dialog-hint");
        refreshPlans(d.deceased().getDocumentNumber(), d.deceased().getDeathDate(), planBox, hint);

        GridPane form = dialogForm();
        addField(form, 0, "DIFUNTO", label(d.item().fullName(), "cell-strong"));
        addField(form, 1, "PLAN EXEQUIAL", new VBox(4, planBox, hint));

        Dialog<ButtonType> dialog = dialog("Vincular plan exequial", form);
        ButtonType save = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary-dialog-button");
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                PlanSuggestion plan = planBox.getValue();
                deceasedService.linkPlan(d.item().id(), plan == null ? null : plan.planId());
            } catch (BusinessException e) {
                event.consume();
                showError(e.getMessage());
            }
        });
        if (dialog.showAndWait().filter(save::equals).isPresent()) {
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

    private static Label band(String text, String styleClass) {
        Label band = wrapped(text, "band");
        band.getStyleClass().add(styleClass);
        band.setMaxWidth(Double.MAX_VALUE);
        return band;
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

    private static VBox titled(String title, Node content) {
        Label caption = new Label(title);
        caption.getStyleClass().add("kpi-caption");
        return new VBox(8, caption, content);
    }

    private static void infoRow(GridPane grid, int row, String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("info-key");
        key.setMinWidth(Region.USE_PREF_SIZE);
        Label val = wrapped(value, "info-value");
        val.setMaxWidth(Double.MAX_VALUE);
        val.setAlignment(Pos.CENTER_RIGHT);
        val.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        GridPane.setHgrow(val, Priority.ALWAYS);
        grid.add(key, 0, row);
        grid.add(val, 1, row);
    }

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    private static GridPane dialogForm() {
        GridPane form = new GridPane();
        form.getStyleClass().add("dialog-form");
        return form;
    }

    private static void addField(GridPane form, int row, String label, Node field) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        caption.setMinWidth(Region.USE_PREF_SIZE);
        GridPane.setValignment(caption, VPos.TOP);
        form.add(caption, 0, row);
        form.add(field, 1, row);
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

    /** Al escribir en el buscador del diálogo se vuelve a consultar; si queda uno solo, se elige. */
    private void bindSearch(TextField field, ComboBox<Option> box, Function<String, List<Option>> search) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        Runnable refresh = () -> {
            try {
                List<Option> options = search.apply(field.getText());
                box.getItems().setAll(options);
                box.setValue(options.size() == 1 ? options.get(0) : null);
            } catch (BusinessException e) {
                showError(e.getMessage());
            }
        };
        delay.setOnFinished(e -> refresh.run());
        field.textProperty().addListener((obs, old, text) -> delay.playFromStart());
        refresh.run();
    }

    private Dialog<ButtonType> dialog(String title, Node content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.initOwner(window());
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        styleDialog(pane);
        return dialog;
    }

    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                DeceasedController.class.getResource("/css/app.css").toExternalForm(),
                DeceasedController.class.getResource("/css/personal.css").toExternalForm(),
                DeceasedController.class.getResource("/css/cemetery.css").toExternalForm(),
                DeceasedController.class.getResource("/css/deceased.css").toExternalForm());
        pane.getStyleClass().add("personal-dialog");
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(window());
        alert.setHeaderText(null);
        styleDialog(alert.getDialogPane());
        alert.showAndWait();
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

    private static String initials(String name) {
        StringBuilder result = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && result.length() < 2) {
                result.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return result.toString();
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
