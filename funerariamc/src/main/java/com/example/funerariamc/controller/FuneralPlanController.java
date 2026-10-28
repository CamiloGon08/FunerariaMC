package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.enums.PlanPaymentStatus;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.Relationship;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.BeneficiaryData;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.FuneralPlanService.BeneficiaryInfo;
import com.example.funerariamc.service.FuneralPlanService.Kpis;
import com.example.funerariamc.service.FuneralPlanService.MonthCell;
import com.example.funerariamc.service.FuneralPlanService.MonthPoint;
import com.example.funerariamc.service.FuneralPlanService.Option;
import com.example.funerariamc.service.FuneralPlanService.Overview;
import com.example.funerariamc.service.FuneralPlanService.PaymentFilter;
import com.example.funerariamc.service.FuneralPlanService.PaymentInfo;
import com.example.funerariamc.service.FuneralPlanService.PlanCard;
import com.example.funerariamc.service.FuneralPlanService.PlanDetail;
import com.example.funerariamc.service.FuneralPlanService.PlanFilter;
import com.example.funerariamc.service.FuneralPlanService.SurchargePreview;
import com.example.funerariamc.service.FuneralPlanService.TypeSlice;
import com.example.funerariamc.service.FuneralPlanService.UsedService;
import com.example.funerariamc.service.FuneralPlanService.WaitingSoon;
import com.example.funerariamc.service.PlanData;
import com.example.funerariamc.service.PlanParameters;
import com.example.funerariamc.service.PlanPaymentData;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Pantalla Planes exequiales (propuesta P-15 / P-15.1): recaudo del mes, indicadores, gráfica de
 * recaudo contra lo esperado, planes por tipo, carencias que terminan pronto y tarjetas de
 * afiliados que se agregan al desplazar (sin paginación). El panel lateral muestra la
 * cuadrícula de cuotas, beneficiarios, pagos y servicios usados, y desde él se registra o anula
 * una cuota, se agrega un beneficiario (RF-PLA-02), se suspende, cancela o cambia el titular
 * (RF-PLA-04). Afiliar plan es RF-PLA-01.
 */
@Component
@Scope("prototype")
public class FuneralPlanController implements RefreshableView {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", ES);
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", ES);
    private static final double DRAWER_WIDTH = 460;
    /** Tarjetas que se agregan cada vez que el usuario llega al final de la página. */
    private static final int CARDS_STEP = 12;
    private static final int COLUMNS = 3;

