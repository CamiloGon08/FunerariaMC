package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.AnalyticsService;
import com.example.funerariamc.service.AnalyticsService.CemeteryOccupancy;
import com.example.funerariamc.service.AnalyticsService.KindBucket;
import com.example.funerariamc.service.AnalyticsService.Kpis;
import com.example.funerariamc.service.AnalyticsService.Overview;
import com.example.funerariamc.service.AnalyticsService.RoomHeat;
import com.example.funerariamc.service.AnalyticsService.SellerLine;
import com.example.funerariamc.service.AnalyticsService.ThanatoLine;
import com.example.funerariamc.service.FinancePeriod;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.SectionView;
import com.example.funerariamc.ui.SharedPeriod;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.service.BrandPalette;
import com.example.funerariamc.ui.BrandTheme;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Panel de análisis del Administrador Maestro. Solo lectura: indicadores del período, servicios por tipo,
 * ocupación de salas y bóvedas, y desempeño de vendedores y tanatólogos. El período puede ser los últimos
 * 7 días, un mes o un año, y se recorre con las flechas (nunca hacia el futuro). Se exporta a PDF.
 */
@Component
@Scope("prototype")
public class AnalyticsController implements RefreshableView, SectionView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Finanzas y análisis";

    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private final AnalyticsService analyticsService;
    private final FuneralPlanService planService;

    @FXML private StackPane root;
    @FXML private ToggleGroup tabGroup;
    @FXML private ToggleGroup rangeGroup;
    @FXML private ToggleButton salesTab;
    @FXML private ToggleButton occupancyTab;
    @FXML private ToggleButton teamTab;
    @FXML private ToggleButton plansTab;
    @FXML private HBox tabBar;
    @FXML private ToggleButton weekRange;
    @FXML private ToggleButton monthRange;
    @FXML private ToggleButton quarterRange;
    @FXML private ToggleButton yearRange;
    @FXML private ToggleButton customRange;
    @FXML private Button previousButton;
    @FXML private Button nextButton;
    @FXML private Label periodLabel;
    @FXML private Button exportButton;
    @FXML private HBox kpiRow;
    @FXML private VBox content;
    @FXML private Label toast;

    private SharedPeriod.Kind kind = SharedPeriod.Kind.MONTH;
    /** Período que se está viendo; es el mismo de las pestañas de Finanzas (SharedPeriod). */
    private FinancePeriod period;
    /** true mientras se marca el botón del período sin volver a calcularlo. */
    private boolean adopting;
    private Overview overview;
    /** Planes exequiales: no dependen del período, se cargan al abrir esa pestaña. */
    private FuneralPlanService.Overview plans;
    private int generation;
    private SequentialTransition toastMotion;

    public AnalyticsController(AnalyticsService analyticsService, FuneralPlanService planService) {
        this.analyticsService = analyticsService;
        this.planService = planService;
    }

    @Override
    public void refresh() {
        adoptSharedPeriod();
        plans = null; // al volver se recargan los planes
        load();
    }

    /**
     * Dentro de Finanzas y análisis las pestañas son las del menú: se esconden las de la pantalla y
     * se muestra la parte pedida ("sales", "plans", "occupancy" o "team").
     */
    @Override
    public void showSection(String section) {
        tabBar.setVisible(false);
        tabBar.setManaged(false);
        (switch (section) {
            case "plans" -> plansTab;
            case "occupancy" -> occupancyTab;
            case "team" -> teamTab;
            default -> salesTab;
        }).setSelected(true);
    }

    /** Toma el período elegido en otra pestaña y marca su botón (Semana, Mes, Trimestre, Año o Rango). */
    private void adoptSharedPeriod() {
        FinancePeriod shared = SharedPeriod.period();
        if (shared != null) {
            kind = SharedPeriod.kind();
            period = shared;
        }
        adopting = true;
        try {
            toggleOf(kind).setSelected(true);
        } finally {
            adopting = false;
        }
    }

    private ToggleButton toggleOf(SharedPeriod.Kind k) {
        return switch (k) {
            case WEEK -> weekRange;
            case QUARTER -> quarterRange;
            case YEAR -> yearRange;
            case CUSTOM -> customRange;
            default -> monthRange;
        };
    }

    private SharedPeriod.Kind kindOf(Object toggle) {
        return toggle == weekRange ? SharedPeriod.Kind.WEEK : toggle == quarterRange ? SharedPeriod.Kind.QUARTER
                : toggle == yearRange ? SharedPeriod.Kind.YEAR : toggle == customRange ? SharedPeriod.Kind.CUSTOM
                : SharedPeriod.Kind.MONTH;
    }

    private void periodChanged() {
        SharedPeriod.set(kind, period);
        load();
    }

    @FXML
    private void initialize() {
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        period = FinancePeriod.month(YearMonth.from(analyticsService.today()));
        adoptSharedPeriod();
        // Un grupo de pestañas nunca queda sin selección.
        keepSelected(tabGroup);
        keepSelected(rangeGroup);
        tabGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null && overview != null) {
                drawTab();
            }
        });
        rangeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null || adopting) {
                return;
            }
            SharedPeriod.Kind chosen = kindOf(now);
            if (chosen == SharedPeriod.Kind.CUSTOM) {
                java.util.Optional<FinancePeriod> range = SharedPeriod.askRange(window(), period, MODULE_NAME);
                if (range.isEmpty()) {
                    // Cancelado: vuelve el botón que estaba
                    adopting = true;
                    try {
                        rangeGroup.selectToggle(old);
                    } finally {
                        adopting = false;
                    }
                    return;
                }
                period = range.get();
            } else {
                LocalDate today = analyticsService.today();
                period = SharedPeriod.of(chosen, period.to().isAfter(today) ? today : period.to());
            }
            kind = chosen;
            periodChanged();
        });
        load();
    }

    @FXML
    private void onPrevious() {
        period = SharedPeriod.shift(kind, period, -1);
        periodChanged();
    }

    @FXML
    private void onNext() {
        if (!nextStartsInFuture()) {
            period = SharedPeriod.shift(kind, period, 1);
            periodChanged();
        }
    }

    private boolean nextStartsInFuture() {
        return SharedPeriod.shift(kind, period, 1).from().isAfter(analyticsService.today());
    }

    @FXML
    private void onExport() {
        if (overview == null) {
            return;
        }
        FinancePeriod period = overview.period();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar panel de análisis");
        chooser.setInitialFileName(AnalyticsService.suggestedName(period));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        exportButton.setDisable(true);
        exportButton.setText("Exportando…");
        async("analytics-export", () -> {
            analyticsService.exportPdf(period, file.toPath());
            return file;
        }, saved -> {
            exportButton.setDisable(false);
            exportButton.setText("Exportar PDF");
            showToast("Archivo guardado: " + saved.getName());
        }, failure -> {
            exportButton.setDisable(false);
            exportButton.setText("Exportar PDF");
            showError(message(failure, "No se pudo generar el archivo."));
        });
    }

    private void load() {
        FinancePeriod period = this.period;
        periodLabel.setText(capitalize(period.label()));
        nextButton.setDisable(nextStartsInFuture());
        exportButton.setDisable(true);
        int request = ++generation;
        if (overview == null) {
            content.getChildren().setAll(label("Cargando…", "empty-board"));
        } else {
            // Mientras llega el nuevo período lo anterior queda atenuado, para que se note que cambió.
            kpiRow.setOpacity(0.45);
            content.setOpacity(0.45);
        }
        async("analytics-load", () -> analyticsService.overview(period), result -> {
            if (request != generation) {
                return;
            }
            overview = result;
            kpiRow.setOpacity(1);
            content.setOpacity(1);
            exportButton.setDisable(false);
            drawKpis(result);
            drawTab();
        }, failure -> {
            if (request == generation) {
                kpiRow.setOpacity(1);
                content.setOpacity(1);
                content.getChildren().setAll(label(message(failure,
                        "No se pudo cargar el panel. Revise la conexión."), "empty-board"));
            }
        });
    }

    // ---------- indicadores ----------

    private void drawKpis(Overview o) {
        Kpis k = o.kpis();
        long diff = k.services() - k.servicesPrevious();
        // El detalle compara con el período anterior y lo dice, para no confundirlo con el período elegido.
        String change = k.servicesPrevious() == 0
                ? "Período anterior (" + o.previousLabel() + "): 0"
                : (diff >= 0 ? "▲ " : "▼ ") + Math.abs(Math.round(diff * 100.0 / k.servicesPrevious()))
                + " % vs. período anterior (" + o.previousLabel() + ": " + k.servicesPrevious() + ")";
        VBox services = kpi("SERVICIOS DEL PERÍODO", String.valueOf(k.services()), change, true);
        if (k.servicesPrevious() > 0) {
            services.getChildren().get(2).getStyleClass().add(diff >= 0 ? "up" : "down");
        }
        kpiRow.getChildren().setAll(services,
                kpi("VALOR PROMEDIO", HomeController.shortPesos(k.averageValue()), "por servicio vendido", false),
                kpi("OCUPACIÓN DE SALAS", k.roomOccupancy() + " %", "de las horas del período", false),
                kpi("OCUPACIÓN DE BÓVEDAS", k.vaultOccupancy() + " %", "de " + k.vaults() + " bóvedas, hoy", false),
                kpi("COMISIONES", HomeController.shortPesos(k.commissions()),
                        k.commissionPeople() == 1 ? "1 persona" : k.commissionPeople() + " personas", false));
    }

    private void drawTab() {
        if (tabGroup.getSelectedToggle() == plansTab) {
            drawPlans();
        } else if (tabGroup.getSelectedToggle() == occupancyTab) {
            content.getChildren().setAll(heatCard(overview.rooms(), overview.periodLabel()),
                    vaultsCard(overview.cemeteries()));
        } else if (tabGroup.getSelectedToggle() == teamTab) {
            content.getChildren().setAll(sellersCard(overview.sellers(), overview.periodLabel()),
                    thanatologistsCard(overview.thanatologists(), overview.periodLabel()));
        } else {
            GridPane grid = new GridPane();
            grid.setHgap(16);
            ColumnConstraints left = new ColumnConstraints();
            left.setPercentWidth(62);
            ColumnConstraints right = new ColumnConstraints();
            right.setPercentWidth(38);
            grid.getColumnConstraints().addAll(left, right);
            Node kinds = kindsCard(overview.kindBuckets(), overview.period());
            Node vaults = vaultsCard(overview.cemeteries());
            grid.add(kinds, 0, 0);
            grid.add(vaults, 1, 0);
            GridPane.setVgrow(vaults, Priority.ALWAYS);
            content.getChildren().setAll(grid);
        }
    }

    // ---------- Ventas y servicios ----------

    /** Barras del período elegido: por día en 7 días, por semana en un mes y por mes en un año. */
    private Node kindsCard(List<KindBucket> months, FinancePeriod period) {
        String unit = switch (AnalyticsService.groupingOf(period)) {
            case DAY -> "por día";
            case WEEK -> "por semana";
            case MONTH -> "por mes";
        };
        VBox card = card("Servicios por tipo", "Ventas de " + period.label() + " " + unit
                + ", sin contar las anuladas");
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setMinorTickVisible(false);
        y.setForceZeroInRange(true);
        StackedBarChart<String, Number> chart = new StackedBarChart<>(x, y);
        chart.getStyleClass().add("kind-chart");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(28);
        chart.setPrefHeight(280);
        chart.setMinHeight(240);
        long max = 0;
        for (int kind = 0; kind < AnalyticsService.KINDS.length; kind++) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(AnalyticsService.KINDS[kind]);
            for (KindBucket m : months) {
                series.getData().add(new XYChart.Data<>(m.label(), m.counts()[kind]));
            }
            chart.getData().add(series);
        }
        for (KindBucket m : months) {
            max = Math.max(max, m.total());
        }
        // Escala de números enteros: no hay "medio servicio".
        y.setAutoRanging(false);
        y.setLowerBound(0);
        long top = Math.max(4, (long) Math.ceil(max * 1.15));
        y.setUpperBound(top);
        y.setTickUnit(Math.max(1, Math.round(top / 4.0)));

        HBox legend = new HBox(16);
        legend.setAlignment(Pos.CENTER_LEFT);
        String[] colors = {"-color-moss", "-color-detail", "-color-forest", "-color-sage-mid"};
        for (int kind = 0; kind < AnalyticsService.KINDS.length; kind++) {
            Region dot = new Region();
            dot.getStyleClass().add("legend-dot");
            dot.setStyle("-fx-background-color: " + colors[kind] + ";");
            HBox item = new HBox(6, dot, label(AnalyticsService.KINDS[kind], "legend-text"));
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        card.getChildren().addAll(chart, legend);
        return card;
    }

    // ---------- Planes exequiales ----------

    /**
     * Las gráficas que antes estaban en Planes exequiales: recaudo de cuotas de los últimos 6 meses,
     * planes por tipo, planes en mora y los que terminan la carencia en los próximos 30 días.
     */
    private void drawPlans() {
        if (plans != null) {
            content.getChildren().setAll(plansGrid(plans));
            return;
        }
        content.getChildren().setAll(label("Cargando planes…", "empty-board"));
        async("analytics-plans", planService::overview, result -> {
            plans = result;
            if (tabGroup.getSelectedToggle() == plansTab) {
                content.getChildren().setAll(plansGrid(result));
            }
        }, failure -> content.getChildren().setAll(label(message(failure,
                "No se pudieron cargar los planes. Revise la conexión."), "empty-board")));
    }

    private Node plansGrid(FuneralPlanService.Overview o) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(16);
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(62);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(38);
        grid.getColumnConstraints().addAll(left, right);
        Node collection = collectionCard(o.collection());
        Node types = typesCard(o.types(), o.cards());
        grid.add(collection, 0, 0);
        grid.add(types, 1, 0);
        GridPane.setVgrow(types, Priority.ALWAYS);
        Node overdue = overdueCard(o.kpis(), o.cards());
        Node waiting = waitingCard(o.waitingSoon());
        grid.add(overdue, 0, 1);
        grid.add(waiting, 1, 1);
        return grid;
    }

    private Node collectionCard(List<FuneralPlanService.MonthPoint> points) {
        VBox card = card("Recaudo de cuotas", "Últimos 6 meses · lo cobrado frente a lo que debía entrar");
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setMinorTickVisible(false);
        y.setForceZeroInRange(true);
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number value) {
                return HomeController.shortPesos(BigDecimal.valueOf(value.doubleValue()));
            }

            @Override
            public Number fromString(String text) {
                return 0;
            }
        });
        javafx.scene.chart.LineChart<String, Number> chart = new javafx.scene.chart.LineChart<>(x, y);
        chart.getStyleClass().add("collection-chart");
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(280);
        chart.setMinHeight(240);
        XYChart.Series<String, Number> collected = new XYChart.Series<>();
        XYChart.Series<String, Number> expected = new XYChart.Series<>();
        java.time.format.DateTimeFormatter shortMonth = java.time.format.DateTimeFormatter.ofPattern("MMM", ES);
        for (FuneralPlanService.MonthPoint p : points) {
            String month = capitalize(shortMonth.format(p.month()).replace(".", ""));
            collected.getData().add(new XYChart.Data<>(month, p.collected()));
            expected.getData().add(new XYChart.Data<>(month, p.expected()));
        }
        chart.getData().setAll(List.of(collected, expected));
        HBox legend = new HBox(16, legendItem("Recaudado", "-color-forest"), legendItem("Esperado", "-color-detail"));
        legend.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(legend, chart);
        return card;
    }

    /** Dona con un color por nombre de plan y el total de planes activos en el centro. */
    private Node typesCard(List<FuneralPlanService.TypeSlice> types, List<FuneralPlanService.PlanCard> cards) {
        VBox card = card("Planes por tipo", "Afiliados activos");
        String[] colors = {"-color-forest", "-color-detail", "-color-moss", "-color-sage-mid", "-color-sage-dark"};
        double radius = 62;
        javafx.scene.shape.Circle track = new javafx.scene.shape.Circle(75, 75, radius);
        track.getStyleClass().add("donut-track");
        track.setStrokeWidth(18);
        javafx.scene.layout.Pane arcs = new javafx.scene.layout.Pane(track);
        arcs.setPrefSize(150, 150);
        arcs.setMinSize(150, 150);
        long total = types.stream().mapToLong(FuneralPlanService.TypeSlice::plans).sum();
        double start = 90;
        VBox legend = new VBox(10);
        for (int i = 0; i < types.size(); i++) {
            FuneralPlanService.TypeSlice slice = types.get(i);
            String color = colors[Math.min(i, colors.length - 1)];
            double length = total == 0 ? 0 : 360.0 * slice.plans() / total;
            javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(75, 75, radius, radius, start, -length);
            arc.setType(javafx.scene.shape.ArcType.OPEN);
            arc.setStrokeWidth(18);
            arc.getStyleClass().add("donut-arc");
            arc.setStyle("-fx-stroke: " + color + ";");
            arcs.getChildren().add(arc);
            start -= length;
            legend.getChildren().add(legendItem(slice.name() + " · " + slice.plans(), color));
        }
        if (types.isEmpty()) {
            legend.getChildren().add(label("Todavía no hay planes activos.", "chart-subtitle"));
        }
        VBox center = new VBox(-2, label(String.valueOf(total), "donut-value"), label("planes activos", "donut-caption"));
        center.setAlignment(Pos.CENTER);
        StackPane donut = new StackPane(arcs, center);
        donut.setMinSize(150, 150);
        HBox body = new HBox(20, donut, legend);
        body.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(body);

        List<FuneralPlanService.PlanCard> active = cards.stream().filter(FuneralPlanService.PlanCard::isActive).toList();
        BigDecimal sum = active.stream().map(FuneralPlanService.PlanCard::monthlyFee)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!active.isEmpty()) {
            card.getChildren().add(label("Cuota promedio " + pesos(sum.divide(BigDecimal.valueOf(active.size()),
                    RoundingMode.HALF_UP)) + " al mes", "chart-subtitle"));
        }
        return card;
    }

    /** Titulares con cuotas atrasadas, de la deuda más grande a la más pequeña. */
    private Node overdueCard(FuneralPlanService.Kpis k, List<FuneralPlanService.PlanCard> cards) {
        List<FuneralPlanService.PlanCard> overdue = cards.stream()
                .filter(FuneralPlanService.PlanCard::isOverdue)
                .sorted(java.util.Comparator.comparing(FuneralPlanService.PlanCard::overdueAmount,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed())
                .toList();
        VBox card = card("Planes en mora", overdue.isEmpty() ? "Todos los planes están al día"
                : (overdue.size() == 1 ? "1 titular" : overdue.size() + " titulares") + " · "
                + pesos(k.overdueAmount()) + " por cobrar");
        for (FuneralPlanService.PlanCard c : overdue.stream().limit(6).toList()) {
            card.getChildren().add(planRow(c.holderName(), c.code() + " · " + c.planName() + " · "
                    + (c.overdueMonths() == 1 ? "1 cuota atrasada" : c.overdueMonths() + " cuotas atrasadas"),
                    pesos(c.overdueAmount()), true));
        }
        if (overdue.size() > 6) {
            card.getChildren().add(label("y " + (overdue.size() - 6) + " más en Clientes › Afiliados a planes.",
                    "chart-subtitle"));
        }
        return card;
    }

    private Node waitingCard(List<FuneralPlanService.WaitingSoon> soon) {
        VBox card = card("Terminan carencia", "Próximos 30 días");
        if (soon.isEmpty()) {
            card.getChildren().add(label("Ningún plan termina la carencia en los próximos 30 días.", "chart-subtitle"));
        }
        LocalDate today = analyticsService.today();
        java.time.format.DateTimeFormatter date = java.time.format.DateTimeFormatter.ofPattern("d MMM", ES);
        for (FuneralPlanService.WaitingSoon w : soon.stream().limit(6).toList()) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, w.waitingEnds());
            card.getChildren().add(planRow(w.holderName(), w.code() + " · cubierto desde el "
                    + date.format(w.waitingEnds()), days <= 0 ? "hoy" : days == 1 ? "en 1 día" : "en " + days + " días",
                    false));
        }
        return card;
    }

    private static Node planRow(String name, String detail, String value, boolean alert) {
        Label title = label(name, "row-title");
        Label sub = label(detail, "row-detail");
        sub.setWrapText(true);
        VBox text = new VBox(2, title, sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        Label amount = label(value, alert ? "row-value-alert" : "row-value");
        amount.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(12, text, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("plan-row");
        return row;
    }

    private static HBox legendItem(String text, String color) {
        Region dot = new Region();
        dot.getStyleClass().add("legend-dot");
        dot.setStyle("-fx-background-color: " + color + ";");
        HBox item = new HBox(6, dot, label(text, "legend-text"));
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---------- Ocupación ----------

    private Node vaultsCard(List<CemeteryOccupancy> cemeteries) {
        VBox card = card("Ocupación de bóvedas", "Estado actual por cementerio · en rojo, las que están en mora");
        if (cemeteries.isEmpty()) {
            card.getChildren().add(label("No hay bóvedas registradas.", "empty-board"));
            return card;
        }
        for (CemeteryOccupancy c : cemeteries) {
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox head = new HBox(label(c.name(), "vault-name"), gap, label(c.percent() + " %", "vault-name"));
            head.setAlignment(Pos.CENTER_LEFT);
            StackPane bar = bar(c.total() == 0 ? 0 : (double) c.occupied() / c.total(),
                    c.total() == 0 ? 0 : (double) c.overdue() / c.total());
            String sub = c.occupied() + " de " + c.total() + " ocupadas · " + c.available() + " disponibles"
                    + (c.overdue() > 0 ? " · " + c.overdue() + " en mora" : "");
            card.getChildren().add(new VBox(6, head, bar, label(sub, "vault-sub")));
        }
        return card;
    }

    /** Barra con lo ocupado en verde y, al final de lo ocupado, la parte en mora en rojo. */
    private static StackPane bar(double occupied, double overdue) {
        Region track = new Region();
        track.getStyleClass().add("bar-track");
        track.setMaxWidth(Double.MAX_VALUE);
        Region fill = new Region();
        fill.getStyleClass().add("bar-fill");
        Region late = new Region();
        late.getStyleClass().add("bar-overdue");
        StackPane bar = new StackPane(track, fill, late);
        bar.setAlignment(Pos.CENTER_LEFT);
        fill.maxWidthProperty().bind(bar.widthProperty().multiply(occupied));
        fill.prefWidthProperty().bind(fill.maxWidthProperty());
        late.maxWidthProperty().bind(bar.widthProperty().multiply(overdue));
        late.prefWidthProperty().bind(late.maxWidthProperty());
        late.translateXProperty().bind(bar.widthProperty().multiply(Math.max(0, occupied - overdue)));
        fill.setVisible(occupied > 0);
        late.setVisible(overdue > 0);
        return bar;
    }

    private Node heatCard(List<RoomHeat> rooms, String periodLabel) {
        VBox card = card("Ocupación de salas por día", "Porcentaje de las horas en que cada sala estuvo en velación"
                + " en " + periodLabel + ", por día de la semana, hasta hoy");
        if (rooms.isEmpty()) {
            card.getChildren().add(label("No hay salas registradas.", "empty-board"));
            return card;
        }
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(6);
        ColumnConstraints name = new ColumnConstraints(140);
        grid.getColumnConstraints().add(name);
        for (int d = 0; d < AnalyticsService.WEEKDAYS.length; d++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setHgrow(Priority.ALWAYS);
            c.setFillWidth(true);
            grid.getColumnConstraints().add(c);
            Label head = label(AnalyticsService.WEEKDAYS[d], "heat-head");
            head.setMaxWidth(Double.MAX_VALUE);
            grid.add(head, d + 1, 0);
        }
        for (int r = 0; r < rooms.size(); r++) {
            RoomHeat room = rooms.get(r);
            grid.add(label(room.room(), "heat-room"), 0, r + 1);
            for (int d = 0; d < room.percents().length; d++) {
                int p = room.percents()[d];
                Label cell = label(p + " %", "heat-cell");
                cell.getStyleClass().add("heat-text");
                cell.setMaxWidth(Double.MAX_VALUE);
                cell.setStyle("-fx-background-color: " + heatColor(p) + "; -fx-text-fill: "
                        + (p >= 50 ? "-color-paper" : "-color-ink") + ";");
                grid.add(cell, d + 1, r + 1);
            }
        }
        HBox scale = new HBox(6, label("Menos", "legend-sub"));
        scale.setAlignment(Pos.CENTER_RIGHT);
        for (int p : new int[]{0, 25, 50, 75, 100}) {
            Region swatch = new Region();
            swatch.getStyleClass().add("legend-dot");
            swatch.setStyle("-fx-background-color: " + heatColor(p) + ";");
            scale.getChildren().add(swatch);
        }
        scale.getChildren().add(label("Más", "legend-sub"));
        card.getChildren().addAll(grid, scale);
        return card;
    }

    /** Del tono más claro (0 %) al color principal de la marca (100 %); en El Edén, de salvia a verde bosque. */
    static String heatColor(int percent) {
        double t = Math.max(0, Math.min(100, percent)) / 100.0;
        BrandPalette palette = BrandTheme.brand().palette();
        int[] from = rgb(palette.get("-color-soft"));
        int[] to = rgb(palette.get("-color-forest"));
        return String.format("#%02X%02X%02X", mix(from[0], to[0], t), mix(from[1], to[1], t), mix(from[2], to[2], t));
    }

    private static int[] rgb(String hex) {
        return new int[]{Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    // ---------- Equipo ----------

    private Node sellersCard(List<SellerLine> sellers, String periodLabel) {
        VBox card = card("Vendedores", "Ventas de " + periodLabel + " sin contar las anuladas; la comisión es la"
                + " registrada en Finanzas");
        if (sellers.isEmpty()) {
            card.getChildren().add(label("No hay vendedores con ventas en este período.", "empty-board"));
            return card;
        }
        card.getChildren().add(head("VENDEDOR", "VENTAS", "TOTAL VENDIDO", "PROMEDIO", "COMISIÓN"));
        for (SellerLine s : sellers) {
            card.getChildren().add(row(s.name(), s.active(), String.valueOf(s.sales()), pesos(s.sold()),
                    pesos(s.average()), pesos(s.commission())));
        }
        return card;
    }

    private Node thanatologistsCard(List<ThanatoLine> lines, String periodLabel) {
        VBox card = card("Tanatólogos", "Preparaciones terminadas en " + periodLabel
                + " y comisión registrada en Finanzas");
        if (lines.isEmpty()) {
            card.getChildren().add(label("No hay tanatólogos con preparaciones en este período.", "empty-board"));
            return card;
        }
        card.getChildren().add(head("TANATÓLOGO", "PREPARACIONES", "", "", "COMISIÓN"));
        for (ThanatoLine t : lines) {
            card.getChildren().add(row(t.name(), t.active(), String.valueOf(t.cases()), "", "", pesos(t.commission())));
        }
        return card;
    }

    private static HBox head(String name, String... columns) {
        HBox head = new HBox(grow(label(name, "head-cell")));
        for (String c : columns) {
            head.getChildren().add(cell(label(c, "head-cell"), 150));
        }
        head.getStyleClass().add("table-head");
        return head;
    }

    private static HBox row(String name, boolean active, String... values) {
        HBox who = new HBox(8, label(name, "item-row-name"));
        who.setAlignment(Pos.CENTER_LEFT);
        if (!active) {
            Label chip = label("Inactivo", "state-chip");
            chip.getStyleClass().add("chip-voided");
            who.getChildren().add(chip);
        }
        HBox row = new HBox(grow(who));
        for (String v : values) {
            row.getChildren().add(cell(label(v, "item-row-text"), 150));
        }
        row.getStyleClass().add("item-row");
        row.setStyle("-fx-border-color: transparent transparent -color-panel transparent; -fx-cursor: default;");
        return row;
    }

    // ---------- piezas ----------

    private static VBox kpi(String caption, String value, String detail, boolean dark) {
        Label detailLabel = label(detail, "hero-kpi-detail");
        detailLabel.setWrapText(true);
        detailLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox box = new VBox(label(caption, "hero-kpi-caption"), label(value, "hero-kpi-value"), detailLabel);
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
        Label sub = label(subtitle, "chart-subtitle");
        sub.setWrapText(true);
        sub.setMinHeight(Region.USE_PREF_SIZE);
        VBox card = new VBox(12, new VBox(2, label(title, "chart-title"), sub));
        card.getStyleClass().add("chart-card");
        return card;
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

    private static String pesos(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return "$ " + NumberFormat.getIntegerInstance(ES).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static void keepSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                group.selectToggle(old);
            }
        });
    }

    private static String message(Throwable failure, String fallback) {
        return failure instanceof BusinessException business ? business.getMessage() : fallback;
    }

    private void showError(String text) {
        Notice.warning(window(), MODULE_NAME, "Atención", text);
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
}

