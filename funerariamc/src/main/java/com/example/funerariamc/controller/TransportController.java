package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.enums.StopType;
import com.example.funerariamc.model.enums.TransportStatus;
import com.example.funerariamc.model.enums.VehicleStatus;
import com.example.funerariamc.model.enums.VehicleType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.RouteMapService;
import com.example.funerariamc.service.TransferData;
import com.example.funerariamc.service.TransportService;
import com.example.funerariamc.service.TransportService.Candidate;
import com.example.funerariamc.service.TransportService.Candidates;
import com.example.funerariamc.service.TransportService.ContractTransport;
import com.example.funerariamc.service.TransportService.DocStatus;
import com.example.funerariamc.service.TransportService.DriverCard;
import com.example.funerariamc.service.TransportService.DriverState;
import com.example.funerariamc.service.TransportService.Kpis;
import com.example.funerariamc.service.TransportService.Option;
import com.example.funerariamc.service.TransportService.Saved;
import com.example.funerariamc.service.TransportService.Screen;
import com.example.funerariamc.service.TransportService.Stop;
import com.example.funerariamc.service.TransportService.Transfer;
import com.example.funerariamc.service.TransportService.Unit;
import com.example.funerariamc.service.TransportService.VehicleCard;
import com.example.funerariamc.service.TransportService.VehicleState;
import com.example.funerariamc.service.VehicleData;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
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
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pantalla de Transporte (pantallas 23, 23.1, 23.2 y 23.3 de Figma, paleta verde oscura): indicadores
 * de la flota, pestañas Monitor, Vehículos, Conductores y Traslados, y los diálogos Nuevo vehículo
 * y Asignar traslado. La tarjeta "Ubicación de traslados activos" deja el
 * espacio del mapa y, mientras tanto, dibuja la ruta como una línea de paradas.
 * Todo se carga en otro hilo con una sola llamada; las pestañas solo repintan lo que ya está en memoria.
 * Mientras la pantalla está abierta se actualiza sola cada 60 segundos.
 */
