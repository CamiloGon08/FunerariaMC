package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.ContractData;
import com.example.funerariamc.service.ContractService;
import com.example.funerariamc.service.ContractService.ContractInfo;
import com.example.funerariamc.service.ContractService.ItemOption;
import com.example.funerariamc.service.ContractService.Kpis;
import com.example.funerariamc.service.ContractService.Line;
import com.example.funerariamc.service.ContractService.Option;
import com.example.funerariamc.service.ContractService.Overview;
import com.example.funerariamc.service.ContractService.Payment;
import com.example.funerariamc.service.ContractService.PaymentState;
import com.example.funerariamc.service.ContractService.Quote;
import com.example.funerariamc.service.ContractService.SaleKind;
import com.example.funerariamc.service.ContractService.SaleRow;
import com.example.funerariamc.service.ContractService.SaleView;
import com.example.funerariamc.service.SalePaymentData;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
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
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pantalla de Contratos y ventas (pantalla 08 de Figma, paleta verde oscura): indicadores del mes,
 * buscador con filtro por tipo de venta y por estado de pago, tabla "Contratos
 * registrados", panel "Ver" con conceptos y abonos, y los diálogos Nuevo contrato, Abonar, Anular venta y Registrar preparación.
 * Todas las consultas corren en otro hilo; la tabla filtra en memoria y solo dibuja las filas visibles.
 */
