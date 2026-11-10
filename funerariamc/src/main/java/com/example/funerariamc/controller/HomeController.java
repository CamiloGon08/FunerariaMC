package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.HomeService;
import com.example.funerariamc.service.HomeService.AgendaItem;
import com.example.funerariamc.service.HomeService.Alert;
import com.example.funerariamc.service.HomeService.Today;
import com.example.funerariamc.service.WakeRoomService;
import com.example.funerariamc.service.WakeRoomService.RoomCard;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Inicio: lo que pasa en la funeraria ahora. Indicadores (servicios en curso, salas, traslados, cremaciones y,
 * solo para el Administrador Maestro, los abonos de hoy), tablero de salas, alertas y la agenda del día.
 * Todo es de lectura y se consulta en otro hilo; mientras está a la vista se refresca cada 60 segundos.
 */
@Component
@Scope("prototype")
public class HomeController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final HomeService homeService;

    @FXML private StackPane root;
    @FXML private Label liveLabel;
    @FXML private HBox kpiRow;
    @FXML private VBox content;

    private int generation;
    private final Timeline live = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
        if (root.getScene() != null && root.isVisible()) {
            load();
        }
    }));

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        content.getChildren().setAll(label("Cargando…", "empty-board"));
        live.setCycleCount(Timeline.INDEFINITE);
        live.play();
        load();
    }

    private void load() {
        int request = ++generation;
        async(homeService::today, today -> {
            if (request == generation) {
                draw(today);
            }
        }, failure -> {
            if (request == generation) {
                content.getChildren().setAll(label(failure instanceof BusinessException business ? business.getMessage()
                        : "No se pudo cargar el resumen del día. Revise la conexión.", "empty-board"));
            }
        });
    }

    private void draw(Today t) {
        liveLabel.setText("En vivo · actualizado " + t.now().format(HOUR));
        WakeRoomService.Kpis rooms = t.rooms().kpis();
        kpiRow.getChildren().setAll(
                kpi("SERVICIOS EN CURSO", String.valueOf(t.servicesNow()),
                        plural(t.wakesNow(), "velación", "velaciones") + " · " + t.transfersInRoute() + " en traslado", true),
                kpi("SALAS OCUPADAS", rooms.inWake() + " de " + rooms.total(),
                        rooms.nextReservation() == null || rooms.nextReservation().isBlank()
                                ? plural(rooms.available(), "libre", "libres")
                                : rooms.nextReservation(), false),
                kpi("TRASLADOS HOY", String.valueOf(t.transfersToday()),
                        t.transfersInRoute() + " en ruta · " + t.transfersDone() + (t.transfersDone() == 1 ? " completado"
                                : " completados"), false),
                kpi("CREMACIONES HOY", String.valueOf(t.cremationsToday()),
                        t.nextCremation() == null ? "ninguna pendiente hoy" : t.nextCremation(), false));
        if (t.payments() != null) {
            kpiRow.getChildren().add(kpi("ABONOS DE HOY", shortPesos(t.payments().total()),
                    plural((int) t.payments().count(), "pago registrado", "pagos registrados"), false));
        }

        GridPane top = new GridPane();
        top.setHgap(16);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(64);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(36);
        top.getColumnConstraints().addAll(left, right);
        Node roomsCard = roomsCard(t);
        Node alertsCard = alertsCard(t.alerts());
        top.add(roomsCard, 0, 0);
        top.add(alertsCard, 1, 0);
        GridPane.setVgrow(roomsCard, Priority.ALWAYS);
        content.getChildren().setAll(top, agendaCard(t));
    }

    private Node roomsCard(Today t) {
        VBox card = card("Salas en este momento", "Ocupación de las salas de velación hoy");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(c);
        }
        List<RoomCard> rooms = t.rooms().rooms();
        for (int i = 0; i < rooms.size(); i++) {
            grid.add(roomTile(rooms.get(i), t.now()), i % 3, i / 3);
        }
        if (rooms.isEmpty()) {
            card.getChildren().add(label("No hay salas registradas.", "empty-board"));
        } else {
            card.getChildren().add(grid);
        }
        return card;
    }

    private Node roomTile(RoomCard room, LocalDateTime now) {
        Label name = label(room.name(), "room-name");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Circle dot = new Circle(4);
        dot.getStyleClass().add("room-dot");
        HBox head = new HBox(name, gap, dot);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox tile = new VBox(head);
        tile.getStyleClass().add("room-tile");
        WakeRoomService.Wake wake = room.wake();
        if (room.state() == WakeRoomService.RoomState.IN_WAKE && wake != null) {
            tile.getStyleClass().add("busy");
            tile.getChildren().addAll(label(wake.deceasedName(), "room-who"), label(wake.endKnown()
                    ? "Sale " + when(wake.end(), now.toLocalDate()) : "En velación", "room-when"));
        } else if (room.state() == WakeRoomService.RoomState.RESERVED && wake != null) {
            tile.getStyleClass().add("reserved");
            tile.getChildren().addAll(label(wake.deceasedName(), "room-who"),
                    label("Llega " + when(wake.start(), now.toLocalDate()), "room-when"));
        } else {
            tile.getChildren().addAll(label("Libre", "room-who"), label(room.freeFrom() == null
                    ? "Disponible todo el día" : "Libre desde las " + room.freeFrom().format(HOUR), "room-when"));
        }
        return tile;
    }

    private Node alertsCard(List<Alert> alerts) {
        Label title = label("Alertas", "chart-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(title, gap);
        head.setAlignment(Pos.CENTER_LEFT);
        if (!alerts.isEmpty()) {
            head.getChildren().add(label(String.valueOf(alerts.size()), "count-badge"));
        }
        VBox card = new VBox(10, head);
        card.getStyleClass().add("chart-card");
        if (alerts.isEmpty()) {
            card.getChildren().add(label("Todo en orden: no hay documentos vencidos, bóvedas en mora ni inventario en el"
                    + " mínimo.", "chart-subtitle"));
            ((Label) card.getChildren().get(1)).setWrapText(true);
        }
        for (Alert a : alerts.stream().limit(6).toList()) {
            Label text = label(a.text(), "alert-text");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            Label title2 = label(a.title(), "alert-title");
            title2.setWrapText(true);
            VBox box = new VBox(title2, text);
            box.getStyleClass().addAll("alert-box", a.level() == HomeService.Level.ALERT ? "alert" : "warn");
            card.getChildren().add(box);
        }
        if (alerts.size() > 6) {
            card.getChildren().add(label("y " + (alerts.size() - 6) + " más", "chart-subtitle"));
        }
        return card;
    }

    private Node agendaCard(Today t) {
        VBox card = card("Servicios y salidas de hoy", "Traslados y cremaciones programados para hoy");
        if (t.agenda().isEmpty()) {
            card.getChildren().add(label("No hay traslados ni cremaciones programados para hoy.", "empty-board"));
            return card;
        }
        HBox head = new HBox(cell(label("HORA", "head-cell"), 90), grow(label("DIFUNTO", "head-cell")),
                cell(label("SERVICIO", "head-cell"), 200), cell(label("DESTINO", "head-cell"), 220),
                cell(label("VEHÍCULO", "head-cell"), 170), cell(label("ESTADO", "head-cell"), 120));
        head.getStyleClass().add("table-head");
        card.getChildren().add(head);
        for (AgendaItem a : t.agenda()) {
            Label state = label(a.state(), "state-chip");
            state.getStyleClass().add(a.active() ? "chip-active" : a.done() ? "chip-done" : "chip-waiting");
            HBox row = new HBox(cell(label(a.at() == null ? "—" : a.at().format(HOUR), "agenda-hour"), 90),
                    grow(label(a.deceased(), "item-row-name")), cell(label(a.kind(), "item-row-text"), 200),
                    cell(label(a.destination(), "item-row-text"), 220), cell(label(a.vehicles(), "item-row-text"), 170),
                    cell(state, 120));
            row.getStyleClass().add("item-row");
            row.setStyle("-fx-border-color: transparent transparent #E6E8DE transparent; -fx-cursor: default;");
            card.getChildren().add(row);
        }
        return card;
    }

    // ---------- piezas ----------

    private static VBox kpi(String caption, String value, String detail, boolean dark) {
        VBox box = new VBox(label(caption, "hero-kpi-caption"), label(value, "hero-kpi-value"),
                label(detail, "hero-kpi-detail"));
        box.getStyleClass().add("hero-kpi");
        if (dark) {
            box.getStyleClass().add("dark");
        }
        HBox.setHgrow(box, Priority.ALWAYS);
        box.setPrefWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static VBox card(String title, String subtitle) {
        VBox card = new VBox(10, new VBox(2, label(title, "chart-title"), label(subtitle, "chart-subtitle")));
        card.getStyleClass().add("chart-card");
        return card;
    }

    /** "15:30" si es hoy; "mañana 10:00" o "12/11 10:00" si no. */
    private static String when(LocalDateTime at, LocalDate today) {
        if (at == null) {
            return "";
        }
        if (at.toLocalDate().equals(today)) {
            return at.format(HOUR);
        }
        if (at.toLocalDate().equals(today.plusDays(1))) {
            return "mañana " + at.format(HOUR);
        }
        return at.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static Node cell(Node content, double width) {
        StackPane box = new StackPane(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        return box;
    }

    private static Node grow(Node content) {
        StackPane box = new StackPane(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(0);
        box.setPrefWidth(0);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /** "$ 3,2 M" desde un millón y "$ 850 mil" desde mil; por debajo, el valor completo. */
    static String shortPesos(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(ES));
        BigDecimal abs = v.abs();
        if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            return "$ " + format.format(v.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP).doubleValue()) + " M";
        }
        if (abs.compareTo(BigDecimal.valueOf(1_000)) >= 0) {
            return "$ " + format.format(v.divide(BigDecimal.valueOf(1_000), 0, RoundingMode.HALF_UP).doubleValue()) + " mil";
        }
        return "$ " + NumberFormat.getIntegerInstance(ES).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static <T> void async(Callable<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, "home-load");
        thread.setDaemon(true);
        thread.start();
    }
}
