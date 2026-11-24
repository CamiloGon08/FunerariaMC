package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.utils.enums.PlanPaymentStatus;
import com.example.funerariamc.utils.enums.PlanStatus;
import com.example.funerariamc.utils.enums.Relationship;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.BeneficiaryData;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.FuneralPlanService.BeneficiaryInfo;
import com.example.funerariamc.service.FuneralPlanService.Kpis;
import com.example.funerariamc.service.FuneralPlanService.MonthCell;
import com.example.funerariamc.service.FuneralPlanService.Option;
import com.example.funerariamc.service.FuneralPlanService.Overview;
import com.example.funerariamc.service.FuneralPlanService.PaymentFilter;
import com.example.funerariamc.service.FuneralPlanService.PaymentInfo;
import com.example.funerariamc.service.FuneralPlanService.PlanCard;
import com.example.funerariamc.service.FuneralPlanService.PlanDetail;
import com.example.funerariamc.service.FuneralPlanService.PlanFilter;
import com.example.funerariamc.service.FuneralPlanService.PlanOffer;
import com.example.funerariamc.service.FuneralPlanService.SurchargePreview;
import com.example.funerariamc.service.FuneralPlanService.UsedService;
import com.example.funerariamc.service.PlanParameters;
import com.example.funerariamc.service.PlanPaymentData;
import com.example.funerariamc.ui.Pager;
import com.example.funerariamc.ui.DetailShell;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
import com.example.funerariamc.ui.MonthPicker;
import com.example.funerariamc.ui.ModuleLink;
import com.example.funerariamc.ui.PlanOfferCard;
import javafx.animation.PauseTransition;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pantalla Planes exequiales: recaudo del mes, indicadores, gráfica de
 * recaudo contra lo esperado, planes por tipo, carencias que terminan pronto y tarjetas de
 * afiliados que se agregan al desplazar (sin paginación). El panel lateral muestra la
 * cuadrícula de cuotas, beneficiarios, pagos y servicios usados, y desde él se registra o anula
 * una cuota, se agrega un beneficiario, se suspende, cancela o cambia el titular.
 */
