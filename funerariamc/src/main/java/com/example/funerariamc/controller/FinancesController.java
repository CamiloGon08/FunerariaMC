package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.service.CommissionService;
import com.example.funerariamc.service.CommissionService.CommissionRow;
import com.example.funerariamc.service.FinancePeriod;
import com.example.funerariamc.service.FinancialTransactionService;
import com.example.funerariamc.service.FinancialTransactionService.AccountOption;
import com.example.funerariamc.service.FinancialTransactionService.MovementRow;
import com.example.funerariamc.service.FinancialTransactionService.SaleOption;
import com.example.funerariamc.service.MovementData;
import com.example.funerariamc.service.ReportService;
import com.example.funerariamc.service.ReportService.FlowPoint;
import com.example.funerariamc.service.ReportService.MonthBalance;
import com.example.funerariamc.service.ReportService.Slice;
import com.example.funerariamc.service.ReportService.Summary;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
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
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Pantalla de Finanzas. Resumen (solo el Administrador Maestro): indicadores con la variación frente
 * al período anterior, flujo de ingresos y egresos, ingresos por origen, balance por mes y comisiones,
 * con exportación a PDF o Excel. Movimientos: registrar un ingreso o egreso y anular los propios de
 * Finanzas. Comisiones: calcular la de cada vendedor o tanatólogo del mes, una sola vez.
 * Todas las consultas corren en otro hilo. Si el período incluye hoy, se refresca cada 60 segundos.
 */
