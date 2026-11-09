package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AssignWakeData;
import com.example.funerariamc.service.ContractService;
import com.example.funerariamc.service.ContractService.SaleView;
import com.example.funerariamc.service.WakeRoomData;
import com.example.funerariamc.service.WakeRoomService;
import com.example.funerariamc.service.WakeRoomService.Board;
import com.example.funerariamc.service.WakeRoomService.Kpis;
import com.example.funerariamc.service.WakeRoomService.Option;
import com.example.funerariamc.service.WakeRoomService.RoomCard;
import com.example.funerariamc.service.WakeRoomService.RoomState;
import com.example.funerariamc.service.WakeRoomService.Wake;
import com.example.funerariamc.service.WakeRoomService.WakeDetail;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pantalla de Salas de velación (tablero 19 y panel 19.1 de Figma, paleta verde oscura):
 * ocupación por día con flechas y "Hoy", filtro por estado, una tarjeta por sala,
 * panel con difunto, familia, servicio y salida, y los diálogos Nueva sala,
 * Asignar a contrato y Cambiar sala.
 * Las consultas corren en otro hilo; si la fecha es hoy, el tablero se refresca cada minuto
 * para que el estado "En velación" y el tiempo que falta sigan la hora real.
 */
@Component
@Scope("prototype")
public class WakeRoomController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", ES);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final double DRAWER_WIDTH = 480;
    /** Columnas del tablero, como en el diseño. */
    private static final int COLUMNS = 3;

    private final WakeRoomService wakeRoomService;
    private final ContractService contractService;
    private final DetailCache<WakeDetail> details;

    @FXML
    private StackPane root;
    @FXML
    private ToggleGroup stateGroup;
    @FXML
    private ToggleButton allToggle;
    @FXML
    private ToggleButton inWakeToggle;
    @FXML
    private ToggleButton reservedToggle;
    @FXML
    private ToggleButton availableToggle;
    @FXML
    private Label occupiedCaption;
    @FXML
    private Label occupiedValue;
    @FXML
    private Label occupiedDetail;
    @FXML
    private Label reservedCaption;
    @FXML
    private Label reservedValue;
    @FXML
    private Label reservedDetail;
    @FXML
    private Label availableValue;
    @FXML
    private Label departuresCaption;
    @FXML
    private Label departuresValue;
    @FXML
    private Label departuresDetail;
    @FXML
    private DatePicker datePicker;
    @FXML
    private Button newRoomButton;
    @FXML
    private GridPane cardGrid;
    @FXML
    private Label emptyLabel;
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

    private Board board;
    /** Contrato del panel abierto; null si está cerrado. */
    private Long drawerContractId;
    /** Descarta respuestas viejas si el usuario cambió de fecha mientras cargaba. */
    private int generation;
    private SequentialTransition toastMotion;
    private Timeline minuteRefresh;

    public WakeRoomController(WakeRoomService wakeRoomService, ContractService contractService) {
        this.wakeRoomService = wakeRoomService;
        this.contractService = contractService;
        this.details = new DetailCache<>(wakeRoomService::detail, "wake-detail");
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        loadBoard(drawerContractId);
    }

    @FXML
    private void initialize() {
        for (Toggle toggle : List.of(allToggle, inWakeToggle, reservedToggle, availableToggle)) {
            toggle.setUserData(null);
        }
        inWakeToggle.setUserData(RoomState.IN_WAKE);
        reservedToggle.setUserData(RoomState.RESERVED);
        availableToggle.setUserData(RoomState.AVAILABLE);
        stateGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un filtro elegido
            } else {
                renderCards();
            }
        });

        for (int i = 0; i < COLUMNS; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            cardGrid.getColumnConstraints().add(column);
        }

        datePicker.setConverter(dayConverter());
        datePicker.setValue(today());
        datePicker.valueProperty().addListener((obs, old, value) -> {
            if (value != null && !value.equals(old)) {
                closeDrawer();
                loadBoard(null);
            }
        });

        boolean canRegister = wakeRoomService.canRegisterRooms();
        newRoomButton.setVisible(canRegister);
        newRoomButton.setManaged(canRegister);

        drawer.setTranslateX(DRAWER_WIDTH);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);

        // Hoy: cada minuto se vuelve a pedir el tablero para seguir la hora real (dos consultas).
        minuteRefresh = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            if (today().equals(datePicker.getValue()) && root.getScene() != null) {
                loadBoard(drawerContractId);
            }
        }));
        minuteRefresh.setCycleCount(Timeline.INDEFINITE);
        minuteRefresh.play();

        loadBoard(null);
    }

    // ---------- carga ----------

    /** Tablero del día elegido. Si reopenContractId no es null, se vuelve a abrir su panel con datos frescos. */
    private void loadBoard(Long reopenContractId) {
        LocalDate day = datePicker.getValue() == null ? today() : datePicker.getValue();
        int request = ++generation;
        if (board == null) {
            emptyLabel.setText("Cargando salas…");
            show(emptyLabel, true);
        }
        async("wake-board", () -> wakeRoomService.board(day), result -> {
            if (request != generation) {
                return;
            }
            board = result;
            details.clear();
            fillKpis(result);
            renderCards();
            List<Long> contracts = result.rooms().stream().filter(c -> c.wake() != null)
                    .map(c -> c.wake().contractId()).toList();
            details.prefetchAll(contracts);
            if (reopenContractId != null && contracts.contains(reopenContractId)) {
                openDrawer(reopenContractId);
            } else if (reopenContractId != null) {
                closeDrawer();
            }
        }, failure -> {
            if (request == generation) {
                emptyLabel.setText("");
                showError(failure instanceof BusinessException business ? business.getMessage()
                        : "No se pudieron cargar las salas.");
            }
        });
    }

    private void fillKpis(Board b) {
        Kpis k = b.kpis();
        occupiedCaption.setText(b.today() ? "OCUPADAS AHORA" : "EN VELACIÓN ESE DÍA");
        occupiedValue.setText(k.inWake() + " de " + k.total());
        occupiedDetail.setText(k.occupancyPercent() + " % de ocupación");
        reservedCaption.setText(b.today() ? "RESERVADAS HOY" : "RESERVADAS ESE DÍA");
        reservedValue.setText(String.valueOf(k.reserved()));
        reservedDetail.setText(k.nextReservation() == null ? "sin próximos ingresos" : k.nextReservation());
        availableValue.setText(String.valueOf(k.available()));
        departuresCaption.setText(b.today() ? "SALIDAS HOY" : "SALIDAS ESE DÍA");
        departuresValue.setText(String.valueOf(k.departures()));
        departuresDetail.setText(k.departureTimes().isEmpty() ? "sin salidas registradas"
                : k.departureTimes().stream().limit(4).map(HOUR::format).collect(Collectors.joining(" · ")));

        long inWake = b.rooms().stream().filter(c -> c.state() == RoomState.IN_WAKE).count();
        long reserved = b.rooms().stream().filter(c -> c.state() == RoomState.RESERVED).count();
        long available = b.rooms().stream().filter(c -> c.state() == RoomState.AVAILABLE).count();
        allToggle.setText("Todas · " + b.rooms().size());
        inWakeToggle.setText("Ocupadas · " + inWake);
        reservedToggle.setText("Reservadas · " + reserved);
        availableToggle.setText("Disponibles · " + available);
    }

    // ---------- tarjetas ----------

    private void renderCards() {
        cardGrid.getChildren().clear();
        if (board == null) {
            return;
        }
        RoomState filter = stateGroup.getSelectedToggle() == null ? null
                : (RoomState) stateGroup.getSelectedToggle().getUserData();
        List<RoomCard> cards = board.rooms().stream().filter(c -> filter == null || c.state() == filter).toList();
        if (board.rooms().isEmpty()) {
            emptyLabel.setText(wakeRoomService.canRegisterRooms()
                    ? "No hay salas registradas. Use «Nueva sala» para registrar la primera."
                    : "No hay salas registradas. El Administrador Maestro las registra.");
            show(emptyLabel, true);
            return;
        }
        if (cards.isEmpty()) {
            emptyLabel.setText("Ninguna sala está «" + filter.getLabel().toLowerCase(ES) + "» ese día.");
            show(emptyLabel, true);
            return;
        }
        show(emptyLabel, false);
        LocalDateTime now = LocalDateTime.now(AccessLogService.APP_ZONE);
        for (int i = 0; i < cards.size(); i++) {
            VBox card = roomCard(cards.get(i), now);
            GridPane.setFillWidth(card, true);
            card.setMaxWidth(Double.MAX_VALUE);
            cardGrid.add(card, i % COLUMNS, i / COLUMNS);
        }
    }

    private VBox roomCard(RoomCard c, LocalDateTime now) {
        Label caption = label("SALA %02d".formatted(c.number()), "room-caption");
        Label name = label(c.name(), "room-name");
        HBox nameRow = new HBox(8, name);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        if (c.special()) {
            nameRow.getChildren().add(label("Especial", "special-tag"));
        }
        VBox titles = new VBox(0, caption, nameRow);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox header = new HBox(10, titles, stateChip(c));
        header.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(header);
        card.getStyleClass().add("room-card");
        Wake w = c.wake();
        switch (c.state()) {
            case IN_WAKE -> {
                card.getStyleClass().add("in-wake");
                card.getChildren().addAll(occupant("DIFUNTO EN SALA", w), timeRow(w, now));
                if (w.destination() != null) {
                    card.getChildren().add(label("Cortejo a " + w.destination(), "route-text"));
                }
            }
            case RESERVED -> {
                card.getStyleClass().add("reserved");
                card.getChildren().add(occupant("PRÓXIMO INGRESO", w));
                Label prep = wrapped(w.preparationCompletedAt() == null
                        ? "Ingreso " + dayAndHour(w.start(), now.toLocalDate()) + " · preparación del cuerpo pendiente"
                        : "Ingreso " + dayAndHour(w.start(), now.toLocalDate()) + " · preparación lista "
                        + HOUR.format(w.preparationCompletedAt()), "prep-band");
                if (w.preparationCompletedAt() != null) {
                    prep.getStyleClass().add("done");
                }
                prep.setMaxWidth(Double.MAX_VALUE);
                card.getChildren().add(prep);
            }
            case AVAILABLE -> {
                String freeTitle = c.freeFrom() == null ? "Libre durante todo el día"
                        : "Libre desde las " + HOUR.format(c.freeFrom());
                VBox free = new VBox(label(freeTitle, "free-title"), label("Precio base " + pesos(c.basePrice())
                        + (c.special() ? " · con recargo" : " · sin recargo"), "free-detail"));
                free.getStyleClass().add("free-box");
                card.getChildren().add(free);
            }
        }

        Label capacity = label(c.capacity() == null ? "Aforo sin registrar" : "Aforo " + c.capacity() + " personas",
                "capacity-text");
        HBox footer = new HBox(capacity);
        footer.setAlignment(Pos.CENTER_LEFT);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        footer.getChildren().add(spacer);
        if (w != null) {
            Button detail = new Button("Ver detalle  →");
            detail.getStyleClass().add("link-action");
            detail.setOnAction(e -> openDrawer(w.contractId()));
            footer.getChildren().add(detail);
            card.setOnMouseClicked(e -> openDrawer(w.contractId()));
            card.setOnMouseEntered(e -> details.prefetch(w.contractId()));
            card.setCursor(javafx.scene.Cursor.HAND);
        } else {
            Button assign = new Button("Asignar a contrato");
            assign.getStyleClass().add("small-dark-button");
            assign.setOnAction(e -> openAssignDialog(c));
            footer.getChildren().add(assign);
        }
        Pane grow = new Pane();
        VBox.setVgrow(grow, Priority.ALWAYS);
        card.getChildren().addAll(grow, footer);
        return card;
    }

    private static VBox occupant(String caption, Wake w) {
        String detail = w.familyName() + " · " + (w.planName() == null ? "Contrato " + w.contractNumber()
                : "Plan exequial " + w.planName());
        VBox box = new VBox(label(caption, "occupant-caption"), label(w.deceasedName(), "occupant-name"),
                wrapped(detail, "occupant-detail"));
        box.getStyleClass().add("occupant-box");
        return box;
    }

    /** Ingreso, salida y la barra con el tiempo que lleva la velación. */
    private static VBox timeRow(Wake w, LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        Label start = label("Ingreso " + dayAndHour(w.start(), day), "time-text");
        Label end = label(w.endKnown() ? "Salida " + dayAndHour(w.end(), day) : "Salida por definir", "time-strong");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(start, spacer, end);
        ProgressBar bar = new ProgressBar(progress(w.start(), w.end(), now));
        bar.getStyleClass().add("progress-dark");
        bar.setMaxWidth(Double.MAX_VALUE);
        return new VBox(6, row, bar);
    }

    private static Label stateChip(RoomCard c) {
        String text = switch (c.state()) {
            case IN_WAKE -> "●  En velación";
            case RESERVED -> "Reservada · " + HOUR.format(c.wake().start());
            case AVAILABLE -> "●  Disponible";
        };
        String style = switch (c.state()) {
            case IN_WAKE -> "state-dark";
            case RESERVED -> "state-warn";
            case AVAILABLE -> "state-ok";
        };
        Label chip = label(text, "state-chip");
        chip.getStyleClass().add(style);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    // ---------- panel de detalle (19.1) ----------

    private void openDrawer(Long contractId) {
        drawerContractId = contractId;
        WakeDetail cached = details.get(contractId);
        if (cached != null) {
            fillDrawer(cached);
        } else {
            drawerHead.getChildren().setAll(label("Cargando la velación…", "drawer-head-text"));
            drawerBody.getChildren().clear();
            drawerActions.getChildren().clear();
            details.load(contractId, detail -> {
                if (contractId.equals(drawerContractId)) {
                    fillDrawer(detail);
                }
            }, failure -> {
                if (contractId.equals(drawerContractId)) {
                    closeDrawer();
                    showError(failure instanceof BusinessException business ? business.getMessage()
                            : "No se pudo cargar el detalle de la sala.");
                }
            });
        }
        slideDrawerIn();
    }

    private void fillDrawer(WakeDetail d) {
        LocalDateTime now = LocalDateTime.now(AccessLogService.APP_ZONE);
        Label caption = label("SALA %02d%s".formatted(d.roomNumber(), d.special() ? " · ESPECIAL" : ""),
                "drawer-head-caption");
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close-light");
        close.setOnAction(e -> closeDrawer());
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(caption, spacer, close);
        top.setAlignment(Pos.CENTER_LEFT);

        Label state = label(switch (d.state()) {
            case IN_WAKE -> "●  En velación";
            case RESERVED -> "Reservada · " + HOUR.format(d.start());
            case AVAILABLE -> "●  Velación terminada";
        }, "state-chip");
        state.getStyleClass().add(d.state() == RoomState.RESERVED ? "state-warn" : "state-ok");
        HBox nameRow = new HBox(12, label(d.roomName(), "drawer-head-name"), state);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        String middle = switch (d.state()) {
            case IN_WAKE -> "Ahora " + HOUR.format(now) + " · faltan " + remaining(now, d.end());
            case RESERVED -> "Ingresa en " + remaining(now, d.start());
            case AVAILABLE -> "Terminó " + dayAndHour(d.end(), now.toLocalDate());
        };
        Pane s1 = new Pane();
        Pane s2 = new Pane();
        HBox.setHgrow(s1, Priority.ALWAYS);
        HBox.setHgrow(s2, Priority.ALWAYS);
        HBox times = new HBox(label("Ingreso " + dayAndHour(d.start(), now.toLocalDate()), "drawer-head-strong"), s1,
                label(middle, "drawer-head-text"), s2,
                label(d.endKnown() ? "Salida " + dayAndHour(d.end(), now.toLocalDate()) : "Salida por definir",
                        "drawer-head-strong"));
        ProgressBar bar = new ProgressBar(progress(d.start(), d.end(), now));
        bar.getStyleClass().add("progress-light");
        bar.setMaxWidth(Double.MAX_VALUE);
        drawerHead.getChildren().setAll(top, nameRow, times, bar);

        // Difunto
        Label initials = label(initials(d.deceasedName()), "initials");
        String sub = (d.deceasedDocument() == null ? "Sin documento" : "Doc. " + d.deceasedDocument())
                + (d.age() == null ? "" : " · " + d.age() + " años");
        VBox who = new VBox(0, wrapped(d.deceasedName(), "person-title"), label(sub, "field-caption"));
        HBox person = new HBox(12, initials, who);
        person.setAlignment(Pos.CENTER_LEFT);
        GridPane deceased = grid();
        field(deceased, 0, 0, "Fecha de fallecimiento", d.deathDate() == null ? "—" : DATE.format(d.deathDate()));
        field(deceased, 1, 0, "Tipo de muerte", deathType(d.deathType()));
        field(deceased, 0, 1, "Certificado de defunción", d.certificate() == null ? "Sin registrar" : d.certificate());
        field(deceased, 1, 1, "Tanatopraxia", d.preparationCompletedAt() == null ? "Pendiente"
                : "Completada " + dayAndHour(d.preparationCompletedAt(), now.toLocalDate())
                + (d.thanatopractor() == null ? "" : " · " + d.thanatopractor()));

        GridPane family = grid();
        field(family, 0, 0, "Cliente titular", d.clientName());
        field(family, 1, 0, "Documento", orDash(d.clientDocument()));
        field(family, 0, 1, "Teléfono", orDash(d.clientPhone()));

        GridPane service = grid();
        field(service, 0, 0, "Contrato", d.contractNumber());
        field(service, 1, 0, "Plan exequial", d.planLabel() == null ? "Sin plan" : d.planLabel());
        field(service, 0, 1, "Vendedor", orDash(d.salesperson()));
        field(service, 1, 1, "Recargo sala especial", d.roomSurcharge() == null
                ? (d.special() ? "Se agrega al generar la venta" : "No aplica") : pesos(d.roomSurcharge()));

        GridPane departure = grid();
        field(departure, 0, 0, "Hora de salida", d.endKnown() ? dayAndHour(d.end(), now.toLocalDate()) : "Por definir");
        field(departure, 1, 0, "Destino", d.destination() == null ? "Sin traslado registrado" : d.destination());

        drawerBody.getChildren().setAll(
                section("DIFUNTO", person, deceased),
                section("FAMILIA Y CLIENTE TITULAR", family),
                section("SERVICIO", service),
                section("SALIDA Y TRASLADO", departure));

        Button contract = drawerButton("Ver contrato", "drawer-secondary");
        contract.setOnAction(e -> openContract(d));
        Button change = drawerButton("Cambiar sala", "drawer-primary");
        change.setDisable(d.state() == RoomState.AVAILABLE);
        change.setOnAction(e -> openChangeDialog(d));
        drawerActions.getChildren().setAll(contract, change);
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
        VBox box = new VBox(2, label(caption, "field-caption"), wrapped(value, "field-value"));
        grid.add(box, column, row);
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
        drawerContractId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerContractId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    // ---------- fecha ----------

    @FXML
    private void onPreviousDay() {
        datePicker.setValue(datePicker.getValue().minusDays(1));
    }

    @FXML
    private void onNextDay() {
        datePicker.setValue(datePicker.getValue().plusDays(1));
    }

    @FXML
    private void onToday() {
        datePicker.setValue(today());
    }

    // ---------- diálogos ----------

    /** Nombre, capacidad, precio base y si es especial (recargo). */
    @FXML
    private void onNewRoom() {
        TextField name = new TextField();
        name.setPromptText("Ej.: Sala Edén");
        TextField capacity = new TextField();
        capacity.setPromptText("Personas");
        TextField price = new TextField();
        price.setPromptText("Ej.: 500000");
        CheckBox special = new CheckBox("Sala especial (genera recargo)");
        special.getStyleClass().add("form-check");
        HBox specialRow = new HBox(special);
        specialRow.getStyleClass().add("form-check-row");

        Form form = new Form()
                .section("Datos de la sala", 3)
                .field("NOMBRE *", name)
                .field("CAPACIDAD (AFORO) *", capacity)
                .field("PRECIO BASE *", price)
                .note(specialRow)
.note(note("La capacidad debe ser mayor que cero. Si la sala es especial, al asignarla "
                        + "a un contrato se agrega el recargo configurado en Parámetros del sistema."));
        Dialog<ButtonType> dialog = formDialog("Nueva sala", "Los campos con * son obligatorios.", form, 720);
        runForm(dialog, form, "Registrar sala", () -> wakeRoomService.register(new WakeRoomData(name.getText(),
                parseInteger(capacity.getText(), "La capacidad"), parseAmount(price.getText(), "El precio base"),
                special.isSelected())), saved -> {
            showToast("Sala " + saved.getName() + " registrada.");
            loadBoard(null);
        });
    }

    /** Contrato activo sin sala, hora de ingreso y de salida; rechaza cruces. */
    private void openAssignDialog(RoomCard room) {
        TextField roomField = readOnly(room.name() + (room.special() ? " · especial (con recargo)" : ""));
        TextField search = new TextField();
        search.setPromptText("Número de contrato, difunto o cliente");
        ComboBox<Option> contract = optionCombo("Seleccione el contrato");
        Label hint = note("");
        bindSearch(search, contract, wakeRoomService::searchContractsWithoutRoom, hint);

        LocalDate day = datePicker.getValue() == null ? today() : datePicker.getValue();
        DatePicker startDate = new DatePicker(day);
        ComboBox<LocalTime> startTime = timeCombo(LocalTime.of(8, 0));
        DatePicker endDate = new DatePicker(day);
        ComboBox<LocalTime> endTime = timeCombo(LocalTime.of(18, 0));

        Form form = new Form()
                .section("Sala", 1)
                .field("SALA", roomField)
                .section("Contrato del servicio", 2)
                .field("BUSCAR CONTRATO", search)
                .field("CONTRATO *", contract)
                .note(hint)
                .section("Horario de la velación", 4)
                .field("FECHA DE INGRESO *", startDate)
                .field("HORA DE INGRESO *", startTime)
                .field("FECHA DE SALIDA *", endDate)
                .field("HORA DE SALIDA *", endTime)
                .note(note(room.special()
                        ? "Es una sala especial: el recargo configurado se agrega como una línea aparte de la venta."
                        : "Sala sin recargo. No se permite que dos velaciones se crucen en la misma sala."));
        Dialog<ButtonType> dialog = formDialog("Asignar " + room.name(), "Los campos con * son obligatorios.", form, 820);
        runForm(dialog, form, "Asignar sala", () -> {
            wakeRoomService.assign(new AssignWakeData(room.id(), idOf(contract.getValue()),
                    at(startDate.getValue(), startTime.getValue()), at(endDate.getValue(), endTime.getValue())));
            return idOf(contract.getValue());
        }, contractId -> {
            showToast(room.name() + " asignada al contrato " + contract.getValue().label() + ".");
            loadBoard(contractId);
        });
    }

    /** "Cambiar sala": mismo horario en otra sala libre; el recargo se ajusta solo. */
    private void openChangeDialog(WakeDetail d) {
        ComboBox<Option> roomBox = optionCombo("Buscando salas libres…");
        Label hint = note("");
        Form form = new Form()
                .section("Sala actual", 2)
                .field("SALA", readOnly(d.roomName()))
                .field("HORARIO", readOnly(DATE_HOUR.format(d.start()) + " a "
                        + (d.endKnown() ? DATE_HOUR.format(d.end()) : "por definir")))
                .section("Sala nueva", 1)
                .field("SALA LIBRE EN ESE HORARIO *", roomBox)
                .note(hint)
.note(note("Si la sala nueva es especial se agrega el recargo; si no lo es, se quita de la venta."));
        async("wake-free-rooms", () -> wakeRoomService.freeRooms(d.contractId(), d.start(), d.end()), rooms -> {
            roomBox.getItems().setAll(rooms);
            roomBox.setPromptText(rooms.isEmpty() ? "No hay salas libres" : "Seleccione la sala");
            hint.setText(rooms.isEmpty() ? "Todas las demás salas están ocupadas en ese horario." : "");
        }, failure -> roomBox.setPromptText("No se pudieron buscar las salas"));
        Dialog<ButtonType> dialog = formDialog("Cambiar sala", "Contrato " + d.contractNumber() + " · "
                + d.deceasedName(), form, 680);
        runForm(dialog, form, "Cambiar sala", () -> {
            wakeRoomService.changeRoom(d.contractId(), idOf(roomBox.getValue()));
            return d.contractId();
        }, contractId -> {
            showToast("Velación pasada a la " + roomBox.getValue().label() + ".");
            loadBoard(contractId);
        });
    }

    /** "Ver contrato": resumen de la venta del contrato con sus conceptos y abonos. */
    private void openContract(WakeDetail d) {
        async("wake-contract", () -> contractService.detailOfContract(d.contractId()), view -> {
            if (view == null) {
                showInfo("Contrato " + d.contractNumber(), "El contrato todavía no tiene venta registrada. "
                        + "Se genera en Contratos y ventas.");
                return;
            }
            showContractDialog(d, view);
        }, failure -> showError(failure instanceof BusinessException business ? business.getMessage()
                : "No se pudo cargar el contrato."));
    }

    private void showContractDialog(WakeDetail d, SaleView view) {
        GridPane lines = new GridPane();
        lines.setHgap(16);
        lines.setVgap(6);
        int row = 0;
        for (ContractService.Line line : view.lines()) {
            Label text = wrapped(line.description() + (line.quantity() > 1 ? " × " + line.quantity() : ""), "field-value");
            Label value = label(pesos(line.subtotal()), "field-value");
            GridPane.setHgrow(text, Priority.ALWAYS);
            lines.add(text, 0, row);
            lines.add(value, 1, row++);
        }
        GridPane totals = grid();
        field(totals, 0, 0, "Total", pesos(view.row().total()));
        field(totals, 1, 0, "Abonado", pesos(view.row().paid()));
        field(totals, 0, 1, "Saldo", pesos(view.row().balance()));
        field(totals, 1, 1, "Estado", view.row().state().getLabel());
        Form form = new Form()
                .section("Conceptos de la venta", 1)
                .note(lines)
                .section("Pago", 2)
                .note(totals)
                .note(note("Los abonos y la anulación se registran en Contratos y ventas."));
        Dialog<ButtonType> dialog = formDialog("Contrato " + d.contractNumber(),
                view.row().kind().getLabel() + " · " + view.clientName(), form, 620);
        ButtonType close = new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(close);
        dialog.showAndWait();
    }

    // ---------- formulario de los diálogos ----------

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
            async("wake-save", save, result -> {
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
    private void bindSearch(TextField field, ComboBox<Option> box, Function<String, List<Option>> search, Label emptyHint) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        int[] requestNumber = new int[1];
        Runnable refresh = () -> {
            String text = field.getText();
            int request = ++requestNumber[0];
            box.setPromptText("Buscando…");
            async("wake-dialog-search", () -> search.apply(text), options -> {
                if (request != requestNumber[0]) {
                    return;
                }
                box.setPromptText(options.isEmpty() ? "Sin resultados" : "Seleccione de la lista");
                box.getItems().setAll(options);
                box.setValue(options.size() == 1 ? options.get(0) : null);
                if (emptyHint != null) {
                    emptyHint.setText(options.isEmpty() && (text == null || text.isBlank())
                            ? "No hay contratos activos sin sala. Regístrelos primero en Contratos y ventas." : "");
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

    // ---------- piezas ----------

    private static ComboBox<LocalTime> timeCombo(LocalTime initial) {
        ComboBox<LocalTime> box = new ComboBox<>();
        box.getStyleClass().add("form-combo");
        for (int minutes = 0; minutes < 24 * 60; minutes += 30) {
            box.getItems().add(LocalTime.of(minutes / 60, minutes % 60));
        }
        box.setValue(initial);
        box.setVisibleRowCount(8);
        box.setConverter(converter(HOUR::format, ""));
        return box;
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
            throw new com.example.funerariamc.exception.InvalidDataException(what + " debe ser un número entero.");
        }
    }

    /** "500.000", "500000" o "$ 500.000" pasan a 500000. */
    private static BigDecimal parseAmount(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.replace("$", "").replace(".", "").replace(" ", "").replace(",", ".");
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            throw new com.example.funerariamc.exception.InvalidDataException(what + " debe ser un valor en pesos.");
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

    private static TextField readOnly(String value) {
        TextField field = new TextField(value);
        field.setDisable(true);
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

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    /** Las ventanas cargan app.css, personal.css y wake-rooms.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                WakeRoomController.class.getResource("/css/app.css").toExternalForm(),
                WakeRoomController.class.getResource("/css/personal.css").toExternalForm(),
                WakeRoomController.class.getResource("/css/wake-rooms.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "wake-root");
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

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.NONE, message, new ButtonType("Entendido", ButtonBar.ButtonData.OK_DONE));
        alert.initOwner(window());
        alert.setTitle(title);
        alert.setHeaderText(title);
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

    private static StringConverter<LocalDate> dayConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : capitalize(DAY_TEXT.format(date));
            }

            @Override
            public LocalDate fromString(String string) {
                return null;
            }
        };
    }

    /** Fracción de la velación ya transcurrida, de 0 a 1. */
    static double progress(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        long total = ChronoUnit.MINUTES.between(start, end);
        if (total <= 0) {
            return 0;
        }
        long done = ChronoUnit.MINUTES.between(start, now);
        return Math.max(0, Math.min(1, done / (double) total));
    }

    /** "3 h 12 min", "45 min" o "2 días". */
    static String remaining(LocalDateTime from, LocalDateTime to) {
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(from, to));
        if (minutes >= 48 * 60) {
            return (minutes / (24 * 60)) + " días";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours == 0 ? rest + " min" : hours + " h " + rest + " min";
    }

    /** "08:00" si es el día de hoy en la pantalla, "Ayer 20:00", "mañana 10:00" o "25/10 10:00". */
    static String dayAndHour(LocalDateTime time, LocalDate reference) {
        LocalDate day = time.toLocalDate();
        String hour = HOUR.format(time);
        if (day.equals(reference)) {
            return hour;
        }
        if (day.equals(reference.minusDays(1))) {
            return "Ayer " + hour;
        }
        if (day.equals(reference.plusDays(1))) {
            return hour + " (mañana)";
        }
        return DateTimeFormatter.ofPattern("dd/MM").format(day) + " " + hour;
    }

    private static String deathType(DeathType type) {
        if (type == null) {
            return "—";
        }
        return switch (type.name()) {
            case "NATURAL" -> "Natural";
            case "NON_NATURAL" -> "No natural";
            default -> type.name();
        };
    }

    private static String pesos(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(ES);
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value);
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
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static Long idOf(Option option) {
        return option == null ? null : option.id();
    }

    private static LocalDate today() {
        return LocalDate.now(AccessLogService.APP_ZONE);
    }
}
