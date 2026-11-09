package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.enums.CremationBatchStatus;
import com.example.funerariamc.model.enums.CremationPurchaseStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AssignCremationData;
import com.example.funerariamc.service.CremationBatchData;
import com.example.funerariamc.service.CremationCertificateData;
import com.example.funerariamc.service.CremationService;
import com.example.funerariamc.service.CremationService.Batch;
import com.example.funerariamc.service.CremationService.Candidate;
import com.example.funerariamc.service.CremationService.Furnace;
import com.example.funerariamc.service.CremationService.FurnaceState;
import com.example.funerariamc.service.CremationService.Item;
import com.example.funerariamc.service.CremationService.Kpis;
import com.example.funerariamc.service.CremationService.Option;
import com.example.funerariamc.service.CremationService.Screen;
import com.example.funerariamc.service.CremationProviderData;
import com.example.funerariamc.service.CremationPurchaseData;
import com.example.funerariamc.service.CremationStockService;
import com.example.funerariamc.service.CremationStockService.ProviderStock;
import com.example.funerariamc.service.CremationStockService.Purchase;
import com.example.funerariamc.service.CremationStockService.Stock;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pantalla de Cremaciones con la paleta verde oscura:
 * indicadores, hornos o proveedores con su lote en curso y el siguiente, agenda del
 * día o de la semana en orden cronológico, panel del lote y los diálogos
 * Programar lote, Reprogramar lote, Agregar difunto con el control de la Fiscalía, Entregar al horno
 * y Registrar certificado. Al final, los cupos prepagados por proveedor (disponibles, separados,
 * cremados y en camino) y sus compras: solicitar, confirmar (gasto en Finanzas), recibir o anular.
 * Toda la pantalla sale de una carga en otro hilo; buscar y filtrar se hace en memoria y el panel
 * del lote se pinta con lo ya cargado, sin consultas nuevas. Cada minuto se refresca.
 */