@Component
@Scope("prototype")
public class TransportController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    /** Cada cuánto se vuelve a leer la flota mientras la pantalla está abierta. */
    private static final Duration AUTO_REFRESH = Duration.seconds(60);
    /** Nombre de la primera parada por defecto (se puede cambiar en el diálogo). */
    private static final String DEFAULT_ORIGIN = "Sede El Edén";

    private enum Tab { MONITOR, VEHICLES, DRIVERS, TRANSFERS }

    private final TransportService transportService;
    private final RouteMapService routeMapService;
    /** Un mapa por espacio (Monitor y Traslados), creado la primera vez que se abre la pantalla. */
    private final Map<StackPane, RouteMapPane> maps = new HashMap<>();
    /** Qué traslado (y con qué llegadas) muestra cada espacio, para no rehacer el mapa en cada recarga. */
    private final Map<StackPane, String> mapKeys = new HashMap<>();

    @FXML
    private StackPane root;
    @FXML
    private ToggleGroup tabGroup;
    @FXML
    private ToggleGroup filterGroup;
    @FXML
    private ToggleButton monitorTab;
    @FXML
    private ToggleButton vehiclesTab;
    @FXML
    private ToggleButton driversTab;
    @FXML
    private ToggleButton transfersTab;
    @FXML
    private Label inRouteValue;
    @FXML
    private Label inRouteDetail;
    @FXML
    private Label availableValue;
    @FXML
    private Label availableDetail;
    @FXML
    private Label maintenanceValue;
    @FXML
    private Label maintenanceDetail;
    @FXML
    private Label expiredValue;
    @FXML
    private Label expiredDetail;
    @FXML
    private Label updatedLabel;
    @FXML
    private HBox filterBox;
    @FXML
    private Button mainButton;
    @FXML
    private VBox monitorPane;
    @FXML
    private StackPane monitorRouteSlot;
    @FXML
    private Label monitorRouteHint;
    @FXML
    private VBox monitorDetail;
    @FXML
    private Label fleetCount;
    @FXML
    private HBox fleetHead;
    @FXML
    private VBox fleetRows;
    @FXML
    private VBox vehiclesPane;
    @FXML
    private FlowPane vehicleCards;
    @FXML
    private Label vehiclesEmpty;
    @FXML
    private VBox driversPane;
    @FXML
    private Label driversCount;
    @FXML
    private HBox driversHead;
    @FXML
    private VBox driverRows;
    @FXML
    private VBox transfersPane;
    @FXML
    private Label transfersTitle;
    @FXML
    private VBox transferList;
    @FXML
    private Label transferRouteTitle;
    @FXML
    private Label transferRouteHint;
    @FXML
    private StackPane transferRouteSlot;
    @FXML
    private HBox transferActions;
    @FXML
    private HBox stopsHead;
    @FXML
    private VBox stopRows;
    @FXML
    private Label toast;

    private Screen screen;
    private Tab tab = Tab.MONITOR;
    /** Filtro de la pestaña Vehículos (null = todos) y de Traslados (0 hoy, 1 mañana, 7 semana). */
    private VehicleType vehicleFilter;
    private int dayFilter;
    /** Traslado elegido: "contrato|hora de salida". */
    private String selectedTransfer;
    /** Cada carga lleva un número; si llega una respuesta vieja, se descarta. */
    private int generation;
    private boolean busy;
    private SequentialTransition toastMotion;
    private final Timeline autoRefresh = new Timeline(new KeyFrame(AUTO_REFRESH, e -> autoReload()));

    public TransportController(TransportService transportService, RouteMapService routeMapService) {
        this.transportService = transportService;
        this.routeMapService = routeMapService;
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        monitorTab.setUserData(Tab.MONITOR);
        vehiclesTab.setUserData(Tab.VEHICLES);
        driversTab.setUserData(Tab.DRIVERS);
        transfersTab.setUserData(Tab.TRANSFERS);
        tabGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda una pestaña elegida
            } else {
                tab = (Tab) toggle.getUserData();
                showTab();
            }
        });

        fleetHead.getChildren().setAll(head("VEHÍCULO", 0), head("PLACA", 130), head("CONDUCTOR", 200),
                head("MISIÓN Y DESTINO", 0), head("ESTADO", 170));
        driversHead.getChildren().setAll(head("CONDUCTOR", 0), head("CATEGORÍA", 110), head("LICENCIA", 200),
                head("VEHÍCULO", 120), head("TRASLADO ACTUAL", 0), head("ESTADO", 140));
        stopsHead.getChildren().setAll(head("N.°", 50), head("LUGAR", 0), head("TIPO", 110), head("DIRECCIÓN", 0),
                head("PREVISTA", 90), head("REAL", 90));

        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        autoRefresh.setCycleCount(Animation.INDEFINITE);
        autoRefresh.play();
        showTab();
        load();
    }

    // ---------- carga ----------

    private void load() {
        int request = ++generation;
        if (screen == null) {
            updatedLabel.setText("Cargando la flota…");
        }
        async("transport-screen", transportService::screen, result -> {
            if (request != generation) {
                return;
            }
            screen = result;
            paint();
        }, failure -> {
            if (request == generation) {
                updatedLabel.setText("Sin conexión");
                showError(message(failure, "No se pudo cargar el módulo de Transporte."));
            }
        });
    }

    /** La actualización automática solo corre si la pantalla está a la vista y no hay una acción en curso. */
    private void autoReload() {
        if (root.getScene() != null && root.isVisible() && !busy) {
            load();
        }
    }

    private void paint() {
        fillKpis(screen.kpis());
        updatedLabel.setText("●  Actualizado " + HOUR.format(screen.loadedAt()));
        showTab();
    }

    private void fillKpis(Kpis k) {
        inRouteValue.setText(String.valueOf(k.inRoute()));
        inRouteDetail.setText(k.inRoute() == 0 ? "ningún traslado en curso"
                : plural(k.inRouteHearses(), "coche fúnebre", "coches fúnebres") + " · "
                + plural(k.inRouteBuses(), "bus", "buses"));
        availableValue.setText(String.valueOf(k.available()));
        availableDetail.setText("de " + plural(k.total(), "vehículo", "vehículos") + " · en la sede");
        maintenanceValue.setText(String.valueOf(k.maintenance()));
        maintenanceDetail.setText(k.maintenanceDetail() == null ? "ninguno en el taller" : k.maintenanceDetail());
        expiredValue.setText(String.valueOf(k.expiredDocs()));
        expiredDetail.setText(k.expiredDetail() == null ? "SOAT y técnico-mecánica al día" : k.expiredDetail());
    }

    // ---------- pestañas ----------

    private void showTab() {
        show(monitorPane, tab == Tab.MONITOR);
        show(vehiclesPane, tab == Tab.VEHICLES);
        show(driversPane, tab == Tab.DRIVERS);
        show(transfersPane, tab == Tab.TRANSFERS);
        boolean admin = screen != null && screen.canManageVehicles();
        if (tab == Tab.VEHICLES) {
            mainButton.setText("+  Nuevo vehículo");
            show(mainButton, admin);
        } else {
            mainButton.setText("+  Asignar traslado");
            show(mainButton, true);
        }
        buildFilter();
        if (screen == null) {
            return;
        }
        switch (tab) {
            case MONITOR -> paintMonitor();
            case VEHICLES -> paintVehicles();
            case DRIVERS -> paintDrivers();
            case TRANSFERS -> paintTransfers();
        }
    }

    /** Vehículos: Todos / Coches fúnebres / Buses. Traslados: Hoy / Mañana / Esta semana. */
    private void buildFilter() {
        filterBox.getChildren().clear();
        filterGroup.getToggles().clear();
        if (tab == Tab.VEHICLES) {
            long hearses = screen == null ? 0 : screen.vehicles().stream().filter(v -> v.type() == VehicleType.HEARSE).count();
            long buses = screen == null ? 0 : screen.vehicles().size() - hearses;
            int all = screen == null ? 0 : screen.vehicles().size();
            addFilter("Todos · " + all, null, vehicleFilter == null);
            addFilter("Coches fúnebres · " + hearses, VehicleType.HEARSE, vehicleFilter == VehicleType.HEARSE);
            addFilter("Buses · " + buses, VehicleType.BUS, vehicleFilter == VehicleType.BUS);
        } else if (tab == Tab.TRANSFERS) {
            addFilter("Hoy · " + countDays(0), 0, dayFilter == 0);
            addFilter("Mañana · " + countDays(1), 1, dayFilter == 1);
            addFilter("Esta semana · " + countDays(7), 7, dayFilter == 7);
        }
        show(filterBox, !filterBox.getChildren().isEmpty());
        filterGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                if (old != null && filterGroup.getToggles().contains(old)) {
                    old.setSelected(true);
                }
                return;
            }
            if (tab == Tab.VEHICLES) {
                vehicleFilter = (VehicleType) toggle.getUserData();
                paintVehicles();
            } else if (tab == Tab.TRANSFERS) {
                dayFilter = (Integer) toggle.getUserData();
                selectedTransfer = null;
                paintTransfers();
            }
        });
    }

    private void addFilter(String text, Object value, boolean selected) {
        ToggleButton toggle = new ToggleButton(text);
        toggle.getStyleClass().add("segment");
        toggle.setUserData(value);
        toggle.setToggleGroup(filterGroup);
        toggle.setSelected(selected);
        filterBox.getChildren().add(toggle);
    }

    private int countDays(int days) {
        return screen == null ? 0 : transfersFor(days).size();
    }

    @FXML
    private void onMainAction() {
        if (tab == Tab.VEHICLES) {
            openVehicleDialog(null);
        } else {
            openAssignDialog();
        }
    }

    // ---------- 23 Monitor ----------

    private void paintMonitor() {
        Transfer active = monitorTransfer();
        showRoute(monitorRouteSlot, monitorRouteHint, active, "No hay traslados en ruta ni programados para hoy.",
                active == null ? "" : "Contrato " + active.contractNumber() + " · ");
        paintDetailCard(active);

        fleetCount.setText(plural(screen.vehicles().size(), "vehículo", "vehículos"));
        List<Node> rows = new ArrayList<>();
        for (VehicleCard v : screen.vehicles()) {
            Transfer t = transferOfVehicle(v.id());
            Label state = stateChip(v.state().getLabel(), vehicleTone(v.state()));
            HBox row = new HBox(
                    col(new VBox(1, label(typeName(v.type()), "t-strong"), label(capacityText(v), "t-muted")), 0),
                    col(label(v.plate(), "t-text"), 130),
                    col(label(orDash(v.driverName()), "t-text"), 200),
                    col(wrapped(t == null ? v.where() : "Contrato " + t.contractNumber() + " · "
                            + orDash(t.destination()), "t-text"), 0),
                    col(state, 170));
            row.getStyleClass().add("t-row");
            if (t != null) {
                row.setOnMouseClicked(e -> {
                    selectedTransfer = key(t);
                    paintMonitor();
                });
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            rows.add(wrapped(screen.canManageVehicles()
                    ? "Todavía no hay vehículos. Regístrelos en la pestaña Vehículos con \"+ Nuevo vehículo\"."
                    : "Todavía no hay vehículos registrados. El Administrador Maestro los registra en la pestaña Vehículos.",
                    "empty-table"));
        }
        fleetRows.getChildren().setAll(rows);
    }

    /** El elegido; si no hay, el primero en ruta; si no, el próximo de hoy. */
    private Transfer monitorTransfer() {
        Transfer chosen = byKey(selectedTransfer);
        if (chosen != null) {
            return chosen;
        }
        LocalDate today = screen.today();
        return screen.transfers().stream().filter(t -> t.status() == TransportStatus.IN_PROGRESS).findFirst()
                .orElse(screen.transfers().stream()
                        .filter(t -> t.status() == TransportStatus.SCHEDULED && t.scheduledAt().toLocalDate().equals(today))
                        .findFirst().orElse(null));
    }

    /** Panel oscuro: vehículo principal, conductor, contrato, llegada estimada y recorrido. */
    private void paintDetailCard(Transfer t) {
        monitorDetail.getChildren().clear();
        if (t == null) {
            monitorDetail.getChildren().addAll(label("Sin traslados activos", "detail-plate"),
                    wrapped("Cuando un traslado salga aparecerá aquí con su conductor, su contrato y el recorrido.",
                            "detail-sub"));
            return;
        }
        Unit main = mainUnit(t);
        Label chip = label("●  " + statusLabel(t.status()), "detail-chip");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(label(main.plate(), "detail-plate"), spacer, chip);
        top.setAlignment(Pos.CENTER_LEFT);
        Label sub = label(typeName(main.type()) + (t.units().size() > 1
                ? " · cortejo con " + plural(t.units().size() - 1, "vehículo más", "vehículos más") : ""), "detail-sub");

        GridPane info = new GridPane();
        info.getStyleClass().add("detail-info");
        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        right.setHgrow(Priority.ALWAYS);
        right.setHalignment(javafx.geometry.HPos.RIGHT);
        info.getColumnConstraints().addAll(left, right);
        Stop next = t.nextStop();
        info.addRow(0, label("Conductor", "detail-key"), label(main.driverName(), "detail-value"));
        info.addRow(1, label("Contrato", "detail-key"), label(t.contractNumber() + " · " + t.deceasedName(), "detail-value"));
        info.addRow(2, label(t.status() == TransportStatus.SCHEDULED ? "Salida" : "Próxima parada", "detail-key"),
                label(t.status() == TransportStatus.SCHEDULED ? when(t.scheduledAt())
                        : next == null ? "Llegó a todas las paradas"
                        : next.placeName() + (next.plannedAt() == null ? "" : " · " + HOUR.format(next.plannedAt())),
                        "detail-value"));

        VBox timeline = new VBox(12);
        timeline.getStyleClass().add("timeline-box");
        if (t.stops().isEmpty()) {
            timeline.getChildren().add(wrapped("Sin paradas registradas.", "tl-sub"));
        }
        for (Stop s : t.stops()) {
            boolean done = s.arrivedAt() != null;
            boolean current = !done && t.status() == TransportStatus.IN_PROGRESS && s.equals(next);
            Circle dot = new Circle(6);
            dot.getStyleClass().addAll("tl-dot", done ? "done" : current ? "current" : "pending");
            VBox text = new VBox(1, label(stopTitle(s), "tl-title"),
                    label(orDash(s.address()) + (current ? " · en camino" : ""), "tl-sub"));
            Pane gap = new Pane();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Label time = label(done ? HOUR.format(s.arrivedAt()) : s.plannedAt() == null ? "—"
                    : HOUR.format(s.plannedAt()), "tl-time");
            HBox line = new HBox(12, dot, text, gap, time);
            line.setAlignment(Pos.TOP_LEFT);
            HBox.setMargin(dot, new Insets(4, 0, 0, 0));
            timeline.getChildren().add(line);
        }
        monitorDetail.getChildren().addAll(top, sub, info, label("RECORRIDO", "detail-section"), timeline,
                actionsBar(t, true));
    }

    // ---------- 23.1 Vehículos ----------

    private void paintVehicles() {
        List<Node> cards = new ArrayList<>();
        for (VehicleCard v : screen.vehicles()) {
            if (vehicleFilter == null || v.type() == vehicleFilter) {
                cards.add(vehicleCard(v));
            }
        }
        vehicleCards.getChildren().setAll(cards);
        String empty = screen.vehicles().isEmpty()
                ? (screen.canManageVehicles() ? "Todavía no hay vehículos. Use \"+ Nuevo vehículo\" para registrar el primero."
                : "Todavía no hay vehículos registrados. El Administrador Maestro los registra aquí.")
                : cards.isEmpty() ? "No hay vehículos de este tipo." : "";
        vehiclesEmpty.setText(empty);
        show(vehiclesEmpty, !empty.isEmpty());
    }

    private Node vehicleCard(VehicleCard v) {
        Label caption = label(typeName(v.type()).toUpperCase(ES), "vehicle-caption");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(caption, spacer, stateChip(v.state().getLabel(), vehicleTone(v.state())));
        top.setAlignment(Pos.CENTER_LEFT);
        Label plate = label(v.plate(), "vehicle-plate");
        Label capacity = label(capacityText(v), "t-muted");

        boolean alert = v.state() == VehicleState.EXPIRED_DOCS;
        VBox where = new VBox(label(v.state() == VehicleState.IN_ROUTE ? "UBICACIÓN" : "DÓNDE ESTÁ", "where-caption"),
                wrapped(v.where(), "where-text"));
        where.getStyleClass().add("where-box");
        if (alert) {
            where.getStyleClass().add("alert");
        }

        VBox card = new VBox(10, top, new VBox(0, plate, capacity), where,
                docRow("SOAT", v.soat()), docRow("Técnico-mecánica", v.technicalReview()));
        Label driver = label("Conductor: " + orDash(v.driverName()), "t-muted");
        Pane gap = new Pane();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox footer = new HBox(8, driver, gap);
        footer.setAlignment(Pos.CENTER_LEFT);
        if (screen.canManageVehicles()) {
            Button edit = linkButton("Editar");
            edit.setOnAction(e -> openVehicleDialog(v));
            footer.getChildren().add(edit);
            if (v.status() != VehicleStatus.IN_SERVICE) {
                boolean inShop = v.status() == VehicleStatus.MAINTENANCE;
                Button shop = linkButton(inShop ? "Marcar disponible" : "A mantenimiento");
                shop.setOnAction(e -> confirmMaintenance(v, !inShop));
                footer.getChildren().add(shop);
            }
        }
        card.getChildren().add(footer);
        card.getStyleClass().add("vehicle-card");
        if (alert) {
            card.getStyleClass().add("blocked");
        }
        card.setPrefWidth(340);
        card.setMinWidth(300);
        return card;
    }

    /** "SOAT … vence 14/03/2027" con una barra que se acorta a medida que se acerca el vencimiento. */
    private Node docRow(String name, DocStatus doc) {
        Label caption = label(name, "t-muted");
        Pane gap = new Pane();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label date = label(doc.expiry() == null ? "sin fecha" : (doc.expired() ? "venció " : "vence ")
                + DATE.format(doc.expiry()), doc.expired() ? "t-alert" : doc.expiringSoon() ? "t-warn" : "t-text");
        HBox line = new HBox(caption, gap, date);
        ProgressBar bar = new ProgressBar(remaining(doc));
        bar.getStyleClass().add("doc-bar");
        if (doc.expired()) {
            bar.getStyleClass().add("alert");
        } else if (doc.expiringSoon()) {
            bar.getStyleClass().add("warn");
        }
        bar.setMaxWidth(Double.MAX_VALUE);
        return new VBox(4, line, bar);
    }

    private double remaining(DocStatus doc) {
        if (doc.expiry() == null || doc.expired()) {
            return 0.02;
        }
        long days = ChronoUnit.DAYS.between(screen.today(), doc.expiry());
        return Math.max(0.04, Math.min(1, days / 365.0));
    }

    private void confirmMaintenance(VehicleCard v, boolean maintenance) {
        boolean ok = confirm(maintenance ? "¿Enviar " + v.plate() + " a mantenimiento?" : "¿Marcar " + v.plate() + " como disponible?",
                maintenance ? "Mientras esté en mantenimiento no se podrá asignar a ningún traslado."
                        : "Volverá a aparecer para asignar, si su SOAT y su técnico-mecánica están vigentes.",
                maintenance ? "Enviar a mantenimiento" : "Marcar disponible");
        if (ok) {
            act(() -> {
                transportService.setMaintenance(v.id(), maintenance);
                return null;
            }, maintenance ? v.plate() + " quedó en mantenimiento." : v.plate() + " quedó disponible.");
        }
    }

    // ---------- 23.2 Conductores ----------

    private void paintDrivers() {
        List<DriverCard> drivers = screen.drivers();
        long inRoute = drivers.stream().filter(d -> d.state() == DriverState.IN_ROUTE).count();
        long soon = drivers.stream().filter(d -> d.license().expiringSoon()).count();
        long blocked = drivers.stream().filter(d -> d.state() == DriverState.BLOCKED).count();
        driversCount.setText(plural(drivers.size(), "conductor activo", "conductores activos") + " · " + inRoute
                + " en ruta · " + plural((int) soon, "licencia por vencer", "licencias por vencer") + " · "
                + plural((int) blocked, "bloqueado", "bloqueados"));
        List<Node> rows = new ArrayList<>();
        for (DriverCard d : drivers) {
            Label licence = label(d.license().expiry() == null ? "sin fecha" : "vence " + DATE.format(d.license().expiry()),
                    d.license().expired() ? "t-alert" : d.license().expiringSoon() ? "t-warn" : "t-text");
            ProgressBar bar = new ProgressBar(remaining(d.license()));
            bar.getStyleClass().add("doc-bar");
            if (d.license().expired()) {
                bar.getStyleClass().add("alert");
            } else if (d.license().expiringSoon()) {
                bar.getStyleClass().add("warn");
            }
            bar.setPrefWidth(160);
            HBox row = new HBox(
                    col(new VBox(1, label(d.name(), "t-strong"),
                            label("Traslados: " + d.transfersToday() + " hoy", "t-muted")), 0),
                    col(label(orDash(d.category()), "t-text"), 110),
                    col(new VBox(4, licence, bar), 200),
                    col(label(orDash(d.vehiclePlate()), "t-text"), 120),
                    col(wrapped(d.currentTransfer(), "t-text"), 0),
                    col(stateChip(d.state().getLabel(), switch (d.state()) {
                        case IN_ROUTE -> "state-dark";
                        case AVAILABLE -> "state-ok";
                        case BLOCKED -> "state-alert";
                    }), 140));
            row.getStyleClass().add("t-row");
            rows.add(row);
        }
        if (rows.isEmpty()) {
            rows.add(wrapped("No hay conductores activos. Regístrelos en Seguridad: personal y cuentas con el cargo Conductor.",
                    "empty-table"));
        }
        driverRows.getChildren().setAll(rows);
    }

    // ---------- 23.3 Traslados ----------

    private void paintTransfers() {
        List<Transfer> list = transfersFor(dayFilter);
        transfersTitle.setText(switch (dayFilter) {
            case 0 -> "Traslados de hoy";
            case 1 -> "Traslados de mañana";
            default -> "Traslados de la semana";
        });
        Transfer selected = byKey(selectedTransfer);
        if (selected == null || !list.contains(selected)) {
            selected = list.isEmpty() ? null : list.get(0);
            selectedTransfer = selected == null ? null : key(selected);
        }
        List<Node> items = new ArrayList<>();
        for (Transfer t : list) {
            items.add(transferItem(t, t.equals(selected)));
        }
        if (items.isEmpty()) {
            items.add(wrapped("No hay traslados para este día. Use \"+ Asignar traslado\".", "empty-table"));
        }
        transferList.getChildren().setAll(items);

        transferRouteTitle.setText(selected == null ? "Ruta del traslado" : "Traslado " + selected.contractNumber() + " · ruta");
        showRoute(transferRouteSlot, transferRouteHint, selected, "Elija un traslado para ver su ruta.", "");
        transferActions.getChildren().setAll(selected == null ? List.of() : actionsBar(selected, false).getChildren());

        List<Node> rows = new ArrayList<>();
        if (selected != null) {
            for (Stop s : selected.stops()) {
                HBox row = new HBox(
                        col(label(String.valueOf(s.order()), "t-text"), 50),
                        col(label(s.placeName(), "t-strong"), 0),
                        col(label(s.type().getDisplayName(), "t-text"), 110),
                        col(wrapped(orDash(s.address()), "t-text"), 0),
                        col(label(s.plannedAt() == null ? "—" : HOUR.format(s.plannedAt()), "t-text"), 90),
                        col(label(s.arrivedAt() == null ? "—" : HOUR.format(s.arrivedAt()), "t-strong"), 90));
                row.getStyleClass().add("t-row");
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            rows.add(wrapped(selected == null ? "" : "Este traslado no tiene paradas registradas.", "empty-table"));
        }
        stopRows.getChildren().setAll(rows);
    }

    private Node transferItem(Transfer t, boolean selected) {
        Label time = label(when(t.scheduledAt()), "transfer-time");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(time, spacer, stateChip(statusLabel(t.status()), statusTone(t.status())));
        top.setAlignment(Pos.CENTER_LEFT);
        String plates = String.join(" + ", t.units().stream().map(Unit::plate).toList());
        VBox item = new VBox(3, top,
                label(t.contractNumber() + " · " + t.deceasedName(), "transfer-title"),
                label(orDash(t.origin()) + " → " + orDash(t.destination()), "transfer-sub"),
                label("Vehículos: " + plates, "transfer-vehicles"));
        item.getStyleClass().add("transfer-item");
        if (selected) {
            item.getStyleClass().add("selected");
        }
        item.setOnMouseClicked(e -> {
            selectedTransfer = key(t);
            paintTransfers();
        });
        return item;
    }

    private List<Transfer> transfersFor(int days) {
        LocalDate today = screen.today();
        Predicate<Transfer> inRange = switch (days) {
            case 0 -> t -> t.scheduledAt().toLocalDate().equals(today) || t.status() == TransportStatus.IN_PROGRESS;
            case 1 -> t -> t.scheduledAt().toLocalDate().equals(today.plusDays(1));
            default -> t -> true;
        };
        return screen.transfers().stream().filter(inRange).toList();
    }

    // ---------- mapa (Leaflet + OpenStreetMap + OpenRouteService) ----------

    /**
     * Sin traslado muestra el mapa vacío centrado en la ciudad y el aviso debajo del título. Con
     * traslado pone de inmediato la línea de paradas y, en segundo plano, busca las coordenadas y la
     * ruta. Si hay al menos dos paradas ubicadas, cambia la línea por el mapa en el mismo espacio. Si
     * no hay clave o internet, se queda la línea de paradas.
     */
    private void showRoute(StackPane slot, Label hint, Transfer t, String emptyText, String prefix) {
        String key = mapKey(t);
        boolean sameMap = mapKeys.containsKey(slot) && Objects.equals(key, mapKeys.get(slot))
                && !slot.getChildren().isEmpty() && slot.getChildren().get(0) instanceof RouteMapPane;
        if (sameMap) {
            return;
        }
        mapKeys.put(slot, key);
        if (t == null) {
            RouteMapPane map = maps.computeIfAbsent(slot, s -> new RouteMapPane());
            slot.getChildren().setAll(map);
            map.clear();
            hint.setText(emptyText);
            return;
        }
        slot.getChildren().setAll(routeView(t, emptyText));
        hint.setText(prefix + (routeMapService.isConfigured() ? "buscando el mapa…" : "recorrido por paradas"));
        if (t.stops().size() < 2) {
            hint.setText(prefix + "recorrido por paradas");
            return;
        }
        async("transport-map", () -> routeMapService.prepare(t), data -> {
            if (!Objects.equals(key, mapKeys.get(slot))) {
                return; // mientras se buscaba, se eligió otro traslado
            }
            if (data.stops().size() < 2) {
                hint.setText(prefix + mapProblem(data));
                return;
            }
            RouteMapPane map = maps.computeIfAbsent(slot, s -> new RouteMapPane());
            slot.getChildren().setAll(map);
            map.show(data, t);
            hint.setText(prefix + routeText(data));
        }, error -> hint.setText(prefix + "recorrido por paradas (no se pudo cargar el mapa)"));
    }

    /** Cambia si cambia el traslado, su estado o sus llegadas. */
    private static String mapKey(Transfer t) {
        if (t == null) {
            return null;
        }
        StringBuilder key = new StringBuilder(t.contractNumber()).append('|').append(t.scheduledAt()).append('|')
                .append(t.status());
        for (Stop s : t.stops()) {
            key.append('|').append(s.placeName()).append(s.arrivedAt() == null ? "" : "✓");
        }
        return key.toString();
    }

    private String mapProblem(RouteMapService.MapData data) {
        if (!routeMapService.isConfigured()) {
            return "recorrido por paradas · falta la clave del mapa (ORS_API_KEY)";
        }
        return data.notFound().isEmpty() ? "recorrido por paradas"
                : "recorrido por paradas · no se encontró en el mapa: " + String.join(", ", data.notFound());
    }

    private String routeText(RouteMapService.MapData data) {
        String text = data.route() == null ? "ruta aproximada en línea recta"
                : String.format(Locale.forLanguageTag("es-CO"), "%.1f km · %d min en carro",
                data.route().km(), data.route().minutes());
        return data.notFound().isEmpty() ? text : text + " · sin ubicar: " + String.join(", ", data.notFound());
    }

    // ---------- ruta por paradas (espacio del mapa) ----------

    /**
     * Línea de paradas: llena hasta la última llegada registrada, con el vehículo en la parada que
     * sigue. Se ve mientras carga el mapa y se queda si no hay clave, internet o paradas ubicadas.
     */
    private Node routeView(Transfer t, String emptyText) {
        if (t == null || t.stops().isEmpty()) {
            Label empty = wrapped(t == null ? emptyText : "Este traslado no tiene paradas registradas.", "route-hint");
            StackPane box = new StackPane(empty);
            box.setMinHeight(170);
            return box;
        }
        Stop next = t.nextStop();
        HBox line = new HBox();
        line.setAlignment(Pos.TOP_CENTER);
        List<Stop> stops = t.stops();
        for (int i = 0; i < stops.size(); i++) {
            Stop s = stops.get(i);
            boolean done = s.arrivedAt() != null;
            boolean current = t.status() == TransportStatus.IN_PROGRESS && s.equals(next);
            Circle dot = new Circle(9);
            dot.getStyleClass().addAll("stop-dot", done ? "done" : current ? "current" : "pending");
            Label marker = label(current ? mainUnit(t).plate() + "  ▸" : "", "vehicle-marker");
            marker.setVisible(current);
            VBox column = new VBox(8, marker, dot, wrapped(s.placeName(), "stop-place"),
                    label(s.type().getDisplayName() + " · " + (done ? "llegó " + HOUR.format(s.arrivedAt())
                            : s.plannedAt() == null ? "sin hora" : "prevista " + HOUR.format(s.plannedAt())), "stop-time"));
            column.setAlignment(Pos.TOP_CENTER);
            column.setMinWidth(120);
            column.setPrefWidth(150);
            column.setMaxWidth(190);
            line.getChildren().add(column);
            if (i < stops.size() - 1) {
                Region segment = new Region();
                segment.getStyleClass().add("stop-line");
                if (stops.get(i + 1).arrivedAt() != null) {
                    segment.getStyleClass().add("done");
                }
                HBox.setHgrow(segment, Priority.ALWAYS);
                segment.setMinWidth(30);
                // La línea pasa por el centro de los puntos (debajo de la etiqueta del vehículo).
                HBox.setMargin(segment, new Insets(36, -20, 0, -20));
                line.getChildren().add(segment);
            }
        }
        // Las columnas se pintan encima de las líneas (viewOrder no cambia el orden del HBox).
        line.getChildren().stream().filter(n -> n instanceof VBox).forEach(n -> n.setViewOrder(-1));
        VBox box = new VBox(10, line, label("Recorrido planeado del traslado " + t.contractNumber(), "route-hint"));
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ---------- salida, llegadas, cierre y cancelación ----------

    /** Botones según el estado: Registrar salida / Llegó a … / Cerrar traslado / Cancelar. */
    private HBox actionsBar(Transfer t, boolean dark) {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        List<Long> ids = t.transportIds();
        if (t.status() == TransportStatus.SCHEDULED) {
            Button start = actionButton("Registrar salida", dark, false);
            start.setOnAction(e -> {
                if (confirm("¿Registrar la salida del traslado " + t.contractNumber() + "?",
                        "Los vehículos " + String.join(", ", t.units().stream().map(Unit::plate).toList())
                                + " quedan en ruta con la hora actual.", "Registrar salida")) {
                    act(() -> {
                        transportService.start(ids);
                        return null;
                    }, "Salida registrada: " + t.contractNumber() + " va en ruta.");
                }
            });
            Button cancel = actionButton("Cancelar", dark, true);
            cancel.setOnAction(e -> {
                if (confirm("¿Cancelar el traslado " + t.contractNumber() + "?",
                        "El traslado queda cancelado (no se borra) y los vehículos y conductores quedan libres en ese horario.",
                        "Cancelar traslado")) {
                    act(() -> {
                        transportService.cancel(ids);
                        return null;
                    }, "Traslado " + t.contractNumber() + " cancelado.");
                }
            });
            bar.getChildren().addAll(start, cancel);
        } else if (t.status() == TransportStatus.IN_PROGRESS) {
            Stop next = t.nextStop();
            if (next != null) {
                Button arrive = actionButton("Llegó a " + shorten(next.placeName()), dark, false);
                arrive.setOnAction(e -> {
                    boolean last = next.equals(t.stops().get(t.stops().size() - 1));
                    if (confirm("¿Registrar la llegada a " + next.placeName() + "?",
                            last ? "Es la última parada: el traslado queda completado y los vehículos vuelven a disponibles."
                                    : "Se guarda la hora real de llegada a esta parada.", "Registrar llegada")) {
                        act(() -> {
                            transportService.arrive(ids, next.order());
                            return null;
                        }, "Llegada registrada en " + next.placeName() + ".");
                    }
                });
                bar.getChildren().add(arrive);
            }
            Button close = actionButton("Cerrar traslado", dark, true);
            close.setOnAction(e -> {
                if (confirm("¿Cerrar el traslado " + t.contractNumber() + "?",
                        "El traslado queda completado aunque falten llegadas por registrar, y los vehículos vuelven a disponibles.",
                        "Cerrar traslado")) {
                    act(() -> {
                        transportService.complete(ids);
                        return null;
                    }, "Traslado " + t.contractNumber() + " completado.");
                }
            });
            bar.getChildren().add(close);
        }
        return bar;
    }

    private static Button actionButton(String text, boolean dark, boolean secondary) {
        Button button = new Button(text);
        if (dark) {
            button.getStyleClass().add("detail-button");
            if (secondary) {
                button.getStyleClass().add("ghost");
            }
        } else {
            button.getStyleClass().add(secondary ? "row-button" : "small-dark-button");
        }
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** Corre la acción en otro hilo; al terminar recarga la pantalla y muestra el aviso. */
    private void act(Callable<Object> work, String done) {
        busy = true;
        async("transport-action", work, result -> {
            busy = false;
            showToast(done);
            load();
        }, failure -> {
            busy = false;
            showError(message(failure, "No se pudo guardar. Revise la conexión e intente de nuevo."));
            load();
        });
    }

    // ---------- Nuevo vehículo / Editar vehículo ----------

    private void openVehicleDialog(VehicleCard current) {
        boolean editing = current != null;
        TextField plate = new TextField(editing ? current.plate() : "");
        plate.setPromptText("KLM-519");
        ComboBox<VehicleType> type = new ComboBox<>();
        type.getItems().addAll(VehicleType.HEARSE, VehicleType.BUS);
        type.getStyleClass().add("form-combo");
        type.setConverter(converter(TransportController::typeName, ""));
        type.setPromptText("Seleccione");
        type.setValue(editing ? current.type() : null);
        TextField capacity = new TextField(editing && current.capacity() != null ? String.valueOf(current.capacity()) : "");
        capacity.setPromptText("Ataúdes o pasajeros");

        DatePicker soat = datePicker(editing ? current.soat().expiry() : null);
        DatePicker review = datePicker(editing ? current.technicalReview().expiry() : null);
        Path[] soatFile = new Path[1];
        Path[] reviewFile = new Path[1];
        Button soatButton = fileButton(editing && current.soatFileUrl() != null ? "Conservar el archivo actual" : "Adjuntar archivo",
                soatFile);
        Button reviewButton = fileButton(editing && current.technicalReviewFileUrl() != null
                ? "Conservar el archivo actual" : "Adjuntar archivo", reviewFile);

        Form form = new Form()
                .section("Datos del vehículo", 3)
                .field("PLACA *", plate)
                .field("TIPO *", type)
                .field("CAPACIDAD *", capacity)
                .section("Documentos", 2)
                .field("VENCE EL SOAT *", soat)
                .field("ARCHIVO DEL SOAT" + (editing ? "" : " *"), soatButton)
                .field("VENCE LA TÉCNICO-MECÁNICA *", review)
                .field("ARCHIVO DE LA TÉCNICO-MECÁNICA" + (editing ? "" : " *"), reviewButton)
                .note(note("Se guarda solo la ubicación del archivo. Si un documento ya venció, el vehículo se "
                        + "guarda pero no podrá asignarse a traslados hasta renovarlo."));
        Dialog<ButtonType> dialog = formDialog(editing ? "Editar vehículo" : "Nuevo vehículo",
                editing ? "Placa, capacidad y vigencia de documentos de " + current.plate()
                        : "Coche fúnebre o bus con su SOAT y su revisión técnico-mecánica", form, 760);
        runForm(dialog, form, editing ? "Guardar cambios" : "Registrar vehículo", () -> {
            VehicleData data = new VehicleData(plate.getText(), type.getValue(),
                    parseInteger(capacity.getText(), "La capacidad"), soat.getValue(), soatFile[0],
                    review.getValue(), reviewFile[0]);
            return editing ? transportService.updateVehicle(current.id(), data) : transportService.registerVehicle(data);
        }, saved -> {
            showToast(saved.warning() != null ? saved.warning()
                    : editing ? "Cambios guardados." : "Vehículo registrado y disponible.");
            load();
        });
    }

    private Button fileButton(String text, Path[] target) {
        Button button = new Button(text);
        button.getStyleClass().add("file-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Seleccione el documento (PDF, PNG o JPG)");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF o imagen", "*.pdf", "*.png", "*.jpg", "*.jpeg"));
            File file = chooser.showOpenDialog(button.getScene().getWindow());
            if (file != null) {
                target[0] = file.toPath();
                button.setText(file.getName());
            }
        });
        return button;
    }

    // ---------- Asignar traslado ----------

    private void openAssignDialog() {
        TextField contractSearch = new TextField();
        contractSearch.setPromptText("Número de contrato o difunto");
        ComboBox<Option> contract = new ComboBox<>();
        contract.setPromptText("Seleccione el contrato");
        contract.getStyleClass().add("form-combo");
        contract.setConverter(converter(o -> o.label() + " · " + o.detail(), ""));
        DatePicker date = datePicker(today());
        ComboBox<LocalTime> time = timeCombo(nextQuarter());

        ComboBox<Candidate> hearse = candidateCombo("Sin coche fúnebre");
        ComboBox<Candidate> hearseDriver = candidateCombo("Conductor del coche");
        ComboBox<Candidate> bus = candidateCombo("Sin bus incluido");
        ComboBox<Candidate> busDriver = candidateCombo("Conductor del bus");
        VBox extraRows = new VBox(8);
        List<ComboBox<Candidate>[]> extras = new ArrayList<>();

        VBox stopBox = new VBox(8);
        List<StopRow> stops = new ArrayList<>();
        Runnable[] reloadCandidates = new Runnable[1];

        Label contractNote = note("");
        Button addBus = new Button("+  Agregar bus adicional");
        addBus.getStyleClass().add("small-dark-button");
        Button addStop = new Button("+  Agregar parada");
        addStop.getStyleClass().add("small-dark-button");

        // Candidatos de los combos (se recargan al cambiar la fecha, la hora o las horas de las paradas).
        Candidates[] loaded = new Candidates[1];
        Runnable fillCombos = () -> {
            if (loaded[0] == null) {
                return;
            }
            setCandidates(hearse, loaded[0].hearses());
            setCandidates(bus, loaded[0].buses());
            setCandidates(hearseDriver, loaded[0].drivers());
            setCandidates(busDriver, loaded[0].drivers());
            for (ComboBox<Candidate>[] pair : extras) {
                setCandidates(pair[0], loaded[0].buses());
                setCandidates(pair[1], loaded[0].drivers());
            }
        };

        addBus.setOnAction(e -> {
            @SuppressWarnings("unchecked")
            ComboBox<Candidate>[] pair = new ComboBox[]{candidateCombo("Bus adicional"), candidateCombo("Conductor")};
            Button remove = new Button("✕");
            remove.getStyleClass().add("chip-remove");
            HBox row = new HBox(10, pair[0], pair[1], remove);
            row.getStyleClass().add("assign-row");
            HBox.setHgrow(pair[0], Priority.ALWAYS);
            HBox.setHgrow(pair[1], Priority.ALWAYS);
            remove.setOnAction(x -> {
                extras.remove(pair);
                extraRows.getChildren().remove(row);
                sizeDialog(extraRows);
            });
            extras.add(pair);
            extraRows.getChildren().add(row);
            fillCombos.run();
            sizeDialog(extraRows);
        });

        Consumer<StopRow> addStopRow = row -> {
            stops.add(row);
            stopBox.getChildren().add(row.node);
            row.remove.setOnAction(x -> {
                stops.remove(row);
                stopBox.getChildren().remove(row.node);
                sizeDialog(stopBox);
                reloadCandidates[0].run();
            });
            row.time.valueProperty().addListener((o, a, b) -> reloadCandidates[0].run());
        };
        addStopRow.accept(new StopRow(StopType.ORIGIN, DEFAULT_ORIGIN, nextQuarter()));
        addStopRow.accept(new StopRow(StopType.CEREMONY, "", null));
        addStopRow.accept(new StopRow(StopType.DESTINATION, "", null));
        addStop.setOnAction(e -> {
            addStopRow.accept(new StopRow(StopType.PICKUP, "", null));
            sizeDialog(stopBox);
        });

        // Al elegir el contrato: qué transporte ya tiene, cuántos buses cobra la venta y salida sugerida.
        contract.valueProperty().addListener((obs, old, value) -> {
            contractNote.setText("");
            hearse.setDisable(false);
            bus.setDisable(false);
            if (value == null) {
                return;
            }
            async("transport-contract", () -> transportService.contractTransport(value.id()), info -> {
                if (!Objects.equals(contract.getValue(), value)) {
                    return;
                }
                applyContract(info, hearse, hearseDriver, bus, busDriver, contractNote, date, time);
            }, failure -> contractNote.setText(message(failure, "No se pudo leer el contrato.")));
        });

        reloadCandidates[0] = () -> {
            LocalDateTime from = at(date.getValue(), time.getValue());
            if (from == null) {
                return;
            }
            LocalDateTime until = stops.stream().map(r -> at(date.getValue(), r.time.getValue()))
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(from);
            async("transport-candidates", () -> transportService.candidates(from, until), result -> {
                loaded[0] = result;
                fillCombos.run();
            }, failure -> contractNote.setText(message(failure, "No se pudo revisar la disponibilidad.")));
        };
        date.valueProperty().addListener((o, a, b) -> reloadCandidates[0].run());
        time.valueProperty().addListener((o, a, b) -> {
            StopRow first = stops.isEmpty() ? null : stops.get(0);
            if (first != null && first.type.getValue() == StopType.ORIGIN && b != null) {
                first.time.setValue(b);
            }
            reloadCandidates[0].run();
        });
        bindSearch(contractSearch, contract, transportService::searchContracts);
        reloadCandidates[0].run();

        Form form = new Form()
                .section("Servicio", 4)
                .field("BUSCAR CONTRATO", contractSearch)
                .field("CONTRATO *", contract)
                .field("FECHA DE SALIDA *", date)
                .field("HORA DE SALIDA *", time)
                .note(contractNote)
                .section("Transporte incluido", 4)
                .field("COCHE FÚNEBRE", hearse)
                .field("CONDUCTOR", hearseDriver)
                .field("BUS INCLUIDO", bus)
                .field("CONDUCTOR", busDriver)
                .section("Buses adicionales", 1)
                .field("CADA UNO CON SU CONDUCTOR", new VBox(8, extraRows, addBus))
                .section("Paradas del recorrido", 1)
                .field("TIPO · LUGAR · DIRECCIÓN · HORA PREVISTA", new VBox(8, stopBox, addStop))
                .note(note("Solo se pueden elegir los vehículos con SOAT, técnico-mecánica vigentes y fuera de "
                        + "mantenimiento, y los conductores con licencia vigente. Un vehículo o conductor "
                        + "ocupado entre la salida y la última parada no se puede asignar."));
        Dialog<ButtonType> dialog = formDialog("Asignar traslado",
                "Coche fúnebre y bus incluidos, buses adicionales y paradas del servicio", form, 1040);
        runForm(dialog, form, "Asignar traslado", () -> {
            Option chosen = contract.getValue();
            if (chosen == null) {
                throw new InvalidDataException("Seleccione el contrato.");
            }
            LocalDateTime start = at(date.getValue(), time.getValue());
            List<TransferData.Assignment> vehicles = new ArrayList<>();
            addAssignment(vehicles, hearse, hearseDriver, true, "el coche fúnebre");
            addAssignment(vehicles, bus, busDriver, true, "el bus incluido");
            for (ComboBox<Candidate>[] pair : extras) {
                addAssignment(vehicles, pair[0], pair[1], false, "el bus adicional");
            }
            List<TransferData.StopData> stopData = new ArrayList<>();
            for (StopRow row : stops) {
                if (row.place.getText() == null || row.place.getText().isBlank()) {
                    continue; // las filas sin lugar no se guardan
                }
                stopData.add(new TransferData.StopData(row.type.getValue(), row.place.getText(), row.address.getText(),
                        at(date.getValue(), row.time.getValue())));
            }
            return transportService.assign(new TransferData(chosen.id(), start, vehicles, stopData, null));
        }, saved -> {
            showToast(saved.warning() != null ? saved.warning() : "Traslado asignado.");
            selectedTransfer = null;
            load();
        });
    }

    private void applyContract(ContractTransport info, ComboBox<Candidate> hearse, ComboBox<Candidate> hearseDriver,
                               ComboBox<Candidate> bus, ComboBox<Candidate> busDriver, Label note,
                               DatePicker date, ComboBox<LocalTime> time) {
        List<String> parts = new ArrayList<>();
        if (info.hasIncludedHearse()) {
            hearse.setValue(null);
            hearseDriver.setValue(null);
            hearse.setDisable(true);
            parts.add("ya tiene su coche fúnebre incluido");
        }
        if (info.hasIncludedBus()) {
            bus.setValue(null);
            busDriver.setValue(null);
            bus.setDisable(true);
            parts.add("ya tiene su bus incluido");
        }
        parts.add("la venta cobra " + plural((int) info.chargedBuses(), "bus adicional", "buses adicionales")
                + (info.additionalBuses() > 0 ? " y hay " + info.additionalBuses() + " asignado(s)" : ""));
        if (info.suggestedDeparture() != null && !info.suggestedDeparture().toLocalDate().isBefore(today())) {
            date.setValue(info.suggestedDeparture().toLocalDate());
            time.setValue(quarter(info.suggestedDeparture().toLocalTime()));
            parts.add("salida sugerida al terminar la velación (" + when(info.suggestedDeparture()) + ")");
        }
        note.setText("Contrato " + info.contractNumber() + ": " + String.join(", ", parts) + ".");
    }

    private static void addAssignment(List<TransferData.Assignment> list, ComboBox<Candidate> vehicle,
                                      ComboBox<Candidate> driver, boolean included, String what) {
        if (vehicle.getValue() == null && driver.getValue() == null) {
            return;
        }
        if (vehicle.getValue() == null) {
            throw new InvalidDataException("Elija " + what + " o quite su conductor.");
        }
        if (driver.getValue() == null) {
            throw new InvalidDataException("Elija el conductor para " + what + " " + vehicle.getValue().label() + ".");
        }
        list.add(new TransferData.Assignment(vehicle.getValue().id(), driver.getValue().id(), included));
    }

    /** Una fila de parada del diálogo. */
    private static final class StopRow {
        private final ComboBox<StopType> type = new ComboBox<>();
        private final TextField place = new TextField();
        private final TextField address = new TextField();
        private final ComboBox<LocalTime> time;
        private final Button remove = new Button("✕");
        private final HBox node;

        StopRow(StopType stopType, String placeName, LocalTime planned) {
            type.getItems().addAll(StopType.values());
            type.setValue(stopType);
            type.getStyleClass().add("form-combo");
            type.setConverter(converter(StopType::getDisplayName, ""));
            type.setPrefWidth(140);
            place.setText(placeName);
            place.setPromptText("Lugar (ej. Parroquia Sagrada Familia)");
            address.setPromptText("Dirección (opcional)");
            time = timeCombo(planned);
            time.getItems().add(0, null);
            time.setPromptText("Hora");
            time.setPrefWidth(120);
            remove.getStyleClass().add("chip-remove");
            for (Node field : List.of(place, address)) {
                field.getStyleClass().add("stop-field");
            }
            HBox.setHgrow(place, Priority.ALWAYS);
            HBox.setHgrow(address, Priority.ALWAYS);
            node = new HBox(10, type, place, address, time, remove);
            node.getStyleClass().addAll("assign-row", "form-field");
        }
    }

    private static ComboBox<Candidate> candidateCombo(String prompt) {
        ComboBox<Candidate> box = new ComboBox<>();
        box.setPromptText(prompt);
        box.getStyleClass().add("form-combo");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(converter(c -> c.label() + (c.detail() == null ? "" : " · " + c.detail()), ""));
        box.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Candidate item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("blocked-option");
                setDisable(false);
                if (empty) {
                    setText(null);
                } else if (item == null) {
                    setText(prompt);
                } else {
                    setText(item.label() + (item.detail() == null ? "" : " · " + item.detail())
                            + (item.blockedReason() == null ? "" : "  —  " + item.blockedReason()));
                    if (item.blockedReason() != null) {
                        getStyleClass().add("blocked-option");
                        setDisable(true); // Se ve por qué, pero no se puede elegir
                    }
                }
            }
        });
        return box;
    }

    /** Pone las opciones (primero las disponibles) y conserva la elegida si sigue disponible. */
    private static void setCandidates(ComboBox<Candidate> box, List<Candidate> options) {
        Candidate current = box.getValue();
        List<Candidate> sorted = new ArrayList<>();
        sorted.add(null); // opción vacía para quitar la elección
        options.stream().filter(c -> c.blockedReason() == null).forEach(sorted::add);
        options.stream().filter(c -> c.blockedReason() != null).forEach(sorted::add);
        box.getItems().setAll(sorted);
        Candidate keep = current == null ? null : options.stream()
                .filter(c -> c.id().equals(current.id()) && c.blockedReason() == null).findFirst().orElse(null);
        box.setValue(keep);
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
            if (input instanceof Region region && !(input instanceof Label)) {
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

    /** Al agregar filas el diálogo crece (hasta el 72 % de la pantalla; después aparece la barra). */
    private static void sizeDialog(Node anyChild) {
        if (anyChild.getScene() != null && anyChild.getScene().getWindow() != null) {
            anyChild.getScene().getWindow().sizeToScene();
        }
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
            busy = true;
            async("transport-save", save, result -> {
                busy = false;
                saved[0] = result;
                dialog.setResult(ok);
                dialog.close();
            }, failure -> {
                busy = false;
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

    /** Buscador del diálogo: espera a que el usuario deje de escribir y consulta en otro hilo. */
    private static void bindSearch(TextField field, ComboBox<Option> box, Function<String, List<Option>> search) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        int[] requestNumber = new int[1];
        String prompt = box.getPromptText();
        Runnable refresh = () -> {
            String text = field.getText();
            int request = ++requestNumber[0];
            box.setPromptText("Buscando…");
            async("transport-dialog-search", () -> search.apply(text), options -> {
                if (request != requestNumber[0]) {
                    return;
                }
                box.setPromptText(options.isEmpty() ? "Sin resultados" : prompt);
                Option current = box.getValue();
                box.getItems().setAll(options);
                if (current != null && options.contains(current)) {
                    box.setValue(current);
                } else if (text != null && !text.isBlank() && options.size() == 1) {
                    box.setValue(options.get(0));
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

    /** Confirmación con la pregunta como encabezado y el botón con el nombre de la acción. */
    private boolean confirm(String question, String detail, String actionText) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(window());
        alert.setTitle("Transporte");
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

    private static ComboBox<LocalTime> timeCombo(LocalTime initial) {
        ComboBox<LocalTime> box = new ComboBox<>();
        box.getStyleClass().add("form-combo");
        for (int minutes = 0; minutes < 24 * 60; minutes += 15) {
            box.getItems().add(LocalTime.of(minutes / 60, minutes % 60));
        }
        box.setValue(initial);
        box.setVisibleRowCount(8);
        box.setConverter(converter(HOUR::format, ""));
        return box;
    }

    private static DatePicker datePicker(LocalDate value) {
        DatePicker picker = new DatePicker(value);
        picker.setEditable(false);
        picker.setConverter(converter(DATE::format, ""));
        picker.setMaxWidth(Double.MAX_VALUE);
        return picker;
    }

    private static LocalTime nextQuarter() {
        LocalTime now = LocalTime.now(AccessLogService.APP_ZONE).plusMinutes(15);
        return quarter(now);
    }

    private static LocalTime quarter(LocalTime time) {
        return LocalTime.of(time.getHour(), (time.getMinute() / 15) * 15);
    }

    private static LocalDateTime at(LocalDate date, LocalTime time) {
        return date == null || time == null ? null : date.atTime(time);
    }

    private static Integer parseInteger(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            throw new InvalidDataException(what + " debe ser un número entero.");
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

    private static Label head(String text, double width) {
        Label label = label(text, "head-cell");
        return (Label) sized(label, width);
    }

    /** Celda de tabla: ancho fijo, o width 0 para repartir el espacio que sobra. */
    private static Node col(Node content, double width) {
        HBox box = new HBox(content);
        box.setAlignment(Pos.CENTER_LEFT);
        return sized(box, width);
    }

    private static Region sized(Region region, double width) {
        if (width > 0) {
            region.setMinWidth(width);
            region.setPrefWidth(width);
            region.setMaxWidth(width);
        } else {
            region.setMinWidth(120);
            region.setPrefWidth(120);
            region.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(region, Priority.ALWAYS);
        }
        return region;
    }

    private static Label stateChip(String text, String tone) {
        Label chip = label("●  " + text, "state-chip");
        chip.getStyleClass().add(tone);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    private static String vehicleTone(VehicleState state) {
        return switch (state) {
            case IN_ROUTE -> "state-dark";
            case AVAILABLE -> "state-ok";
            case MAINTENANCE -> "state-warn";
            case EXPIRED_DOCS -> "state-alert";
        };
    }

    private static String statusTone(TransportStatus status) {
        return switch (status) {
            case IN_PROGRESS -> "state-dark";
            case SCHEDULED -> "state-ok";
            case COMPLETED -> "state-ok";
            case CANCELLED -> "state-alert";
        };
    }

    private static String statusLabel(TransportStatus status) {
        return status == TransportStatus.SCHEDULED ? "Programado" : status.getDisplayName();
    }

    private static Button linkButton(String text) {
        Button button = new Button(text + "  →");
        button.getStyleClass().add("link-action");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** Las ventanas cargan app.css, personal.css y transport.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                TransportController.class.getResource("/css/app.css").toExternalForm(),
                TransportController.class.getResource("/css/personal.css").toExternalForm(),
                TransportController.class.getResource("/css/transport.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "transport-root");
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
        toastMotion = new SequentialTransition(in, new PauseTransition(Duration.seconds(text.length() > 80 ? 6 : 3)), out);
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

    private Transfer byKey(String key) {
        if (key == null || screen == null) {
            return null;
        }
        return screen.transfers().stream().filter(t -> key(t).equals(key)).findFirst().orElse(null);
    }

    private static String key(Transfer t) {
        return t.contractId() + "|" + t.scheduledAt();
    }

    /** Traslado en ruta del vehículo o, si no tiene, el próximo programado. */
    private Transfer transferOfVehicle(Long vehicleId) {
        Transfer next = null;
        for (Transfer t : screen.transfers()) {
            for (Unit u : t.units()) {
                if (u.vehicleId().equals(vehicleId)) {
                    if (u.status() == TransportStatus.IN_PROGRESS) {
                        return t;
                    }
                    if (u.status() == TransportStatus.SCHEDULED && next == null) {
                        next = t;
                    }
                }
            }
        }
        return next;
    }

    /** El coche fúnebre del traslado o, si no hay, el primer vehículo. */
    private static Unit mainUnit(Transfer t) {
        return t.units().stream().filter(u -> u.type() == VehicleType.HEARSE).findFirst().orElse(t.units().get(0));
    }

    private static String stopTitle(Stop s) {
        return switch (s.type()) {
            case ORIGIN -> "Salida · " + s.placeName();
            case PICKUP -> "Recogida en " + s.placeName();
            case CEREMONY -> s.placeName();
            case DESTINATION -> "Llegada a " + s.placeName();
        };
    }

    private static String typeName(VehicleType type) {
        return type == VehicleType.HEARSE ? "Coche fúnebre" : "Bus";
    }

    private static String capacityText(VehicleCard v) {
        if (v.capacity() == null) {
            return "";
        }
        return v.type() == VehicleType.BUS ? v.capacity() + " pasajeros"
                : v.capacity() + (v.capacity() == 1 ? " ataúd" : " ataúdes");
    }

    /** "14:00", "mañana 10:00" o "lunes 9 de noviembre · 08:30". */
    private String when(LocalDateTime at) {
        LocalDate today = today();
        if (at.toLocalDate().equals(today)) {
            return HOUR.format(at);
        }
        if (at.toLocalDate().equals(today.plusDays(1))) {
            return HOUR.format(at) + " mañana";
        }
        return DAY.format(at) + " · " + HOUR.format(at);
    }

    private static String shorten(String text) {
        return text.length() > 26 ? text.substring(0, 25) + "…" : text;
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static LocalDate today() {
        return LocalDate.now(AccessLogService.APP_ZONE);
    }
}