@Component
@Scope("prototype")
public class ContractController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final double DRAWER_WIDTH = 500;
    /** Filas que se traen al caché apenas carga la tabla (las primeras que se ven). */
    private static final int PREFETCH = 12;

    /** Anchos de las columnas de la tabla; DIFUNTO / CLIENTE toma el espacio que sobra. */
    private static final double W_NUMBER = 70;
    private static final double W_TYPE = 132;
    private static final double W_MONEY = 112;
    private static final double W_STATE = 112;
    private static final double W_ACTIONS = 150;

    private final ContractService contractService;
    private final DetailCache<SaleView> details;

    @FXML
    private StackPane root;
    @FXML
    private ToggleGroup typeGroup;
    @FXML
    private ToggleButton allToggle;
    @FXML
    private ToggleButton contractToggle;
    @FXML
    private ToggleButton privateToggle;
    @FXML
    private ToggleButton productToggle;
    @FXML
    private Label recordsValue;
    @FXML
    private Label recordsDetail;
    @FXML
    private Label soldCaption;
    @FXML
    private Label soldValue;
    @FXML
    private Label soldDetail;
    @FXML
    private Label receivableValue;
    @FXML
    private Label receivableDetail;
    @FXML
    private Label voidedValue;
    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<PaymentState> paymentCombo;
    @FXML
    private Button newButton;
    @FXML
    private Label countLabel;
    @FXML
    private HBox tableHead;
    @FXML
    private ListView<SaleRow> salesList;
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

    private final ObservableList<SaleRow> visibleRows = FXCollections.observableArrayList();
    private List<SaleRow> allRows = List.of();
    private boolean loaded;
    /** Venta abierta en el panel; null si está cerrado. */
    private Long drawerSaleId;
    /** Cada carga lleva un número; si llega una respuesta vieja, se descarta. */
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
        this.details = new DetailCache<>(contractService::detail, "sale-detail");
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        loadOverview(drawerSaleId);
    }

    @FXML
    private void initialize() {
        for (Toggle toggle : List.of(allToggle, contractToggle, privateToggle, productToggle)) {
            toggle.setUserData(null);
        }
        contractToggle.setUserData(SaleType.CONTRACT);
        privateToggle.setUserData(SaleType.PRIVATE);
        productToggle.setUserData(SaleType.PRODUCT);
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un filtro elegido
            } else {
                applyFilter();
            }
        });

        paymentCombo.getItems().add(null);
        paymentCombo.getItems().addAll(PaymentState.values());
        paymentCombo.setConverter(converter(state -> "Pago: " + state.getLabel().toLowerCase(ES), "Pago: todos"));
        paymentCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(PaymentState item, boolean empty) {
                super.updateItem(item, empty);
                setText(item == null ? "Pago: todos" : "Pago: " + item.getLabel().toLowerCase(ES));
            }
        });
        paymentCombo.setValue(null);
        paymentCombo.valueProperty().addListener((obs, old, value) -> applyFilter());

        searchDelay.setOnFinished(e -> applyFilter());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        tableHead.getChildren().setAll(
                cell(label("N.°", "head-cell"), W_NUMBER),
                grow(label("DIFUNTO / CLIENTE", "head-cell")),
                cell(label("TIPO", "head-cell"), W_TYPE),
                cell(label("TOTAL", "head-cell"), W_MONEY),
                cell(label("ABONADO", "head-cell"), W_MONEY),
                cell(label("SALDO", "head-cell"), W_MONEY),
                cell(label("ESTADO", "head-cell"), W_STATE),
                cell(label("ACCIONES", "head-cell"), W_ACTIONS));

        salesList.setItems(visibleRows);
        salesList.setCellFactory(list -> new SaleCell());
        salesList.setFixedCellSize(77);
        salesList.setPlaceholder(new Label(""));
        salesList.setMinWidth(0);
        salesList.setPrefWidth(0); // la tabla toma el ancho disponible, no el de su contenido

        drawer.setTranslateX(DRAWER_WIDTH);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);

        loadOverview(null);
    }

    // ---------- carga ----------

    /** Indicadores y filas (dos consultas). Si reopenSaleId no es null, se vuelve a abrir su panel. */
    private void loadOverview(Long reopenSaleId) {
        int request = ++generation;
        if (!loaded) {
            setEmpty("Cargando contratos y ventas…");
        }
        async("sales-overview", contractService::overview, overview -> {
            if (request != generation) {
                return;
            }
            loaded = true;
            details.clear();
            fillKpis(overview);
            allRows = overview.rows();
            applyFilter();
            details.prefetchAll(allRows.stream().limit(PREFETCH).map(SaleRow::saleId).toList());
            if (reopenSaleId != null) {
                openDrawer(reopenSaleId);
            }
        }, failure -> {
            if (request == generation) {
                setEmpty("");
                showError(message(failure, "No se pudieron cargar los contratos y ventas."));
            }
        });
    }

    private void fillKpis(Overview overview) {
        Kpis k = overview.kpis();
        recordsValue.setText(String.valueOf(k.records()));
        recordsDetail.setText(k.contracts() + (k.contracts() == 1 ? " contrato · " : " contratos · ")
                + k.privates() + (k.privates() == 1 ? " particular · " : " particulares · ")
                + k.products() + (k.products() == 1 ? " venta" : " ventas"));
        soldCaption.setText("VENDIDO EN " + k.monthName().toUpperCase(ES));
        soldValue.setText(short_(k.soldThisMonth()));
        Double change = k.changePercent();
        soldDetail.setText(change == null ? "sin ventas en " + k.lastMonthName()
                : (change >= 0 ? "+" : "") + oneDecimal(change) + " % vs. " + k.lastMonthName());
        receivableValue.setText(short_(k.receivable()));
        receivableDetail.setText(k.onCredit() + (k.onCredit() == 1 ? " contrato a crédito" : " contratos a crédito"));
        voidedValue.setText(String.valueOf(k.voided()));
    }

    /** Tipo, estado de pago y texto se combinan en memoria (sin volver a la BD). */
    private void applyFilter() {
        SaleType type = typeGroup.getSelectedToggle() == null ? null
                : (SaleType) typeGroup.getSelectedToggle().getUserData();
        List<SaleRow> rows = ContractService.filter(allRows, type, paymentCombo.getValue(), searchField.getText());
        visibleRows.setAll(rows);
        boolean filtered = rows.size() != allRows.size();
        countLabel.setText(allRows.isEmpty() ? "Sin registros todavía"
                : (filtered ? rows.size() + " de " + allRows.size() + " registros" : rows.size() + " registros")
                + (rows.size() > 8 ? " · desplace para ver más" : ""));
        if (!loaded) {
            return;
        }
        setEmpty(allRows.isEmpty() ? "Todavía no hay contratos ni ventas. Use \"+ Nuevo contrato\" para registrar el primero."
                : rows.isEmpty() ? "Ningún registro coincide con la búsqueda y los filtros elegidos." : "");
    }

    private void setEmpty(String text) {
        emptyLabel.setText(text);
        emptyLabel.setVisible(!text.isEmpty());
    }

    /** Una fila de la tabla. Los nodos se crean una vez por celda y solo cambian los textos. */
    private final class SaleCell extends ListCell<SaleRow> {
        private final Label number = label("", "sale-number");
        private final Label title = label("", "sale-title");
        private final Label subtitle = label("", "sale-subtitle");
        private final Label type = label("", "sale-text");
        private final Label total = label("", "sale-text");
        private final Label paid = label("", "sale-text");
        private final Label balance = label("", "sale-balance");
        private final Label state = label("", "state-chip");
        private final Button view = rowButton("Ver");
        private final Button pay = rowButton("Abonar");
        private final HBox row;

        SaleCell() {
            VBox who = new VBox(2, title, subtitle);
            who.setAlignment(Pos.CENTER_LEFT);
            HBox actions = new HBox(6, view, pay);
            actions.setAlignment(Pos.CENTER_LEFT);
            row = new HBox(cell(number, W_NUMBER), grow(who), cell(type, W_TYPE), cell(total, W_MONEY),
                    cell(paid, W_MONEY), cell(balance, W_MONEY), cell(state, W_STATE), cell(actions, W_ACTIONS));
            row.getStyleClass().add("sale-row");
            row.setMinWidth(0);
            setPrefWidth(0); // la fila se ajusta al ancho de la lista, sin barra horizontal
            view.setOnAction(e -> {
                if (getItem() != null) {
                    openDrawer(getItem().saleId());
                }
            });
            pay.setOnAction(e -> {
                if (getItem() != null) {
                    openPaymentDialog(getItem());
                }
            });
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    openDrawer(getItem().saleId());
                }
            });
            setOnMouseEntered(e -> {
                if (getItem() != null) {
                    details.prefetch(getItem().saleId());
                }
            });
        }

        @Override
        protected void updateItem(SaleRow item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            number.setText(item.number());
            title.setText(item.title());
            subtitle.setText(item.subtitle());
            type.setText(item.kind().getLabel());
            total.setText(pesos(item.total()));
            paid.setText(pesos(item.paid()));
            balance.setText(pesos(item.balance()));
            balance.getStyleClass().remove("pending");
            if (item.balance().signum() > 0) {
                balance.getStyleClass().add("pending");
            }
            stateChip(state, item.state());
            pay.setDisable(item.state() != PaymentState.WITH_BALANCE);
            setGraphic(row);
        }
    }

    private static void stateChip(Label chip, PaymentState state) {
        chip.setText("●  " + state.getLabel());
        chip.getStyleClass().removeAll("state-ok", "state-warn", "state-alert");
        chip.getStyleClass().add(switch (state) {
            case PAID -> "state-ok";
            case WITH_BALANCE -> "state-warn";
            case VOIDED -> "state-alert";
        });
    }

    private static Button rowButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("row-button");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Node cell(Node content, double width) {
        HBox box = new HBox(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(width);
        box.setPrefWidth(width);
        box.setMaxWidth(width);
        return box;
    }

    private static Node grow(Node content) {
        HBox box = new HBox(content);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinWidth(180);
        box.setPrefWidth(180);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    // ---------- panel "Ver" ----------

    private void openDrawer(Long saleId) {
        drawerSaleId = saleId;
        SaleView cached = details.get(saleId);
        if (cached != null) {
            fillDrawer(cached);
        } else {
            drawerHead.getChildren().setAll(label("Cargando la venta…", "drawer-head-text"));
            drawerBody.getChildren().clear();
            drawerActions.getChildren().clear();
            details.load(saleId, view -> {
                if (saleId.equals(drawerSaleId)) {
                    fillDrawer(view);
                }
            }, failure -> {
                if (saleId.equals(drawerSaleId)) {
                    closeDrawer();
                    showError(message(failure, "No se pudo cargar el detalle de la venta."));
                }
            });
        }
        slideDrawerIn();
    }

    private void fillDrawer(SaleView v) {
        SaleRow r = v.row();
        Label caption = label(("VENTA N.° " + r.number() + " · " + r.kind().getLabel()).toUpperCase(ES),
                "drawer-head-caption");
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close-light");
        close.setOnAction(e -> closeDrawer());
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(caption, spacer, close);
        top.setAlignment(Pos.CENTER_LEFT);

        Label state = label("", "state-chip");
        stateChip(state, r.state());
        Label name = wrapped(r.title(), "drawer-head-name");
        HBox nameRow = new HBox(12, name, state);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Pane s1 = new Pane();
        Pane s2 = new Pane();
        HBox.setHgrow(s1, Priority.ALWAYS);
        HBox.setHgrow(s2, Priority.ALWAYS);
        HBox money = new HBox(label("Total " + pesos(r.total()), "drawer-head-strong"), s1,
                label("Abonado " + pesos(r.paid()), "drawer-head-text"), s2,
                label("Saldo " + pesos(r.balance()), "drawer-head-strong"));
        ProgressBar bar = new ProgressBar(r.total().signum() == 0 ? 1
                : r.paid().divide(r.total(), 4, RoundingMode.HALF_UP).doubleValue());
        bar.getStyleClass().add("progress-light");
        bar.setMaxWidth(Double.MAX_VALUE);
        Label date = label(r.saleDate() == null ? "" : "Registrada el " + DATE_HOUR.format(r.saleDate()),
                "drawer-head-text");
        drawerHead.getChildren().setAll(top, nameRow, date, money, bar);

        GridPane client = grid();
        field(client, 0, 0, r.kind() == SaleKind.PENSION ? "Entidad pagadora" : "Cliente", v.clientName());
        field(client, 1, 0, "Documento", orDash(v.clientDocument()));
        field(client, 0, 1, "Teléfono", orDash(v.clientPhone()));
        field(client, 1, 1, "Vendedor", orDash(v.salesperson()));
        List<Node> sections = new ArrayList<>();
        sections.add(section(r.kind() == SaleKind.PENSION ? "ENTIDAD PAGADORA" : "CLIENTE", client));

        ContractInfo c = v.contract();
        if (c != null) {
            GridPane service = grid();
            field(service, 0, 0, "Contrato", c.number());
            field(service, 1, 0, "Estado del contrato", contractStatus(c.status()));
            field(service, 0, 1, "Difunto", orDash(c.deceasedName()));
            field(service, 1, 1, "Plan exequial", c.planLabel() == null ? "Sin plan" : c.planLabel());
            field(service, 0, 2, "Sala de velación", c.roomName() == null ? "Sin sala asignada"
                    : c.roomName() + (c.wakeStart() == null ? "" : " · " + DATE_HOUR.format(c.wakeStart())
                    + (c.wakeEnd() == null ? "" : " a " + HOUR.format(c.wakeEnd()))));
            field(service, 1, 2, "Preparación (tanatopraxia)", c.preparationCompletedAt() == null ? "Pendiente"
                    : "Completada " + DATE_HOUR.format(c.preparationCompletedAt())
                    + (c.thanatopractor() == null ? "" : " · " + c.thanatopractor()));
            sections.add(section("SERVICIO", service));
        }

        sections.add(section("CONCEPTOS", linesGrid(v.lines(), r.total())));

        VBox payments = new VBox(8);
        if (v.payments().isEmpty()) {
            payments.getChildren().add(label("Sin abonos registrados.", "field-caption"));
        }
        for (Payment p : v.payments()) {
            Label amount = label(pesos(p.amount()) + (p.voided() ? "  · anulado" : ""), "line-value");
            Label when = label((p.date() == null ? "" : DATE_HOUR.format(p.date())) + (p.employee() == null ? "" : " · " + p.employee()),
                    "field-caption");
            VBox text = new VBox(1, amount, when);
            if (p.description() != null && !p.description().isBlank()) {
                text.getChildren().add(wrapped(p.description(), "line-text"));
            }
            if (p.voided()) {
                amount.getStyleClass().add("payment-voided");
            }
            payments.getChildren().add(text);
        }
        sections.add(section("ABONOS", payments));
        drawerBody.getChildren().setAll(sections);

        List<Button> actions = new ArrayList<>();
        if (r.state() != PaymentState.VOIDED) {
            Button voidButton = drawerButton("Anular venta", "drawer-danger");
            voidButton.setOnAction(e -> confirmVoid(r));
            actions.add(voidButton);
        }
        if (c != null && v.canRegisterPreparation() && c.status() == ContractStatus.ACTIVE
                && c.preparationCompletedAt() == null) {
            Button prep = drawerButton("Registrar preparación", "drawer-secondary");
            prep.setOnAction(e -> openPreparationDialog(r, c));
            actions.add(prep);
        }
        if (r.state() == PaymentState.WITH_BALANCE) {
            Button pay = drawerButton("Abonar", "drawer-primary");
            pay.setOnAction(e -> openPaymentDialog(r));
            actions.add(pay);
        }
        drawerActions.getChildren().setAll(actions);
        show(drawerActions, !actions.isEmpty());
    }

    private static GridPane linesGrid(List<Line> lines, BigDecimal total) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        ColumnConstraints text = new ColumnConstraints();
        text.setHgrow(Priority.ALWAYS);
        ColumnConstraints value = new ColumnConstraints();
        value.setMinWidth(Region.USE_PREF_SIZE);
        grid.getColumnConstraints().addAll(text, value);
        int row = 0;
        for (Line line : lines) {
            String description = line.description() + (line.quantity() > 1
                    ? " × " + line.quantity() + " (" + pesos(line.unitPrice()) + " c/u)" : "");
            grid.add(wrapped(description, "line-text"), 0, row);
            grid.add(label(pesos(line.subtotal()), "line-value"), 1, row++);
        }
        if (lines.isEmpty()) {
            grid.add(label("Sin conceptos.", "field-caption"), 0, row++);
        }
        grid.add(label("Total", "line-total"), 0, row);
        grid.add(label(pesos(total), "line-total"), 1, row);
        return grid;
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
        grid.add(new VBox(2, label(caption, "field-caption"), wrapped(value, "field-value")), column, row);
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
        drawerSaleId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerSaleId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    // ---------- Nuevo contrato ----------

    @FXML
    private void onNewContract() {
        // Tipo de venta
        ToggleGroup kindGroup = new ToggleGroup();
        HBox kinds = new HBox();
        kinds.getStyleClass().addAll("segmented", "form-segmented");
        for (SaleKind kind : SaleKind.values()) {
            ToggleButton toggle = new ToggleButton(kind.getLabel());
            toggle.getStyleClass().add("segment");
            toggle.setUserData(kind);
            toggle.setToggleGroup(kindGroup);
            kinds.getChildren().add(toggle);
        }
        kindGroup.getToggles().get(0).setSelected(true);
        kindGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true);
            }
        });

        // Cliente, plan, difunto y vendedor
        TextField clientSearch = new TextField();
        clientSearch.setPromptText("Nombre o documento");
        ComboBox<Option> client = optionCombo("Seleccione el cliente");
        ComboBox<Option> plan = optionCombo("Elija primero el cliente");
        TextField deceasedSearch = new TextField();
        deceasedSearch.setPromptText("Nombre o documento del difunto");
        ComboBox<Option> deceased = optionCombo("Seleccione el difunto");
        ComboBox<Option> salesperson = optionCombo("Seleccione el vendedor");
        bindSearch(clientSearch, client, contractService::searchClients);
        bindSearch(deceasedSearch, deceased, contractService::searchDeceased);
        async("sale-salespeople", contractService::salespeople, list -> {
            salesperson.getItems().setAll(list);
            salesperson.setPromptText(list.isEmpty() ? "No hay vendedores activos" : "Seleccione el vendedor");
        }, failure -> salesperson.setPromptText("No se pudo cargar"));

        // Servicio
        TextField base = new TextField();
        base.setPromptText("Ej.: 1.200.000");
        TextField wages = new TextField();
        wages.setPromptText("Ej.: 4");
        TextField buses = new TextField("0");
        TextField coffinSearch = new TextField();
        coffinSearch.setPromptText("Buscar ataúd en inventario");
        ComboBox<ItemOption> coffin = itemCombo("Sin ataúd del inventario");
        bindItemSearch(coffinSearch, coffin);

        // Sala de velación
        ComboBox<Option> room = optionCombo("Sin sala por ahora");
        room.setConverter(converter(o -> o.label() + " · " + o.detail(), "Sin sala por ahora"));
        async("sale-rooms", contractService::rooms, list -> {
            List<Option> items = new ArrayList<>();
            items.add(null); // "Sin sala por ahora"
            items.addAll(list);
            room.getItems().setAll(items);
        }, failure -> room.setPromptText("No se pudo cargar"));
        DatePicker startDate = new DatePicker(today());
        ComboBox<LocalTime> startTime = timeCombo(LocalTime.of(8, 0));
        DatePicker endDate = new DatePicker(today().plusDays(1));
        ComboBox<LocalTime> endTime = timeCombo(LocalTime.of(10, 0));
        for (DatePicker picker : List.of(startDate, endDate)) {
            picker.setEditable(false);
            picker.setConverter(dateConverter());
        }

        // Productos
        TextField productSearch = new TextField();
        productSearch.setPromptText("Buscar producto en inventario");
        ComboBox<ItemOption> product = itemCombo("Seleccione el producto");
        bindItemSearch(productSearch, product);
        TextField quantity = new TextField("1");
        Button addProduct = new Button("Agregar");
        addProduct.getStyleClass().add("small-dark-button");
        List<ContractData.ItemLine> products = new ArrayList<>();
        List<String> productNames = new ArrayList<>();
        VBox productList = new VBox(6);
        Label productError = note("");

        // Pago
        TextField initial = new TextField();
        initial.setPromptText("Ej.: 500.000 (0 si es a crédito)");
        Label quoteHint = note("");
        VBox quoteBox = new VBox(6);
        quoteBox.getStyleClass().add("quote-box");
        Label quoteTotal = label("Total: —", "quote-total");

        Form form = new Form()
                .section("Tipo de venta", 1)
                .field("TIPO *", kinds)
                .section("Cliente y difunto", 3)
                .field("BUSCAR CLIENTE", clientSearch)
                .field("CLIENTE / ENTIDAD PAGADORA *", client)
                .field("PLAN EXEQUIAL *", plan)
                .field("BUSCAR DIFUNTO", deceasedSearch)
                .field("DIFUNTO *", deceased)
                .field("VENDEDOR *", salesperson)
                .section("Servicio", 3)
                .field("CUOTA BASE DEL SERVICIO *", base)
                .field("SALARIOS MÍNIMOS (PENSIÓN) *", wages)
                .field("BUSES ADICIONALES", buses)
                .field("BUSCAR ATAÚD", coffinSearch)
                .field("ATAÚD", coffin)
                .section("Sala de velación", 3)
                .field("SALA", room)
                .field("FECHA DE INGRESO", startDate)
                .field("HORA DE INGRESO", startTime)
                .field("", new Region())
                .field("FECHA DE SALIDA", endDate)
                .field("HORA DE SALIDA", endTime)
                .section("Productos", 3)
                .field("BUSCAR PRODUCTO", productSearch)
                .field("PRODUCTO", product)
                .field("CANTIDAD", new HBox(8, quantity, addProduct))
                .note(productList)
                .note(productError)
                .section("Resumen y pago", 1)
                .note(quoteBox)
                .note(quoteHint)
                .section("Abono", 3)
                .field("ABONO INICIAL *", initial)