@Component
@Scope("prototype")
public class FuneralPlanController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Planes exequiales";

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", ES);
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", ES);
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
    private Label countLabel;
    @FXML
    private GridPane cardGrid;
    @FXML
    private Label emptyLabel;
    @FXML
    private VBox drawer;
    @FXML
    private Region veil;

    private Overview overview;
    private List<PlanCard> filtered = List.of();
    private Pager<PlanCard> pager;
    /** Filtros con que se armó la página actual (para saber si hay que volver a la página 1). */
    private String lastFilter;
    /** Plan del panel abierto; null si está cerrado. */
    private Long drawerPlanId;
    private DetailShell detail;

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
        // Paginación como la bitácora: 12 tarjetas por página, debajo de la cuadrícula.
        pager = Pager.<PlanCard>local(Pager.CARD_PAGE, "plan", "planes", this::drawCards).below(cardGrid);

        detail = DetailShell.install(drawer, veil, DetailShell.Kind.MODAL);
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

    // ---------- indicadores ----------

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

    // ---------- tarjetas ----------

    private void renderCards() {
        if (overview == null) {
            return;
        }
        PlanStatus status = statusGroup.getSelectedToggle() == null ? PlanStatus.ACTIVE
                : (PlanStatus) statusGroup.getSelectedToggle().getUserData();
        filtered = FuneralPlanService.filter(overview.cards(),
                new PlanFilter(status, paymentFilter.getValue(), typeFilter.getValue(), searchField.getText()));
        // Un filtro nuevo vuelve a la página 1; recargar tras guardar se queda en la misma página.
        String filterKey = status + "|" + paymentFilter.getValue() + "|" + typeFilter.getValue() + "|" + searchField.getText();
        if (filterKey.equals(lastFilter)) {
            pager.setItems(filtered);
        } else {
            pager.setItemsFromStart(filtered);
        }
        lastFilter = filterKey;
        countLabel.setText(count(filtered.size(), "plan", "planes"));
        boolean empty = filtered.isEmpty();
        emptyLabel.setText(overview.cards().isEmpty() ? "Todavía no hay planes. Se afilian desde la pestaña Clientes."
                : "Ningún plan coincide con los filtros.");
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
    }

    /** Dibuja las tarjetas de la página visible. */
    private void drawCards(List<PlanCard> page) {
        cardGrid.getChildren().clear();
        for (int i = 0; i < page.size(); i++) {
            cardGrid.add(planCard(page.get(i)), i % COLUMNS, i / COLUMNS);
        }
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

    // ---------- panel del plan ----------

    /**
     * Abre el plan al instante: con los datos si ya están guardados, o con el titular de la tarjeta y
     * "Cargando plan…" mientras llegan. Si ya muestra ese plan (recarga después de guardar), conserva
     * lo que se ve hasta que llegue lo nuevo.
     */
    private void openDrawer(Long planId) {
        boolean sameOpen = detail.isOpen() && planId.equals(drawerPlanId);
        drawerPlanId = planId;
        PlanDetail cached = details.get(planId);
        if (cached != null) {
            fillDrawer(cached);
            return;
        }
        if (!sameOpen) {
            fillDrawerLoading(planId);
        }
        details.load(planId, data -> {
            if (planId.equals(drawerPlanId)) {
                fillDrawer(data);
            }
        }, error -> {
            if (planId.equals(drawerPlanId)) {
                closeDrawer();
                showError(error instanceof BusinessException business ? business.getMessage()
                        : "No se pudo cargar el plan.");
            }
        });
    }

    private void fillDrawerLoading(Long planId) {
        PlanCard c = overview == null ? null : overview.cards().stream()
                .filter(card -> planId.equals(card.id())).findFirst().orElse(null);
        String name = c == null ? "Plan" : c.holderName();
        DetailShell.Hero hero = new DetailShell.Hero("Plan exequial" + (c == null ? "" : " · " + c.code()), name)
                .leading(DetailShell.initials(name))
                .onClose(this::closeDrawer);
        detail.show(planId, hero, List.of(DetailShell.empty("Cargando plan…")), List.of(), null);
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerPlanId = null;
        detail.close(null);
    }

    private void fillDrawer(PlanDetail d) {
        PlanCard c = d.card();
        List<String> contact = new ArrayList<>();
        contact.add(c.corporate() ? "Empresa titular" : "Titular");
        if (c.holderDocument() != null) {
            contact.add(c.holderDocument());
        }
        if (d.holderPhone() != null && !d.holderPhone().isBlank()) {
            contact.add(d.holderPhone());
        }
        DetailShell.Hero hero = new DetailShell.Hero("Plan exequial · " + c.code(), c.holderName())
                .subtitle(String.join(" · ", contact))
                .leading(DetailShell.initials(c.holderName()))
                .chips(statusChip(c),
                        c.inWaiting() ? DetailShell.chip("Carencia hasta " + DATE.format(c.waitingEnds()), DetailShell.Tone.WARN)
                                : DetailShell.chip("Carencia cumplida", DetailShell.Tone.OK),
                        DetailShell.chip(c.planName() + " · " + ClientController.pesos(c.monthlyFee()) + "/mes",
                                DetailShell.Tone.NEUTRAL))
                .extra(monthStrip(d))
                .navigation(DetailShell.neighbor(filtered, x -> x.id().equals(c.id()), -1, x -> openDrawer(x.id())),
                        DetailShell.neighbor(filtered, x -> x.id().equals(c.id()), 1, x -> openDrawer(x.id())))
                .onClose(this::closeDrawer);
        if (d.nextDue() != null) {
            hero.extra(DetailShell.heroLine("Próxima cuota:", capitalize(MONTH.format(d.nextDue())) + ", vence el "
                    + DATE.format(FuneralPlanService.dueDate(c.startDate(), d.nextDue()))));
        }
        if (!c.corporate() && d.beneficiaryLimit() > 0) {
            hero.extra(DetailShell.progress("Beneficiarios", d.beneficiaries().size() + " de " + d.beneficiaryLimit() + " cupos",
                    d.beneficiaries().size() / (double) d.beneficiaryLimit(), DetailShell.Tone.NEUTRAL));
        }

        List<Node> sections = new ArrayList<>();
        if (d.holderDeceased()) {
            sections.add(DetailShell.nextStep("El titular falleció: asigne un nuevo titular para que el plan siga vigente.",
                    DetailShell.Tone.WARN));
        }
        sections.add(DetailShell.columns(List.of(beneficiariesSection(d)),
                List.of(DetailShell.section("Pagos registrados", paymentsBox(d)),
                        DetailShell.section("Servicios usados", usedBox(d.usedServices())))));

        Button status = DetailShell.secondary(c.status() == PlanStatus.SUSPENDED ? "Reactivar plan" : "Suspender plan", () -> {
            if (c.status() == PlanStatus.SUSPENDED) {
                confirmAndRun("Reactivar plan", "¿Reactivar el plan " + c.code() + "?",
                        "Vuelve a cubrir a sus beneficiarios.", "Reactivar", false, () -> planService.reactivate(c.id()));
            } else {
                confirmAndRun("Suspender plan", "¿Suspender el plan " + c.code() + "?",
                        "Mientras esté suspendido no cubre servicios ni recibe beneficiarios nuevos.", "Suspender", true,
                        () -> planService.suspend(c.id()));
            }
        });
        status.setDisable(c.status() == PlanStatus.CANCELLED);
        // "Más" abre un menú con las acciones menos frecuentes.
        MenuItem holder = new MenuItem("Cambiar titular");
        holder.setOnAction(e -> openChangeHolderDialog(c));
        MenuItem change = new MenuItem("Cambiar plan");
        change.setOnAction(e -> openChangePlanDialog(c));
        change.setDisable(c.status() != PlanStatus.ACTIVE);
        MenuItem cancel = new MenuItem("Cancelar plan");
        cancel.getStyleClass().add("menu-danger");
        cancel.setOnAction(e -> confirmAndRun("Cancelar plan", "¿Cancelar definitivamente el plan " + c.code() + "?",
                "No se borra: queda en «Cancelados» con sus cuotas.", "Cancelar plan", true,
                () -> planService.cancel(c.id())));
        ContextMenu menu = new ContextMenu(change, holder, cancel);
        Button more = DetailShell.secondary("Más…", null);
        more.setOnAction(e -> menu.show(more, javafx.geometry.Side.TOP, 0, -4));
        more.setDisable(c.status() == PlanStatus.CANCELLED);
        Button pay = DetailShell.primary("Registrar pago", () -> openPaymentDialog(d));
        pay.setDisable(c.status() == PlanStatus.CANCELLED);
        detail.show(c.id(), hero, sections, List.of(status, more, pay), null);
    }

    /** Estado del plan en la banda: mora (con cuántas cuotas), suspendido, cancelado o al día. */
    private static Label statusChip(PlanCard c) {
        Label pill = statusPill(c);
        String text = pill.getText();
        DetailShell.Tone tone = pill.getStyleClass().contains("pill-alert") ? DetailShell.Tone.ALERT
                : pill.getStyleClass().contains("pill-warning") ? DetailShell.Tone.WARN
                : pill.getStyleClass().contains("status-active") ? DetailShell.Tone.OK : DetailShell.Tone.NEUTRAL;
        return DetailShell.chip(tone == DetailShell.Tone.ALERT ? "● " + text : text, tone);
    }

    /** Cuotas de los últimos 12 meses en la banda. */
    private static VBox monthStrip(PlanDetail d) {
        List<DetailShell.Cell> cells = new ArrayList<>();
        for (MonthCell cell : d.grid()) {
            DetailShell.CellState state = switch (cell.state()) {
                case PAID_ON_TIME -> DetailShell.CellState.OK;
                case PAID_LATE -> DetailShell.CellState.LATE;
                case UNPAID -> DetailShell.CellState.MISSING;
                case UPCOMING, BEFORE_START -> DetailShell.CellState.NONE;
            };
            cells.add(new DetailShell.Cell(capitalize(SHORT_MONTH.format(cell.month()).replace(".", "")),
                    capitalize(MONTH.format(cell.month())) + ": " + monthStateLabel(cell), state));
        }
        return DetailShell.strip(cells, DetailShell.CellState.OK, "A tiempo",
                DetailShell.CellState.LATE, "Con retraso", DetailShell.CellState.MISSING, "Sin pagar");
    }

    private VBox beneficiariesSection(PlanDetail d) {
        PlanCard c = d.card();
        Button add = new Button("+ Agregar");
        add.setDisable(!c.isActive());
        add.setOnAction(e -> openBeneficiaryDialog(d));
        List<Node> rows = new ArrayList<>();
        rows.add(DetailShell.person(DetailShell.initials(c.holderName()), c.holderName(),
                (c.corporate() ? "Empresa titular" : "Titular") + " · " + orDash(c.holderDocument()),
                d.holderDeceased() ? DetailShell.chip("Falleció", DetailShell.Tone.NEUTRAL) : coveredChip(c)));
        for (BeneficiaryInfo b : d.beneficiaries()) {
            String line = b.relationshipLabel() + " · CC " + orDash(b.documentNumber());
            if (b.isDeceased()) {
                line += " · falleció " + (b.deathDate() == null ? "" : DATE.format(b.deathDate()));
            }
            if (b.surcharge().signum() > 0) {
                line += "\nRecargo " + ClientController.pesos(b.surcharge()) + " · " + b.surchargeReason();
            }
            Node aside = b.isDeceased() ? DetailShell.chip("Usó el plan", DetailShell.Tone.GOLD) : coveredChip(c);
            if (b.isDeceased() && withoutContract(d).contains(b.deceasedId())) {
                aside = asideWithContract(aside, b.deceasedId());
            }
            rows.add(DetailShell.person(DetailShell.initials(b.fullName()), b.fullName(), line, aside));
        }
        if (d.beneficiaries().isEmpty()) {
            rows.add(wrapped("Sin beneficiarios. El titular está cubierto.", "detail-empty"));
        }
        if (d.totalSurcharge().signum() > 0) {
            rows.add(wrapped("Recargos: " + ClientController.pesos(d.totalSurcharge())
                    + ", se cobran una vez al vender el servicio.", "detail-empty"));
        }
        String title = "Beneficiarios · " + d.beneficiaries().size() + (c.corporate() ? "" : " de " + d.beneficiaryLimit());
        return DetailShell.sectionWithAction(title, add, DetailShell.list(rows.toArray(Node[]::new)));
    }

    private static Label coveredChip(PlanCard c) {
        return c.isActive() && !c.inWaiting() ? DetailShell.chip("Cubierto", DetailShell.Tone.OK)
                : c.isActive() ? DetailShell.chip("En carencia", DetailShell.Tone.WARN)
                : DetailShell.chip("Sin cobertura", DetailShell.Tone.NEUTRAL);
    }

    private Node paymentsBox(PlanDetail d) {
        if (d.payments().isEmpty()) {
            return DetailShell.empty("Todavía no hay cuotas registradas.");
        }
        List<DetailShell.Event> events = new ArrayList<>();
        for (PaymentInfo p : d.payments().stream().limit(8).toList()) {
            boolean voided = p.status() == PlanPaymentStatus.VOIDED;
            String line = voided ? "Anulada" : (p.paidAt() == null ? "" : "Pagada el " + DATE.format(p.paidAt()))
                    + (p.registeredBy() == null ? "" : " · " + p.registeredBy());
            Label amount = DetailShell.value(ClientController.pesos(p.amount()), null);
            if (voided) {
                amount.getStyleClass().add("detail-voided");
            }
            HBox aside = new HBox(8, amount);
            aside.setAlignment(Pos.TOP_RIGHT);
            if (!voided) {
                Button cancel = DetailShell.link("Anular", () -> confirmAndRun("Anular cuota", "¿Anular la cuota de "
                        + MONTH.format(p.period()) + " por " + ClientController.pesos(p.amount()) + "?",
                        "El ingreso queda anulado en Finanzas, no se borra.", "Anular cuota", true,
                        () -> planService.voidPayment(p.id())));
                cancel.getStyleClass().add("detail-link-danger");
                aside.getChildren().add(cancel);
            }
            events.add(new DetailShell.Event(capitalize(MONTH.format(p.period())), line,
                    voided ? DetailShell.Tone.NEUTRAL : DetailShell.Tone.OK, aside));
        }
        VBox box = new VBox(4, DetailShell.timeline(events));
        if (d.payments().size() > 8) {
            box.getChildren().add(wrapped("y " + (d.payments().size() - 8) + " cuotas anteriores.", "detail-empty"));
        }
        return box;
    }

    private static Node usedBox(List<UsedService> used) {
        if (used.isEmpty()) {
            return DetailShell.empty("El plan no se ha usado.");
        }
        List<Node> rows = new ArrayList<>();
        for (UsedService u : used) {
            String title = (u.contractNumber() == null ? "Sin contrato" : u.contractNumber()) + " · " + u.deceasedName();
            String line = (u.deathDate() == null ? "" : "Falleció el " + DATE.format(u.deathDate()))
                    + (u.wakeRoom() == null ? "" : " · velación sala " + u.wakeRoom());
            Node aside = u.contractNumber() == null ? startContractLink(u.deceasedId()) : null;
            rows.add(DetailShell.item(null, title, line, aside));
        }
        return DetailShell.list(rows.toArray(Node[]::new));
    }

    /** Difuntos del plan que todavía no tienen contrato. */
    private static Set<Long> withoutContract(PlanDetail d) {
        return d.usedServices().stream().filter(u -> u.contractNumber() == null)
                .map(UsedService::deceasedId).collect(Collectors.toSet());
    }

    /** "Iniciar contrato": abre Contratos con el difunto, el titular y el plan ya llenos. */
    private static Node startContractLink(Long deceasedId) {
        if (deceasedId == null || !ModuleLink.canOpen(AppModule.CONTRATOS)) {
            return null;
        }
        return DetailShell.link("Iniciar contrato", () -> ModuleLink.openInPlace(AppModule.CONTRATOS, deceasedId));
    }

    private static Node asideWithContract(Node chip, Long deceasedId) {
        Node link = startContractLink(deceasedId);
        if (link == null) {
            return chip;
        }
        VBox box = new VBox(4, chip, link);
        box.setAlignment(Pos.TOP_RIGHT);
        return box;
    }

    // ---------- diálogos ----------

    /** "Cambiar plan": pasa el plan de normal a premium (o al revés) con la cuota de Parámetros. */
    private void openChangePlanDialog(PlanCard c) {
        run(planService::planOffers, offers -> {
            PlanOffer current = offerOf(offers, c.planName());
            PlanOfferCard now = new PlanOfferCard(c.planName(), ClientController.pesos(c.monthlyFee()) + " / mes",
                    current == null ? List.of() : current.includes());
            ComboBox<String> name = new ComboBox<>();
            name.getItems().setAll(offers.stream().map(PlanOffer::name)
                    .filter(n -> !n.equalsIgnoreCase(c.planName())).toList());
            FormShell.choose(name);
            name.getStyleClass().add("form-combo");
            name.setMaxWidth(Double.MAX_VALUE);
            PlanOfferCard next = new PlanOfferCard("", null, List.of());
            next.setSelected(true);
            next.setVisible(false);
            TextField fee = new TextField();
            fee.setPromptText("Se llena al elegir el plan");
            lockFee(fee);
            Label feeHint = wrapped("", "dialog-hint");
            name.valueProperty().addListener((obs, old, value) -> {
                PlanOffer offer = offerOf(offers, value);
                next.setVisible(offer != null);
                if (offer != null) {
                    next.show(offer.name(), feeText(offer.monthlyFee()), offer.includes());
                    fillFee(fee, feeHint, offer);
                }
            });
            HBox compare = new HBox(12, labeled("PLAN ACTUAL", now), labeled("NUEVO PLAN", next));
            compare.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
            Form form = new Form()
                    .section(null, 2)
                    .wide(null, compare)
                    .field("NUEVO PLAN *", name)
                    .field("NUEVA CUOTA MENSUAL (COP) *", new VBox(4, fee, feeHint))
                    .note(note("Los beneficiarios, las cuotas ya pagadas y la carencia se conservan. "
                            + "Las cuotas que se paguen desde ahora usan la nueva cuota."));
            Dialog<ButtonType> dialog = formDialog("Cambiar plan", "Plan " + c.code() + " · titular "
                    + c.holderName() + ".", form, 640, false);
            FormShell.window(dialog, "Planes exequiales");
            FormShell.money(fee);
            if (runForm(dialog, form, "Cambiar plan",
                    () -> planService.changePlan(c.id(), name.getValue()))) {
                loadOverview(c.id());
            }
        }, "No se pudieron cargar los tipos de plan.");
    }

    private static PlanOffer offerOf(List<PlanOffer> offers, String name) {
        return name == null ? null : offers.stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst()
                .orElse(null);
    }

    private static String feeText(BigDecimal fee) {
        return fee == null ? null : ClientController.pesos(fee) + " / mes";
    }

    /** Llena la cuota con el valor de Parámetros; el campo no se puede editar. */
    /** La cuota sale de Parámetros: el campo se ve pero no se escribe en él. */
    private static void lockFee(TextField fee) {
        fee.setEditable(false);
        fee.setFocusTraversable(false);
    }

    private static void fillFee(TextField fee, Label hint, PlanOffer offer) {
        if (offer.monthlyFee() == null) {
            fee.clear();
            fee.getStyleClass().remove("auto-filled");
            hint.setText("La cuota del " + offer.name() + " no está definida en Parámetros: pídale al "
                    + "administrador que la registre allí.");
            return;
        }
        fee.setText(String.valueOf(offer.monthlyFee().longValue()));
        if (!fee.getStyleClass().contains("auto-filled")) {
            fee.getStyleClass().add("auto-filled");
        }
        hint.setText("Valor del " + offer.name() + " en Parámetros. Solo cambia si se cambia allí.");
    }

    private static VBox labeled(String caption, Node card) {
        Label label = new Label(caption);
        label.getStyleClass().add("field-label");
        VBox box = new VBox(4, label, card);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /** Afiliados atrasados a los que toca mandarles carta por correo certificado (30, 60 y 90 días). */
    @FXML
    private void onLetters() {
        run(planService::overdueLetters, letters -> {
            Form form = new Form();
            if (letters.isEmpty()) {
                form.body.getChildren().add(wrapped("Ningún afiliado cumple hoy un tramo de atraso para carta.",
                        "card-subtitle"));
            }
            int shownDay = -1;
            for (FuneralPlanService.OverdueLetter letter : letters) {
                if (letter.letterDay() != shownDay) {
                    shownDay = letter.letterDay();
                    form.body.getChildren().add(legend("Carta de " + shownDay + " días"));
                }
                VBox text = new VBox(2,
                        wrapped(letter.holderName() + " · " + letter.code(), "attention-name"),
                        wrapped(orDash(letter.address()) + " · Tel. " + orDash(letter.phone()), "card-subtitle"),
                        wrapped(letter.overdueMonths() + (letter.overdueMonths() == 1 ? " cuota" : " cuotas")
                                + " sin pagar · " + ClientController.pesos(letter.overdueAmount()) + " · "
                                + letter.daysLate() + " días de atraso", "attention-detail-overdue"));
                text.getStyleClass().add("letter-row");
                form.body.getChildren().add(text);
            }
            Dialog<ButtonType> dialog = formDialog("Cartas por atraso",
                    "Se envían por correo certificado. Cuando el plan ya venció no se manda nada más.",
                    form, 620, true);
            dialog.getDialogPane().getButtonTypes().setAll(new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE));
            dialog.showAndWait();
        }, "No se pudo armar la lista de cartas.");
    }

    private void openPaymentDialog(PlanDetail d) {
        PlanCard c = d.card();
        YearMonth first = d.nextDue() == null ? YearMonth.now(AccessLogService.APP_ZONE) : d.nextDue();
        YearMonth limit = YearMonth.now(AccessLogService.APP_ZONE).plusMonths(planParameters.maxMonthsAhead());
        // Meses sin pagar desde el inicio del plan hasta el máximo de cuotas adelantadas configurado.
        YearMonth from = c.startDate() == null ? first : YearMonth.from(c.startDate());
        java.util.Set<YearMonth> paid = new java.util.HashSet<>();
        d.payments().stream().filter(p -> p.status() == PlanPaymentStatus.PAID).forEach(p -> paid.add(p.period()));
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = from; !m.isAfter(limit); m = m.plusMonths(1)) {
            if (!paid.contains(m)) {
                months.add(m);
            }
        }
        // Mes y año con flechas y calendario de meses; los ya pagados se ven apagados y no se eligen.
        // Sin mes puesto: el usuario elige qué cuota paga (la próxima por vencer se indica abajo).
        MonthPicker month = new MonthPicker("PERÍODO", from, limit, m -> !paid.contains(m), "Elija el mes");
        TextField amount = new TextField(c.monthlyFee() == null ? "" : String.valueOf(c.monthlyFee().longValue()));
        Label hint = note((months.contains(first) ? "Próxima cuota por pagar: " + MonthPicker.label(first) + ". " : "")
                + "Se registra como ingreso en Finanzas (cuota del plan " + c.code() + ").");

        Form form = new Form()
                .section(null, 2)
                .field("MES QUE PAGA *", month)
                .field("VALOR (COP) *", amount)
                .note(hint);

        Dialog<ButtonType> dialog = formDialog("Registrar pago", c.code() + " · " + c.holderName()
                + ". Los campos con * son obligatorios.", form, 640, false);
        FormShell.window(dialog, "Planes exequiales");
        FormShell.money(amount);
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
        FormShell.document(document, null, 11);
        // Queda en el plan para llenarla sola si la persona fallece
        DatePicker birth = new DatePicker();
        birth.setEditable(false);
        birth.setPromptText("dd/mm/aaaa");
        birth.setMaxWidth(Double.MAX_VALUE);
        birth.setConverter(converter(DATE::format, ""));
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
                .field("FECHA DE NACIMIENTO *", birth)
                .field("PARENTESCO *", relationship)
                .note(surchargeHint)
                .note(accept);

        String count = d.beneficiaries().size() + (c.corporate() ? "" : " de " + d.beneficiaryLimit()) + " beneficiarios";
        Dialog<ButtonType> dialog = formDialog("Agregar beneficiario", c.code() + " · " + count
                + ". Los campos con * son obligatorios.", form, 620, false);
        FormShell.window(dialog, "Planes exequiales");
        if (runForm(dialog, form, "Agregar", () -> {
            if (birth.getValue() == null) {
                throw new InvalidDataException("Elija la fecha de nacimiento del beneficiario.");
            }
            planService.addBeneficiary(new BeneficiaryData(c.id(), name.getText(), lastname.getText(),
                    document.getText(), relationship.getValue(), accept.isSelected(), birth.getValue()));
        })) {
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
            parts.add(ClientController.pesos(p.nonDirect()) + " por parentesco no directo");
        }
        if (p.extra().signum() > 0) {
            parts.add(ClientController.pesos(p.extra()) + " por superar " + p.limit() + " beneficiarios");
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
        FormShell.window(dialog, "Planes exequiales");
        if (runForm(dialog, form, "Cambiar titular", () -> planService.changeHolder(c.id(), idOf(clientBox.getValue())))) {
            loadOverview(c.id());
        }
    }

    /** Confirmación como la de Personal: la pregunta arriba, la explicación y un botón con la acción. */
    private void confirmAndRun(String title, String question, String detail, String actionText, boolean danger,
                               Runnable action) {
        String back = actionText.startsWith("Cancelar") ? "Volver" : "Cancelar";
        if (!Notice.confirm(window(), MODULE_NAME, question, detail, actionText, back, danger)) {
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
        Notice.warning(window(), MODULE_NAME, "Atención", message);
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
