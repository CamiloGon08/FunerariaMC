package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.AnalyticsService;
import com.example.funerariamc.service.AnalyticsService.CemeteryOccupancy;
import com.example.funerariamc.service.AnalyticsService.KindMonth;
import com.example.funerariamc.service.AnalyticsService.Kpis;
import com.example.funerariamc.service.AnalyticsService.Overview;
import com.example.funerariamc.service.AnalyticsService.RoomHeat;
import com.example.funerariamc.service.AnalyticsService.SellerLine;
import com.example.funerariamc.service.AnalyticsService.ThanatoLine;
import com.example.funerariamc.service.FinancePeriod;
import com.example.funerariamc.ui.RefreshableView;
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
import javafx.scene.control.Alert;
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
public class AnalyticsController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");

    private enum Range { WEEK, MONTH, YEAR }

    private final AnalyticsService analyticsService;

    @FXML private StackPane root;
    @FXML private ToggleGroup tabGroup;
    @FXML private ToggleGroup rangeGroup;
    @FXML private ToggleButton salesTab;
    @FXML private ToggleButton occupancyTab;
    @FXML private ToggleButton teamTab;
    @FXML private ToggleButton weekRange;
    @FXML private ToggleButton monthRange;
    @FXML private ToggleButton yearRange;
    @FXML private Button previousButton;
    @FXML private Button nextButton;
    @FXML private Label periodLabel;
    @FXML private Button exportButton;
    @FXML private HBox kpiRow;
    @FXML private VBox content;
    @FXML private Label toast;

    private Range range = Range.MONTH;
    /** Último día del período que se está viendo. */
    private LocalDate anchor;
    private Overview overview;
    private int generation;
    private SequentialTransition toastMotion;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        anchor = analyticsService.today();
        // Un grupo de pestañas nunca queda sin selección.
        keepSelected(tabGroup);
        keepSelected(rangeGroup);
        tabGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now != null && overview != null) {
                drawTab();
            }
        });
        rangeGroup.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                return;
            }
            range = now == weekRange ? Range.WEEK : now == yearRange ? Range.YEAR : Range.MONTH;
            anchor = analyticsService.today();
            load();
        });
        load();
    }

    @FXML
    private void onPrevious() {
        anchor = switch (range) {
            case WEEK -> anchor.minusDays(7);
            case MONTH -> YearMonth.from(anchor).minusMonths(1).atEndOfMonth();
            case YEAR -> LocalDate.of(anchor.getYear() - 1, 12, 31);
        };
        load();
    }

    @FXML
    private void onNext() {
        LocalDate today = analyticsService.today();
        LocalDate next = switch (range) {
            case WEEK -> anchor.plusDays(7);
            case MONTH -> YearMonth.from(anchor).plusMonths(1).atEndOfMonth();
            case YEAR -> LocalDate.of(anchor.getYear() + 1, 12, 31);
        };
        anchor = next.isAfter(today) && range == Range.WEEK ? today : next;
        load();
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

    /** Período que corresponde al rango y a la fecha ancla. */
    private FinancePeriod period() {
        return switch (range) {
            case WEEK -> new FinancePeriod(anchor.minusDays(6), anchor);
            case MONTH -> FinancePeriod.month(YearMonth.from(anchor));
            case YEAR -> FinancePeriod.year(anchor.getYear());
        };
    }

    private void load() {
        FinancePeriod period = period();
        periodLabel.setText(period.label());
        nextButton.setDisable(!period.to().isBefore(analyticsService.today()));
        exportButton.setDisable(true);
        int request = ++generation;
        if (overview == null) {
            content.getChildren().setAll(label("Cargando…", "empty-board"));
        }
        async("analytics-load", () -> analyticsService.overview(period), result -> {
            if (request != generation) {
                return;
            }
            overview = result;
            exportButton.setDisable(false);
            drawKpis(result);
            drawTab();
        }, failure -> {
            if (request == generation) {
                content.getChildren().setAll(label(message(failure,
                        "No se pudo cargar el panel. Revise la conexión."), "empty-board"));
            }
        });
    }

    // ---------- indicadores ----------

    private void drawKpis(Overview o) {
        Kpis k = o.kpis();
        long diff = k.services() - k.servicesPrevious();
        String change = k.servicesPrevious() == 0
                ? "0 en " + o.previousLabel()
                : (diff >= 0 ? "▲ " : "▼ ") + Math.abs(Math.round(diff * 100.0 / k.servicesPrevious()))
                + " % vs. " + o.previousLabel();
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
        if (tabGroup.getSelectedToggle() == occupancyTab) {
            content.getChildren().setAll(heatCard(overview.rooms()), vaultsCard(overview.cemeteries()));
        } else if (tabGroup.getSelectedToggle() == teamTab) {
            content.getChildren().setAll(sellersCard(overview.sellers()), thanatologistsCard(overview.thanatologists()));
        } else {
            GridPane grid = new GridPane();
            grid.setHgap(16);
            ColumnConstraints left = new ColumnConstraints();
            left.setPercentWidth(62);
            ColumnConstraints right = new ColumnConstraints();
            right.setPercentWidth(38);
            grid.getColumnConstraints().addAll(left, right);
            Node kinds = kindsCard(overview.kindMonths());
            Node vaults = vaultsCard(overview.cemeteries());
            grid.add(kinds, 0, 0);
            grid.add(vaults, 1, 0);
            GridPane.setVgrow(vaults, Priority.ALWAYS);
            content.getChildren().setAll(grid);
        }
    }

    // ---------- Ventas y servicios ----------

    private Node kindsCard(List<KindMonth> months) {
        VBox card = card("Servicios por tipo", "Ventas de los últimos " + months.size()
                + " meses, sin contar las anuladas");
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
            for (KindMonth m : months) {
                series.getData().add(new XYChart.Data<>(m.label(), m.counts()[kind]));
            }
            chart.getData().add(series);
        }
        for (KindMonth m : months) {
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
        String[] colors = {"#3E5A3F", "#C4AF84", "#1E3A2E", "#9FB09A"};
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

    private Node heatCard(List<RoomHeat> rooms) {
        VBox card = card("Ocupación de salas por día", "Porcentaje de las horas en que cada sala estuvo en velación,"
                + " por día de la semana, hasta hoy");
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
                        + (p >= 50 ? "#F4F5EE" : "#2B3A2F") + ";");
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

    /** De un salvia muy claro (0 %) al verde bosque (100 %). */
    static String heatColor(int percent) {
        double t = Math.max(0, Math.min(100, percent)) / 100.0;
        int[] from = {0xEE, 0xF0, 0xE6};
        int[] to = {0x1E, 0x3A, 0x2E};
        return String.format("#%02X%02X%02X", mix(from[0], to[0], t), mix(from[1], to[1], t), mix(from[2], to[2], t));
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    // ---------- Equipo ----------

    private Node sellersCard(List<SellerLine> sellers) {
        VBox card = card("Vendedores", "Ventas del período sin contar las anuladas; la comisión es la registrada en"
                + " Finanzas");
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

    private Node thanatologistsCard(List<ThanatoLine> lines) {
        VBox card = card("Tanatólogos", "Preparaciones terminadas en el período y comisión registrada en Finanzas");
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
        row.setStyle("-fx-border-color: transparent transparent #E6E8DE transparent; -fx-cursor: default;");
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
        Alert alert = new Alert(Alert.AlertType.WARNING, text);
        alert.initOwner(window());
        alert.setHeaderText(null);
        alert.getDialogPane().getStylesheets().addAll(root.getStylesheets());
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
}