.note(note("Con plan se cobra solo el excedente del ataúd frente al básico del plan y el "
                        + "recargo por beneficiarios; la sala especial agrega su recargo y "
                        + "cada bus adicional su tarifa. Los valores salen de Parámetros del sistema. "
                        + "Si el abono es igual al total, la venta queda pagada."));
        HBox.setHgrow(quantity, Priority.ALWAYS);

        // Habilita solo los campos que usa cada tipo de venta
        Runnable applyKind = () -> {
            SaleKind kind = (SaleKind) kindGroup.getSelectedToggle().getUserData();
            boolean service = kind.isService();
            plan.setDisable(kind != SaleKind.PLAN);
            deceasedSearch.setDisable(!service);
            deceased.setDisable(!service);
            salesperson.setDisable(!service);
            base.setDisable(!(kind == SaleKind.PLAN || kind == SaleKind.PRIVATE));
            wages.setDisable(kind != SaleKind.PENSION);
            buses.setDisable(!service);
            coffinSearch.setDisable(!service);
            coffin.setDisable(!service);
            room.setDisable(!service);
            boolean withRoom = service && room.getValue() != null;
            for (Node n : List.of(startDate, startTime, endDate, endTime)) {
                n.setDisable(!withRoom);
            }
            client.setPromptText(kind == SaleKind.PENSION ? "Seleccione la entidad (empresa)" : "Seleccione el cliente");
        };

        Function<Boolean, ContractData> data = strict -> {
            SaleKind kind = (SaleKind) kindGroup.getSelectedToggle().getUserData();
            boolean service = kind.isService();
            boolean withRoom = service && room.getValue() != null;
            return new ContractData(kind, idOf(client.getValue()),
                    service ? idOf(deceased.getValue()) : null,
                    kind == SaleKind.PLAN ? idOf(plan.getValue()) : null,
                    service ? idOf(salesperson.getValue()) : null,
                    base.isDisabled() ? null : parseAmount(base.getText(), "La cuota base"),
                    wages.isDisabled() ? null : parseDecimal(wages.getText(), "Los salarios mínimos"),
                    withRoom ? room.getValue().id() : null,
                    withRoom ? at(startDate.getValue(), startTime.getValue()) : null,
                    withRoom ? at(endDate.getValue(), endTime.getValue()) : null,
                    service && coffin.getValue() != null ? coffin.getValue().id() : null,
                    service ? orZero(parseInteger(buses.getText(), "Los buses adicionales")) : 0,
                    List.copyOf(products),
                    strict ? parseAmount(initial.getText(), "El abono inicial") : null);
        };

        // Resumen en vivo: se recalcula en otro hilo cuando el usuario deja de cambiar datos
        PauseTransition quoteDelay = new PauseTransition(Duration.millis(350));
        int[] quoteRequest = new int[1];
        quoteDelay.setOnFinished(e -> {
            int request = ++quoteRequest[0];
            ContractData current;
            try {
                current = data.apply(false);
            } catch (BusinessException ex) {
                quoteBox.getChildren().setAll(quoteTotal);
                quoteTotal.setText("Total: —");
                quoteHint.setText(ex.getMessage());
                return;
            }
            async("sale-quote", () -> contractService.quote(current), quote -> {
                if (request == quoteRequest[0]) {
                    showQuote(quote, quoteBox, quoteTotal);
                    quoteHint.setText("");
                }
            }, failure -> {
                if (request == quoteRequest[0]) {
                    quoteBox.getChildren().setAll(quoteTotal);
                    quoteTotal.setText("Total: —");
                    quoteHint.setText(message(failure, "No se pudo calcular el total."));
                }
            });
        });
        Runnable requote = quoteDelay::playFromStart;

        Runnable[] renderProducts = new Runnable[1];
        renderProducts[0] = () -> {
            productList.getChildren().clear();
            for (int i = 0; i < products.size(); i++) {
                int index = i;
                Button remove = new Button("✕");
                remove.getStyleClass().add("chip-remove");
                remove.setOnAction(e -> {
                    products.remove(index);
                    productNames.remove(index);
                    renderProducts[0].run(); // se redibuja para que cada ✕ quite la línea correcta
                    requote.run();
                });
                HBox chip = new HBox(label(productNames.get(i) + " × " + products.get(i).quantity(),
                        "product-chip-text"), remove);
                chip.getStyleClass().add("product-chip");
                chip.setMaxWidth(Region.USE_PREF_SIZE);
                productList.getChildren().add(chip);
            }
        };
        addProduct.setOnAction(e -> {
            productError.setText("");
            ItemOption item = product.getValue();
            if (item == null) {
                productError.setText("Seleccione el producto que quiere agregar.");
                return;
            }
            Integer amount;
            try {
                amount = parseInteger(quantity.getText(), "La cantidad");
            } catch (BusinessException ex) {
                productError.setText(ex.getMessage());
                return;
            }
            if (amount == null || amount <= 0) {
                productError.setText("La cantidad debe ser mayor que cero.");
                return;
            }
            products.add(new ContractData.ItemLine(item.id(), amount));
            productNames.add(item.name());
            quantity.setText("1");
            product.setValue(null);
            renderProducts[0].run();
            requote.run();
        });

        kindGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle != null) {
                applyKind.run();
                requote.run();
            }
        });
        client.valueProperty().addListener((obs, old, value) -> {
            plan.getItems().clear();
            plan.setValue(null);
            if (value == null) {
                plan.setPromptText("Elija primero el cliente");
            } else {
                plan.setPromptText("Buscando planes…");
                async("sale-plans", () -> contractService.plansOf(value.id()), list -> {
                    if (value.equals(client.getValue())) {
                        plan.getItems().setAll(list);
                        plan.setValue(list.size() == 1 ? list.get(0) : null);
                        plan.setPromptText(list.isEmpty() ? "El cliente no tiene plan activo" : "Seleccione el plan");
                    }
                }, failure -> plan.setPromptText("No se pudo cargar"));
            }
            requote.run();
        });
        room.valueProperty().addListener((obs, old, value) -> {
            applyKind.run();
            requote.run();
        });
        for (ComboBox<?> box : List.of(plan, deceased, coffin, startTime, endTime)) {
            box.valueProperty().addListener((obs, old, value) -> requote.run());
        }
        for (DatePicker picker : List.of(startDate, endDate)) {
            picker.valueProperty().addListener((obs, old, value) -> requote.run());
        }
        for (TextField field : List.of(base, wages, buses)) {
            field.textProperty().addListener((obs, old, value) -> requote.run());
        }
        applyKind.run();
        requote.run();

        Dialog<ButtonType> dialog = formDialog("Nuevo contrato", "Los campos con * son obligatorios.", form, 900);
        runForm(dialog, form, "Registrar", () -> contractService.register(data.apply(true)), saleId -> {
            showToast("Venta N.° %04d registrada.".formatted(saleId));
            loadOverview(saleId);
        });
    }

    private static void showQuote(Quote quote, VBox box, Label total) {
        box.getChildren().setAll(linesGrid(quote.lines(), quote.total()));
        total.setText("Total: " + pesos(quote.total()));
    }

    // ---------- Abonar, Anular y Preparación ----------

    private void openPaymentDialog(SaleRow r) {
        TextField amount = new TextField();
        amount.setPromptText("Ej.: 500.000");
        TextField description = new TextField();
        description.setPromptText("Opcional · hasta 150 caracteres");
        Form form = new Form()
                .section("Venta", 3)
                .field("VENTA", readOnly("N.° " + r.number() + " · " + r.kind().getLabel()))
                .field("TOTAL", readOnly(pesos(r.total())))
                .field("SALDO PENDIENTE", readOnly(pesos(r.balance())))
                .section("Abono", 3)
                .field("VALOR DEL ABONO *", amount)
                .field("DESCRIPCIÓN", description)
.note(note("El abono no puede ser mayor que el saldo. Se registra como ingreso en Finanzas."));
        Dialog<ButtonType> dialog = formDialog("Abonar a la venta N.° " + r.number(), r.title() + " · " + r.subtitle(),
                form, 760);
        runForm(dialog, form, "Registrar abono", () -> {
            contractService.registerPayment(new SalePaymentData(r.saleId(),
                    parseAmount(amount.getText(), "El valor del abono"), description.getText()));
            return r.saleId();
        }, saleId -> {
            showToast("Abono registrado en la venta N.° " + r.number() + ".");
            loadOverview(drawerSaleId);
        });
    }

    private void confirmVoid(SaleRow r) {
        ButtonType voidType = new ButtonType("Anular venta", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE, "La venta queda como anulada (no se borra), sus abonos se "
                + "anulan en Finanzas, los productos vuelven al inventario y, si tiene contrato, el contrato se "
                + "cancela. Esta acción no se puede deshacer.", cancel, voidType);
        alert.initOwner(window());
        alert.setTitle("Anular venta");
        alert.setHeaderText("¿Anular la venta N.° " + r.number() + "?");
        styleDialog(alert.getDialogPane());
        if (alert.getDialogPane().lookupButton(voidType) instanceof Button button) {
            button.getStyleClass().add("danger-button");
        }
        if (alert.showAndWait().filter(voidType::equals).isEmpty()) {
            return;
        }
        async("sale-void", () -> {
            contractService.voidSale(r.saleId());
            return r.saleId();
        }, saleId -> {
            showToast("Venta N.° " + r.number() + " anulada.");
            loadOverview(drawerSaleId);
        }, failure -> showError(message(failure, "No se pudo anular la venta.")));
    }

    private void openPreparationDialog(SaleRow r, ContractInfo c) {
        ComboBox<Option> thanatopractor = optionCombo("Cargando…");
        async("sale-thanatopractors", contractService::thanatopractors, list -> {
            thanatopractor.getItems().setAll(list);
            thanatopractor.setPromptText(list.isEmpty() ? "No hay tanatólogos activos" : "Seleccione el tanatólogo");
        }, failure -> thanatopractor.setPromptText("No se pudo cargar"));
        DatePicker date = new DatePicker(today());
        date.setEditable(false);
        date.setConverter(dateConverter());
        LocalTime now = LocalTime.now(AccessLogService.APP_ZONE);
        ComboBox<LocalTime> time = timeCombo(LocalTime.of(now.getHour(), now.getMinute() < 30 ? 0 : 30));
        Form form = new Form()
                .section("Preparación del cuerpo", 3)
                .field("CONTRATO", readOnly(c.number()))
                .field("DIFUNTO", readOnly(orDash(c.deceasedName())))
                .field("TANATÓLOGO *", thanatopractor)
                .field("FECHA EN QUE TERMINÓ *", date)
                .field("HORA *", time)
.note(note("La preparación la registra el administrador o la secretaria."));
        Dialog<ButtonType> dialog = formDialog("Registrar preparación", "Los campos con * son obligatorios.", form, 760);
        runForm(dialog, form, "Registrar preparación", () -> {
            contractService.registerPreparation(c.id(), idOf(thanatopractor.getValue()),
                    at(date.getValue(), time.getValue()));
            return c.id();
        }, id -> {
            showToast("Preparación registrada en el contrato " + c.number() + ".");
            loadOverview(r.saleId());
        });
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
            async("sale-save", save, result -> {
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

    /** Buscador del diálogo: espera a que el usuario deje de escribir y consulta en otro hilo. */
    private static void bindSearch(TextField field, ComboBox<Option> box, Function<String, List<Option>> search) {
        bindGenericSearch(field, box, search);
    }

    private void bindItemSearch(TextField field, ComboBox<ItemOption> box) {
        bindGenericSearch(field, box, contractService::searchItems);
    }

    private static <T> void bindGenericSearch(TextField field, ComboBox<T> box, Function<String, List<T>> search) {
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        int[] requestNumber = new int[1];
        String prompt = box.getPromptText();
        Runnable refresh = () -> {
            String text = field.getText();
            int request = ++requestNumber[0];
            box.setPromptText("Buscando…");
            async("sale-dialog-search", () -> search.apply(text), options -> {
                if (request != requestNumber[0]) {
                    return;
                }
                box.setPromptText(options.isEmpty() ? "Sin resultados" : prompt);
                T current = box.getValue();
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
            throw new InvalidDataException(what + " debe ser un número entero.");
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
            throw new InvalidDataException(what + " debe ser un valor en pesos.");
        }
    }

    /** "4" o "3,5" salarios mínimos. */
    private static BigDecimal parseDecimal(String text, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            throw new InvalidDataException(what + " deben ser un número.");
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

    private static ComboBox<ItemOption> itemCombo(String prompt) {
        ComboBox<ItemOption> box = new ComboBox<>();
        box.setPromptText(prompt);
        box.getStyleClass().add("form-combo");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setConverter(converter(i -> i.name() + " · " + pesos(i.price()) + " · " + i.stock() + " en existencia", ""));
        return box;
    }

    private static Button drawerButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("drawer-button", styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    /** Las ventanas cargan app.css, personal.css y contracts.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                ContractController.class.getResource("/css/app.css").toExternalForm(),
                ContractController.class.getResource("/css/personal.css").toExternalForm(),
                ContractController.class.getResource("/css/contracts.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "contracts-root");
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

    private static StringConverter<LocalDate> dateConverter() {
        return converter(DATE::format, "");
    }

    private static String contractStatus(ContractStatus status) {
        if (status == null) {
            return "—";
        }
        return switch (status) {
            case ACTIVE -> "Vigente";
            case COMPLETED -> "Completado";
            case CANCELLED -> "Cancelado";
        };
    }

    static String pesos(BigDecimal value) {
        NumberFormat format = NumberFormat.getIntegerInstance(ES);
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value.setScale(0, RoundingMode.HALF_UP));
    }

    /** "$ 58,4 M" desde un millón; por debajo, el valor completo. */
    static String short_(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        if (v.abs().compareTo(BigDecimal.valueOf(1_000_000)) < 0) {
            return pesos(v);
        }
        return "$ " + oneDecimal(v.divide(BigDecimal.valueOf(1_000_000), 1, RoundingMode.HALF_UP).doubleValue()) + " M";
    }

    private static String oneDecimal(double value) {
        DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(ES));
        return format.format(value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static Long idOf(Option option) {
        return option == null ? null : option.id();
    }

    private static LocalDate today() {
        return LocalDate.now(AccessLogService.APP_ZONE);
    }
}