@Component
@Scope("prototype")
public class CremationController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DAY_TEXT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", ES);
    private static final double DRAWER_WIDTH = 500;
    /** Tarjetas de hornos por fila,. */
    private static final int COLUMNS = 3;
    private static final int WEEK_DAYS = 7;

    private final CremationService cremationService;
    private final CremationStockService stockService;

    @FXML
    private StackPane root;
    @FXML
    private ToggleGroup stateGroup;
    @FXML
    private ToggleButton allToggle;
    @FXML
    private ToggleButton scheduledToggle;
    @FXML
    private ToggleButton inProgressToggle;
    @FXML
    private ToggleButton finishedToggle;
    @FXML
    private TextField searchField;
    @FXML
    private Label inProgressValue;
    @FXML
    private Label inProgressDetail;
    @FXML
    private Label todayValue;
    @FXML
    private Label todayDetail;
    @FXML
    private Label monthCaption;
    @FXML
    private Label monthValue;
    @FXML
    private Label monthDetail;
    @FXML
    private Label pendingValue;
    @FXML
    private GridPane furnaceGrid;
    @FXML
    private Label furnaceEmpty;
    @FXML
    private Label agendaTitle;
    @FXML
    private Label agendaSubtitle;
    @FXML
    private Button rangeButton;
    @FXML
    private VBox agendaRows;
    @FXML
    private Label agendaEmpty;
    @FXML
    private Label stockSubtitle;
    @FXML
    private Label stockAlert;
    @FXML
    private VBox providerRows;
    @FXML
    private Label providerEmpty;
    @FXML
    private VBox purchaseRows;
    @FXML
    private Label purchaseEmpty;
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

    private Screen screen;
    private Stock stock;
    private boolean weekRange;
    private Long drawerBatchId;
    private int generation;
    private SequentialTransition toastMotion;
    private Timeline minuteRefresh;

    public CremationController(CremationService cremationService, CremationStockService stockService) {
        this.cremationService = cremationService;
        this.stockService = stockService;
    }

    /** Lo que trae cada carga: los lotes y el saldo de cupos (cuatro consultas en total). */
    private record Loaded(Screen screen, Stock stock) {
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        load(drawerBatchId);
    }

    @FXML
    private void initialize() {
        scheduledToggle.setUserData(CremationBatchStatus.SCHEDULED);
        inProgressToggle.setUserData(CremationBatchStatus.IN_PROGRESS);
        finishedToggle.setUserData(CremationBatchStatus.FINISHED);
        stateGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un filtro elegido
            } else {
                renderAgenda();
            }
        });

        PauseTransition typing = new PauseTransition(Duration.millis(200));
        typing.setOnFinished(e -> render());
        searchField.textProperty().addListener((obs, old, text) -> typing.playFromStart());

        for (int i = 0; i < COLUMNS; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            furnaceGrid.getColumnConstraints().add(column);
        }

        drawer.setTranslateX(DRAWER_WIDTH);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);

        // Cada minuto se vuelve a pedir la pantalla (dos consultas) para seguir la hora real.
        minuteRefresh = new Timeline(new KeyFrame(Duration.seconds(60), e -> {
            if (root.getScene() != null) {
                load(drawerBatchId);
            }
        }));
        minuteRefresh.setCycleCount(Timeline.INDEFINITE);
        minuteRefresh.play();

        load(null);
    }

    // ---------- carga ----------

    /** Carga la pantalla. Si reopenBatchId no es null, se vuelve a pintar su panel con datos frescos. */
    private void load(Long reopenBatchId) {
        int request = ++generation;
        if (screen == null) {
            agendaEmpty.setText("Cargando cremaciones…");
            show(agendaEmpty, true);
        }
        async("cremation-screen", () -> new Loaded(cremationService.screen(), stockService.stock()), loaded -> {
            if (request != generation) {
                return;
            }
            Screen result = loaded.screen();
            screen = result;
            stock = loaded.stock();
            fillKpis(result);
            render();
            renderStock();
            if (reopenBatchId != null) {
                Optional<Batch> batch = find(reopenBatchId);
                if (batch.isPresent()) {
                    fillDrawer(batch.get());
                } else {
                    closeDrawer();
                }
            }
        }, failure -> {
            if (request == generation) {
                agendaEmpty.setText("");
                showError(failure instanceof BusinessException business ? business.getMessage()
                        : "No se pudieron cargar las cremaciones.");
            }
        });
    }

    private void fillKpis(Screen s) {
        Kpis k = s.kpis();
        inProgressValue.setText(String.valueOf(k.inProgress()));
        inProgressDetail.setText(k.inProgressDetail() == null ? "ningún lote en el horno" : k.inProgressDetail());
        todayValue.setText(String.valueOf(k.scheduledToday()));
        todayDetail.setText(k.batchesToday() == 0 ? "sin lotes hoy"
                : count(k.batchesToday(), "lote", "lotes"));
        monthCaption.setText("REALIZADAS EN " + MONTH.format(k.month()).toUpperCase(ES));
        monthValue.setText(String.valueOf(k.finishedThisMonth()));
        monthDetail.setText(k.batchesThisMonth() == 0 ? "sin cremaciones terminadas"
                : "en " + count((int) k.batchesThisMonth(), "lote", "lotes"));
        pendingValue.setText(String.valueOf(k.pendingCertificates()));

        long scheduled = s.batches().stream().filter(b -> b.status() == CremationBatchStatus.SCHEDULED).count();
        long inProgress = s.batches().stream().filter(b -> b.status() == CremationBatchStatus.IN_PROGRESS).count();
        long finished = s.batches().stream().filter(b -> b.status() == CremationBatchStatus.FINISHED).count();
        scheduledToggle.setText("Programados · " + scheduled);
        inProgressToggle.setText("En curso · " + inProgress);
        finishedToggle.setText("Realizados · " + finished);
    }

    private void render() {
        renderFurnaces();
        renderAgenda();
    }

    // ---------- hornos o proveedores ----------

    private void renderFurnaces() {
        furnaceGrid.getChildren().clear();
        if (screen == null) {
            return;
        }
        String text = searchText();
        List<Furnace> furnaces = screen.furnaces().stream()
                .filter(f -> text.isEmpty() || f.code().toLowerCase(ES).contains(text)
                        || matches(f.current(), text) || matches(f.next(), text))
                .toList();
        if (furnaces.isEmpty()) {
            furnaceEmpty.setText(screen.furnaces().isEmpty()
                    ? "No hay lotes abiertos. Use «Programar lote» con el código del horno o del proveedor que hará la cremación."
                    : "Ningún horno o proveedor coincide con la búsqueda.");
            show(furnaceEmpty, true);
            return;
        }
        show(furnaceEmpty, false);
        for (int i = 0; i < furnaces.size(); i++) {
            VBox card = furnaceCard(furnaces.get(i));
            card.setMaxWidth(Double.MAX_VALUE);
            card.setMaxHeight(Double.MAX_VALUE);
            GridPane.setFillWidth(card, true);
            GridPane.setFillHeight(card, true);
            furnaceGrid.add(card, i % COLUMNS, i / COLUMNS);
        }
    }

    private VBox furnaceCard(Furnace f) {
        LocalDate today = screen.now().toLocalDate();
        boolean inUse = f.state() == FurnaceState.IN_USE;
        Label caption = label("HORNO O PROVEEDOR", "furnace-caption");
        Label name = wrapped(f.code(), "furnace-name");
        VBox titles = new VBox(0, caption, name);
        HBox.setHgrow(titles, Priority.ALWAYS);
        Label chip = label(inUse ? "●  En uso" : "Programado", "state-chip");
        chip.getStyleClass().add(inUse ? "state-dark" : "state-warn");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(10, titles, chip);
        header.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(header);
        card.getStyleClass().add("furnace-card");
        Batch main = inUse ? f.current() : f.next();
        if (inUse) {
            card.getStyleClass().add("in-use");
        }
        card.getChildren().add(label(main.code() + " · " + count(main.items().size(), "difunto", "difuntos"),
                "furnace-batch"));
        String detail = inUse
                ? "Entregado · programado " + dayAndHour(main.scheduledAt(), today)
                + (main.pendingCertificates() > 0 ? " · " + count((int) main.pendingCertificates(),
                "certificado pendiente", "certificados pendientes") : "")
                : "Programado " + dayAndHour(main.scheduledAt(), today);
        card.getChildren().add(wrapped(detail, "furnace-detail"));

        VBox box = new VBox(2);
        box.getStyleClass().add("furnace-box");
        if (inUse) {
            box.getChildren().addAll(label("DIFUNTOS EN EL LOTE", "furnace-box-caption"),
                    wrapped(names(main), "furnace-box-name"),
                    wrapped(thanatopractors(main), "furnace-box-detail"));
            if (f.next() != null) {
                card.getChildren().add(wrapped("Siguiente: " + f.next().code() + " "
                        + dayAndHour(f.next().scheduledAt(), today), "furnace-detail"));
            }
        } else {
            Item first = main.items().isEmpty() ? null : main.items().get(0);
            box.getChildren().addAll(label("SIGUIENTE", "furnace-box-caption"),
                    wrapped(first == null ? "Sin difuntos todavía" : names(main), "furnace-box-name"),
                    wrapped(first == null ? "Agregue difuntos desde el panel del lote"
                            : thanatopractors(main), "furnace-box-detail"));
        }
        Pane grow = new Pane();
        VBox.setVgrow(grow, Priority.ALWAYS);
        card.getChildren().addAll(grow, box);
        card.setOnMouseClicked(e -> openDrawer(main.id()));
        return card;
    }

    // ---------- agenda ----------

    private void renderAgenda() {
        agendaRows.getChildren().clear();
        if (screen == null) {
            return;
        }
        LocalDateTime now = screen.now();
        LocalDate today = now.toLocalDate();
        String text = searchText();
        CremationBatchStatus filter = stateGroup.getSelectedToggle() == null ? null
                : (CremationBatchStatus) stateGroup.getSelectedToggle().getUserData();
        // Con búsqueda o con "Realizados" se muestra todo lo cargado (abiertos y últimos 30 días).
        boolean everything = !text.isEmpty() || filter == CremationBatchStatus.FINISHED;
        List<Batch> rows = screen.batches().stream()
                .filter(b -> filter == null || b.status() == filter)
                .filter(b -> text.isEmpty() || matches(b, text))
                .filter(b -> everything || inRange(b, today))
                .toList();

        if (everything) {
            agendaTitle.setText(text.isEmpty() ? "Lotes realizados" : "Resultados de la búsqueda");
            agendaSubtitle.setText("Lotes abiertos y de los últimos " + CremationService.FINISHED_DAYS_SHOWN
                    + " días · orden cronológico");
        } else if (weekRange) {
            agendaTitle.setText("Cremaciones de la semana");
            agendaSubtitle.setText(capitalize(DAY_TEXT.format(today)) + " al "
                    + DAY_TEXT.format(today.plusDays(WEEK_DAYS - 1)) + " · orden cronológico");
        } else {
            agendaTitle.setText("Cremaciones de hoy");
            agendaSubtitle.setText(capitalize(DAY_TEXT.format(today)) + " · orden cronológico");
        }
        rangeButton.setText(weekRange ? "←  Ver hoy" : "Ver semana  →");
        show(rangeButton, !everything);

        if (rows.isEmpty()) {
            agendaEmpty.setText(everything ? "No hay lotes que coincidan."
                    : weekRange ? "No hay lotes programados esta semana." : "No hay lotes para hoy.");
            show(agendaEmpty, true);
            return;
        }
        show(agendaEmpty, false);
        for (Batch batch : rows) {
            agendaRows.getChildren().add(agendaRow(batch, today));
        }
    }

    /** Hoy: lo de hoy y lo abierto de días anteriores. Semana: lo de los próximos 7 días y lo abierto anterior. */
    private boolean inRange(Batch b, LocalDate today) {
        if (b.scheduledAt() == null) {
            return b.status() != CremationBatchStatus.FINISHED;
        }
        LocalDate day = b.scheduledAt().toLocalDate();
        LocalDate end = weekRange ? today.plusDays(WEEK_DAYS) : today.plusDays(1);
        if (day.isBefore(today)) {
            return b.status() != CremationBatchStatus.FINISHED;
        }
        return day.isBefore(end);
    }

    private HBox agendaRow(Batch b, LocalDate today) {
        VBox hour = new VBox(0, label(b.scheduledAt() == null ? "—" : HOUR.format(b.scheduledAt()), "agenda-hour"));
        if (b.scheduledAt() != null && !b.scheduledAt().toLocalDate().equals(today)) {
            hour.getChildren().add(label(shortDay(b.scheduledAt().toLocalDate(), today), "agenda-day"));
        }
        fixed(hour, 110);

        VBox batch = new VBox(1, wrapped("Lote " + b.code() + " · " + b.furnaceCode(), "agenda-batch"));
        grow(batch);
        VBox people = new VBox(1, wrapped(b.items().isEmpty() ? "Sin difuntos todavía" : names(b),
                b.items().isEmpty() ? "agenda-muted" : "agenda-text"));
        grow(people);

        Label authorization = wrapped(authorization(b), b.blocked() ? "agenda-alert" : "agenda-text");
        VBox authBox = new VBox(authorization);
        fixed(authBox, 190);

        HBox stateBox = new HBox(stateChip(b));
        stateBox.setAlignment(Pos.CENTER_LEFT);
        fixed(stateBox, 200);

        HBox row = new HBox(12, hour, batch, people, authBox, stateBox);
        row.getStyleClass().add("agenda-row");
        row.setOnMouseClicked(e -> openDrawer(b.id()));
        return row;
    }

    /** Texto de la columna Autorización. */
    private static String authorization(Batch b) {
        if (b.items().isEmpty()) {
            return "—";
        }
        if (b.blocked()) {
            return "Pendiente orden de la Fiscalía";
        }
        boolean nonNatural = b.items().stream().anyMatch(i -> i.deathType() == DeathType.NON_NATURAL);
        return nonNatural ? "Fiscalía · orden registrada" : "Muerte natural";
    }

    private static Label stateChip(Batch b) {
        String text = switch (b.status()) {
            case SCHEDULED -> b.blocked() ? "Bloqueado · Fiscalía" : "Programado";
            case IN_PROGRESS -> b.pendingCertificates() > 0
                    ? "En curso · " + count((int) b.pendingCertificates(), "certificado", "certificados")
                    : "En curso";
            case FINISHED -> "●  Realizado";
        };
        String style = switch (b.status()) {
            case SCHEDULED -> b.blocked() ? "state-alert" : "state-warn";
            case IN_PROGRESS -> "state-dark";
            case FINISHED -> "state-ok";
        };
        Label chip = label(text, "state-chip");
        chip.getStyleClass().add(style);
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    @FXML
    private void onToggleRange() {
        weekRange = !weekRange;
        renderAgenda();
    }

    // ---------- panel del lote ----------

    private void openDrawer(Long batchId) {
        find(batchId).ifPresent(batch -> {
            fillDrawer(batch);
            slideDrawerIn();
        });
    }

    private void fillDrawer(Batch b) {
        drawerBatchId = b.id();
        LocalDate today = screen.now().toLocalDate();
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close-light");
        close.setOnAction(e -> closeDrawer());
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(label("LOTE DE CREMACIÓN", "drawer-head-caption"), spacer, close);
        top.setAlignment(Pos.CENTER_LEFT);
        Label chip = stateChip(b);
        if (b.status() == CremationBatchStatus.IN_PROGRESS) {
            chip.getStyleClass().setAll("state-chip", "state-ok");
        }
        HBox nameRow = new HBox(12, label(b.code(), "drawer-head-name"), chip);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label where = wrapped("Horno o proveedor " + b.furnaceCode() + " · "
                + (b.scheduledAt() == null ? "sin fecha" : DATE_HOUR.format(b.scheduledAt())), "drawer-head-strong");
        drawerHead.getChildren().setAll(top, nameRow, where);

        VBox members = new VBox(10);
        if (b.items().isEmpty()) {
            members.getChildren().add(wrapped("Este lote todavía no tiene difuntos.", "field-caption"));
        }
        for (Item item : b.items()) {
            members.getChildren().add(memberRow(b, item));
        }

        drawerBody.getChildren().setAll(
                section("DIFUNTOS EN EL LOTE · " + b.items().size(), members),
                section("QUÉ SIGUE", note(nextStep(b, today))));

        drawerActions.getChildren().clear();
        if (b.status() == CremationBatchStatus.SCHEDULED) {
            Button reschedule = drawerButton("Reprogramar", "drawer-secondary");
            reschedule.setOnAction(e -> openRescheduleDialog(b));
            Button add = drawerButton("Agregar difunto", "drawer-secondary");
            add.setOnAction(e -> openAssignDialog(b));
            Button start = drawerButton("Entregar al horno", "drawer-primary");
            start.setDisable(b.items().isEmpty() || b.blocked());
            start.setOnAction(e -> confirmStart(b));
            drawerActions.getChildren().setAll(reschedule, add, start);
        } else {
            Button done = drawerButton("Cerrar", "drawer-secondary");
            done.setOnAction(e -> closeDrawer());
            drawerActions.getChildren().setAll(done);
        }
    }

    private VBox memberRow(Batch b, Item item) {
        VBox row = new VBox(4);
        row.getStyleClass().add("member-row");
        row.getChildren().add(wrapped(item.deceasedName(), "member-name"));
        row.getChildren().add(wrapped(item.deceasedCode()
                + (item.documentNumber() == null ? " · sin documento" : " · Doc. " + item.documentNumber())
                + " · " + deathType(item.deathType()), "member-detail"));
        if (item.deathType() == DeathType.NON_NATURAL) {
            row.getChildren().add(wrapped(item.authorized()
                    ? "Orden de entrega de la Fiscalía registrada"
                    : "Falta la orden de entrega de la Fiscalía", item.authorized() ? "member-ok" : "member-alert"));
        }
        row.getChildren().add(wrapped("Tanatólogo: " + orDash(item.thanatopractor()), "member-detail"));
        if (item.certificatePending()) {
            row.getChildren().add(wrapped(b.status() == CremationBatchStatus.SCHEDULED
                    ? "Certificado: se registra cuando el horno termine" : "Certificado pendiente", "member-detail"));
        } else {
            row.getChildren().add(wrapped("Certificado " + item.certificateNumber() + " · terminó "
                    + DATE_HOUR.format(item.completedAt()), "member-ok"));
        }

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (b.status() == CremationBatchStatus.SCHEDULED) {
            Button remove = new Button("Quitar del lote");
            remove.getStyleClass().addAll("link-action", "link-danger");
            remove.setOnAction(e -> confirmRemove(b, item));
            actions.getChildren().add(remove);
        } else if (b.status() == CremationBatchStatus.IN_PROGRESS && item.certificatePending()) {
            Button certificate = new Button("Registrar certificado");
            certificate.getStyleClass().add("small-dark-button");
            certificate.setOnAction(e -> openCertificateDialog(b, item));
            actions.getChildren().add(certificate);
        }
        if (!actions.getChildren().isEmpty()) {
            row.getChildren().add(actions);
        }
        return row;
    }

    private static String nextStep(Batch b, LocalDate today) {
        return switch (b.status()) {
            case SCHEDULED -> b.items().isEmpty()
                    ? "Agregue los difuntos que van en este lote. Una muerte no natural necesita la orden de entrega "
                    + "de la Fiscalía registrada en Difuntos."
                    : "Cuando el cuerpo salga hacia el horno o el proveedor, use «Entregar al horno». Después se "
                    + "registra el certificado de cada cremación.";
            case IN_PROGRESS -> b.pendingCertificates() == 0
                    ? "Todas las cremaciones tienen certificado."
                    : "Registre el número del certificado y la hora en que terminó cada cremación. Al registrar "
                    + "el último, el lote queda «Realizado».";
            case FINISHED -> "Lote realizado: todas sus cremaciones tienen certificado.";
        };
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
        drawerBatchId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerBatchId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    // ---------- diálogos ----------

    /** Código del horno o proveedor y fecha y hora; no se acepta una fecha pasada. */
    @FXML
    private void onScheduleBatch() {
        ComboBox<ProviderStock> code = providerCombo(null);
        LocalDateTime suggested = nextHalfHour(LocalDateTime.now(AccessLogService.APP_ZONE));
        DatePicker date = new DatePicker(suggested.toLocalDate());
        ComboBox<LocalTime> time = timeCombo(suggested.toLocalTime(), 30);

        Form form = new Form()
                .section("Horno o proveedor", 1)
                .field("HORNO O PROVEEDOR *", code)
                .section("Fecha y hora programadas", 2)
                .field("FECHA *", date)
                .field("HORA *", time)
                .note(note("La funeraria no tiene horno propio: elija el proveedor externo que hará la cremación. "
                        + "Cada difunto del lote usa un cupo prepagado de ese proveedor. "
                        + "La fecha y la hora no pueden ser anteriores a la actual."))
                .note(noProvidersNote(code));
        Dialog<ButtonType> dialog = formDialog("Programar lote", "Los campos con * son obligatorios.", form, 640);
        runForm(dialog, form, "Programar lote",
                () -> cremationService.schedule(new CremationBatchData(codeOf(code), at(date.getValue(), time.getValue()))),
                saved -> {
                    showToast("Lote " + saved.code() + " programado.");
                    drawerBatchId = saved.id();
                    load(saved.id());
                    slideDrawerIn();
                });
    }

    /**
     * Cambia la fecha, la hora o el horno de un lote programado. Los difuntos siguen en el lote.
     * Se abre con los datos actuales; la hora actual se agrega a la lista si no cae en la media hora.
     */
    private void openRescheduleDialog(Batch b) {
        ComboBox<ProviderStock> code = providerCombo(b.furnaceCode());
        DatePicker date = new DatePicker(b.scheduledAt().toLocalDate());
        LocalTime current = b.scheduledAt().toLocalTime();
        ComboBox<LocalTime> time = timeCombo(current, 30);
        if (!time.getItems().contains(current)) {
            time.getItems().add(current);
            time.getItems().sort(null);
            time.setValue(current);
        }

        Form form = new Form()
                .section("Horno o proveedor", 1)
                .field("HORNO O PROVEEDOR *", code)
                .section("Nueva fecha y hora", 2)
                .field("FECHA *", date)
                .field("HORA *", time)
                .note(note(switch (b.items().size()) {
                    case 0 -> "El lote aún no tiene difuntos. ";
                    case 1 -> "El difunto del lote se queda como está. ";
                    default -> "Los " + b.items().size() + " difuntos del lote se quedan como están. ";
                } + "Si cambia de proveedor, los cupos se separan del nuevo. La nueva fecha y "
                        + "hora no pueden ser anteriores a la actual. El cambio queda en la bitácora."));
        Dialog<ButtonType> dialog = formDialog("Reprogramar lote " + b.code(), "Los campos con * son obligatorios.",
                form, 640);
        runForm(dialog, form, "Reprogramar", () -> {
            cremationService.reschedule(b.id(), new CremationBatchData(codeOf(code), at(date.getValue(), time.getValue())));
            return b.id();
        }, id -> {
            showToast("Lote " + b.code() + " reprogramado.");
            load(id);
        });
    }

    /**
     * Difunto, tanatólogo y, si el contrato activo no tiene la preparación registrada,
     * cuándo terminó. Muerte no natural sin orden de entrega: no se puede agregar.
     */
    private void openAssignDialog(Batch b) {
        TextField search = new TextField();
        search.setPromptText("Nombre, apellido o documento");
        ComboBox<Candidate> deceased = new ComboBox<>();
        deceased.setPromptText("Seleccione el difunto");
        deceased.getStyleClass().add("form-combo");
        deceased.setMaxWidth(Double.MAX_VALUE);
        deceased.setConverter(converter(c -> c.code() + " · " + c.name()
                + (c.documentNumber() == null ? "" : " · " + c.documentNumber()), ""));

        ComboBox<Option> thanatopractor = new ComboBox<>();
        thanatopractor.setPromptText("Cargando tanatólogos…");
        thanatopractor.getStyleClass().add("form-combo");
        thanatopractor.setMaxWidth(Double.MAX_VALUE);
        thanatopractor.setConverter(converter(Option::label, ""));

        LocalDateTime nowTime = LocalDateTime.now(AccessLogService.APP_ZONE);
        DatePicker prepDate = new DatePicker(nowTime.toLocalDate());
        ComboBox<LocalTime> prepTime = timeCombo(nowTime.toLocalTime().truncatedTo(ChronoUnit.HOURS), 15);
        Label status = note("");
        Label alert = alertNote("");

        Form form = new Form()
                .section("Lote", 2)
                .field("LOTE", readOnly(b.code() + " · " + b.furnaceCode()))
                .field("PROGRAMADO", readOnly(b.scheduledAt() == null ? "—" : DATE_HOUR.format(b.scheduledAt())))
                .section("Difunto", 2)
                .field("BUSCAR DIFUNTO", search)
                .field("DIFUNTO *", deceased)
                .note(status)
                .note(alert)
                .section("Preparación del cuerpo", 3)
                .field("TANATÓLOGO *", thanatopractor);
        VBox prepDateField = form.fieldBox("TERMINÓ LA PREPARACIÓN (FECHA) *", prepDate);
        VBox prepTimeField = form.fieldBox("HORA *", prepTime);
        form.note(note("Solo aparecen difuntos sin cremación, que no están en una bóveda y sin una exhumación con "
                + "otro destino. El tanatólogo nunca registra su propia preparación: la registra el administrador."));
        Optional<ProviderStock> provider = provider(b.furnaceCode());
        long free = provider.map(ProviderStock::available).orElse(0L);
        form.note(free > 0
                ? note("Al agregarlo se separa 1 cupo prepagado de " + b.furnaceCode() + ". Disponibles: " + free + ".")
                : alertNote("El proveedor " + b.furnaceCode() + " no tiene cremaciones prepagadas disponibles. "
                + "Reciba una compra en Cupos prepagados antes de agregar difuntos."));
        showField(prepDateField, false);
        showField(prepTimeField, false);

        async("cremation-thanatopractors", cremationService::thanatopractors, options -> {
            thanatopractor.getItems().setAll(options);
            thanatopractor.setPromptText(options.isEmpty() ? "No hay tanatólogos activos" : "Seleccione el tanatólogo");
            applyCandidate(deceased.getValue(), thanatopractor, prepDateField, prepTimeField, status, alert);
        }, failure -> thanatopractor.setPromptText("No se pudo cargar la lista"));

        deceased.valueProperty().addListener((obs, old, c) ->
                applyCandidate(c, thanatopractor, prepDateField, prepTimeField, status, alert));
        bindSearch(search, deceased, cremationService::searchCandidates, status);

        Dialog<ButtonType> dialog = formDialog("Agregar difunto al lote " + b.code(),
                "Los campos con * son obligatorios.", form, 820);
        runForm(dialog, form, "Agregar al lote", () -> {
            Candidate c = deceased.getValue();
            boolean asksPreparation = prepDateField.isVisible();
            cremationService.assign(new AssignCremationData(b.id(), c == null ? null : c.deceasedId(),
                    thanatopractor.getValue() == null ? null : thanatopractor.getValue().id(),
                    asksPreparation ? at(prepDate.getValue(), prepTime.getValue()) : null));
            return c;
        }, c -> {
            showToast(c.name() + " agregado al lote " + b.code() + ".");
            load(b.id());
        });
    }

    /** Ajusta el diálogo al difunto elegido: contrato, preparación ya registrada y el control de la Fiscalía. */
    private static void applyCandidate(Candidate c, ComboBox<Option> thanatopractor, VBox prepDate, VBox prepTime,
                                       Label status, Label alert) {
        alert.setText("");
        if (c == null) {
            thanatopractor.setDisable(false);
            showField(prepDate, false);
            showField(prepTime, false);
            return;
        }
        if (!c.authorized()) {
            alert.setText(c.deathType() == null
                    ? "El difunto no tiene registrado el tipo de muerte. Regístrelo en Difuntos antes de programarlo."
                    : "Cremación no autorizada: pendiente autorización de la Fiscalía. Registre la orden de entrega "
                    + "del cuerpo en Difuntos y vuelva a intentarlo.");
        }
        boolean hasContract = c.contractId() != null;
        if (hasContract && c.preparationRegistered()) {
            Option registered = thanatopractor.getItems().stream()
                    .filter(o -> o.id().equals(c.thanatopractorId())).findFirst()
                    .orElse(c.thanatopractorId() == null ? null : new Option(c.thanatopractorId(), c.thanatopractor()));
            thanatopractor.setValue(registered);
            thanatopractor.setDisable(registered != null);
            status.setText("Contrato " + c.contractNumber() + ": preparación registrada el "
                    + DATE_HOUR.format(c.preparationCompletedAt())
                    + (c.thanatopractor() == null ? "." : " por " + c.thanatopractor() + "."));
            showField(prepDate, false);
            showField(prepTime, false);
        } else {
            thanatopractor.setDisable(false);
            status.setText(hasContract
                    ? "Contrato " + c.contractNumber() + " sin preparación registrada: al agregarlo queda registrada "
                    + "a nombre del tanatólogo elegido."
                    : "El difunto no tiene un contrato de servicio activo (por ejemplo, viene de una exhumación).");
            showField(prepDate, hasContract);
            showField(prepTime, hasContract);
        }
    }

    /** Número del certificado y la fecha y hora en que terminó la cremación. */
    private void openCertificateDialog(Batch b, Item item) {
        TextField number = new TextField();
        number.setPromptText("Número del certificado");
        LocalDateTime nowTime = LocalDateTime.now(AccessLogService.APP_ZONE);
        DatePicker date = new DatePicker(nowTime.toLocalDate());
        ComboBox<LocalTime> time = timeCombo(nowTime.toLocalTime().truncatedTo(ChronoUnit.HOURS), 15);
        Form form = new Form()
                .section("Cremación", 2)
                .field("DIFUNTO", readOnly(item.deceasedName()))
                .field("LOTE", readOnly(b.code() + " · " + b.furnaceCode()))
                .section("Certificado de cremación", 3)
                .field("NÚMERO DEL CERTIFICADO *", number)
                .field("TERMINÓ (FECHA) *", date)
                .field("HORA *", time)
                .note(note("Cuando todos los difuntos del lote tienen certificado, el lote queda «Realizado». "
                        + "La entrega de las cenizas no se registra en el sistema."));
        Dialog<ButtonType> dialog = formDialog("Registrar certificado", "Los campos con * son obligatorios.", form, 760);
        runForm(dialog, form, "Registrar certificado", () -> {
            cremationService.registerCertificate(new CremationCertificateData(item.cremationId(), number.getText(),
                    at(date.getValue(), time.getValue())));
            return item;
        }, saved -> {
            showToast("Certificado de " + saved.deceasedName() + " registrado.");
            load(b.id());
        });
    }

    private void confirmStart(Batch b) {
        if (confirm("¿Entregar el lote " + b.code() + " al horno?",
                "El lote pasa a «En curso» con " + count(b.items().size(), "difunto", "difuntos")
                        + ". Después ya no se pueden agregar ni quitar difuntos.", "Entregar al horno")) {
            async("cremation-start", () -> {
                cremationService.start(b.id());
                return b;
            }, done -> {
                showToast("Lote " + b.code() + " entregado al horno.");
                load(b.id());
            }, this::showFailure);
        }
    }

    private void confirmRemove(Batch b, Item item) {
        if (confirm("¿Quitar a " + item.deceasedName() + " del lote " + b.code() + "?",
                "El difunto queda libre para programarlo en otro lote.", "Quitar del lote")) {
            async("cremation-remove", () -> {
                cremationService.remove(item.cremationId());
                return item;
            }, done -> {
                showToast(item.deceasedName() + " quitado del lote " + b.code() + ".");
                load(b.id());
            }, this::showFailure);
        }
    }

    // ---------- cupos prepagados ----------

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));

    static {
        MONEY.setMaximumFractionDigits(0);
    }

    /** Saldo por proveedor y la lista de compras, con lo ya cargado (sin consultas nuevas). */
    private void renderStock() {
        providerRows.getChildren().clear();
        purchaseRows.getChildren().clear();
        if (stock == null) {
            return;
        }
        List<ProviderStock> providers = stock.providers();
        long available = providers.stream().filter(ProviderStock::active).mapToLong(ProviderStock::available).sum();
        stockSubtitle.setText(providers.isEmpty() ? "Registre el proveedor que hace las cremaciones para empezar."
                : count((int) available, "cremación disponible", "cremaciones disponibles")
                + " · un cupo se separa al agregar el difunto al lote y se resta al registrar el certificado");

        List<String> warnings = providers.stream().filter(ProviderStock::atMinimum)
                .map(p -> p.code() + " (" + p.available() + " de mínimo " + p.minimumAvailable() + ")").toList();
        StringBuilder alert = new StringBuilder();
        if (!warnings.isEmpty()) {
            alert.append("Llegó al mínimo de cupos: ").append(String.join(", ", warnings))
                    .append(". Solicite una compra para reponerlos.");
        }
        if (stock.requests() > 0) {
            alert.append(alert.isEmpty() ? "" : " ")
                    .append(count((int) stock.requests(), "solicitud de compra espera", "solicitudes de compra esperan"))
                    .append(" confirmación.");
        }
        stockAlert.setText(alert.toString());
        stockAlert.setGraphic(infoIcon());
        show(stockAlert, !alert.isEmpty());

        providerEmpty.setText("No hay proveedores registrados. Use «Nuevo proveedor».");
        show(providerEmpty, providers.isEmpty());
        for (ProviderStock p : providers) {
            providerRows.getChildren().add(providerRow(p));
        }

        purchaseEmpty.setText("Todavía no hay compras. Use «Solicitar compra».");
        show(purchaseEmpty, stock.purchases().isEmpty());
        for (Purchase purchase : stock.purchases()) {
            purchaseRows.getChildren().add(purchaseRow(purchase));
        }
    }

    private HBox providerRow(ProviderStock p) {
        VBox name = new VBox(1, wrapped(p.code(), "agenda-batch"), wrapped(p.name() + " · mínimo "
                + p.minimumAvailable(), "agenda-muted"));
        grow(name);
        Label available = label(String.valueOf(p.available()), "stock-number");
        if (p.atMinimum()) {
            available.getStyleClass().add("low");
        }
        VBox availableBox = new VBox(available);
        fixed(availableBox, 110);
        VBox reserved = new VBox(label(String.valueOf(p.reserved()), "stock-number"));
        fixed(reserved, 100);
        VBox used = new VBox(label(String.valueOf(p.used()), "stock-number"));
        fixed(used, 100);
        VBox incoming = new VBox(label(String.valueOf(p.incoming()), "stock-number"));
        fixed(incoming, 100);

        Label chip = label(!p.active() ? "Inactivo" : p.atMinimum() ? "Bajo el mínimo" : "Con cupos", "state-chip");
        chip.getStyleClass().add(!p.active() ? "state-warn" : p.atMinimum() ? "state-alert" : "state-ok");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        Button edit = new Button("Editar");
        edit.getStyleClass().add("link-action");
        edit.setOnAction(e -> openProviderDialog(p));
        HBox state = new HBox(10, chip, edit);
        state.setAlignment(Pos.CENTER_LEFT);
        fixed(state, 200);

        HBox row = new HBox(12, name, availableBox, reserved, used, incoming, state);
        row.getStyleClass().addAll("agenda-row", "stock-row");
        return row;
    }

    private HBox purchaseRow(Purchase p) {
        VBox code = new VBox(1, label(p.code(), "agenda-batch"), label(DATE_HOUR.format(p.requestedAt()), "agenda-day"));
        fixed(code, 110);
        VBox provider = new VBox(1, wrapped(p.providerCode() + " · " + p.providerName(), "agenda-text"),
                wrapped(p.registeredBy() == null ? "Solicitud automática" : "Registró " + p.registeredBy(),
                        "agenda-muted"));
        grow(provider);
        VBox quantity = new VBox(1, label(count(p.quantity(), "cremación", "cremaciones"), "agenda-text"),
                label(MONEY.format(p.unitPrice()) + " c/u", "agenda-muted"));
        fixed(quantity, 150);
        VBox total = new VBox(label(MONEY.format(p.total()), "agenda-text"));
        fixed(total, 130);

        Label chip = label(switch (p.status()) {
            case REQUESTED -> "Solicitada";
            case ORDERED -> "Confirmada · en camino";
            case RECEIVED -> "Recibida";
            case CANCELLED -> "Anulada";
        }, "state-chip");
        chip.getStyleClass().add(switch (p.status()) {
            case REQUESTED -> "state-warn";
            case ORDERED -> "state-dark";
            case RECEIVED -> "state-ok";
            case CANCELLED -> "state-alert";
        });
        chip.setMinWidth(Region.USE_PREF_SIZE);
        HBox state = new HBox(chip);
        state.setAlignment(Pos.CENTER_LEFT);
        fixed(state, 170);

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (p.status() == CremationPurchaseStatus.REQUESTED) {
            actions.getChildren().add(actionButton("Confirmar", "small-dark-button", () -> confirmPurchase(p)));
        } else if (p.status() == CremationPurchaseStatus.ORDERED) {
            actions.getChildren().add(actionButton("Recibir", "small-dark-button", () -> receivePurchase(p)));
        }
        if (p.status() == CremationPurchaseStatus.REQUESTED || p.status() == CremationPurchaseStatus.ORDERED) {
            Button cancel = actionButton("Anular", "link-action", () -> cancelPurchase(p));
            cancel.getStyleClass().add("link-danger");
            actions.getChildren().add(cancel);
        }
        fixed(actions, 190);

        HBox row = new HBox(12, code, provider, quantity, total, state, actions);
        row.getStyleClass().addAll("agenda-row", "stock-row");
        return row;
    }

    private static Button actionButton(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> action.run());
        return button;
    }

    @FXML
    private void onNewProvider() {
        openProviderDialog(null);
    }

    /** Nuevo proveedor o edición del nombre, el mínimo de cupos y si está activo (el código no cambia). */
    private void openProviderDialog(ProviderStock existing) {
        TextField code = new TextField(existing == null ? "" : existing.code());
        code.setPromptText("Ej.: BAQ-CREMA1");
        code.setDisable(existing != null);
        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("Nombre del crematorio o proveedor");
        TextField minimum = new TextField(existing == null ? "2" : String.valueOf(existing.minimumAvailable()));
        CheckBox active = new CheckBox("Proveedor activo");
        active.setSelected(existing == null || existing.active());
        Form form = new Form()
                .section("Proveedor", 2)
                .field("CÓDIGO *", code)
                .field("NOMBRE *", name)
                .section("Cupos", 2)
                .field("MÍNIMO DE CUPOS DISPONIBLES *", minimum)
                .field("ESTADO", active)
                .note(note("El código es el que aparece como horno o proveedor en los lotes (máximo "
                        + CremationStockService.MAX_CODE + " caracteres) y no se cambia después. Cuando los cupos "
                        + "disponibles llegan al mínimo, la pantalla avisa para solicitar otra compra."));
        Dialog<ButtonType> dialog = formDialog(existing == null ? "Nuevo proveedor" : "Editar proveedor " + existing.code(),
                "Los campos con * son obligatorios.", form, 700);
        runForm(dialog, form, "Guardar", () -> stockService.saveProvider(new CremationProviderData(
                existing == null ? null : existing.id(), code.getText(), name.getText(), integer(minimum.getText()),
                active.isSelected())), saved -> {
            showToast("Proveedor " + saved.getCode() + " guardado.");
            load(drawerBatchId);
        });
    }

    @FXML
    private void onRequestPurchase() {
        ComboBox<ProviderStock> provider = new ComboBox<>();
        provider.getStyleClass().add("form-combo");
        provider.setPromptText("Seleccione el proveedor");
        provider.setConverter(converter(p -> p.label() + " · " + p.available() + " disponibles", ""));
        if (stock != null) {
            provider.getItems().setAll(stock.providers().stream().filter(ProviderStock::active).toList());
        }
        if (provider.getItems().size() == 1) {
            provider.setValue(provider.getItems().get(0));
        }
        TextField quantity = new TextField();
        quantity.setPromptText("Ej.: 10");
        TextField price = new TextField();
        price.setPromptText("Ej.: 1800000");
        Label total = note("");
        Runnable updateTotal = () -> {
            Integer q = integer(quantity.getText());
            BigDecimal u = money(price.getText());
            total.setText(q == null || u == null || q <= 0 ? ""
                    : "Total de la compra: " + MONEY.format(u.multiply(BigDecimal.valueOf(q))) + ".");
        };
        quantity.textProperty().addListener((obs, old, text) -> updateTotal.run());
        price.textProperty().addListener((obs, old, text) -> updateTotal.run());
        Form form = new Form()
                .section("Compra en lote", 3)
                .field("PROVEEDOR *", provider)
                .field("CANTIDAD DE CREMACIONES *", quantity)
                .field("PRECIO POR CREMACIÓN (COP) *", price)
                .note(total)
                .note(note("La solicitud queda esperando confirmación. Al confirmarla se registra el gasto en "
                        + "Finanzas, y cuando lleguen los cupos se marca como recibida."));
        if (provider.getItems().isEmpty()) {
            form.note(alertNote("No hay proveedores activos. Registre uno con «Nuevo proveedor»."));
        }
        Dialog<ButtonType> dialog = formDialog("Solicitar compra de cremaciones", "Los campos con * son obligatorios.",
                form, 860);
        runForm(dialog, form, "Solicitar compra", () -> stockService.requestPurchase(new CremationPurchaseData(
                provider.getValue() == null ? null : provider.getValue().id(), integer(quantity.getText()),
                money(price.getText()))), saved -> {
            showToast("Compra " + saved.code() + " solicitada.");
            load(drawerBatchId);
        });
    }

    private void confirmPurchase(Purchase p) {
        if (confirm("¿Confirmar la compra " + p.code() + "?", count(p.quantity(), "cremación", "cremaciones")
                + " de " + p.providerCode() + " por " + MONEY.format(p.total()) + ". Se registra el gasto en "
                + "Finanzas y la compra queda en camino hasta que lleguen los cupos.", "Confirmar compra")) {
            purchaseAction(() -> stockService.confirm(p.id()), "Compra " + p.code() + " confirmada; gasto registrado.");
        }
    }

    private void receivePurchase(Purchase p) {
        if (confirm("¿Llegaron los cupos de la compra " + p.code() + "?", count(p.quantity(), "cremación queda",
                "cremaciones quedan") + " disponibles en " + p.providerCode() + ".", "Recibir cupos")) {
            purchaseAction(() -> stockService.receive(p.id()), "Compra " + p.code() + " recibida.");
        }
    }

    private void cancelPurchase(Purchase p) {
        String detail = p.status() == CremationPurchaseStatus.ORDERED
                ? "El gasto registrado en Finanzas queda anulado (no se borra)."
                : "La solicitud deja de esperar confirmación.";
        if (confirm("¿Anular la compra " + p.code() + "?", detail, "Anular compra")) {
            purchaseAction(() -> stockService.cancel(p.id()), "Compra " + p.code() + " anulada.");
        }
    }

    private void purchaseAction(Runnable action, String done) {
        async("cremation-purchase", () -> {
            action.run();
            return done;
        }, text -> {
            showToast(text);
            load(drawerBatchId);
        }, this::showFailure);
    }

    /** Lista de proveedores activos para programar o reprogramar un lote, con sus cupos disponibles. */
    private ComboBox<ProviderStock> providerCombo(String selectedCode) {
        ComboBox<ProviderStock> box = new ComboBox<>();
        box.getStyleClass().add("form-combo");
        box.setPromptText("Seleccione el proveedor");
        box.setConverter(converter(p -> p.label() + " · " + count((int) p.available(), "cupo disponible",
                "cupos disponibles"), ""));
        if (stock != null) {
            box.getItems().setAll(stock.providers().stream()
                    .filter(p -> p.active() || p.code().equals(selectedCode)).toList());
        }
        box.getItems().stream().filter(p -> p.code().equals(selectedCode)).findFirst().ifPresentOrElse(box::setValue,
                () -> {
                    if (selectedCode == null && box.getItems().size() == 1) {
                        box.setValue(box.getItems().get(0));
                    }
                });
        return box;
    }

    private static Label noProvidersNote(ComboBox<ProviderStock> box) {
        return alertNote(box.getItems().isEmpty()
                ? "No hay proveedores activos. Regístrelo primero en Cupos prepagados con «Nuevo proveedor»." : "");
    }

    private static String codeOf(ComboBox<ProviderStock> box) {
        return box.getValue() == null ? null : box.getValue().code();
    }

    private Optional<ProviderStock> provider(String code) {
        return stock == null || code == null ? Optional.empty()
                : stock.providers().stream().filter(p -> p.code().equals(code)).findFirst();
    }

    private static Integer integer(String text) {
        try {
            return text == null || text.isBlank() ? null : Integer.valueOf(text.trim().replace(".", ""));
        } catch (NumberFormatException e) {
            return -1; // el servicio responde con el mensaje de cantidad inválida
        }
    }

    /** Acepta 1800000, 1.800.000 o 1800000,50 (formato colombiano). */
    static BigDecimal money(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.trim().replace("$", "").replace(" ", "").replace(".", "").replace(",", ".");
        try {
            return new BigDecimal(clean);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO; // el servicio responde con el mensaje de precio inválido
        }
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
            fieldBox(label, input);
            return this;
        }

        /** Igual que field, pero devuelve la caja para poder ocultarla según lo que se elija. */
        VBox fieldBox(String label, Node input) {
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
            return box;
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
            async("cremation-save", save, result -> {
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
    private void bindSearch(TextField field, ComboBox<Candidate> box, Function<String, List<Candidate>> search,
                            Label emptyHint) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        int[] requestNumber = new int[1];
        Runnable refresh = () -> {
            String text = field.getText();
            int request = ++requestNumber[0];
            box.setPromptText("Buscando…");
            async("cremation-dialog-search", () -> search.apply(text), options -> {
                if (request != requestNumber[0]) {
                    return;
                }
                box.setPromptText(options.isEmpty() ? "Sin resultados" : "Seleccione de la lista");
                box.getItems().setAll(options);
                box.setValue(options.size() == 1 ? options.get(0) : null);
                if (options.isEmpty() && (text == null || text.isBlank())) {
                    emptyHint.setText("No hay difuntos para cremar. Regístrelos primero en Difuntos.");
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

    /** Confirmación con la pregunta arriba, Cancelar a la izquierda y la acción con su nombre. */
    private boolean confirm(String question, String detail, String action) {
        ButtonType yes = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE, detail, no, yes);
        alert.initOwner(window());
        alert.setTitle(action);
        alert.setHeaderText(question);
        styleDialog(alert.getDialogPane());
        ((Button) alert.getDialogPane().lookupButton(yes)).getStyleClass().add("primary-dialog-button");
        return alert.showAndWait().filter(yes::equals).isPresent();
    }

    // ---------- piezas ----------

    private static ComboBox<LocalTime> timeCombo(LocalTime initial, int stepMinutes) {
        ComboBox<LocalTime> box = new ComboBox<>();
        box.getStyleClass().add("form-combo");
        for (int minutes = 0; minutes < 24 * 60; minutes += stepMinutes) {
            box.getItems().add(LocalTime.of(minutes / 60, minutes % 60));
        }
        box.setValue(initial);
        box.setVisibleRowCount(8);
        box.setConverter(converter(HOUR::format, ""));
        return box;
    }

    private static LocalDateTime nextHalfHour(LocalDateTime time) {
        LocalDateTime base = time.truncatedTo(ChronoUnit.HOURS);
        return time.getMinute() < 30 ? base.plusMinutes(30) : base.plusHours(1);
    }

    private static LocalDateTime at(LocalDate date, LocalTime time) {
        return date == null || time == null ? null : date.atTime(time);
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

    private static Label alertNote(String text) {
        Label note = note(text);
        note.getStyleClass().setAll("label", "note-alert");
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

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    private static VBox section(String title, Node... content) {
        VBox box = new VBox(10, label(title, "drawer-section-title"));
        box.getChildren().addAll(content);
        box.getStyleClass().add("drawer-section");
        return box;
    }

    /** Las ventanas cargan app.css, personal.css y cremations.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                CremationController.class.getResource("/css/app.css").toExternalForm(),
                CremationController.class.getResource("/css/personal.css").toExternalForm(),
                CremationController.class.getResource("/css/cremations.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "cremation-root");
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showFailure(Throwable failure) {
        showError(failure instanceof BusinessException business ? business.getMessage()
                : "No se pudo completar la acción. Revise la conexión e intente de nuevo.");
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

    // ---------- utilidades ----------

    private Optional<Batch> find(Long batchId) {
        return screen == null || batchId == null ? Optional.empty()
                : screen.batches().stream().filter(b -> b.id().equals(batchId)).findFirst();
    }

    private String searchText() {
        String text = searchField.getText();
        return text == null ? "" : text.trim().toLowerCase(ES);
    }

    /** Busca en el código del lote, el horno y los nombres o documentos de sus difuntos. */
    static boolean matches(Batch b, String text) {
        if (b == null) {
            return false;
        }
        if (b.code().toLowerCase(ES).contains(text)
                || (b.furnaceCode() != null && b.furnaceCode().toLowerCase(ES).contains(text))) {
            return true;
        }
        return b.items().stream().anyMatch(i -> i.deceasedName().toLowerCase(ES).contains(text)
                || i.deceasedCode().toLowerCase(ES).contains(text)
                || (i.documentNumber() != null && i.documentNumber().contains(text)));
    }

    private static String names(Batch b) {
        return b.items().stream().map(Item::deceasedName).collect(Collectors.joining(", "));
    }

    private static String thanatopractors(Batch b) {
        String names = b.items().stream().map(Item::thanatopractor).filter(t -> t != null).distinct()
                .collect(Collectors.joining(", "));
        return names.isEmpty() ? "Tanatólogo sin registrar" : "Tanatólogo: " + names;
    }

    private static void fixed(Region region, double width) {
        region.setMinWidth(width);
        region.setPrefWidth(width);
        region.setMaxWidth(width);
    }

    private static void grow(Region region) {
        region.setPrefWidth(0);
        region.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(region, Priority.ALWAYS);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static void showField(VBox field, boolean visible) {
        field.setVisible(visible);
        field.setManaged(visible);
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

    /** "1 lote", "3 lotes". */
    static String count(int value, String one, String many) {
        return value + " " + (value == 1 ? one : many);
    }

    /** "08:00" si es hoy, "ayer 20:00", "mañana 10:00" o "25/10 10:00". */
    static String dayAndHour(LocalDateTime time, LocalDate reference) {
        if (time == null) {
            return "sin fecha";
        }
        LocalDate day = time.toLocalDate();
        String hour = HOUR.format(time);
        if (day.equals(reference)) {
            return "hoy " + hour;
        }
        if (day.equals(reference.minusDays(1))) {
            return "ayer " + hour;
        }
        if (day.equals(reference.plusDays(1))) {
            return "mañana " + hour;
        }
        return DateTimeFormatter.ofPattern("dd/MM").format(day) + " " + hour;
    }

    /** "Ayer", "Mañana" o "jue 12/11" debajo de la hora de la agenda. */
    static String shortDay(LocalDate day, LocalDate today) {
        if (day.equals(today.minusDays(1))) {
            return "Ayer";
        }
        if (day.equals(today.plusDays(1))) {
            return "Mañana";
        }
        return capitalize(DateTimeFormatter.ofPattern("EEE dd/MM", ES).format(day).replace(".", ""));
    }

    private static String deathType(DeathType type) {
        if (type == null) {
            return "tipo de muerte sin registrar";
        }
        return type == DeathType.NATURAL ? "muerte natural" : "muerte no natural";
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