@Component
@Scope("prototype")
public class FinancesController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] SLICE_COLORS = {"#1E3A2E", "#C4AF84", "#3E5A3F", "#9FB09A", "#6E6A45", "#D9D2BC"};

    /** Anchos de las tablas; la descripción o el nombre toman el espacio que sobra. */
    private static final double W_DATE = 140;
    private static final double W_TYPE = 104;
    private static final double W_ORIGIN = 170;
    private static final double W_MONEY = 130;
    private static final double W_ACTION = 150;
    private static final double W_ROLE = 160;
    private static final double W_CASES = 110;

    private final FinancialTransactionService transactionService;
    private final CommissionService commissionService;
    private final ReportService reportService;

    @FXML private StackPane root;
    @FXML private ToggleGroup tabGroup;
    @FXML private ToggleGroup rangeGroup;
    @FXML private ToggleGroup typeGroup;
    @FXML private ToggleButton summaryTab;
    @FXML private ToggleButton movementsTab;
    @FXML private ToggleButton commissionsTab;
    @FXML private ToggleButton weekRange;
    @FXML private ToggleButton monthRange;
    @FXML private ToggleButton quarterRange;
    @FXML private ToggleButton yearRange;
    @FXML private ToggleButton customRange;
    @FXML private ToggleButton allType;
    @FXML private ToggleButton incomeType;
    @FXML private ToggleButton expenseType;
    @FXML private ToggleButton voidedType;
    @FXML private HBox rangeBar;
    @FXML private Button previousButton;
    @FXML private Button nextButton;
    @FXML private VBox periodBox;
    @FXML private Label periodLabel;
    @FXML private MenuButton exportButton;
    @FXML private VBox kpi1;
    @FXML private Label kpi1Caption;
    @FXML private Label kpi1Value;
    @FXML private Label kpi1Detail;
    @FXML private Label kpi2Caption;
    @FXML private Label kpi2Value;
    @FXML private Label kpi2Detail;
    @FXML private Label kpi3Caption;
    @FXML private Label kpi3Value;
    @FXML private Label kpi3Detail;
    @FXML private Label kpi4Caption;
    @FXML private Label kpi4Value;
    @FXML private Label kpi4Detail;
    @FXML private ScrollPane summaryPane;
    @FXML private VBox summaryContent;
    @FXML private VBox movementsPane;
    @FXML private Label movementsCount;
    @FXML private TextField searchField;
    @FXML private HBox movementsHead;
    @FXML private ListView<MovementRow> movementsList;
    @FXML private Label movementsEmpty;
    @FXML private VBox commissionsPane;
    @FXML private Label commissionsTitle;
    @FXML private Label commissionsCount;
    @FXML private Label commissionMonth;
    @FXML private Button nextMonthButton;
    @FXML private HBox commissionNote;
    @FXML private Label commissionNoteText;
    @FXML private HBox commissionsHead;
    @FXML private ListView<CommissionRow> commissionsList;
    @FXML private Label commissionsEmpty;
    @FXML private Label toast;

    private final ObservableList<MovementRow> movementRows = FXCollections.observableArrayList();
    private final ObservableList<CommissionRow> commissionRows = FXCollections.observableArrayList();
    private List<MovementRow> allMovements = List.of();
    private FinancePeriod period;
    private YearMonth month;
    private boolean master;
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));
    /** Refresco cada 60 s mientras la pantalla está a la vista y el período incluye hoy. */
    private final Timeline live = new Timeline(new KeyFrame(Duration.seconds(60), e -> liveRefresh()));

    public FinancesController(FinancialTransactionService transactionService, CommissionService commissionService,
                              ReportService reportService) {
        this.transactionService = transactionService;
        this.commissionService = commissionService;
        this.reportService = reportService;
    }

    @Override
    public void refresh() {
        loadCurrent();
    }

    @FXML
    private void initialize() {
        master = reportService.canSeeSummary();
        LocalDate today = reportService.today();
        period = FinancePeriod.month(YearMonth.from(today));
        month = YearMonth.from(today);

        // La Secretaría no ve el Resumen (balance y exportar): solo Movimientos y Comisiones.
        show(summaryTab, master);
        show(exportButton, master);
        keepOneSelected(tabGroup, this::applyTab);
        keepOneSelected(rangeGroup, this::onRangeKind);
        keepOneSelected(typeGroup, this::applyMovementFilter);
        searchDelay.setOnFinished(e -> applyMovementFilter());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        movementsHead.getChildren().setAll(
                cell(label("FECHA", "head-cell"), W_DATE),
                cell(label("TIPO", "head-cell"), W_TYPE),
                grow(label("DESCRIPCIÓN", "head-cell")),
                cell(label("ORIGEN · CUENTA", "head-cell"), W_ORIGIN),
                cell(label("VALOR", "head-cell"), W_MONEY),
                cell(label("", "head-cell"), W_ACTION));
        movementsList.setItems(movementRows);
        movementsList.setCellFactory(list -> new MovementCell());
        movementsList.setFixedCellSize(62);
        movementsList.setPlaceholder(new Label(""));
        movementsList.setPrefWidth(0);

        commissionsHead.getChildren().setAll(
                grow(label("PERSONA", "head-cell")),
                cell(label("CARGO", "head-cell"), W_ROLE),
                cell(label("CASOS", "head-cell"), W_CASES),
                cell(label("BASE", "head-cell"), W_MONEY),
                cell(label("TARIFA", "head-cell"), W_CASES),
                cell(label("COMISIÓN", "head-cell"), W_MONEY),
                cell(label("", "head-cell"), W_ACTION + 20));
        commissionsList.setItems(commissionRows);
        commissionsList.setCellFactory(list -> new CommissionCell());
        commissionsList.setFixedCellSize(62);
        commissionsList.setPlaceholder(new Label(""));
        commissionsList.setPrefWidth(0);

        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        live.setCycleCount(Timeline.INDEFINITE);
        live.play();

        (master ? summaryTab : movementsTab).setSelected(true);
        applyTab();
    }

    private static void keepOneSelected(ToggleGroup group, Runnable onChange) {
        group.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                if (old != null) {
                    old.setSelected(true);
                }
            } else {
                onChange.run();
            }
        });
    }

    // ---------- pestañas y período ----------

    private void applyTab() {
        Object tab = tabGroup.getSelectedToggle();
        summaryPane.setVisible(tab == summaryTab);
        movementsPane.setVisible(tab == movementsTab);
        commissionsPane.setVisible(tab == commissionsTab);
        // Las comisiones van por mes: el selector de período se oculta en esa pestaña.
        boolean usesPeriod = tab != commissionsTab;
        rangeBar.setDisable(!usesPeriod);
        previousButton.setDisable(!usesPeriod);
        nextButton.setDisable(!usesPeriod || nextStartsInFuture());
        periodBox.setDisable(!usesPeriod);
        exportButton.setDisable(tab != summaryTab);
        loadCurrent();
    }

    private void onRangeKind() {
        LocalDate anchor = period.to().isAfter(reportService.today()) ? reportService.today() : period.to();
        Object kind = rangeGroup.getSelectedToggle();
        if (kind == customRange) {
            onPickRange();
            return;
        }
        if (kind == weekRange) {
            period = FinancePeriod.week(anchor);
        } else if (kind == quarterRange) {
            period = FinancePeriod.quarter(anchor);
        } else if (kind == yearRange) {
            period = FinancePeriod.year(anchor.getYear());
        } else {
            period = FinancePeriod.month(YearMonth.from(anchor));
        }
        periodChanged();
    }

    @FXML
    private void onPrevious() {
        period = shift(-1);
        periodChanged();
    }

    @FXML
    private void onNext() {
        if (!nextStartsInFuture()) {
            period = shift(1);
            periodChanged();
        }
    }

    private boolean nextStartsInFuture() {
        return shift(1).from().isAfter(reportService.today());
    }

    private FinancePeriod shift(int step) {
        Object kind = rangeGroup.getSelectedToggle();
        if (kind == weekRange) {
            return FinancePeriod.week(period.from().plusWeeks(step));
        }
        if (kind == quarterRange) {
            return FinancePeriod.quarter(period.from().plusMonths(3L * step));
        }
        if (kind == yearRange) {
            return FinancePeriod.year(period.from().getYear() + step);
        }
        if (kind == customRange) {
            long days = period.days();
            return new FinancePeriod(period.from().plusDays(days * step), period.to().plusDays(days * step));
        }
        return FinancePeriod.month(YearMonth.from(period.from()).plusMonths(step));
    }

    /** Rango libre: desde y hasta en un diálogo. */
    @FXML
    private void onPickRange() {
        Form form = new Form();
        DatePicker from = new DatePicker(period.from());
        DatePicker to = new DatePicker(period.to());
        form.section("Rango de fechas", 2).field("DESDE *", from).field("HASTA *", to);
        form.note(note("Para ver los últimos 7 días, el mes o el año use los botones Semana, Mes o Año."));
        Dialog<ButtonType> dialog = formDialog("Elegir rango", "Los totales incluyen ambos días.", form, 520);
        runForm(dialog, form, "Ver rango", () -> new FinancePeriod(from.getValue(), to.getValue()), chosen -> {
            period = chosen;
            if (rangeGroup.getSelectedToggle() != customRange) {
                customRange.setSelected(true);
            }
            periodChanged();
        });
    }

    private void periodChanged() {
        nextButton.setDisable(nextStartsInFuture());
        loadCurrent();
    }

    @FXML
    private void onPreviousMonth() {
        month = month.minusMonths(1);
        loadCommissions();
    }

    @FXML
    private void onNextMonth() {
        if (month.isBefore(YearMonth.from(reportService.today()))) {
            month = month.plusMonths(1);
            loadCommissions();
        }
    }

    private void liveRefresh() {
        boolean onScreen = root.getScene() != null && root.isVisible() && root.getParent() != null;
        if (onScreen && period.contains(reportService.today())) {
            loadCurrent();
        }
    }

    // ---------- carga ----------

    private void loadCurrent() {
        periodLabel.setText(capitalize(period.label()));
        Object tab = tabGroup.getSelectedToggle();
        if (tab == summaryTab) {
            loadSummary();
        } else if (tab == commissionsTab) {
            loadCommissions();
        } else {
            loadMovements();
        }
    }

    private void loadSummary() {
        int request = ++generation;
        FinancePeriod asked = period;
        if (summaryContent.getChildren().isEmpty()) {
            summaryContent.getChildren().setAll(emptyLabel("Cargando el resumen…"));
        }
        async("finances-summary", () -> reportService.summary(asked), summary -> {
            if (request == generation) {
                fillSummaryKpis(summary);
                drawSummary(summary);
            }
        }, failure -> {
            if (request == generation) {
                summaryContent.getChildren().setAll(emptyLabel(""));
                showError(message(failure, "No se pudo cargar el resumen financiero."));
            }
        });
    }

    private void loadMovements() {
        int request = ++generation;
        FinancePeriod asked = period;
        setMovementsEmpty("Cargando los movimientos…");
        async("finances-movements", () -> transactionService.movements(asked), rows -> {
            if (request == generation) {
                allMovements = rows;
                if (!master) {
                    fillMovementKpis(rows);
                }
                applyMovementFilter();
            }
        }, failure -> {
            if (request == generation) {
                setMovementsEmpty("");
                showError(message(failure, "No se pudieron cargar los movimientos."));
            }
        });
    }

    private void loadCommissions() {
        int request = ++generation;
        YearMonth asked = month;
        commissionMonth.setText(capitalize(FinancePeriod.monthName(asked)) + " " + asked.getYear());
        nextMonthButton.setDisable(!asked.isBefore(YearMonth.from(reportService.today())));
        setCommissionsEmpty("Cargando las comisiones…");
        async("finances-commissions", () -> commissionService.overview(asked), overview -> {
            if (request != generation) {
                return;
            }
            commissionRows.setAll(overview.rows());
            long done = overview.rows().stream().filter(CommissionRow::liquidated).count();
            commissionsCount.setText(overview.rows().size() + (overview.rows().size() == 1 ? " persona" : " personas")
                    + " · " + done + " calculadas · " + pesos(overview.liquidated()) + " registrado como egreso");
            commissionNote.getStyleClass().remove("warn");
            if (overview.monthOpen()) {
                commissionNote.getStyleClass().add("warn");
                commissionNoteText.setText("El mes todavía no termina. Si calcula una comisión ahora, las ventas o"
                        + " preparaciones que entren después no se suman; para corregirla anule el egreso en Movimientos.");
            } else {
                commissionNoteText.setText("Vendedor: total de sus ventas vigentes del mes por su porcentaje."
                        + " Tanatólogo: preparaciones del cuerpo del mes por su tarifa. Cada persona se calcula una sola vez por mes.");
            }
            setCommissionsEmpty(overview.rows().isEmpty()
                    ? "No hay vendedores funerarios ni tanatólogos registrados en Personal." : "");
            if (!master) {
                fillCommissionKpis(overview);
            }
        }, failure -> {
            if (request == generation) {
                setCommissionsEmpty("");
                showError(message(failure, "No se pudieron cargar las comisiones."));
            }
        });
    }

    // ---------- indicadores ----------

    private void fillSummaryKpis(Summary s) {
        ReportService.Totals t = s.totals();
        setKpi(kpi1Caption, kpi1Value, kpi1Detail, "INGRESOS", pesos(t.income()), change(t.incomeChange(), s.previousLabel(), false));
        setKpi(kpi2Caption, kpi2Value, kpi2Detail, "EGRESOS", pesos(t.expense()), change(t.expenseChange(), s.previousLabel(), true));
        setKpi(kpi3Caption, kpi3Value, kpi3Detail, "BALANCE", pesos(t.balance()), change(t.balanceChange(), s.previousLabel(), false));
        setKpi(kpi4Caption, kpi4Value, kpi4Detail, "POR COBRAR HOY", pesos(s.receivable()),
                s.onCredit() + (s.onCredit() == 1 ? " venta a crédito" : " ventas a crédito"));
        tone(kpi1Detail, t.incomeChange(), false);
        tone(kpi2Detail, t.expenseChange(), true);
        tone(kpi3Detail, t.balanceChange(), false);
        kpi4Detail.getStyleClass().removeAll("up", "down");
    }

    private void fillMovementKpis(List<MovementRow> rows) {
        long incomes = rows.stream().filter(r -> !r.voided() && r.type() == TransactionType.INCOME).count();
        long expenses = rows.stream().filter(r -> !r.voided() && r.type() == TransactionType.EXPENSE).count();
        long voided = rows.stream().filter(MovementRow::voided).count();
        setKpi(kpi1Caption, kpi1Value, kpi1Detail, "MOVIMIENTOS", String.valueOf(rows.size() - voided), period.label());
        setKpi(kpi2Caption, kpi2Value, kpi2Detail, "INGRESOS", String.valueOf(incomes), "registros vigentes");
        setKpi(kpi3Caption, kpi3Value, kpi3Detail, "EGRESOS", String.valueOf(expenses), "registros vigentes");
        setKpi(kpi4Caption, kpi4Value, kpi4Detail, "ANULADOS", String.valueOf(voided), "no cuentan en los totales");
    }

    private void fillCommissionKpis(CommissionService.Overview overview) {
        long done = overview.rows().stream().filter(CommissionRow::liquidated).count();
        long pending = overview.rows().stream().filter(r -> !r.liquidated() && r.cases() > 0).count();
        setKpi(kpi1Caption, kpi1Value, kpi1Detail, "COMISIONES CALCULADAS", String.valueOf(done),
                FinancePeriod.monthName(overview.month()) + " " + overview.month().getYear());
        setKpi(kpi2Caption, kpi2Value, kpi2Detail, "POR CALCULAR", String.valueOf(pending), "personas con casos en el mes");
        setKpi(kpi3Caption, kpi3Value, kpi3Detail, "REGISTRADO", pesos(overview.liquidated()), "como egreso");
        setKpi(kpi4Caption, kpi4Value, kpi4Detail, "PENDIENTE", pesos(overview.pending()), "si se calcula hoy");
    }

    private static void setKpi(Label caption, Label value, Label detail, String c, String v, String d) {
        caption.setText(c);
        value.setText(v);
        detail.setText(d);
    }

    private static String change(Double percent, String previous, boolean expense) {
        if (percent == null) {
            return "sin datos de " + previous;
        }
        String sign = percent > 0 ? "+" : "";
        return sign + new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(ES)).format(percent) + " % vs. " + previous;
    }

    /** Verde si mejora y terracota si empeora; para egresos, subir es empeorar. */
    private static void tone(Label detail, Double percent, boolean expense) {
        detail.getStyleClass().removeAll("up", "down");
        if (percent != null && percent != 0) {
            boolean good = expense ? percent < 0 : percent > 0;
            detail.getStyleClass().add(good ? "up" : "down");
        }
    }

    // ---------- resumen ----------

    private void drawSummary(Summary s) {
        GridPane top = twoColumns(64);
        top.add(flowCard(s), 0, 0);
        top.add(originCard(s), 1, 0);
        GridPane bottom = twoColumns(64);
        bottom.add(monthCard(s), 0, 0);
        bottom.add(commissionCard(s), 1, 0);
        summaryContent.getChildren().setAll(top, bottom, accountCard(s));
    }

    private static GridPane twoColumns(double firstPercent) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        ColumnConstraints a = new ColumnConstraints();
        a.setPercentWidth(firstPercent);
        ColumnConstraints b = new ColumnConstraints();
        b.setPercentWidth(100 - firstPercent);
        grid.getColumnConstraints().addAll(a, b);
        return grid;
    }

    private Node flowCard(Summary s) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(millions());
        y.setForceZeroInRange(true);
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.getStyleClass().add("flow-chart");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        chart.setPrefHeight(300);
        chart.setMinHeight(260);
        XYChart.Series<String, Number> income = new XYChart.Series<>();
        XYChart.Series<String, Number> expense = new XYChart.Series<>();
        for (FlowPoint p : s.flow()) {
            income.getData().add(new XYChart.Data<>(p.label(), p.income()));
            expense.getData().add(new XYChart.Data<>(p.label(), p.expense()));
        }
        chart.getData().setAll(List.of(income, expense));
        // Con muchos días el eje oculta solo las etiquetas que se encimarían.
        x.setTickLabelsVisible(true);
        for (int i = 0; i < s.flow().size(); i++) {
            FlowPoint p = s.flow().get(i);
            Tooltip tip = new Tooltip(p.longLabel() + "\nIngresos  " + pesos(p.income()) + "\nEgresos   " + pesos(p.expense()));
            tip.getStyleClass().add("chart-tip");
            tip.setShowDelay(Duration.millis(80));
            Node a = income.getData().get(i).getNode();
            Node b = expense.getData().get(i).getNode();
            if (a != null) {
                Tooltip.install(a, tip);
            }
            if (b != null) {
                Tooltip.install(b, tip);
            }
        }
        HBox legend = new HBox(16, legendItem("#1E3A2E", "Ingresos"), legendItem("#C4AF84", "Egresos"));
        legend.setAlignment(Pos.CENTER_RIGHT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(new VBox(2, label("Flujo de ingresos y egresos", "chart-title"),
                label(capitalize(s.periodLabel()) + " · " + (s.byDay() ? "por día" : "por mes")
                        + (s.period().contains(reportService.today()) ? " · en vivo, se actualiza cada minuto" : ""),
                        "chart-subtitle")), gap, legend);
        head.setAlignment(Pos.TOP_LEFT);
        VBox card = new VBox(head, chart);
        card.getStyleClass().add("chart-card");
        if (s.flow().stream().allMatch(p -> p.income().signum() == 0 && p.expense().signum() == 0)) {
            card.getChildren().add(label("No hay movimientos vigentes en el período.", "chart-subtitle"));
        }
        return card;
    }


    private Node originCard(Summary s) {
        VBox card = new VBox(8, label("Ingresos por origen", "chart-title"), label("Del período elegido", "chart-subtitle"));
        card.getStyleClass().add("chart-card");
        if (s.incomeByOrigin().isEmpty()) {
            card.getChildren().add(emptyLabel("No hay ingresos en el período."));
            return card;
        }
        PieChart pie = new PieChart();
        pie.getStyleClass().add("donut-chart");
        pie.setLegendVisible(false);
        pie.setLabelsVisible(false);
        pie.setAnimated(false);
        pie.setStartAngle(90);
        pie.setPrefSize(200, 200);
        pie.setMinSize(200, 200);
        pie.setMaxSize(200, 200);
        VBox legend = new VBox(10);
        BigDecimal total = s.totals().income();
        for (int i = 0; i < s.incomeByOrigin().size(); i++) {
            Slice slice = s.incomeByOrigin().get(i);
            PieChart.Data data = new PieChart.Data(slice.label(), slice.amount().doubleValue());
            pie.getData().add(data);
            String color = SLICE_COLORS[i % SLICE_COLORS.length];
            data.getNode().setStyle("-fx-pie-color: " + color + ";");
            String percent = total.signum() == 0 ? "" : " · " + slice.amount().multiply(BigDecimal.valueOf(100))
                    .divide(total, 0, RoundingMode.HALF_UP) + " %";
            Region dot = new Region();
            dot.getStyleClass().add("legend-dot");
            dot.setStyle("-fx-background-color: " + color + ";");
            Label name = label(slice.label(), "legend-text");
            name.setWrapText(true);
            VBox text = new VBox(1, name, label(pesos(slice.amount()) + percent, "legend-sub"));
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox item = new HBox(8, dot, text);
            item.setAlignment(Pos.TOP_LEFT);
            HBox.setMargin(dot, new Insets(4, 0, 0, 0));
            legend.getChildren().add(item);
            Tooltip tip = new Tooltip(slice.label() + "\n" + pesos(slice.amount()) + percent);
            tip.getStyleClass().add("chart-tip");
            Tooltip.install(data.getNode(), tip);
        }
        Circle hole = new Circle(58);
        hole.getStyleClass().add("donut-hole");
        hole.setMouseTransparent(true);
        VBox center = new VBox(0, label(shortPesos(total), "donut-value"), label("ingresos", "donut-caption"));
        center.setAlignment(Pos.CENTER);
        center.setMouseTransparent(true);
        StackPane donut = new StackPane(pie, hole, center);
        donut.setMinSize(200, 200);
        HBox.setHgrow(legend, Priority.ALWAYS);
        legend.setMinWidth(0);
        HBox body = new HBox(18, donut, legend);
        body.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(body);
        return card;
    }

    private Node monthCard(Summary s) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(millions());
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.getStyleClass().add("month-chart");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(14);
        chart.setPrefHeight(250);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (MonthBalance m : s.months()) {
            series.getData().add(new XYChart.Data<>(m.month(), m.balance()));
        }
        chart.getData().add(series);
        int currentMonth = reportService.today().getYear() == s.year() ? reportService.today().getMonthValue() : -1;
        for (int i = 0; i < s.months().size(); i++) {
            MonthBalance m = s.months().get(i);
            Node bar = series.getData().get(i).getNode();
            if (bar == null) {
                continue;
            }
            if (m.balance().signum() < 0) {
                bar.getStyleClass().add("negative");
            } else if (i + 1 == currentMonth) {
                bar.getStyleClass().add("current");
            }
            Tooltip tip = new Tooltip(m.month() + " " + s.year() + "\nIngresos  " + pesos(m.income())
                    + "\nEgresos   " + pesos(m.expense()) + "\nSaldo     " + pesos(m.balance()));
            tip.getStyleClass().add("chart-tip");
            Tooltip.install(bar, tip);
        }
        VBox card = new VBox(label("Balance por mes", "chart-title"),
                label("Ingresos menos egresos · " + s.year(), "chart-subtitle"), chart);
        card.getStyleClass().add("chart-card");
        return card;
    }

    private Node commissionCard(Summary s) {
        Button all = new Button("Ver todas ›");
        all.getStyleClass().add("link-button");
        all.setOnAction(e -> {
            month = YearMonth.from(s.period().to().isAfter(reportService.today()) ? reportService.today() : s.period().to());
            commissionsTab.setSelected(true);
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(new VBox(2, label("Comisiones", "chart-title"),
                label("Registradas como egreso en el período", "chart-subtitle")), gap, all);
        head.setAlignment(Pos.TOP_LEFT);
        VBox card = new VBox(6, head);
        card.getStyleClass().add("chart-card");
        if (s.commissions().isEmpty()) {
            card.getChildren().add(emptyLabel("Todavía no hay comisiones calculadas en el período."));
        }
        s.commissions().stream().limit(6).forEach(c -> {
            Region space = new Region();
            HBox.setHgrow(space, Priority.ALWAYS);
            String detail = c.description() == null ? "" : c.description().replaceFirst("^Comisión \\d{4}-\\d{2} · [^·]+· ", "");
            HBox line = new HBox(new VBox(2, label(c.name() == null ? "—" : c.name(), "commission-name"),
                    label(detail, "commission-sub")), space, label(pesos(c.amount()), "commission-amount"));
            line.getStyleClass().add("commission-line");
            line.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(line);
        });
        return card;
    }

    /** Egresos del período por cuenta (administración, cementerio...), como barras horizontales. */
    private Node accountCard(Summary s) {
        VBox card = new VBox(8, label("Egresos por cuenta", "chart-title"),
                label("Del período elegido · los egresos automáticos (comisiones, aliadas, cremaciones) van sin cuenta",
                        "chart-subtitle"));
        card.getStyleClass().add("chart-card");
        if (s.expenseByAccount().isEmpty()) {
            card.getChildren().add(emptyLabel("No hay egresos en el período."));
            return card;
        }
        BigDecimal total = s.totals().expense();
        BigDecimal largest = s.expenseByAccount().stream().map(Slice::amount).reduce(BigDecimal.ZERO, BigDecimal::max);
        for (Slice slice : s.expenseByAccount()) {
            Label name = label(slice.label(), "legend-text");
            name.setMinWidth(220);
            name.setPrefWidth(220);
            Region fill = new Region();
            fill.getStyleClass().add("account-bar-fill");
            if (FinancialTransactionService.NO_ACCOUNT.equals(slice.label())) {
                fill.getStyleClass().add("no-account");
            }
            StackPane track = new StackPane(fill);
            track.getStyleClass().add("account-bar-track");
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            double share = largest.signum() == 0 ? 0 : slice.amount().doubleValue() / largest.doubleValue();
            fill.maxWidthProperty().bind(track.widthProperty().multiply(share));
            HBox.setHgrow(track, Priority.ALWAYS);
            String percent = total.signum() == 0 ? "" : slice.amount().multiply(BigDecimal.valueOf(100))
                    .divide(total, 0, RoundingMode.HALF_UP) + " %";
            Label value = label(pesos(slice.amount()), "commission-amount");
            value.setMinWidth(130);
            value.setAlignment(Pos.CENTER_RIGHT);
            Label share100 = label(percent, "legend-sub");
            share100.setMinWidth(48);
            share100.setAlignment(Pos.CENTER_RIGHT);
            HBox line = new HBox(14, name, track, value, share100);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("account-line");
            card.getChildren().add(line);
        }
        return card;
    }

    private static HBox legendItem(String color, String text) {
        Region dot = new Region();
        dot.getStyleClass().add("legend-dot");
        dot.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 99;");
        HBox item = new HBox(6, dot, label(text, "legend-text"));
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private static StringConverter<Number> millions() {
        return new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return shortPesos(BigDecimal.valueOf(value.doubleValue()));
            }

            @Override
            public Number fromString(String string) {
                return 0;
            }
        };
    }

    // ---------- movimientos ----------

    private void applyMovementFilter() {
        Object kind = typeGroup.getSelectedToggle();
        String query = normalize(searchField.getText());
        List<MovementRow> rows = allMovements.stream()
                .filter(r -> kind == voidedType ? r.voided()
                        : kind == incomeType ? !r.voided() && r.type() == TransactionType.INCOME
                        : kind != expenseType || !r.voided() && r.type() == TransactionType.EXPENSE)
                .filter(r -> query.isEmpty() || normalize(r.code() + " " + r.description() + " "
                        + r.origin().getLabel() + " " + (r.registeredBy() == null ? "" : r.registeredBy()) + " "
                        + (r.account() == null ? "" : r.account())).contains(query))
                .toList();
        movementRows.setAll(rows);
        BigDecimal in = rows.stream().filter(r -> !r.voided() && r.type() == TransactionType.INCOME)
                .map(MovementRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal out = rows.stream().filter(r -> !r.voided() && r.type() == TransactionType.EXPENSE)
                .map(MovementRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        movementsCount.setText(rows.size()
                + (rows.size() == 1 ? " movimiento" : " movimientos") + " · entran " + pesos(in) + " · salen " + pesos(out));
        setMovementsEmpty(rows.isEmpty() ? (allMovements.isEmpty()
                ? "No hay movimientos en el período." : "Ningún movimiento coincide con el filtro.") : "");
    }

    private final class MovementCell extends ListCell<MovementRow> {
        private final Label date = label("", "item-row-text");
        private final Label code = label("", "item-row-sub");
        private final Label type = label("", "state-chip");
        private final Label description = label("", "item-row-name");
        private final Label who = label("", "item-row-sub");
        private final Label origin = label("", "item-row-text");
        private final Label account = label("", "item-row-sub");
        private final Label amount = label("", "amount-income");
        private final Button voidButton = new Button("Anular");
        private final Label hint = label("", "row-hint");
        private final HBox row;

        MovementCell() {
            voidButton.getStyleClass().add("row-button");
            voidButton.setOnAction(e -> {
                if (getItem() != null) {
                    voidMovement(getItem());
                }
            });
            hint.setWrapText(true);
            description.setMinWidth(0);
            VBox when = new VBox(2, date, code);
            VBox what = new VBox(2, description, who);
            StackPane action = new StackPane(voidButton, hint);
            StackPane.setAlignment(voidButton, Pos.CENTER_LEFT);
            StackPane.setAlignment(hint, Pos.CENTER_LEFT);
            VBox source = new VBox(2, origin, account);
            row = new HBox(cell(when, W_DATE), cell(type, W_TYPE), grow(what), cell(source, W_ORIGIN),
                    cell(amount, W_MONEY), cell(action, W_ACTION));
            row.getStyleClass().add("item-row");
        }

        @Override
        protected void updateItem(MovementRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            date.setText(item.date() == null ? "" : item.date().format(DATE_HOUR));
            code.setText(item.code());
            boolean income = item.type() == TransactionType.INCOME;
            type.setText(item.voided() ? "Anulado" : income ? "Ingreso" : "Egreso");
            type.getStyleClass().setAll("state-chip", item.voided() ? "chip-voided" : income ? "chip-income" : "chip-expense");
            description.setText(item.description() == null ? "" : item.description());
            who.setText((item.saleNumber() == null ? "" : "Venta N.° " + item.saleNumber() + " · ")
                    + (item.registeredBy() == null ? "" : item.registeredBy()));
            origin.setText(item.origin().getLabel());
            // La cuenta solo aplica a egresos; en ingresos no se muestra nada.
            account.setText(income ? "" : item.account() == null ? FinancialTransactionService.NO_ACCOUNT : item.account());
            show(account, !income);
            amount.setText((income ? "+ " : "− ") + pesos(item.amount()));
            amount.getStyleClass().setAll(income ? "amount-income" : "amount-expense");
            row.getStyleClass().remove("row-voided");
            if (item.voided()) {
                row.getStyleClass().add("row-voided");
            }
            show(voidButton, item.voidableHere());
            hint.setText(item.voided() || item.voidableHere() ? "" : "Se anula en " + item.origin().getOwnerModule());
            show(hint, !item.voidableHere() && !item.voided());
            setGraphic(row);
        }
    }

    private void voidMovement(MovementRow r) {
        String question = "¿Anular el " + (r.type() == TransactionType.INCOME ? "ingreso " : "egreso ") + r.code()
                + " por " + pesos(r.amount()) + "?";
        String detail = "No se borra: queda en la lista como anulado, deja de contar en los totales y el cambio"
                + " queda en la bitácora." + (r.origin() == FinancialTransactionService.Origin.COMMISSION
                ? " La comisión de esa persona y ese mes se podrá calcular de nuevo." : "")
                + (r.saleNumber() != null ? " El saldo de la venta N.° " + r.saleNumber() + " vuelve a subir." : "");
        if (!confirm(question, detail, "Anular movimiento")) {
            return;
        }
        async("finances-void", () -> {
            transactionService.voidMovement(r.id());
            return Boolean.TRUE;
        }, ok -> {
            showToast("Movimiento " + r.code() + " anulado.");
            loadMovements();
        }, failure -> showError(message(failure, "No se pudo anular el movimiento.")));
    }

    /** Registrar un ingreso o un egreso; un ingreso puede ser el abono de una venta con saldo. */
    @FXML
    private void onNewMovement() {
        Form form = new Form();
        ToggleGroup kind = new ToggleGroup();
        ToggleButton income = new ToggleButton("Ingreso");
        ToggleButton expense = new ToggleButton("Egreso");
        income.setToggleGroup(kind);
        expense.setToggleGroup(kind);
        income.getStyleClass().add("segment");
        expense.getStyleClass().add("segment");
        income.setSelected(true);
        keepOneSelected(kind, () -> { });
        HBox kindBox = new HBox(income, expense);
        kindBox.getStyleClass().addAll("segmented", "form-segmented");
        TextField amount = new TextField();
        amount.setPromptText("$ 0");
        TextField description = new TextField();
        description.setPromptText("Ej.: Pago de energía de octubre");
        ComboBox<SaleOption> sale = new ComboBox<>();
        sale.getStyleClass().add("form-combo");
        sale.setPromptText("Ninguna (movimiento general)");
        sale.setConverter(new StringConverter<>() {
            @Override
            public String toString(SaleOption o) {
                return o == null ? "" : o.label() + " · saldo " + pesos(o.balance());
            }

            @Override
            public SaleOption fromString(String s) {
                return null;
            }
        });
        sale.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(SaleOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Ninguna (movimiento general)" : sale.getConverter().toString(item));
            }
        });
        async("finances-sales", transactionService::salesWithBalance, options -> {
            sale.getItems().setAll(options);
            sale.getItems().add(0, null);
        }, failure -> { });
        ComboBox<AccountOption> account = new ComboBox<>();
        account.getStyleClass().add("form-combo");
        account.setPromptText(FinancialTransactionService.NO_ACCOUNT);
        account.setConverter(new StringConverter<>() {
            @Override
            public String toString(AccountOption o) {
                return o == null ? FinancialTransactionService.NO_ACCOUNT : o.name();
            }

            @Override
            public AccountOption fromString(String s) {
                return null;
            }
        });
        account.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AccountOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? FinancialTransactionService.NO_ACCOUNT : item.name());
            }
        });
        account.setDisable(true);
        async("finances-accounts", transactionService::accounts, options -> {
            account.getItems().setAll(options);
            account.getItems().add(0, null);
        }, failure -> { });
        Label saleHint = note("Si es el abono de una venta a crédito, elíjala: el abono baja su saldo y no puede superarlo.");
        expense.selectedProperty().addListener((obs, old, isExpense) -> {
            sale.setDisable(isExpense);
            account.setDisable(!isExpense);
            if (isExpense) {
                sale.setValue(null);
            } else {
                account.setValue(null);
            }
        });
        form.section("Movimiento", 2)
                .field("TIPO *", kindBox)
                .field("MONTO *", amount);
        form.section("Detalle", 1)
                .field("DESCRIPCIÓN *", description)
                .field("VENTA ASOCIADA (SOLO INGRESOS)", sale)
                .field("CUENTA (SOLO EGRESOS)", account);
        form.note(saleHint);
        form.note(note("Se guarda con la fecha y hora de ahora. Un movimiento nunca se borra: si hay un error se anula."));
        Dialog<ButtonType> dialog = formDialog("Registrar movimiento", "Los campos con * son obligatorios.", form, 640);
        runForm(dialog, form, "Registrar", () -> {
            BigDecimal value = parseAmount(amount.getText(), "El monto");
            if (value == null) {
                throw new InvalidDataException("Escriba el monto.");
            }
            transactionService.register(new MovementData(income.isSelected() ? TransactionType.INCOME
                    : TransactionType.EXPENSE, value, description.getText(),
                    sale.getValue() == null ? null : sale.getValue().saleId(),
                    account.getValue() == null ? null : account.getValue().id()));
            return Boolean.TRUE;
        }, ok -> {
            showToast("Movimiento registrado.");
            loadCurrent();
        });
    }

    // ---------- comisiones ----------

    private final class CommissionCell extends ListCell<CommissionRow> {
        private final Label name = label("", "item-row-name");
        private final Label sub = label("", "item-row-sub");
        private final Label role = label("", "item-row-text");
        private final Label cases = label("", "item-row-text");
        private final Label base = label("", "item-row-text");
        private final Label rate = label("", "item-row-text");
        private final Label amount = label("", "item-row-name");
        private final Button liquidate = new Button("Calcular comisión");
        private final Label done = label("", "state-chip");
        private final HBox row;

        CommissionCell() {
            liquidate.getStyleClass().addAll("row-button", "strong");
            liquidate.setOnAction(e -> {
                if (getItem() != null) {
                    liquidate(getItem());
                }
            });
            done.getStyleClass().add("chip-done");
            StackPane action = new StackPane(liquidate, done);
            StackPane.setAlignment(liquidate, Pos.CENTER_LEFT);
            StackPane.setAlignment(done, Pos.CENTER_LEFT);
            VBox person = new VBox(2, name, sub);
            person.setAlignment(Pos.CENTER_LEFT);
            row = new HBox(grow(person), cell(role, W_ROLE), cell(cases, W_CASES), cell(base, W_MONEY),
                    cell(rate, W_CASES), cell(amount, W_MONEY), cell(action, W_ACTION + 20));
            row.getStyleClass().add("item-row");
        }

        @Override
        protected void updateItem(CommissionRow item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            boolean seller = item.kind() == CommissionService.Kind.SALESPERSON;
            name.setText(item.name());
            sub.setText(item.active() ? "" : "Inactivo");
            show(sub, !item.active());
            role.setText(item.kind().getLabel());
            cases.setText(item.cases() + (seller ? (item.cases() == 1 ? " venta" : " ventas")
                    : (item.cases() == 1 ? " preparación" : " preparaciones")));
            base.setText(seller ? pesos(item.base()) : "—");
            rate.setText(item.rate() == null ? "Sin registrar" : seller
                    ? item.rate().stripTrailingZeros().toPlainString() + " %" : pesos(item.rate()) + " c/u");
            amount.setText(pesos(item.amount()));
            if (item.liquidated()) {
                done.setText("Calculada " + (item.liquidatedAt() == null ? "" : item.liquidatedAt().format(DAY)));
            }
            show(done, item.liquidated());
            show(liquidate, !item.liquidated());
            liquidate.setDisable(item.cases() == 0 || item.rate() == null || item.amount().signum() <= 0);
            liquidate.setText(item.cases() == 0 ? "Sin casos" : "Calcular comisión");
            setGraphic(row);
        }
    }

    private void liquidate(CommissionRow r) {
        String monthText = FinancePeriod.monthName(month) + " " + month.getYear();
        String question = "¿Calcular la comisión de " + r.name() + " de " + monthText + "?";
        String detail = "Se registra un egreso de " + pesos(r.amount()) + " a su nombre ("
                + (r.kind() == CommissionService.Kind.SALESPERSON
                ? r.cases() + " ventas por " + pesos(r.base()) + " al " + r.rate().stripTrailingZeros().toPlainString() + "\u00A0%"
                : r.cases() + " preparaciones a " + pesos(r.rate()) + " cada una")
                + "). No se puede calcular otra vez el mismo mes.";
        if (!confirm(question, detail, "Calcular comisión")) {
            return;
        }
        YearMonth asked = month;
        async("finances-commission", () -> commissionService.liquidate(r.employeeId(), asked), amount -> {
            showToast("Comisión de " + r.name() + " registrada: " + pesos(amount) + ".");
            loadCommissions();
        }, failure -> showError(message(failure, "No se pudo calcular la comisión.")));
    }

    // ---------- exportar ----------

    @FXML
    private void onExportPdf() {
        export(ReportService.Format.PDF);
    }

    @FXML
    private void onExportExcel() {
        export(ReportService.Format.EXCEL);
    }

    private void export(ReportService.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar resumen financiero");
        chooser.setInitialFileName(ReportService.suggestedName(period, format));
        chooser.getExtensionFilters().add(format == ReportService.Format.PDF
                ? new FileChooser.ExtensionFilter("PDF", "*.pdf")
                : new FileChooser.ExtensionFilter("Excel", "*.xlsx"));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        FinancePeriod asked = period;
        exportButton.setDisable(true);
        exportButton.setText("Exportando…");
        async("finances-export", () -> {
            reportService.export(asked, format, file.toPath());
            return file;
        }, saved -> {
            exportButton.setDisable(false);
            exportButton.setText("Exportar");
            showToast("Archivo guardado: " + saved.getName());
        }, failure -> {
            exportButton.setDisable(false);
            exportButton.setText("Exportar");
            showError(message(failure, "No se pudo generar el archivo."));
        });
    }

    // ---------- formulario "Personal · El Edén" ----------

    /** Formulario con sección en mayúsculas y filete, etiqueta arriba del campo y columnas de igual ancho. */
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
            if (input instanceof Region region && !(input instanceof HBox)) {
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
        VBox header = new VBox(2, label(title, "form-title"), label(subtitle, "card-subtitle"));
        header.getStyleClass().add("form-header");
        VBox.setMargin(form.error, new Insets(6, 28, 4, 28));
        VBox content = new VBox(header, form.body, form.error);
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
            async("finances-save", save, result -> {
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

    private boolean confirm(String question, String detail, String actionText) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(window());
        alert.setTitle("Finanzas");
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

    private void setMovementsEmpty(String text) {
        movementsEmpty.setText(text);
        movementsEmpty.setVisible(!text.isEmpty());
        if (!text.isEmpty() && text.startsWith("Cargando")) {
            movementRows.clear();
        }
    }

    private void setCommissionsEmpty(String text) {
        commissionsEmpty.setText(text);
        commissionsEmpty.setVisible(!text.isEmpty());
        if (!text.isEmpty() && text.startsWith("Cargando")) {
            commissionRows.clear();
        }
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("note-info");
        note.setWrapText(true);
        SVGPath icon = new SVGPath();
        icon.setContent("M8 1a7 7 0 1 1 0 14A7 7 0 0 1 8 1z M8 7v4.5 M8 4.6v.2");
        icon.getStyleClass().add("note-icon");
        note.setGraphic(icon);
        note.setGraphicTextGap(10);
        note.setMaxWidth(Double.MAX_VALUE);
        note.setMinHeight(Region.USE_PREF_SIZE);
        return note;
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

    private static Label emptyLabel(String text) {
        Label label = label(text, "empty-board");
        label.setWrapText(true);
        return label;
    }

    /** Las ventanas cargan app.css, personal.css y finances.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                FinancesController.class.getResource("/css/app.css").toExternalForm(),
                FinancesController.class.getResource("/css/personal.css").toExternalForm(),
                FinancesController.class.getResource("/css/finances.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "finances-root");
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
    }

    private void showError(String text) {
        Alert alert = new Alert(Alert.AlertType.WARNING, text);
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

    static String pesos(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(ES);
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP));
    }

    /** "$ 48,2 M" desde un millón y "$ 850 mil" desde mil; por debajo, el valor completo. */
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
        return pesos(v);
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text.trim().toLowerCase(ES), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