    private final FuneralPlanService planService;
    // Límites del plan leídos de system_parameter (por ejemplo, cuotas que se pueden adelantar).
    private final PlanParameters planParameters;
    /** Paneles de plan ya consultados o adelantados; se vacía en cada recarga. */
    private final DetailCache<PlanDetail> details;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(250));

    @FXML
    private StackPane root;
    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup statusGroup;
    @FXML
    private ToggleButton activeToggle;
    @FXML
    private ToggleButton suspendedToggle;
    @FXML
    private ToggleButton cancelledToggle;
    @FXML
    private ComboBox<String> typeFilter;
    @FXML
    private ComboBox<PaymentFilter> paymentFilter;
    @FXML
    private ScrollPane pageScroll;
    @FXML
    private Label collectedCaption;
    @FXML
    private Label collectedValue;
    @FXML
    private Label collectedDetail;
    @FXML
    private Label activeValue;
    @FXML
    private Label activeDetail;
    @FXML
    private Label overdueValue;
    @FXML
    private Label overdueDetail;
    @FXML
    private Label waitingValue;
    @FXML
    private Label waitingDetail;
    @FXML
    private LineChart<String, Number> collectionChart;
    @FXML
    private CategoryAxis monthAxis;
    @FXML
    private NumberAxis amountAxis;
    @FXML
    private StackPane donut;
    @FXML
    private VBox typeLegend;
    @FXML
    private Label averageFee;
    @FXML
    private VBox waitingBox;
    @FXML
    private Label countLabel;
    @FXML
    private GridPane cardGrid;
    @FXML
    private Label emptyLabel;
    @FXML
    private VBox drawer;
    @FXML
    private Region veil;
    @FXML
    private VBox drawerBody;
    @FXML
    private HBox drawerActions;

    private Overview overview;
    private List<PlanCard> filtered = List.of();
    private int shown;
    /** Plan del panel abierto; null si está cerrado. */
    private Long drawerPlanId;
    /** Animación del panel en curso (abrir o cerrar); null si está quieto. */
    private ParallelTransition drawerMotion;

    public FuneralPlanController(FuneralPlanService planService, PlanParameters planParameters) {
        this.planService = planService;
        this.planParameters = planParameters;
        this.details = new DetailCache<>(planService::detail, "plan-detail");
    }

    /** Al volver al módulo se recargan los datos sin limpiar la pantalla; el plan abierto sigue abierto. */
    @Override
    public void refresh() {
        loadOverview(drawerPlanId);
    }

    @FXML
    private void initialize() {
        activeToggle.setUserData(PlanStatus.ACTIVE);
        suspendedToggle.setUserData(PlanStatus.SUSPENDED);
        cancelledToggle.setUserData(PlanStatus.CANCELLED);
        statusGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true);
            } else {
                renderCards();
            }
        });

        typeFilter.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText("Plan: " + (item == null ? "todos" : item));
            }
        });
        typeFilter.setPromptText("Plan: todos"); // se muestra mientras el filtro está vacío
        typeFilter.setCellFactory(list -> textCell(name -> name == null ? "Todos los planes" : name));
        typeFilter.valueProperty().addListener((obs, old, value) -> renderCards());

        paymentFilter.getItems().setAll(PaymentFilter.values());
        paymentFilter.setConverter(converter(PaymentFilter::getLabel, ""));
        paymentFilter.setValue(PaymentFilter.ALL);
        paymentFilter.valueProperty().addListener((obs, old, value) -> renderCards());

        searchDelay.setOnFinished(e -> renderCards());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        for (int i = 0; i < COLUMNS; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            cardGrid.getColumnConstraints().add(column);
        }
        // Al llegar al final de la página se agregan más tarjetas (sin paginación).
        pageScroll.vvalueProperty().addListener((obs, old, value) -> {
            if (value.doubleValue() >= 0.98 && shown < filtered.size()) {
                appendCards();
            }
        });

        amountAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return ClientController.shortPesos(BigDecimal.valueOf(value.doubleValue()));
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });
        drawer.setTranslateX(DRAWER_WIDTH);
        loadOverview(null);
    }

    private void loadOverview(Long reopenId) {
        details.clear(); // después de guardar o al volver, nada de lo guardado puede quedar viejo
        if (overview == null) {
            countLabel.setText("Cargando planes…");
        }
        run(planService::overview, result -> {
            overview = result;
            String type = typeFilter.getValue();
            List<String> names = new ArrayList<>();
            names.add(null);
            names.addAll(result.planNames());
            typeFilter.getItems().setAll(names);
            typeFilter.setValue(names.contains(type) ? type : null);
            renderKpis(result.kpis(), result.cards());
            renderChart(result.collection());
            renderTypes(result.types(), result.cards());
            renderWaiting(result.waitingSoon());
            renderCards();
            if (reopenId != null) {
                openDrawer(reopenId);
            }
            // Se adelantan los planes que más se abren: la primera fila de tarjetas y los que salen de carencia.
            List<Long> first = new ArrayList<>(filtered.stream().limit(COLUMNS).map(PlanCard::id).toList());
            result.waitingSoon().forEach(w -> first.add(w.planId()));
            details.prefetchAll(first);
        }, "No se pudieron cargar los planes.");
    }

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
        Thread thread = new Thread(task, "plans-loader");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------- indicadores, gráfica y columna derecha ----------

    private void renderKpis(Kpis k, List<PlanCard> cards) {
        YearMonth now = YearMonth.now(AccessLogService.APP_ZONE);
        collectedCaption.setText("RECAUDO DE " + MONTH.format(now).split(" ")[0].toUpperCase(ES));
        collectedValue.setText(ClientController.shortPesos(k.collectedThisMonth()));
        Double change = k.changePercent();
        String trend = change == null ? "" : (change >= 0 ? "▲ " : "▼ ")
                + String.format(ES, "%.1f", Math.abs(change)) + " % · ";
        collectedDetail.setText(trend + k.paidThisMonth() + " de " + k.activePlans() + " cuotas pagadas");

        activeValue.setText(String.valueOf(k.activePlans()));
        activeDetail.setText("+" + k.newThisMonth() + " " + (k.newThisMonth() == 1 ? "afiliación" : "afiliaciones")
                + " este mes");
        overdueValue.setText(String.valueOf(k.overduePlans()));
        overdueDetail.setText(k.overduePlans() == 0 ? "Todos al día"
                : ClientController.shortPesos(k.overdueAmount()) + " por cobrar");
        waitingValue.setText(String.valueOf(k.inWaiting()));
        waitingDetail.setText(k.waitingEndsSoon() + " la cumplen en los próximos 30 días");

        long suspended = cards.stream().filter(c -> c.status() == PlanStatus.SUSPENDED).count();
        long cancelled = cards.stream().filter(c -> c.status() == PlanStatus.CANCELLED).count();
        activeToggle.setText("Activos · " + k.activePlans());
        suspendedToggle.setText("Suspendidos · " + suspended);
        cancelledToggle.setText("Cancelados · " + cancelled);
    }

    private void renderChart(List<MonthPoint> points) {
        XYChart.Series<String, Number> collected = new XYChart.Series<>();
        XYChart.Series<String, Number> expected = new XYChart.Series<>();
        for (MonthPoint p : points) {
            String month = capitalize(SHORT_MONTH.format(p.month()).replace(".", ""));
            collected.getData().add(new XYChart.Data<>(month, p.collected()));
            expected.getData().add(new XYChart.Data<>(month, p.expected()));
        }
        collectionChart.getData().setAll(List.of(collected, expected));
        for (int i = 0; i < points.size(); i++) {
            MonthPoint p = points.get(i);
            Tooltip tip = new Tooltip(capitalize(MONTH.format(p.month())) + "\n"
                    + ClientController.pesos(p.collected()) + " de " + ClientController.pesos(p.expected()));
            Tooltip.install(collected.getData().get(i).getNode(), tip);
            Tooltip.install(expected.getData().get(i).getNode(), tip);
        }
    }

    /** Dona dibujada con arcos: un color por nombre de plan, total de activos en el centro. */
    private void renderTypes(List<TypeSlice> types, List<PlanCard> cards) {
        double radius = 54;
        Circle track = new Circle(radius);
        track.getStyleClass().add("donut-track");
        track.setStrokeWidth(16);
        Pane arcs = new Pane();
        arcs.setPrefSize(130, 130);
        track.setCenterX(65);
        track.setCenterY(65);
        arcs.getChildren().add(track);
        long total = types.stream().mapToLong(TypeSlice::plans).sum();
        double start = 90;
        typeLegend.getChildren().clear();
        for (int i = 0; i < types.size(); i++) {
            TypeSlice slice = types.get(i);
            int color = Math.min(i, 4);
            double length = total == 0 ? 0 : 360.0 * slice.plans() / total;
            Arc arc = new Arc(65, 65, radius, radius, start, -length);
            arc.setType(ArcType.OPEN);
            arc.setStrokeWidth(16);
            arc.getStyleClass().addAll("donut-arc", "donut-" + color);
            arcs.getChildren().add(arc);
            start -= length;

            Region swatch = new Region();
            swatch.getStyleClass().addAll("legend-swatch", "swatch-" + color);
            Label name = new Label(slice.name());
            name.getStyleClass().add("type-name");
            Pane gap = new Pane();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Label count = new Label(String.valueOf(slice.plans()));
            count.getStyleClass().add("type-count");
            HBox row = new HBox(8, swatch, name, gap, count);
            row.setAlignment(Pos.CENTER_LEFT);
            typeLegend.getChildren().add(row);
        }
        if (types.isEmpty()) {
            typeLegend.getChildren().add(wrapped("Todavía no hay planes activos.", "card-subtitle"));
        }
        Label value = new Label(String.valueOf(total));
        value.getStyleClass().add("donut-value");
        Label caption = new Label("planes activos");
        caption.getStyleClass().add("donut-caption");
        VBox center = new VBox(-2, value, caption);
        center.setAlignment(Pos.CENTER);
        donut.getChildren().setAll(arcs, center);

        List<PlanCard> active = cards.stream().filter(PlanCard::isActive).toList();
        BigDecimal sum = active.stream().map(PlanCard::monthlyFee).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        averageFee.setText(active.isEmpty() ? "" : "Cuota promedio "
                + ClientController.pesos(sum.divide(BigDecimal.valueOf(active.size()), java.math.RoundingMode.HALF_UP))
                + " al mes");
    }

    private void renderWaiting(List<WaitingSoon> soon) {
        waitingBox.getChildren().clear();
        if (soon.isEmpty()) {
            waitingBox.getChildren().add(wrapped("Ningún plan termina la carencia en los próximos 30 días.",
                    "card-subtitle"));
            return;
        }
        LocalDate today = LocalDate.now(AccessLogService.APP_ZONE);
        for (WaitingSoon w : soon.stream().limit(6).toList()) {
            Label code = new Label(w.code());
            code.getStyleClass().addAll("attention-code", "plan-code", "code-expiring");
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, w.waitingEnds());
            VBox text = new VBox(1, wrapped(w.holderName(), "attention-name"),
                    wrapped("Cubierto desde el " + DATE.format(w.waitingEnds()) + " · en " + count(days, "día"),
                            "attention-detail-expiring"));
            HBox row = new HBox(code, text);
            row.getStyleClass().add("attention-row");
            row.setStyle("-fx-cursor: hand;");
            row.setOnMouseEntered(e -> details.prefetch(w.planId()));
            row.setOnMouseClicked(e -> openDrawer(w.planId()));
            waitingBox.getChildren().add(row);
        }
    }

    // ---------- tarjetas ----------

    private void renderCards() {
        if (overview == null) {
            return;
        }
        PlanStatus status = statusGroup.getSelectedToggle() == null ? PlanStatus.ACTIVE
                : (PlanStatus) statusGroup.getSelectedToggle().getUserData();
        filtered = FuneralPlanService.filter(overview.cards(),
                new PlanFilter(status, paymentFilter.getValue(), typeFilter.getValue(), searchField.getText()));
        cardGrid.getChildren().clear();
        shown = 0;
        appendCards();
        boolean empty = filtered.isEmpty();
        emptyLabel.setText(overview.cards().isEmpty() ? "Todavía no hay planes. Use «Afiliar plan»."
                : "Ningún plan coincide con los filtros.");
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
    }

    private void appendCards() {
        int until = Math.min(filtered.size(), shown + CARDS_STEP);
        for (int i = shown; i < until; i++) {
            cardGrid.add(planCard(filtered.get(i)), i % COLUMNS, i / COLUMNS);
        }
        shown = until;
        countLabel.setText(count(filtered.size(), "plan", "planes")
                + (shown < filtered.size() ? " · desplace para ver más" : ""));
    }

    private VBox planCard(PlanCard c) {
        Label name = wrapped(c.holderName(), "plan-holder");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox top = new HBox(8, name, statusPill(c));
        top.setAlignment(Pos.TOP_LEFT);
        String since = c.startDate() == null ? "" : " · afiliado desde " + SINCE.format(c.startDate());
        Label meta = wrapped(c.code() + since, "plan-meta");

        Label plan = new Label(c.planName());
        plan.getStyleClass().add("plan-name");
        Label fee = new Label(ClientController.pesos(c.monthlyFee()) + " / mes");
        fee.getStyleClass().add("plan-fee");
        HBox planRow = new HBox(8, plan, fee);
        planRow.setAlignment(Pos.BASELINE_LEFT);

        HBox avatars = new HBox(-6);
        Label holder = new Label(initialsOf(c.holderName()));
        holder.getStyleClass().addAll("mini-avatar", "holder");
        avatars.getChildren().add(holder);
        c.initials().stream().limit(3).forEach(text -> {
            Label avatar = new Label(text);
            avatar.getStyleClass().add("mini-avatar");
            avatars.getChildren().add(avatar);
        });
        if (c.initials().size() > 3) {
            Label more = new Label("+" + (c.initials().size() - 3));
            more.getStyleClass().add("mini-avatar");
            avatars.getChildren().add(more);
        }
        Label people = new Label(count(c.beneficiaries(), "beneficiario"));
        people.getStyleClass().add("plan-fee");
        HBox peopleRow = new HBox(8, avatars, people);
        peopleRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(new VBox(2, top, meta), planRow, peopleRow, footer(c));
        card.getStyleClass().add("plan-card");
        if (!c.isActive()) {
            card.getStyleClass().add("inactive");
        }
        card.setOnMouseEntered(e -> details.prefetch(c.id()));
        card.setOnMouseClicked(e -> openDrawer(c.id()));
        return card;
    }

    private Node footer(PlanCard c) {
        HBox footer = new HBox();
        footer.getStyleClass().add("card-footer");
        if (c.isActive() && c.inWaiting()) {
            ProgressBar bar = new ProgressBar(c.waitingDays() == 0 ? 1
                    : 1 - (double) c.waitingDaysLeft() / c.waitingDays());
            bar.getStyleClass().add("waiting-bar");
            bar.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(bar, Priority.ALWAYS);
            footer.getChildren().addAll(label("Carencia", "footer-text"), bar,
                    label(count(c.waitingDaysLeft(), "día") + " restantes", "footer-gold"));
            return footer;
        }
        Pane gap = new Pane();
        HBox.setHgrow(gap, Priority.ALWAYS);
        String last = c.lastPaidAt() == null ? "Sin cuotas pagadas" : "Último pago " + DATE.format(c.lastPaidAt());
        Label right;
        if (c.isOverdue()) {
            right = label(ClientController.pesos(c.overdueAmount()) + " en mora", "footer-alert");
        } else if (c.servicesUsed() > 0) {
            right = label(count(c.servicesUsed(), "servicio usado", "servicios usados"), "footer-strong");
        } else {
            right = label(c.isActive() ? "Cubierto" : "", "footer-strong");
        }
        footer.getChildren().addAll(label(last, "footer-text"), gap, right);
        return footer;
    }

    private static Label statusPill(PlanCard c) {
        if (c.status() == PlanStatus.SUSPENDED) {
            return pill("Suspendido", "pill-warning");
        }
        if (c.status() == PlanStatus.CANCELLED) {
            return pill("Cancelado", "pill-neutral");
        }
        if (c.isOverdue()) {
            return pill("Mora · " + count(c.overdueMonths(), "cuota"), "pill-alert");
        }
        if (c.inWaiting()) {
            return pill("En carencia", "pill-warning");
        }
        return pill("Al día", "status-active");
    }

    // ---------- panel del plan (P-15.1) ----------

    /**
     * Abre el panel al instante: con el plan si ya está guardado, o con el titular de la tarjeta y
     * "Cargando plan…" mientras llega. Si el panel ya muestra ese plan (recarga después de guardar),
     * conserva lo que se ve hasta que llegue lo nuevo.
     */
    private void openDrawer(Long planId) {
        boolean sameOpen = drawer.isVisible() && planId.equals(drawerPlanId);
        drawerPlanId = planId;
        PlanDetail cached = details.get(planId);
        if (cached != null) {
            fillDrawer(cached);
        } else {
            if (!sameOpen) {
                fillDrawerLoading(planId);
            }
            details.load(planId, detail -> {
                if (planId.equals(drawerPlanId)) {
                    fillDrawer(detail);
                }
            }, error -> {
                if (planId.equals(drawerPlanId)) {
                    closeDrawer();
                    showError(error instanceof BusinessException business ? business.getMessage()
                            : "No se pudo cargar el plan.");
                }
            });
        }
        slideIn();
    }

    private void fillDrawerLoading(Long planId) {
        PlanCard c = overview == null ? null : overview.cards().stream()
                .filter(card -> planId.equals(card.id())).findFirst().orElse(null);
        Label caption = new Label("PLAN EXEQUIAL" + (c == null ? "" : " · " + c.code()));
        caption.getStyleClass().add("kpi-caption");
        VBox titles = new VBox(2, caption, wrapped(c == null ? "Plan" : c.holderName(), "drawer-name"));
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(event -> closeDrawer());
        HBox header = new HBox(14, titles, close);
        header.setAlignment(Pos.TOP_LEFT);
        drawerBody.getChildren().setAll(header, wrapped("Cargando plan…", "card-subtitle"));
        drawerActions.getChildren().clear();
    }

    private void slideIn() {
        if (drawer.isVisible() && drawer.getTranslateX() == 0 && drawerMotion == null) {
            return;
        }
        if (drawerMotion != null) {
            drawerMotion.stop(); // si se estaba cerrando, vuelve a abrir desde donde iba
        }
        boolean hidden = !drawer.isVisible();
        veil.setVisible(true);
        drawer.setVisible(true);
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), drawer);
        slide.setToX(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), veil);
        fade.setFromValue(hidden ? 0 : veil.getOpacity());
        fade.setToValue(1);
        drawerMotion = new ParallelTransition(slide, fade);
        drawerMotion.setOnFinished(event -> drawerMotion = null);
        drawerMotion.play();
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerPlanId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        if (drawerMotion != null) {
            drawerMotion.stop();
        }
        ParallelTransition out = new ParallelTransition(slide, fade);
        drawerMotion = out;
        out.setOnFinished(event -> {
            drawerMotion = null;
            if (drawerPlanId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    private void fillDrawer(PlanDetail d) {
        PlanCard c = d.card();
        Label initials = new Label(initialsOf(c.holderName()));
        initials.getStyleClass().add("avatar-text");
        StackPane avatar = new StackPane(initials);
        avatar.getStyleClass().addAll("avatar", "avatar-large", "avatar-gold");
        avatar.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        Label caption = new Label("PLAN EXEQUIAL · " + c.code());
        caption.getStyleClass().add("kpi-caption");
        List<String> contact = new ArrayList<>();
        contact.add(c.corporate() ? "Empresa titular" : "Titular");
        if (c.holderDocument() != null) {
            contact.add(c.holderDocument());
        }
        if (d.holderPhone() != null && !d.holderPhone().isBlank()) {
            contact.add(d.holderPhone());
        }
        VBox titles = new VBox(2, caption, wrapped(c.holderName(), "drawer-name"),
                wrapped(String.join(" · ", contact), "card-subtitle"));
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(event -> closeDrawer());
        HBox header = new HBox(14, avatar, titles, close);
        header.setAlignment(Pos.TOP_LEFT);

        FlowPane pills = new FlowPane(8, 8, statusPill(c),
                c.inWaiting() ? pill("Carencia hasta " + DATE.format(c.waitingEnds()), "pill-warning")
                        : pill("Carencia cumplida", "status-active"),
                pill(c.planName() + " · " + ClientController.pesos(c.monthlyFee()) + "/mes", "pill-neutral"));

        drawerBody.getChildren().setAll(header, pills);
        if (d.holderDeceased()) {
            drawerBody.getChildren().add(band("El titular falleció: asigne un nuevo titular para que el plan siga "
                    + "vigente (RF-PLA-04).", "band-warning"));
        }
        drawerBody.getChildren().addAll(
                titled("CUOTAS · ÚLTIMOS 12 MESES", monthGrid(d)),
                beneficiariesSection(d),
                titled("PAGOS REGISTRADOS", paymentsBox(d)),
                titled("SERVICIOS USADOS", usedBox(d.usedServices())));

        drawerActions.getChildren().clear();
        Button status = drawerButton(c.status() == PlanStatus.SUSPENDED ? "Reactivar plan" : "Suspender plan",
                "drawer-secondary");
        status.setDisable(c.status() == PlanStatus.CANCELLED);
        status.setOnAction(e -> {
            if (c.status() == PlanStatus.SUSPENDED) {
                confirmAndRun("Reactivar plan", "¿Reactivar el plan " + c.code() + "?",
                        "Vuelve a cubrir a sus beneficiarios.", "Reactivar", false, () -> planService.reactivate(c.id()));
            } else {
                confirmAndRun("Suspender plan", "¿Suspender el plan " + c.code() + "?",
                        "Mientras esté suspendido no cubre servicios ni recibe beneficiarios nuevos.", "Suspender", true,
                        () -> planService.suspend(c.id()));
            }
        });
        // "Más" abre un menú con las acciones menos frecuentes (RF-PLA-04).
        Button more = drawerButton("Más…", "drawer-secondary");
        MenuItem holder = new MenuItem("Cambiar titular");
        holder.setOnAction(e -> openChangeHolderDialog(c));
        MenuItem cancel = new MenuItem("Cancelar plan");
        cancel.setOnAction(e -> confirmAndRun("Cancelar plan", "¿Cancelar definitivamente el plan " + c.code() + "?",
                "No se borra: queda en «Cancelados» con sus cuotas.", "Cancelar plan", true,
                () -> planService.cancel(c.id())));
        ContextMenu menu = new ContextMenu(holder, cancel);
        more.setOnAction(e -> menu.show(more, javafx.geometry.Side.TOP, 0, -4));
        more.setDisable(c.status() == PlanStatus.CANCELLED);
        Button pay = drawerButton("Registrar pago", "drawer-primary");
        pay.setDisable(c.status() == PlanStatus.CANCELLED);
        pay.setOnAction(e -> openPaymentDialog(d));
        drawerActions.getChildren().addAll(status, more, pay);
    }

    private VBox monthGrid(PlanDetail d) {
        HBox cells = new HBox(5);
        for (MonthCell cell : d.grid()) {
            Region box = new Region();
            box.getStyleClass().addAll("month-cell", "month-" + cell.state().name().toLowerCase(Locale.ROOT));
            Tooltip.install(box, new Tooltip(capitalize(MONTH.format(cell.month())) + ": " + monthStateLabel(cell)));
            Label month = new Label(capitalize(SHORT_MONTH.format(cell.month()).replace(".", "")));
            month.getStyleClass().add("month-label");
            VBox column = new VBox(3, box, month);
            column.setAlignment(Pos.CENTER);
            HBox.setHgrow(column, Priority.ALWAYS);
            cells.getChildren().add(column);
        }
        HBox legendRow = new HBox(12, legendSwatch("month-paid_on_time", "A tiempo"),
                legendSwatch("month-paid_late", "Con retraso"), legendSwatch("month-unpaid", "Sin pagar"));
        legendRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, cells, legendRow);
        if (d.nextDue() != null) {
            box.getChildren().add(label("Próxima cuota: " + capitalize(MONTH.format(d.nextDue())) + ", vence el "
                    + DATE.format(FuneralPlanService.dueDate(d.card().startDate(), d.nextDue())), "cell-strong"));
        }
        box.getStyleClass().add("section-box");
        return box;
    }

    private VBox beneficiariesSection(PlanDetail d) {
        PlanCard c = d.card();
        Label caption = new Label("BENEFICIARIOS · " + d.beneficiaries().size()
                + (c.corporate() ? "" : " DE " + d.beneficiaryLimit()));
        caption.getStyleClass().add("kpi-caption");
        Pane gap = new Pane();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button add = new Button("+ Agregar");
        add.getStyleClass().add("text-action");
        add.setDisable(!c.isActive());
        add.setOnAction(e -> openBeneficiaryDialog(d));
        HBox head = new HBox(caption, gap, add);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox();
        list.getStyleClass().add("section-box");
        list.getChildren().add(personRow(initialsOf(c.holderName()), c.holderName(),
                (c.corporate() ? "Empresa titular" : "Titular") + " · " + orDash(c.holderDocument()),
                d.holderDeceased() ? pill("Falleció", "pill-neutral") : coveredPill(c)));
        for (BeneficiaryInfo b : d.beneficiaries()) {
            String detail = b.relationshipLabel() + " · CC " + orDash(b.documentNumber());
            if (b.isDeceased()) {
                detail += " · falleció " + (b.deathDate() == null ? "" : DATE.format(b.deathDate()));
            }
            HBox row = personRow(initialsOf(b.fullName()), b.fullName(), detail,
                    b.isDeceased() ? pill("Usó el plan", "pill-neutral") : coveredPill(c));
            if (b.surcharge().signum() > 0) {
                ((VBox) row.getChildren().get(1)).getChildren().add(
                        label("Recargo " + ClientController.pesos(b.surcharge()) + " · " + b.surchargeReason(), "surcharge"));
            }
            list.getChildren().add(row);
        }
        if (d.beneficiaries().isEmpty()) {
            list.getChildren().add(wrapped("Sin beneficiarios. El titular está cubierto.", "card-subtitle"));
        }
        if (d.totalSurcharge().signum() > 0) {
            list.getChildren().add(wrapped("Recargos: " + ClientController.pesos(d.totalSurcharge())
                    + ", se cobran una vez al vender el servicio (RN-09, RN-10).", "card-subtitle"));
        }
        return new VBox(8, head, list);
    }

    private static Label coveredPill(PlanCard c) {
        return c.isActive() && !c.inWaiting() ? pill("Cubierto", "status-active")
                : c.isActive() ? pill("En carencia", "pill-warning") : pill("Sin cobertura", "pill-neutral");
    }

    private static HBox personRow(String initials, String name, String detail, Label state) {
        Label avatarText = new Label(initials);
        avatarText.getStyleClass().add("mini-avatar");
        avatarText.setMinSize(30, 30);
        VBox text = new VBox(1, wrapped(name, "list-name"), wrapped(detail, "list-detail"));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(avatarText, text, state);
        row.getStyleClass().add("list-row");
        return row;
    }

    private VBox paymentsBox(PlanDetail d) {
        VBox box = new VBox();
        box.getStyleClass().add("section-box");
        if (d.payments().isEmpty()) {
            box.getChildren().add(wrapped("Todavía no hay cuotas registradas.", "card-subtitle"));
            return box;
        }
        for (PaymentInfo p : d.payments().stream().limit(8).toList()) {
            boolean voided = p.status() == PlanPaymentStatus.VOIDED;
            String detail = (p.paidAt() == null ? "" : "Pagada el " + DATE.format(p.paidAt()))
                    + (p.registeredBy() == null ? "" : " · " + p.registeredBy());
            VBox text = new VBox(1, label(capitalize(MONTH.format(p.period())), voided ? "list-voided" : "list-name"),
                    label(voided ? "Anulada" : detail, "list-detail"));
            HBox.setHgrow(text, Priority.ALWAYS);
            Label amount = label(ClientController.pesos(p.amount()), voided ? "list-voided" : "cell-strong");
            HBox row = new HBox(text, amount);
            row.getStyleClass().add("list-row");
            if (!voided) {
                Button cancel = new Button("Anular");
                cancel.getStyleClass().add("text-action");
                cancel.setOnAction(e -> confirmAndRun("Anular cuota", "¿Anular la cuota de "
                        + MONTH.format(p.period()) + " por " + ClientController.pesos(p.amount()) + "?",
                        "El ingreso queda anulado en Finanzas, no se borra.", "Anular cuota", true,
                        () -> planService.voidPayment(p.id())));
                row.getChildren().add(cancel);
            }
            box.getChildren().add(row);
        }
        if (d.payments().size() > 8) {
            box.getChildren().add(wrapped("y " + (d.payments().size() - 8) + " cuotas anteriores.", "card-subtitle"));
        }
        return box;
    }

    private static VBox usedBox(List<UsedService> used) {
        VBox box = new VBox();
        box.getStyleClass().add("section-box");
        if (used.isEmpty()) {
            box.getChildren().add(wrapped("El plan no se ha usado.", "card-subtitle"));
            return box;
        }
        for (UsedService u : used) {
            String title = (u.contractNumber() == null ? "Sin contrato" : u.contractNumber()) + " · " + u.deceasedName();
            String detail = (u.deathDate() == null ? "" : "Falleció el " + DATE.format(u.deathDate()))
                    + (u.wakeRoom() == null ? "" : " · velación sala " + u.wakeRoom());
            VBox text = new VBox(1, wrapped(title, "list-name"), wrapped(detail, "list-detail"));
            HBox row = new HBox(text);
            row.getStyleClass().add("list-row");
            box.getChildren().add(row);
        }
        return box;
    }

    // ---------- diálogos ----------

    @FXML
    private void onRegister() {
        TextField clientSearch = searchInput("Buscar cliente por nombre o documento");
        ComboBox<Option> clientBox = optionCombo("Seleccione el titular");
        bindSearch(clientSearch, clientBox, planService::searchClients);
        ComboBox<String> name = new ComboBox<>();
        name.setEditable(true);
        name.getItems().setAll(overview == null || overview.planNames().isEmpty()
                ? List.of("Plan Familiar", "Plan Individual", "Plan Empresarial") : overview.planNames());
        name.setPromptText("Ej. Plan Familiar");
        name.getStyleClass().add("form-combo");
        name.setMaxWidth(Double.MAX_VALUE);
        TextField fee = new TextField();
        fee.setPromptText("Ej. 52.000");
        DatePicker start = new DatePicker(LocalDate.now(AccessLogService.APP_ZONE));
        Label hint = wrapped("", "dialog-hint");
        Runnable updateHint = () -> hint.setText(start.getValue() == null ? "Ingrese la fecha de inicio."
                : "Carencia: cubre servicios desde el "
                + DATE.format(start.getValue().plusDays(overview == null ? 0 : overview.waitingDays()))
                + " (RN-08).");
        start.valueProperty().addListener((obs, old, value) -> updateHint.run());
        updateHint.run();

        Form form = new Form()
                .section(null, 2)
                .wide("CLIENTE TITULAR *", new VBox(6, clientSearch, clientBox))
                .field("NOMBRE DEL PLAN *", name)
                .field("CUOTA MENSUAL (COP) *", fee)
                .field("FECHA DE INICIO *", new VBox(4, start, hint));

        Long[] savedId = new Long[1];
        Dialog<ButtonType> dialog = formDialog("Afiliar plan", "Los campos con * son obligatorios.", form, 620, false);
        if (runForm(dialog, form, "Afiliar", () -> savedId[0] = planService.register(new PlanData(
                idOf(clientBox.getValue()), name.getEditor().getText(), money(fee, "La cuota"), start.getValue())).getId())) {
            loadOverview(savedId[0]);
        }
    }

    private void openPaymentDialog(PlanDetail d) {
        PlanCard c = d.card();
        YearMonth first = d.nextDue() == null ? YearMonth.now(AccessLogService.APP_ZONE) : d.nextDue();
        ComboBox<YearMonth> month = new ComboBox<>();
        YearMonth limit = YearMonth.now(AccessLogService.APP_ZONE).plusMonths(planParameters.maxMonthsAhead());
        List<YearMonth> months = new ArrayList<>();
        // Meses sin pagar desde el inicio del plan hasta el máximo de cuotas adelantadas configurado.
        YearMonth from = c.startDate() == null ? first : YearMonth.from(c.startDate());
        java.util.Set<YearMonth> paid = new java.util.HashSet<>();
        d.payments().stream().filter(p -> p.status() == PlanPaymentStatus.PAID).forEach(p -> paid.add(p.period()));
        for (YearMonth m = from; !m.isAfter(limit); m = m.plusMonths(1)) {
            if (!paid.contains(m)) {
                months.add(m);
            }
        }
        month.getItems().setAll(months);
        month.setConverter(converter(m -> capitalize(MONTH.format(m)), ""));
        month.setValue(months.contains(first) ? first : months.isEmpty() ? null : months.get(0));
        month.getStyleClass().add("form-combo");
        TextField amount = new TextField(c.monthlyFee() == null ? "" : String.valueOf(c.monthlyFee().longValue()));
        Label hint = note("Se registra como ingreso en Finanzas (cuota del plan " + c.code() + ").");

        Form form = new Form()
                .section(null, 2)
                .field("MES QUE PAGA *", month)
                .field("VALOR (COP) *", amount)
                .note(hint);

        Dialog<ButtonType> dialog = formDialog("Registrar pago", c.code() + " · " + c.holderName()
                + ". Los campos con * son obligatorios.", form, 520, false);
        if (runForm(dialog, form, "Registrar", () -> planService.registerPayment(
                new PlanPaymentData(c.id(), month.getValue(), money(amount, "El valor"))))) {
            loadOverview(c.id());
        }
    }

    private void openBeneficiaryDialog(PlanDetail d) {
        PlanCard c = d.card();
        TextField name = new TextField();
        name.setPromptText("Nombres");
        TextField lastname = new TextField();
        lastname.setPromptText("Apellidos");
        TextField document = new TextField();
        document.setPromptText("Solo números, 5 a 11 dígitos");
        ComboBox<Relationship> relationship = new ComboBox<>();
        relationship.getItems().setAll(Relationship.values());
        relationship.setConverter(converter(Relationship::getDisplayName, ""));
        relationship.setPromptText("Parentesco con el titular");
        relationship.getStyleClass().add("form-combo");
        Label surchargeHint = note("");
        CheckBox accept = new CheckBox("El titular acepta el recargo");
        accept.getStyleClass().add("form-check");
        accept.setVisible(false);
        accept.setManaged(false);
        relationship.valueProperty().addListener((obs, old, value) -> {
            if (value == null) {
                return;
            }
            try {
                SurchargePreview preview = planService.previewSurcharge(c.id(), value);
                accept.setSelected(false);
                accept.setVisible(preview.applies());
                accept.setManaged(preview.applies());
                surchargeHint.setText(surchargeText(preview));
            } catch (BusinessException e) {
                showError(e.getMessage());
            }
        });

        Form form = new Form()
                .section(null, 2)
                .field("NOMBRES *", name)
                .field("APELLIDOS *", lastname)
                .field("NÚMERO DE DOCUMENTO *", document)
                .field("PARENTESCO *", relationship)
                .note(surchargeHint)
                .note(accept);

        String count = d.beneficiaries().size() + (c.corporate() ? "" : " de " + d.beneficiaryLimit()) + " beneficiarios";
        Dialog<ButtonType> dialog = formDialog("Agregar beneficiario", c.code() + " · " + count
                + ". Los campos con * son obligatorios.", form, 620, false);
        if (runForm(dialog, form, "Agregar", () -> planService.addBeneficiary(new BeneficiaryData(
                c.id(), name.getText(), lastname.getText(), document.getText(), relationship.getValue(),
                accept.isSelected())))) {
            loadOverview(c.id());
        }
    }

    private static String surchargeText(SurchargePreview p) {
        if (p.corporate()) {
            return "Plan empresarial: sin tope de beneficiarios.";
        }
        if (!p.applies()) {
            return "Beneficiario " + p.position() + " de " + p.limit() + ": sin recargo.";
        }
        List<String> parts = new ArrayList<>();
        if (p.nonDirect().signum() > 0) {
            parts.add(ClientController.pesos(p.nonDirect()) + " por parentesco no directo (RN-09)");
        }
        if (p.extra().signum() > 0) {
            parts.add(ClientController.pesos(p.extra()) + " por superar " + p.limit() + " beneficiarios (RN-10)");
        }
        return "Recargo de " + ClientController.pesos(p.total()) + ": " + String.join(" y ", parts)
                + ". Se cobra una vez al vender el servicio.";
    }

    private void openChangeHolderDialog(PlanCard c) {
        TextField clientSearch = searchInput("Buscar cliente por nombre o documento");
        ComboBox<Option> clientBox = optionCombo("Seleccione el nuevo titular");
        bindSearch(clientSearch, clientBox, planService::searchClients);
        Form form = new Form()
                .section(null, 1)
                .field("NUEVO TITULAR *", new VBox(6, clientSearch, clientBox))
                .note(note("Si no está registrado, regístrelo primero en Clientes."));

        Dialog<ButtonType> dialog = formDialog("Cambiar titular", "Plan " + c.code() + " · titular actual "
                + c.holderName() + ".", form, 540, false);
        if (runForm(dialog, form, "Cambiar titular", () -> planService.changeHolder(c.id(), idOf(clientBox.getValue())))) {
            loadOverview(c.id());
        }
    }

    /** Confirmación como la de Personal: la pregunta arriba, la explicación y un botón con la acción. */
    private void confirmAndRun(String title, String question, String detail, String actionText, boolean danger,
                               Runnable action) {
        ButtonType confirm = new ButtonType(actionText, ButtonBar.ButtonData.OK_DONE);
        ButtonType back = new ButtonType(actionText.startsWith("Cancelar") ? "Volver" : "Cancelar",
                ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE, detail, back, confirm);
        alert.initOwner(window());
        alert.setTitle(title);
        alert.setHeaderText(question);
        styleDialog(alert.getDialogPane());
        alert.getDialogPane().lookupButton(confirm).getStyleClass().add(danger ? "danger-button" : "primary-dialog-button");
        if (alert.showAndWait().filter(confirm::equals).isEmpty()) {
            return;
        }
        Long reopen = drawerPlanId;
        try {
            action.run();
            loadOverview(reopen);
        } catch (BusinessException e) {
            showError(e.getMessage());
        }
    }

    // ---------- utilidades de interfaz ----------

    private static String monthStateLabel(MonthCell cell) {
        return switch (cell.state()) {
            case PAID_ON_TIME -> "pagada a tiempo";
            case PAID_LATE -> "pagada con retraso";
            case UNPAID -> "sin pagar";
            case UPCOMING -> "por vencer";
            case BEFORE_START -> "antes de la afiliación";
        };
    }

    private static HBox legendSwatch(String styleClass, String text) {
        Region swatch = new Region();
        swatch.getStyleClass().addAll("legend-swatch", styleClass);
        Label label = label(text, "legend-text");
        label.setMinWidth(Region.USE_PREF_SIZE);
        HBox item = new HBox(swatch, label);
        item.getStyleClass().add("legend-item");
        return item;
    }

    /** Pesos sin decimales: acepta "52.000", "52000" o "$ 52 000". */
    private static BigDecimal money(TextField field, String what) {
        String value = field.getText();
        if (value == null || value.isBlank()) {
            return null; // el servicio dice que es obligatorio
        }
        String digits = value.replaceAll("[$.,\\s]", "");
        if (!digits.matches("\\d+")) {
            throw new NumberFormatException(what + " debe ser un valor en pesos, por ejemplo 52.000.");
        }
        return new BigDecimal(digits);
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

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
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

        Form() {
            body.getStyleClass().add("form-body");
            error.getStyleClass().add("error-label");
            error.setWrapText(true);
            error.managedProperty().bind(error.textProperty().isNotEmpty());
            error.visibleProperty().bind(error.managedProperty());
        }

        Form section(String title, int columns) {
            if (title != null) {
                Label caption = new Label(title);
                caption.getStyleClass().add("section-title");
                body.getChildren().add(caption);
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
            column += span;
            if (column >= columns) {
                column = 0;
                row++;
            }
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
        note.setMaxWidth(Double.MAX_VALUE);
        note.managedProperty().bind(note.textProperty().isNotEmpty());
        note.visibleProperty().bind(note.managedProperty());
        return note;
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
                FuneralPlanController.class.getResource("/css/app.css").toExternalForm(),
                FuneralPlanController.class.getResource("/css/personal.css").toExternalForm(),
                FuneralPlanController.class.getResource("/css/funeral-plans.css").toExternalForm());
        pane.getStyleClass().add("personal-dialog");
        // Cancelar a la izquierda y la acción a la derecha también en Windows, como en Personal.
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

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static ListCell<String> textCell(Function<String, String> text) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : text.apply(item));
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

    private static String count(long amount, String word) {
        return count(amount, word, word + "s");
    }

    private static String count(long amount, String singular, String plural) {
        return amount + " " + (amount == 1 ? singular : plural);
    }

    private static String initialsOf(String name) {
        StringBuilder result = new StringBuilder();
        if (name != null) {
            for (String part : name.trim().split("\\s+")) {
                if (!part.isEmpty() && result.length() < 2) {
                    result.append(Character.toUpperCase(part.charAt(0)));
                }
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
