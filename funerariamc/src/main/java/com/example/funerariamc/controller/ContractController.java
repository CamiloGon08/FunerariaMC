package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.utils.enums.ContractStatus;
import com.example.funerariamc.utils.enums.SaleType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AppModule;
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
import com.example.funerariamc.service.ClientData;
import com.example.funerariamc.service.ClientService;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.FuneralPlanService.PlanOffer;
import com.example.funerariamc.service.SalePaymentData;
import com.example.funerariamc.ui.Pager;
import com.example.funerariamc.ui.DetailShell;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.Responsive;
import com.example.funerariamc.ui.DetailCache;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
import com.example.funerariamc.ui.ModuleLink;
import com.example.funerariamc.ui.ModuleIcons;
import com.example.funerariamc.ui.BrandTheme;
import com.example.funerariamc.ui.PlanOfferCard;
import com.example.funerariamc.ui.SearchField;
import com.example.funerariamc.service.DeathDocumentData;
import com.example.funerariamc.service.DeceasedData;
import com.example.funerariamc.service.FileStorageService;
import com.example.funerariamc.service.PersonNames;
import com.example.funerariamc.service.PlanDeathService;
import com.example.funerariamc.utils.enums.ClientType;
import com.example.funerariamc.utils.enums.DeathType;
import com.example.funerariamc.utils.enums.DocumentType;
import com.example.funerariamc.utils.enums.ExternalCause;
import com.example.funerariamc.utils.enums.Relationship;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Pantalla de Contratos y ventas (paleta verde oscura): indicadores del mes,
 * buscador con filtro por tipo de venta y por estado de pago, tabla "Contratos
 * registrados", panel "Ver" con conceptos y abonos, y los diálogos Nuevo contrato, Abonar, Anular venta y Registrar preparación.
 * Todas las consultas corren en otro hilo; la tabla filtra en memoria y solo dibuja las filas visibles.
 */
@Component
@Scope("prototype")
public class ContractController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Contratos y ventas";

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_HOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Filas que se traen al caché apenas carga la tabla (las primeras que se ven). */
    private static final int PREFETCH = 12;

    /** Anchos de las columnas de la tabla; DIFUNTO / CLIENTE toma el espacio que sobra. */
    private static final double W_NUMBER = 70;
    private static final double W_TYPE = 132;
    private static final double W_MONEY = 112;
    private static final double W_STATE = 112;
    private static final double W_ACTIONS = 150;
    /** Con el módulo más angosto que esto (ventana pequeña) se ocultan Tipo y Abonado: el resto cabe sin barra horizontal. */
    private static final double NARROW_MODULE = 1090;

    private final ContractService contractService;
    private final FuneralPlanService planService;
    private final PlanDeathService planDeathService;
    private final DetailCache<SaleView> details;
    /** Verdadero en ventana angosta: Tipo y Abonado se ocultan (siguen en el detalle de la venta). */
    private ObservableBooleanValue narrow;

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
    private Label toast;

    private final ObservableList<SaleRow> visibleRows = FXCollections.observableArrayList();
    private Pager<SaleRow> pager;
    /** Filtros con los que se armó la página actual (para saber si hay que volver a la página 1). */
    private String lastFilter;
    private List<SaleRow> allRows = List.of();
    private boolean loaded;
    /** Venta abierta en el panel; null si está cerrado. */
    private Long drawerSaleId;
    private DetailShell detail;
    /** Cada carga lleva un número; si llega una respuesta vieja, se descarta. */
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));
    /** Verdadero mientras "Nuevo contrato" está abierto porque se pidió desde Clientes o Planes. */
    private boolean openedFromLink;
    /** Planes que se ofrecen (Normal y Premium con su cuota); se traen en segundo plano al abrir el módulo. */
    private volatile List<PlanOffer> offerCache;

    public ContractController(ContractService contractService, FuneralPlanService planService,
                              PlanDeathService planDeathService) {
        this.contractService = contractService;
        this.planService = planService;
        this.planDeathService = planDeathService;
        this.details = new DetailCache<>(contractService::detail, "sale-detail");
    }

    /** Al volver al módulo se pinta lo último que se vio y se refresca en segundo plano. */
    @Override
    public void refresh() {
        loadOverview(drawerSaleId);
        loadOffers();
        takeLink();
    }

    /** Trae en segundo plano los planes que se ofrecen, para abrir "Nuevo contrato" sin esperar. */
    private void loadOffers() {
        async("sale-plan-offers", planService::planOffers, list -> offerCache = list, failure -> { });
    }

    /** Los planes que se ofrecen; si todavía no llegaron, se traen ahora. */
    private List<PlanOffer> offers() {
        List<PlanOffer> list = offerCache;
        if (list == null) {
            list = planService.planOffers();
            offerCache = list;
        }
        return list;
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

        narrow = Responsive.narrowerThan(root, NARROW_MODULE);
        Node typeHead = cell(label("TIPO", "head-cell"), W_TYPE);
        Node paidHead = cell(label("ABONADO", "head-cell"), W_MONEY);
        Responsive.hideWhen(narrow, typeHead, paidHead);
        tableHead.getChildren().setAll(
                cell(label("N.°", "head-cell"), W_NUMBER),
                grow(label("DIFUNTO / CLIENTE", "head-cell")),
                typeHead,
                cell(label("TOTAL", "head-cell"), W_MONEY),
                paidHead,
                cell(label("SALDO", "head-cell"), W_MONEY),
                cell(label("ESTADO", "head-cell"), W_STATE),
                cell(label("ACCIONES", "head-cell"), W_ACTIONS));

        salesList.setItems(visibleRows);
        salesList.setCellFactory(list -> new SaleCell());
        salesList.setFixedCellSize(77);
        salesList.setPlaceholder(new Label(""));
        salesList.setMinWidth(0);
        salesList.setPrefWidth(0); // la tabla toma el ancho disponible, no el de su contenido
        // Paginación como la bitácora: 20 filas por página, debajo de la tabla.
        pager = Pager.<SaleRow>local(Pager.TABLE_PAGE, "registro", "registros", page -> {
            visibleRows.setAll(page);
            salesList.scrollTo(0);
        }).below(salesList.getParent());

        detail = DetailShell.install(drawer, veil, DetailShell.Kind.MODAL);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);

        loadOverview(null);
        Platform.runLater(this::takeLink); // primera vez que se abre, desde el plan exequial
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
        // Un filtro nuevo vuelve a la página 1; recargar tras guardar se queda en la misma página.
        String filterKey = type + "|" + paymentCombo.getValue() + "|" + searchField.getText();
        if (filterKey.equals(lastFilter)) {
            pager.setItems(rows);
        } else {
            pager.setItemsFromStart(rows);
        }
        lastFilter = filterKey;
        boolean filtered = rows.size() != allRows.size();
        countLabel.setText(allRows.isEmpty() ? "Sin registros todavía"
                : filtered ? rows.size() + " de " + allRows.size() + " registros" : rows.size() + " registros");
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
            Node typeCell = cell(type, W_TYPE);
            Node paidCell = cell(paid, W_MONEY);
            Responsive.hideWhen(narrow, typeCell, paidCell);
            row = new HBox(cell(number, W_NUMBER), grow(who), typeCell, cell(total, W_MONEY),
                    paidCell, cell(balance, W_MONEY), cell(state, W_STATE), cell(actions, W_ACTIONS));
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

    // ---------- detalle de la venta ----------

    private void openDrawer(Long saleId) {
        drawerSaleId = saleId;
        SaleView cached = details.get(saleId);
        if (cached != null) {
            fillDrawer(cached);
            return;
        }
        detail.show(saleId, new DetailShell.Hero("Venta", "Cargando la venta…").onClose(this::closeDrawer),
                List.of(), List.of(), null);
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

    private void fillDrawer(SaleView v) {
        SaleRow r = v.row();
        double paidShare = r.total().signum() == 0 ? 1
                : r.paid().divide(r.total(), 4, RoundingMode.HALF_UP).doubleValue();
        DetailShell.Hero hero = new DetailShell.Hero("Venta n.° " + r.number() + " · " + r.kind().getLabel(), r.title())
                .subtitle(r.saleDate() == null ? null : "Registrada el " + DATE_HOUR.format(r.saleDate()))
                .chips(DetailShell.chip("● " + r.state().getLabel(), switch (r.state()) {
                    case PAID -> DetailShell.Tone.OK;
                    case WITH_BALANCE -> DetailShell.Tone.GOLD;
                    case VOIDED -> DetailShell.Tone.ALERT;
                }))
                .extra(DetailShell.heroSpread(DetailShell.heroLine("Total", pesos(r.total())),
                        DetailShell.heroLine("Abonado", pesos(r.paid())),
                        DetailShell.heroLine("Saldo", pesos(r.balance()))))
                .extra(DetailShell.bar(paidShare, DetailShell.Tone.GOLD))
                .navigation(DetailShell.neighbor(pager.items(), x -> x.saleId().equals(r.saleId()), -1, x -> openDrawer(x.saleId())),
                        DetailShell.neighbor(pager.items(), x -> x.saleId().equals(r.saleId()), 1, x -> openDrawer(x.saleId())))
                .onClose(this::closeDrawer);

        List<Node> left = new ArrayList<>();
        left.add(DetailShell.section(r.kind() == SaleKind.PENSION ? "Entidad pagadora" : "Cliente",
                DetailShell.card(DetailShell.fields(
                        r.kind() == SaleKind.PENSION ? "Entidad pagadora" : "Cliente", v.clientName(),
                        "Documento", orDash(v.clientDocument()),
                        "Teléfono", orDash(v.clientPhone()),
                        "Vendedor", orDash(v.salesperson())))));
        ContractInfo c = v.contract();
        if (c != null) {
            left.add(DetailShell.section("Servicio", DetailShell.card(DetailShell.fields(
                    "Contrato", c.number(),
                    "Estado del contrato", contractStatus(c.status()),
                    "Difunto", orDash(c.deceasedName()),
                    "Plan exequial", c.planLabel() == null ? "Sin plan" : c.planLabel(),
                    "Sala de velación", c.roomName() == null ? "Sin sala asignada"
                            : c.roomName() + (c.wakeStart() == null ? "" : " · " + DATE_HOUR.format(c.wakeStart())
                            + (c.wakeEnd() == null ? "" : " a " + HOUR.format(c.wakeEnd()))),
                    "Preparación (tanatopraxia)", c.preparationCompletedAt() == null ? "Pendiente"
                            : "Completada " + DATE_HOUR.format(c.preparationCompletedAt())
                            + (c.thanatopractor() == null ? "" : " · " + c.thanatopractor())))));
        }
        List<Node> right = List.of(DetailShell.section("Conceptos", linesBox(v.lines(), r.total())),
                DetailShell.section("Abonos", paymentsBox(v.payments())));

        List<Button> actions = new ArrayList<>();
        if (r.state() != PaymentState.VOIDED) {
            actions.add(DetailShell.danger("Anular venta", () -> confirmVoid(r)));
        }
        if (c != null && v.canRegisterPreparation() && c.status() == ContractStatus.ACTIVE
                && c.preparationCompletedAt() == null) {
            actions.add(DetailShell.secondary("Registrar preparación", () -> openPreparationDialog(r, c)));
        }
        if (r.state() == PaymentState.WITH_BALANCE) {
            actions.add(DetailShell.primary("Abonar", () -> openPaymentDialog(r)));
        }
        detail.show(r.saleId(), hero, List.of(DetailShell.columns(left, right)), actions, null);
    }

    /** Conceptos con su valor y, separado por una línea, el total. */
    private static VBox linesBox(List<Line> lines, BigDecimal total) {
        List<Object> pairs = new ArrayList<>();
        for (Line line : lines) {
            pairs.add(line.description() + (line.quantity() > 1
                    ? " × " + line.quantity() + " (" + pesos(line.unitPrice()) + " c/u)" : ""));
            pairs.add(pesos(line.subtotal()));
        }
        Node concepts = lines.isEmpty() ? label("Sin conceptos.", "detail-empty") : DetailShell.rows(pairs.toArray());
        Label totalValue = DetailShell.value(pesos(total), null);
        totalValue.setStyle("-fx-font-size: 13px;");
        return DetailShell.list(concepts, DetailShell.rows("Total", totalValue));
    }

    private static Node paymentsBox(List<Payment> payments) {
        if (payments.isEmpty()) {
            return DetailShell.empty("Sin abonos registrados.");
        }
        List<DetailShell.Event> events = new ArrayList<>();
        for (Payment p : payments) {
            String line = (p.date() == null ? "" : DATE_HOUR.format(p.date())) + (p.employee() == null ? "" : " · " + p.employee())
                    + (p.description() == null || p.description().isBlank() ? "" : " · " + p.description());
            events.add(new DetailShell.Event(pesos(p.amount()), line,
                    p.voided() ? DetailShell.Tone.NEUTRAL : DetailShell.Tone.OK,
                    p.voided() ? DetailShell.chip("Anulado", DetailShell.Tone.NEUTRAL) : DetailShell.chip("Abono", DetailShell.Tone.OK)));
        }
        return DetailShell.timeline(events);
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

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerSaleId = null;
        detail.close(null);
    }

    // ---------- Nuevo contrato ----------

    @FXML
    private void onNewContract() {
        openNewSale();
    }

    /**
     * Salto desde Clientes: con newSale se abre "Nuevo contrato" con el cliente ya elegido (el
     * vendedor elige el tipo); sin él se muestran las ventas del cliente que tienen saldo, para abonar sobre una de ellas.
     */
    public record ClientLink(Option client, boolean newSale) {
    }

    /** Desde Clientes, recién registrado el cliente: abre la afiliación a un plan con él ya elegido. */
    public record AffiliationLink(Option client) {
    }

    /**
     * Llega desde el plan exequial con el difunto (se abre la venta con él ya elegido)
     * o desde Clientes con el cliente.
     */
    private void takeLink() {
        ModuleLink.take(AppModule.CONTRATOS).ifPresent(payload -> {
            if (payload instanceof Long deceasedId) {
                openSaleFor(deceasedId);
            } else if (payload instanceof ClientLink link && link.newSale()) {
                // Con el cliente ya elegido; al cerrar se vuelve a Clientes, no se queda en Contratos y ventas
                openedFromLink = true;
                try {
                    openNewSale(null, null, link.client());
                } finally {
                    openedFromLink = false;
                }
                ModuleLink.back(AppModule.CONTRATOS);
            } else if (payload instanceof AffiliationLink link) {
                openedFromLink = true;
                try {
                    openAffiliation(link.client());
                } finally {
                    openedFromLink = false;
                }
            } else if (payload instanceof ClientLink link) {
                showSalesOf(link.client());
            }
        });
    }

    /** Filtra la lista por el nombre del cliente y deja solo las ventas con saldo. */
    private void showSalesOf(Option client) {
        allToggle.setSelected(true);
        paymentCombo.setValue(PaymentState.WITH_BALANCE);
        searchField.setText(client.label());
    }

    private void openSaleFor(Long deceasedId) {
        async("sale-from-plan", () -> contractService.deceasedForSale(deceasedId), found -> {
            if (found.isPresent()) {
                // Desde Planes: al cerrar el contrato se vuelve a Planes exequiales
                openedFromLink = true;
                try {
                    openNewSale(found.get(), null, null);
                } finally {
                    openedFromLink = false;
                }
                ModuleLink.back(AppModule.CONTRATOS);
            } else {
                showError("Ese difunto ya tiene un contrato vigente. Búsquelo en la lista de contratos.");
            }
        }, failure -> showError(message(failure, "No se pudo abrir el contrato del difunto.")));
    }

    /** Causa externa de una muerte no natural, como se lee en pantalla. */
    private static final Map<ExternalCause, String> CAUSES = Map.of(
            ExternalCause.ACCIDENT, "Accidente",
            ExternalCause.HOMICIDE, "Homicidio",
            ExternalCause.SUICIDE, "Suicidio",
            ExternalCause.UNDETERMINED, "Indeterminada");
    /** Quién expide el certificado: Medicina Legal si la muerte es violenta; si es natural, el médico o la clínica. */
    private static final String FORENSIC_AUTHORITY = "Medicina Legal";
    private static final String DOCTOR_AUTHORITY = "Médico tratante o clínica";

    /** Afiliación a un plan exequial: se elige en el paso 1 como un tipo más, pero no es una venta. */
    private static final String AFFILIATION = "AFFILIATION";
    /** Alto de las tarjetas del paso 1. */
    private static final double CARD_HEIGHT = 178;
    /** Alto de cada persona del plan en "Contrato · plan". */
    private static final double PERSON_HEIGHT = 60;

    /** Tarjeta de un tipo del paso 1: ícono del menú, nombre, qué es y por qué pasos sigue. */
    private record KindInfo(Object choice, String icon, String title, String text, String flow) {
    }

    private static final List<KindInfo> KINDS = List.of(
            new KindInfo(AFFILIATION, "PLANES", "Plan exequial",
                    "Si el cliente no tiene plan, lo afilia aquí (Normal o Premium y sus beneficiarios). Si ya "
                            + "tiene, ve su plan, lo cambia o le agrega o quita personas.",
                    "Cliente → Plan y beneficiarios → Resumen"),
            new KindInfo(SaleKind.PLAN, "CONTRATOS", "Registrar difunto del plan",
                    "Falleció alguien del plan: se escoge a esa persona, se llenan los datos de la muerte y el "
                            + "certificado, y el servicio pasa a preparación.",
                    "Titular y persona → Fallecimiento → Fuera del plan → Sala → Pago"),
            new KindInfo(SaleKind.PENSION, "FINANZAS", "Contrato · pensión",
                    "Servicio que paga una entidad (empresa o fondo de pensiones) en salarios mínimos.",
                    "Entidad y difunto → Servicio → Sala → Productos y pago"),
            new KindInfo(SaleKind.PRIVATE, "CLIENTES", "Particular",
                    "Servicio funerario sin plan: la familia paga todo, de contado o por abonos.",
                    "Cliente y difunto → Servicio → Sala → Productos y pago"),
            new KindInfo(SaleKind.PRODUCT, "INVENTARIO", "Venta",
                    "Solo productos del inventario (ataúd, cofre, flores…), sin servicio funerario.",
                    "Cliente → Productos y pago"));

    /**
     * Tarjetas del paso 1. Ninguna viene elegida; al tocar una se avisa a onPick. Todas siguen en la
     * misma ventana: solo cambian los pasos.
     */
    private static GridPane kindCards(ToggleGroup group, Object selected, Consumer<Object> onPick) {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / 3);
            grid.getColumnConstraints().add(constraints);
        }
        for (int i = 0; i < KINDS.size(); i++) {
            KindInfo info = KINDS.get(i);
            Label title = label(info.title(), "choice-card-title");
            Label text = wrapped(info.text(), "choice-card-text");
            Label flow = wrapped(info.flow(), "choice-card-flow");
            StackPane icon = new StackPane(ModuleIcons.of(info.icon()));
            icon.getStyleClass().add("choice-card-icon");
            icon.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            HBox top = new HBox(icon);
            if (info.choice() == AFFILIATION || info.choice() == SaleKind.PENSION) {
                Region gap = new Region();
                HBox.setHgrow(gap, Priority.ALWAYS);
                top.getChildren().addAll(gap, label(info.choice() == AFFILIATION ? "AFILIAR O ACTUALIZAR"
                        : "SOLO EMPRESAS", "choice-card-badge"));
            }
            VBox content = new VBox(6, top, title, text, flow);
            VBox.setVgrow(text, Priority.ALWAYS);
            ToggleButton card = new ToggleButton();
            card.setGraphic(content);
            card.getStyleClass().add("choice-card");
            card.setUserData(info.choice());
            card.setToggleGroup(group);
            card.setMaxWidth(Double.MAX_VALUE);
            // Alto fijo: el texto envuelto calcula su alto con un ancho angosto y estiraba la tarjeta
            card.setMinHeight(CARD_HEIGHT);
            card.setPrefHeight(CARD_HEIGHT);
            card.setMaxHeight(CARD_HEIGHT);
            content.prefWidthProperty().bind(card.widthProperty().subtract(32));
            card.setSelected(info.choice() == selected);
            card.setOnAction(e -> {
                if (!card.isSelected()) {
                    card.setSelected(true); // una tarjeta elegida no se desmarca con otro clic
                }
                onPick.accept(info.choice());
            });
            grid.add(card, i % 3, i / 3);
        }
        return grid;
    }

    /** Muestra u oculta la tarjeta de un tipo y reacomoda las demás para que no quede un hueco. */
    private static void showKindCard(GridPane grid, Object choice, boolean shown) {
        int index = 0;
        for (Node card : grid.getChildren()) {
            if (card.getUserData() == choice) {
                card.setVisible(shown);
                card.setManaged(shown);
            }
            if (card.isManaged()) {
                GridPane.setConstraints(card, index % 3, index / 3);
                index++;
            }
        }
    }

    /** El contrato con pensión lo paga una entidad: solo se ofrece si el cliente es una empresa o fondo. */
    private static boolean isEntity(Option client) {
        return client == null || (client.detail() != null && client.detail().startsWith("Empresa"));
    }

    /** "Nuevo contrato" desde cero: ningún tipo ni cliente viene puesto. */
    private void openNewSale() {
        openNewSale(null, null, null);
    }

    /**
     * presetDeceased llega desde Planes ("Iniciar contrato") con el difunto del plan: se abre como
     * "Contrato · plan" con el titular y el difunto ya elegidos. presetKind abre la ventana con ese
     * tipo elegido (al pasar desde la afiliación). presetClient llega desde Clientes (o al cambiar de
     * tipo) con el cliente ya elegido; el vendedor puede cambiarlo.
     */
    private void openNewSale(Option presetDeceased, SaleKind presetKind, Option presetClient) {
        Dialog<ButtonType>[] opened = newDialogHolder();
        // Tipo de venta: al elegir una tarjeta solo cambian los pasos, la ventana es la misma
        ToggleGroup kindGroup = new ToggleGroup();
        GridPane kinds = kindCards(kindGroup, presetKind, choice -> { });
        // Registrar difunto del plan crea el difunto: solo lo ve quien tiene acceso a Difuntos
        showKindCard(kinds, SaleKind.PLAN, contractService.canRegisterDeceased());
        // "Plan exequial": afiliar al cliente o actualizar el plan que ya tiene
        AffiliationPart aff = new AffiliationPart(offers());
        // La columna derecha cambia: total de la venta, o vista previa del contrato del plan
        Runnable[] showSide = {() -> { }};

        // Cliente o titular, plan, difunto y vendedor
        Label clientCaption = label("CLIENTE *", "field-label");
        // En "Contrato · pensión" el buscador solo trae entidades (empresas o fondos de pensiones)
        boolean[] entitiesOnly = {presetKind == SaleKind.PENSION};
        SearchField<Option> client = new SearchField<>("Buscar por nombre o documento", "clientes",
                text -> entitiesOnly[0] ? contractService.searchClients(text).stream().filter(ContractController::isEntity)
                        .toList() : contractService.searchClients(text), Option::label, Option::detail);
        TextField deceasedSearch = new TextField();
        deceasedSearch.setPromptText("Nombre o documento del difunto");
        ComboBox<Option> deceased = optionCombo("Seleccione el difunto");
        ComboBox<Option> salesperson = optionCombo("Seleccione el vendedor");
        // Contrato con plan: el plan del titular y la gente del plan (solo se elige a quien está en Difuntos)
        VBox planBox = new VBox(12);
        planBox.getStyleClass().add("plan-board");
        ContractService.PlanBoard[] board = new ContractService.PlanBoard[1];
        // Persona del plan que falleció (puede no estar aún en Difuntos: se registra en el paso siguiente)
        ContractService.PlanPerson[] planPerson = new ContractService.PlanPerson[1];
        Long[] wantedDeceased = new Long[1];
        Label planNote = note("");
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

        // Contrato · plan: el plan cubre el servicio; aquí solo va lo que se cobra por fuera
        TextField planCoffinSearch = new TextField();
        planCoffinSearch.setPromptText("Buscar ataúd superior en inventario");
        ComboBox<ItemOption> planCoffin = itemCombo("El ataúd que cubre el plan");
        bindItemSearch(planCoffinSearch, planCoffin);
        TextField planBuses = new TextField("0");
        TextField planKm = new TextField("0");

        // Velación en casa: no lleva sala y no tiene descuento (se entrega la cafetería)
        CheckBox homeWake = new CheckBox("La familia vela en la casa");
        homeWake.getStyleClass().add("form-check");
        // Traslado desde otra ciudad: el plan no lo cubre, se cobra por kilómetro
        TextField intercityKm = new TextField("0");
        // Cementerio: arriendo de bóveda, lote doble u osario, construcción y adecuación del lote
        ComboBox<ContractData.CemeteryCharge> cemetery = new ComboBox<>();
        cemetery.getItems().setAll(ContractData.CemeteryCharge.values());
        cemetery.setConverter(converter(ContractData.CemeteryCharge::getLabel, ""));
        FormShell.choose(cemetery); // sin opción puesta; si no se elige nada, no se cobra cementerio
        cemetery.getStyleClass().add("form-combo");
        cemetery.setMaxWidth(Double.MAX_VALUE);
        TextField constructions = new TextField("0");
        CheckBox preparation = new CheckBox("Se usa un puesto del lote (adecuación)");
        preparation.getStyleClass().add("form-check");

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
        ComboBox<LocalTime> startTime = timeCombo(null);
        DatePicker endDate = new DatePicker(today().plusDays(1));
        ComboBox<LocalTime> endTime = timeCombo(null);
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
        addProduct.setMinWidth(Region.USE_PREF_SIZE);
        addProduct.getStyleClass().add("small-dark-button");
        List<ContractData.ItemLine> products = new ArrayList<>();
        List<String> productNames = new ArrayList<>();
        VBox productList = new VBox(6);
        Label productError = note("");

        // Registrar difunto del plan: datos del fallecimiento y certificado de la persona elegida.
        // Nombres y documento vienen del plan y no se escriben otra vez.
        TextField deathNames = new TextField();
        TextField deathLastnames = new TextField();
        TextField deathDocument = new TextField();
        for (TextField field : List.of(deathNames, deathLastnames, deathDocument)) {
            field.setEditable(false);
            field.getStyleClass().add("form-readonly");
        }
        DatePicker birth = new DatePicker();
        DatePicker deathDate = new DatePicker(today());
        for (DatePicker picker : List.of(birth, deathDate)) {
            picker.setEditable(false);
            picker.setConverter(dateConverter());
        }
        ComboBox<DeathType> deathType = new ComboBox<>();
        deathType.getItems().setAll(DeathType.values());
        deathType.setConverter(converter(DeathType::getDisplayName, ""));
        FormShell.choose(deathType); // sin tipo de muerte puesto: el usuario lo elige
        ComboBox<ExternalCause> cause = new ComboBox<>();
        cause.getItems().setAll(ExternalCause.values());
        cause.setConverter(converter(CAUSES::get, ""));
        cause.setPromptText("Solo si no es natural");
        cause.disableProperty().bind(deathType.valueProperty().isNotEqualTo(DeathType.NON_NATURAL));
        Label deathHint = wrapped("", "dialog-hint");
        // Lo expide Medicina Legal si la muerte es violenta, o el médico tratante o la clínica si es natural
        ComboBox<String> authority = new ComboBox<>();
        authority.setEditable(true);
        authority.getItems().setAll(DOCTOR_AUTHORITY, FORENSIC_AUTHORITY);
        authority.setPromptText("Entidad que lo expide");
        authority.setMaxWidth(Double.MAX_VALUE);
        deathType.valueProperty().addListener((obs, old, value) -> {
            authority.getEditor().setText(value == null ? "" : value == DeathType.NON_NATURAL
                    ? FORENSIC_AUTHORITY : DOCTOR_AUTHORITY);
            deathHint.setText(value == DeathType.NON_NATURAL ? "Muerte no natural: no se puede cremar hasta que la "
                    + "Fiscalía cierre el caso. Después suba el informe forense y la orden de entrega en Difuntos." : "");
        });
        for (ComboBox<?> box : List.of(deathType, cause, authority)) {
            box.getStyleClass().add("form-combo");
            box.setMaxWidth(Double.MAX_VALUE);
        }
        TextField certificateNumber = new TextField();
        certificateNumber.setPromptText("Número del certificado");
        DatePicker issued = new DatePicker(today());
        issued.setEditable(false);
        issued.setConverter(dateConverter());
        Path[] certificateFile = new Path[1];
        HBox fileBox = certificateChooser(certificateFile);
        Label deathTitle = label("", "card-subtitle");
        Runnable fillDeath = () -> {
            ContractService.PlanPerson person = planPerson[0];
            deathNames.setText(person == null ? "" : orEmpty(person.firstName()));
            deathLastnames.setText(person == null ? "" : orEmpty(person.lastName()));
            deathDocument.setText(person == null ? "" : orEmpty(person.document()));
            deathTitle.setText(person == null ? "" : person.name() + " · " + person.role());
        };

        // Pago
        TextField initial = new TextField();
        initial.setPromptText("Ej.: 500.000 (0 si es a crédito)");
        Label quoteHint = note("");
        VBox quoteBox = new VBox(6);
        quoteBox.getStyleClass().add("quote-box");
        // Sin conceptos calculados no se muestra el recuadro vacío
        quoteBox.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(quoteBox.getChildren()));
        quoteBox.managedProperty().bind(quoteBox.visibleProperty());
        Label quoteTotal = label("—", "form-summary-value");

        Form form = new Form()
                .section("Tipo de venta", 1)
                .note(kinds);
        aff.addMain(form);
        form.section("Cliente y difunto", 3)
                .wide(null, new VBox(2, clientCaption, client.node()))
                .note(planBox)
                .grid(3)
                .field("BUSCAR DIFUNTO", deceasedSearch)
                .field("DIFUNTO *", deceased)
                .field("VENDEDOR *", salesperson)
                .note(planNote)
                .section("Fallecimiento y certificado", 3)
                .wide(null, deathTitle)
                .field("NOMBRES", deathNames)
                .field("APELLIDOS", deathLastnames)
                .field("DOCUMENTO", deathDocument)
                .field("FECHA DE NACIMIENTO", birth)
                .field("FECHA DE DEFUNCIÓN *", deathDate)
                .field("TIPO DE MUERTE *", deathType)
                .field("CAUSA EXTERNA", cause)
                .wide(null, deathHint)
                .field("ENTIDAD QUE EXPIDE EL CERTIFICADO *", authority)
                .field("NÚMERO DEL CERTIFICADO *", certificateNumber)
                .field("FECHA DE EXPEDICIÓN *", issued)
                .wide("CERTIFICADO DE DEFUNCIÓN (ARCHIVO) *", fileBox)
                .note(note("El difunto queda registrado en Difuntos con este certificado, a nombre del titular y "
                        + "vinculado al plan. Al registrar el contrato, el servicio pasa a Preparación."))
                .section("Servicio", 3)
                .field("CUOTA BASE DEL SERVICIO *", base)
                .field("SALARIOS MÍNIMOS (PENSIÓN) *", wages)
                .field("BUSES ADICIONALES", buses)
                .field("BUSCAR ATAÚD", coffinSearch)
                .field("ATAÚD", coffin)
                .field("TRASLADO DESDE OTRA CIUDAD (KM)", intercityKm)
                .section("Fuera del plan", 3)
                .note(note("Lo que cubre el plan no se cobra: el servicio funerario, el ataúd del plan y un bus. "
                        + "Aquí solo se registra lo que va por fuera: un ataúd superior (se cobra la diferencia), "
                        + "buses adicionales y kilómetros de traslado desde otra ciudad."))
                .field("BUSCAR ATAÚD SUPERIOR", planCoffinSearch)
                .field("ATAÚD", planCoffin)
                .field("BUSES ADICIONALES", planBuses)
                .field("TRASLADO DESDE OTRA CIUDAD (KM)", planKm)
                .section("Sala de velación", 3)
                .field("VELACIÓN", homeWake)
                .field("SALA", room)
                .field("FECHA DE INGRESO", startDate)
                .field("HORA DE INGRESO", startTime)
                .field("", new Region())
                .field("FECHA DE SALIDA", endDate)
                .field("HORA DE SALIDA", endTime)
                .section("Cementerio", 3)
                .field("QUÉ SE COBRA", cemetery)
                .field("PUESTOS A CONSTRUIR", constructions)
                .field("ADECUACIÓN", preparation)
                .section("Productos", 3)
                .field("BUSCAR PRODUCTO", productSearch)
                .field("PRODUCTO", product)
                .field("CANTIDAD", new HBox(8, quantity, addProduct))
                .note(productList)
                .note(productError)
                .section("Abono", 3)
                .field("ABONO INICIAL *", initial)
                .note(note("Con plan se cobra solo el excedente del ataúd frente al que cubre el plan y el "
                        + "recargo por beneficiarios; el plan premium cubre un ataúd más grande y la sala especial. "
                        + "Sin plan premium la sala especial agrega su recargo, y cada bus adicional y cada "
                        + "kilómetro de traslado desde otra ciudad su tarifa. Los valores salen de Parámetros "
                        + "del sistema. Si el abono es igual al total, la venta queda pagada."));
        HBox.setHgrow(quantity, Priority.ALWAYS);

        // Habilita solo los campos que usa cada tipo de venta
        Runnable applyKind = () -> {
            SaleKind kind = kindOf(kindGroup);
            boolean service = kind == null || kind.isService();
            boolean withPlan = kind == SaleKind.PLAN;
            planBox.setVisible(withPlan);
            planBox.setManaged(withPlan);
            // Con plan el difunto se elige entre la gente del plan; particular y pensión buscan entre
            // todos los difuntos sin contrato (se registran en Difuntos sin cliente)
            showField(deceasedSearch, kind == SaleKind.PENSION || kind == SaleKind.PRIVATE);
            showField(deceased, service && !withPlan);
            showField(salesperson, service);
            base.setDisable(kind != SaleKind.PRIVATE);
            wages.setDisable(kind != SaleKind.PENSION);
            buses.setDisable(!service);
            coffinSearch.setDisable(!service);
            coffin.setDisable(!service);
            homeWake.setDisable(!service);
            intercityKm.setDisable(!service);
            room.setDisable(!service || homeWake.isSelected());
            boolean withRoom = service && !homeWake.isSelected() && room.getValue() != null;
            for (Node n : List.of(startDate, startTime, endDate, endTime)) {
                n.setDisable(!withRoom);
            }
            // La venta no lleva difunto: el título de la sección dice solo "Cliente"
            for (Node title : form.body.lookupAll(".section-title")) {
                if (title instanceof Label l && l.getText().startsWith("Cliente")) {
                    l.setText(kind == SaleKind.PRODUCT ? "Cliente" : kind == SaleKind.PLAN ? "Titular y persona"
                            : "Cliente y difunto");
                }
            }
            clientCaption.setText(kind == SaleKind.PENSION ? "ENTIDAD PAGADORA *"
                    : withPlan ? "TITULAR DEL PLAN *" : "CLIENTE *");
            client.field().setPromptText(kind == SaleKind.PENSION ? "Buscar la entidad (empresa) por nombre o NIT"
                    : withPlan ? "Buscar el titular por nombre o documento" : "Buscar el cliente por nombre o documento");
        };

        Function<Boolean, ContractData> data = strict -> {
            SaleKind kind = kindOf(kindGroup);
            if (kind == null) {
                throw new InvalidDataException("Elija en el paso 1 qué va a registrar.");
            }
            boolean service = kind.isService();
            boolean withRoom = service && !homeWake.isSelected() && room.getValue() != null;
            Long deceasedId = kind == SaleKind.PLAN ? (planPerson[0] == null ? null : planPerson[0].deceasedId())
                    : idOf(deceased.getValue());
            return new ContractData(kind, idOf(client.getValue()),
                    service ? deceasedId : null,
                    kind == SaleKind.PLAN && board[0] != null ? board[0].planId() : null,
                    service ? idOf(salesperson.getValue()) : null,
                    base.isDisable() ? null : parseAmount(base.getText(), "La cuota base"),
                    wages.isDisable() ? null : parseDecimal(wages.getText(), "Los salarios mínimos"),
                    withRoom ? room.getValue().id() : null,
                    withRoom ? at(startDate.getValue(), startTime.getValue()) : null,
                    withRoom ? at(endDate.getValue(), endTime.getValue()) : null,
                    kind == SaleKind.PLAN ? (planCoffin.getValue() == null ? null : planCoffin.getValue().id())
                            : service && coffin.getValue() != null ? coffin.getValue().id() : null,
                    kind == SaleKind.PLAN ? orZero(parseInteger(planBuses.getText(), "Los buses adicionales"))
                            : service ? orZero(parseInteger(buses.getText(), "Los buses adicionales")) : 0,
                    List.copyOf(products),
                    strict ? parseAmount(initial.getText(), "El abono inicial") : null,
                    service && homeWake.isSelected(),
                    kind == SaleKind.PLAN ? orZero(parseInteger(planKm.getText(), "Los kilómetros del traslado"))
                            : service ? orZero(parseInteger(intercityKm.getText(), "Los kilómetros del traslado")) : 0,
                    kind == SaleKind.PENSION || !service ? ContractData.CemeteryCharge.NONE : cemetery.getValue(),
                    kind == SaleKind.PENSION || !service ? 0
                            : orZero(parseInteger(constructions.getText(), "Los puestos a construir")),
                    kind != SaleKind.PENSION && service && preparation.isSelected());
        };

        // Resumen en vivo: se recalcula en otro hilo cuando el usuario deja de cambiar datos
        PauseTransition quoteDelay = new PauseTransition(Duration.millis(350));
        int[] quoteRequest = new int[1];
        quoteDelay.setOnFinished(e -> {
            int request = ++quoteRequest[0];
            ContractData current;
            try {
                current = kindOf(kindGroup) == null ? null : data.apply(false);
            } catch (BusinessException ex) {
                quoteBox.getChildren().clear();
                quoteTotal.setText("—");
                quoteHint.setText(ex.getMessage());
                return;
            }
            if (current == null) {
                quoteBox.getChildren().clear();
                return;
            }
            // Total parcial: suma lo elegido hasta ahora aunque falten datos
            async("sale-quote", () -> contractService.preview(current), quote -> {
                if (request == quoteRequest[0]) {
                    showQuote(quote, quoteBox, quoteTotal);
                    quoteHint.setText("");
                }
            }, failure -> {
                if (request == quoteRequest[0]) {
                    quoteBox.getChildren().clear();
                    quoteTotal.setText("—");
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

        // Plan del titular: se busca al elegirlo y se dibuja con la gente del plan
        Runnable[] loadBoard = new Runnable[1];
        loadBoard[0] = () -> {
            board[0] = null;
            planPerson[0] = null;
            planBox.getChildren().clear();
            Option holder = client.getValue();
            if (kindOf(kindGroup) != SaleKind.PLAN || holder == null) {
                if (kindOf(kindGroup) == SaleKind.PLAN) {
                    planBox.getChildren().add(note("Busque y elija el titular: aquí se verá su plan y las personas que cubre."));
                }
                requote.run();
                return;
            }
            planBox.getChildren().add(label("Buscando el plan del titular…", "card-subtitle"));
            async("sale-plan-board", () -> contractService.planBoard(holder.id()), found -> {
                if (!holder.equals(client.getValue())) {
                    return;
                }
                board[0] = found.orElse(null);
                drawBoard(planBox, board[0], planPerson, wantedDeceased[0], () -> {
                    fillDeath.run();
                    requote.run();
                    if (opened[0] != null) {
                        FormShell.refreshSteps(opened[0]);
                    }
                }, () -> {
                    if (board[0] != null) {
                        openChangePlanDialog(board[0], loadBoard[0]);
                    }
                });
                wantedDeceased[0] = null;
                requote.run();
            }, failure -> {
                planBox.getChildren().setAll(note(message(failure, "No se pudo traer el plan del titular.")));
                requote.run();
            });
        };

        kindGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle != null && toggle.getUserData() instanceof SaleKind) {
                entitiesOnly[0] = toggle.getUserData() == SaleKind.PENSION;
                if (entitiesOnly[0] && !isEntity(client.getValue())) {
                    client.setValue(null); // una persona natural no puede ser la entidad pagadora
                }
                applyKind.run();
                loadBoard[0].run();
            }
            showSide[0].run();
            if (opened[0] != null) {
                FormShell.refreshSteps(opened[0]);
            }
        });
        client.valueProperty().addListener((obs, old, value) -> {
            showKindCard(kinds, SaleKind.PENSION, isEntity(value));
            loadBoard[0].run();
        });
        deceased.valueProperty().addListener((obs, old, value) -> {
            planNote.setText("");
            requote.run();
            if (value == null) {
                return;
            }
            // Si el difunto está en un plan exequial se avisa: ese servicio va como "Contrato · plan"
            async("sale-deceased-plan", () -> contractService.planOfDeceased(value.id()), match -> {
                if (value.equals(deceased.getValue()) && match.isPresent()) {
                    planNote.setText(match.get().note() + " Si el servicio es con el plan, elija «Registrar difunto del plan».");
                }
            }, failure -> planNote.setText(""));
        });
        room.valueProperty().addListener((obs, old, value) -> {
            applyKind.run();
            requote.run();
        });
        for (ComboBox<?> box : List.of(coffin, startTime, endTime)) {
            box.valueProperty().addListener((obs, old, value) -> requote.run());
        }
        for (DatePicker picker : List.of(startDate, endDate)) {
            picker.valueProperty().addListener((obs, old, value) -> requote.run());
        }
        planCoffin.valueProperty().addListener((obs, old, value) -> requote.run());
        for (TextField field : List.of(base, wages, buses, intercityKm, constructions, planBuses, planKm)) {
            field.textProperty().addListener((obs, old, value) -> requote.run());
        }
        cemetery.valueProperty().addListener((obs, old, value) -> requote.run());
        for (CheckBox box : List.of(homeWake, preparation)) {
            box.selectedProperty().addListener((obs, old, value) -> {
                applyKind.run();
                requote.run();
            });
        }
        applyKind.run();
        requote.run();
        if (presetClient != null && presetDeceased == null) {
            client.setValue(presetClient);
        }
        if (presetDeceased != null) {
            // Desde Planes: el difunto ya está en un plan; se elige "Contrato · plan", el titular y el difunto
            async("sale-deceased-plan", () -> contractService.planOfDeceased(presetDeceased.id()), match -> {
                if (match.isEmpty()) {
                    planNote.setText("El difunto no está en un plan activo: elija el tipo de venta.");
                    return;
                }
                wantedDeceased[0] = presetDeceased.id();
                for (Toggle toggle : kindGroup.getToggles()) {
                    if (toggle.getUserData() == SaleKind.PLAN) {
                        toggle.setSelected(true);
                    }
                }
                planNote.setText(match.get().note());
                client.setValue(match.get().client()); // carga el plan y elige al difunto
            }, failure -> planNote.setText(""));
        } else if (presetKind != null) {
            loadBoard[0].run();
        }

        if (presetClient != null && presetDeceased == null) {
            aff.preset(presetClient);
        }

        Dialog<ButtonType> dialog = formDialog("Nuevo contrato", "Los campos con * son obligatorios.", form, 900);
        opened[0] = dialog;
        FormShell.page(dialog, "Contratos y ventas");
        FormShell.money(base, initial, aff.fee);
        // Resumen siempre visible a la derecha: conceptos calculados y el total en la tarjeta oscura
        VBox totalCard = new VBox(label("TOTAL", "form-summary-title"), quoteTotal);
        totalCard.getStyleClass().add("form-total");
        // El total no dice nada hasta que se elige qué se va a registrar
        Label summaryEmpty = note("Elija en el paso 1 qué va a registrar; aquí se irá sumando el total.");
        VBox summary = new VBox(12, label("Resumen y pago", "form-side-title"), summaryEmpty, quoteHint, totalCard, quoteBox);
        // Con "Plan exequial" la derecha muestra la vista previa del contrato del plan en vez del total
        VBox side = new VBox(summary, aff.side);
        showSide[0] = () -> {
            boolean plan = isAffiliation(kindGroup);
            boolean chosen = plan || kindOf(kindGroup) != null;
            summary.setVisible(!plan);
            summary.setManaged(!plan);
            aff.side.setVisible(plan);
            aff.side.setManaged(plan);
            totalCard.setVisible(chosen);
            totalCard.setManaged(chosen);
            summaryEmpty.setText(chosen ? "" : "Elija en el paso 1 qué va a registrar; aquí se irá sumando el total.");
        };
        showSide[0].run();
        aff.onChange = () -> {
            aff.refresh();
            FormShell.refreshSteps(dialog);
        };
        FormShell.steps(dialog, side,
                new FormShell.Step("Tipo", "Tipo de venta"),
                new FormShell.Step("Cliente", "Cliente titular"),
                new FormShell.Step("Plan y beneficiarios", "Plan exequial", "Beneficiarios"),
                new FormShell.Step("Cliente y difunto", "Cliente y difunto"),
                new FormShell.Step("Fallecimiento y certificado", "Fallecimiento y certificado"),
                new FormShell.Step("Servicio", "Servicio"),
                new FormShell.Step("Fuera del plan", "Fuera del plan"),
                new FormShell.Step("Sala y cementerio", "Sala de velación", "Cementerio"),
                new FormShell.Step("Productos y pago", "Productos", "Abono"));
        // "Plan exequial" solo pasa por Cliente y Plan y beneficiarios (la vista previa queda a la derecha). Registrar difunto del plan
        // no pasa por Servicio (el plan lo cubre) y llena el fallecimiento solo si la persona no está en Difuntos;
        // los demás no tienen "Fuera del plan". La venta no lleva sala ni cementerio.
        FormShell.stepRules(dialog, step -> {
            boolean plan = isAffiliation(kindGroup);
            SaleKind kind = kindOf(kindGroup);
            if ("Tipo".equals(step)) {
                return false;
            }
            if (List.of("Cliente", "Plan y beneficiarios").contains(step)) {
                return !plan;
            }
            if (plan) {
                return true;
            }
            return switch (step) {
                case "Fallecimiento y certificado" -> kind != SaleKind.PLAN
                        || (planPerson[0] != null && planPerson[0].registered());
                case "Servicio" -> kind == SaleKind.PLAN || kind == SaleKind.PRODUCT;
                case "Fuera del plan" -> kind != SaleKind.PLAN;
                case "Sala y cementerio" -> kind == SaleKind.PRODUCT;
                default -> false;
            };
        }, step -> {
            SaleKind kind = kindOf(kindGroup);
            if ("Tipo".equals(step)) {
                return kind == null && !isAffiliation(kindGroup) ? "Elija qué va a registrar." : null;
            }
            if (isAffiliation(kindGroup)) {
                return aff.check(step);
            }
            if ("Fallecimiento y certificado".equals(step)) {
                if (deathDate.getValue() == null || deathType.getValue() == null) {
                    return "Escriba la fecha de defunción y el tipo de muerte.";
                }
                if (authority.getEditor().getText().isBlank() || certificateNumber.getText().isBlank()
                        || issued.getValue() == null) {
                    return "Escriba quién expide el certificado, su número y la fecha de expedición.";
                }
                return certificateFile[0] == null ? "Adjunte el certificado de defunción (PDF o imagen)." : null;
            }
            if (!"Cliente y difunto".equals(step) || kind == null) {
                return null;
            }
            if (client.getValue() == null) {
                return kind == SaleKind.PENSION ? "Elija la entidad que paga el servicio."
                        : kind == SaleKind.PLAN ? "Elija el titular del plan." : "Elija el cliente.";
            }
            if (kind == SaleKind.PRODUCT) {
                return null; // la venta solo necesita el cliente
            }
            if (kind == SaleKind.PENSION && !isEntity(client.getValue())) {
                return "El contrato con pensión solo es para entidades (empresa o fondo de pensiones).";
            }
            if (kind == SaleKind.PLAN) {
                if (board[0] == null) {
                    return "Este cliente no tiene un plan exequial activo. Para afiliarlo, elija «Plan exequial».";
                }
                if (planPerson[0] == null) {
                    return "Elija la persona del plan que falleció.";
                }
            } else if (deceased.getValue() == null) {
                return "Busque y elija el difunto del servicio. Si no aparece, primero regístrelo en la pestaña Difuntos.";
            }
            return salesperson.getValue() == null ? "Elija el vendedor que gestiona el contrato." : null;
        });
        // En el contrato con plan el paso del cliente se lee "Titular y persona"; en la venta, solo "Cliente"
        FormShell.stepLabels(dialog, step -> {
            if (!"Cliente y difunto".equals(step)) {
                return step;
            }
            SaleKind kind = kindOf(kindGroup);
            return kind == SaleKind.PLAN ? "Titular y persona" : kind == SaleKind.PRODUCT ? "Cliente" : step;
        });
        boolean[] savedPlan = new boolean[1];
        runForm(dialog, form, "Registrar", () -> {
            savedPlan[0] = isAffiliation(kindGroup);
            if (savedPlan[0]) {
                return aff.save();
            }
            ContractData sale = data.apply(true);
            if (sale.kind() != SaleKind.PLAN) {
                return contractService.register(sale);
            }
            ContractService.PlanPerson person = planPerson[0];
            if (person == null) {
                throw new InvalidDataException("Elija la persona del plan que falleció.");
            }
            DeceasedData dead = person.registered() ? null : new DeceasedData(false, person.firstName(),
                    person.lastName(), person.document(), birth.getValue(), deathDate.getValue(), deathType.getValue(),
                    cause.getValue(), sale.clientId(), sale.planId());
            DeathDocumentData certificate = person.registered() ? null : new DeathDocumentData(
                    DocumentType.MEDICAL_CERTIFICATE, authority.getEditor().getText(), certificateNumber.getText(),
                    issued.getValue(), certificateFile[0], null);
            return planDeathService.register(sale, dead, certificate);
        }, id -> {
            if (savedPlan[0]) {
                Notice.success(window(), MODULE_NAME, aff.savedTitle(), aff.savedMessage(id));
                return;
            }
            showToast("Venta N.° %04d registrada.".formatted(id));
            loadOverview(id);
            savedFromLink("Venta N.° %04d registrada. La encuentra en la pestaña Contratos y ventas.".formatted(id));
        });
    }

    /** La tarjeta elegida en el paso 1 es "Plan exequial" (afiliar o actualizar el plan). */
    private static boolean isAffiliation(ToggleGroup group) {
        Toggle toggle = group.getSelectedToggle();
        return toggle != null && toggle.getUserData() == AFFILIATION;
    }

    @SuppressWarnings("unchecked")
    private static Dialog<ButtonType>[] newDialogHolder() {
        return (Dialog<ButtonType>[]) new Dialog[1];
    }

    private static SaleKind kindOf(ToggleGroup group) {
        Toggle toggle = group.getSelectedToggle();
        return toggle != null && toggle.getUserData() instanceof SaleKind kind ? kind : null;
    }

    /** Oculta o muestra un campo del formulario con su título (la caja que lo contiene). */
    private static void showField(Node input, boolean shown) {
        Node box = input.getParent() != null && input.getParent().getStyleClass().contains("form-field")
                ? input.getParent() : input;
        box.setVisible(shown);
        box.setManaged(shown);
    }

    /**
     * Plan del titular en "Contrato · plan": tarjeta oscura con el plan, su estado y "Cambiar plan", y
     * debajo las personas del plan. Solo se puede elegir a quien ya está registrado en Difuntos.
     */
    private void drawBoard(VBox box, ContractService.PlanBoard b, ContractService.PlanPerson[] chosen, Long wanted,
                           Runnable changed, Runnable changePlan) {
        box.getChildren().clear();
        if (b == null) {
            box.getChildren().add(note("Este cliente no tiene un plan exequial activo. Para afiliarlo, elija "
                    + "«Plan exequial» en el paso 1; si el servicio no es con plan, elija «Particular»."));
            return;
        }
        Label title = label(b.planName() + " · " + b.code(), "plan-board-title");
        HBox chips = new HBox(6, chip(b.upToDate() ? "Al día" : "Con cuotas atrasadas", b.upToDate()),
                chip(b.waitingDone() ? "Carencia cumplida"
                        : "En carencia hasta " + (b.waitingEnds() == null ? "—" : DATE.format(b.waitingEnds())),
                        b.waitingDone()));
        HBox head = new HBox(10, title, chips);
        head.setAlignment(Pos.CENTER_LEFT);
        Label sub = label(pesos(b.monthlyFee()) + " / mes · desde "
                + (b.startDate() == null ? "—" : DATE.format(b.startDate())) + " · " + b.beneficiaries() + " de "
                + b.beneficiaryLimit() + " beneficiarios", "plan-board-sub");
        Button change = new Button("⇄  Cambiar plan");
        change.getStyleClass().add("plan-board-change");
        change.setOnAction(e -> changePlan.run());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        StackPane icon = new StackPane(ModuleIcons.of("PLANES"));
        icon.getStyleClass().add("plan-board-icon");
        HBox card = new HBox(14, icon, new VBox(4, head, sub), gap, change);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("plan-board-card");

        Label caption = label("¿QUIÉN FALLECIÓ? * · PERSONAS DE ESTE PLAN", "field-label");
        GridPane people = new GridPane();
        people.setHgap(10);
        people.setVgap(10);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(50);
            people.getColumnConstraints().add(constraints);
        }
        ToggleGroup group = new ToggleGroup();
        for (int i = 0; i < b.people().size(); i++) {
            ContractService.PlanPerson p = b.people().get(i);
            String detail = p.role() + (p.document() == null ? "" : " · Doc. " + p.document())
                    + (p.deathDate() == null ? "" : " · falleció " + DATE.format(p.deathDate()));
            // En carencia o con cuotas atrasadas el plan no cubre servicios: se ven las personas, pero no se escogen
            boolean selectable = p.selectable() && b.waitingDone() && b.upToDate();
            String state = p.hasContract() ? "Ya tiene contrato" : !b.waitingDone() ? "En carencia"
                    : !b.upToDate() ? "Cuotas atrasadas"
                    : p.registered() ? "Ya está en Difuntos" : "Cubierto";
            Label stateChip = label(state, selectable ? "person-card-chip-on" : "person-card-chip");
            stateChip.setMinWidth(Region.USE_PREF_SIZE);
            VBox text = new VBox(2, label(p.name(), "person-card-name"), label(detail, "person-card-detail"));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox content = new HBox(10, text, spacer, stateChip);
            content.setAlignment(Pos.CENTER_LEFT);
            ToggleButton person = new ToggleButton();
            person.setGraphic(content);
            person.getStyleClass().add("person-card");
            person.setMaxWidth(Double.MAX_VALUE);
            person.setMinHeight(PERSON_HEIGHT);
            person.setPrefHeight(PERSON_HEIGHT);
            person.setMaxHeight(PERSON_HEIGHT);
            content.prefWidthProperty().bind(person.widthProperty().subtract(30));
            person.setDisable(!selectable);
            person.setToggleGroup(group);
            person.setUserData(p);
            if (selectable && wanted != null && wanted.equals(p.deceasedId())) {
                person.setSelected(true);
                chosen[0] = p;
            }
            people.add(person, i % 2, i / 2);
        }
        group.selectedToggleProperty().addListener((obs, old, toggle) -> {
            chosen[0] = toggle == null ? null : (ContractService.PlanPerson) toggle.getUserData();
            changed.run();
        });
        box.getChildren().addAll(card, caption, people);
        if (!b.waitingDone()) {
            box.getChildren().add(note("El plan sigue en carencia hasta el "
                    + (b.waitingEnds() == null ? "—" : DATE.format(b.waitingEnds()))
                    + ": todavía no cubre servicios. Si el servicio se hace ahora, elija «Particular»."));
        } else if (!b.upToDate()) {
            box.getChildren().add(note("El plan tiene cuotas atrasadas: debe ponerse al día en Afiliados a planes "
                    + "antes de usar sus beneficios. Si el servicio se hace ahora, elija «Particular»."));
        } else {
            box.getChildren().add(note("Solo salen las personas de este plan. Si la persona no está en Difuntos, "
                    + "en el paso siguiente se registran los datos de la muerte y el certificado."));
        }
    }

    private static Label chip(String text, boolean ok) {
        Label chip = label(text, ok ? "plan-board-chip-ok" : "plan-board-chip-warn");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    /** "Cambiar plan" desde el titular del contrato: normal ⇄ premium con la cuota de Parámetros. */
    private void openChangePlanDialog(ContractService.PlanBoard b, Runnable reload) {
        async("sale-plan-offers", planService::planOffers, offers -> {
            PlanOffer current = offerOf(offers, b.planName());
            PlanOfferCard now = new PlanOfferCard(b.planName(), pesos(b.monthlyFee()) + " / mes",
                    current == null ? List.of() : current.includes());
            ComboBox<String> name = new ComboBox<>();
            name.getItems().setAll(offers.stream().map(PlanOffer::name)
                    .filter(n -> !n.equalsIgnoreCase(b.planName())).toList());
            FormShell.choose(name);
            name.getStyleClass().add("form-combo");
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
            HBox compare = new HBox(12, captioned("PLAN ACTUAL", now), captioned("NUEVO PLAN", next));
            compare.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
            Form form = new Form()
                    .section("Plan", 2)
                    .wide(null, compare)
                    .field("NUEVO PLAN *", name)
                    .field("NUEVA CUOTA MENSUAL (COP) *", new VBox(4, fee, feeHint))
                    .note(note("Los beneficiarios, las cuotas ya pagadas y la carencia se conservan. "
                            + "Las cuotas que se paguen desde ahora usan la nueva cuota."));
            Dialog<ButtonType> dialog = formDialog("Cambiar plan", b.code() + " · " + b.planName() + ".", form, 640);
            FormShell.window(dialog, "Planes exequiales");
            FormShell.money(fee);
            runForm(dialog, form, "Cambiar plan", () -> {
                planService.changePlan(b.planId(), name.getValue());
                return b.planId();
            }, planId -> reload.run());
        }, failure -> showError(message(failure, "No se pudieron cargar los tipos de plan.")));
    }

    private static PlanOffer offerOf(List<PlanOffer> offers, String name) {
        return name == null ? null : offers.stream().filter(o -> o.name().equalsIgnoreCase(name)).findFirst()
                .orElse(null);
    }

    private static String feeText(BigDecimal fee) {
        return fee == null ? null : pesos(fee) + " / mes";
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

    /** Botón "Adjuntar" con el nombre del archivo elegido (PDF o imagen, hasta 10 MB). */
    private HBox certificateChooser(Path[] selected) {
        Label fileName = label("Ningún archivo (PDF, PNG o JPG, máx. 10 MB)", "file-name");
        fileName.setWrapText(true);
        Button attach = new Button("Adjuntar…");
        attach.getStyleClass().add("secondary-button");
        attach.setMinWidth(Region.USE_PREF_SIZE);
        attach.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Seleccionar el certificado de defunción");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF o imagen",
                    FileStorageService.EXTENSIONS.stream().map(x -> "*." + x).toList()));
            java.io.File file = chooser.showOpenDialog(window());
            if (file != null) {
                selected[0] = file.toPath();
                fileName.setText(file.getName());
            }
        });
        HBox box = new HBox(10, attach, fileName);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("file-box");
        return box;
    }

    private static VBox captioned(String caption, Node node) {
        VBox box = new VBox(4, label(caption, "field-label"), node);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    // ---------- Plan exequial (afiliar o actualizar) ----------

    /**
     * Al registrar un cliente y aceptar afiliarlo: la ventana abre directo en el plan y los beneficiarios,
     * con el cliente ya elegido (sin pasar por el tipo de contrato ni por el cliente).
     */
    private void openAffiliation(Option client) {
        AffiliationPart aff = new AffiliationPart(offers());
        Form form = new Form();
        aff.addMain(form);
        aff.preset(client);
        Dialog<ButtonType> dialog = formDialog("Afiliar a plan exequial", client.label() + ": elija el plan y agregue "
                + "sus beneficiarios. Los campos con * son obligatorios.", form, 900);
        FormShell.page(dialog, "Clientes");
        FormShell.money(aff.fee);
        aff.onChange = () -> {
            aff.refresh();
            FormShell.refreshSteps(dialog);
        };
        FormShell.steps(dialog, aff.side,
                new FormShell.Step("Cliente", "Cliente titular"),
                new FormShell.Step("Plan y beneficiarios", "Plan exequial", "Beneficiarios"));
        FormShell.stepRules(dialog, "Cliente"::equals, aff::check);
        runForm(dialog, form, "Registrar", aff::save,
                planId -> Notice.success(window(), MODULE_NAME, aff.savedTitle(), aff.savedMessage(planId)));
    }

    /**
     * Pasos de "Plan exequial" dentro de "Nuevo contrato": si el cliente no tiene plan, se afilia
     * (cliente ya registrado o nuevo, plan y beneficiarios); si ya lo tiene, se ve su plan y se
     * cambia el tipo o se agregan y quitan personas. Todo se guarda junto al final.
     */
    private final class AffiliationPart {
        final ToggleButton existing = new ToggleButton("Ya está registrado");
        final ToggleButton fresh = new ToggleButton("Cliente nuevo");
        final HBox modes = new HBox(existing, fresh);
        final SearchField<Option> holder = new SearchField<>("Buscar por nombre o documento", "clientes",
                contractService::searchClients, Option::label, Option::detail);
        final ComboBox<ClientType> clientType = new ComboBox<>();
        final ComboBox<String> documentType = new ComboBox<>();
        final TextField document = new TextField();
        final TextField names = new TextField();
        final TextField lastnames = new TextField();
        final TextField phone = new TextField();
        final TextField email = new TextField();
        final TextField address = new TextField();
        final String[] planName = new String[1];
        final TextField fee = new TextField();
        final Label feeHint = wrapped("", "dialog-hint");
        final Label planHint = note("");
        final HBox planCards = new HBox(14);
        final List<PlanOfferCard> cards = new ArrayList<>();
        final List<PlanOffer> offers;
        final DatePicker start = new DatePicker(today());
        final Label startHint = wrapped("", "dialog-hint");
        final VBox startBox = new VBox(4, start, startHint);
        final List<FuneralPlanService.NewBeneficiary> added = new ArrayList<>();
        final List<String> surcharges = new ArrayList<>();
        final List<Long> removed = new ArrayList<>();
        final VBox beneficiaryList = new VBox(8);
        final TextField benName = new TextField();
        final TextField benLastname = new TextField();
        final TextField benDocument = new TextField();
        final ComboBox<Relationship> relationship = new ComboBox<>();
        final Button addBeneficiary = new Button("+ Agregar");
        final Label beneficiaryError = note("");
        final VBox preview = new VBox(4);
        final Label feeTotal = label("—", "form-summary-value");
        final VBox side;
        /** Plan activo del titular elegido; null si no tiene (entonces se afilia). */
        FuneralPlanService.PlanDetail current;
        /** Titular cuyo plan se está buscando, para no pintar una respuesta vieja. */
        Option loading;
        Runnable onChange = () -> { };

        AffiliationPart(List<PlanOffer> offers) {
            this.offers = offers;
            modes.getStyleClass().addAll("segmented", "form-segmented");
            modes.setMaxWidth(Region.USE_PREF_SIZE);
            ToggleGroup clientMode = new ToggleGroup();
            for (ToggleButton mode : List.of(existing, fresh)) {
                mode.getStyleClass().add("segment");
                mode.setToggleGroup(clientMode);
            }
            existing.setSelected(true);
            clientMode.selectedToggleProperty().addListener((obs, old, toggle) -> {
                if (toggle == null) {
                    old.setSelected(true);
                    return;
                }
                loadPlan();
            });
            clientType.getItems().setAll(ClientType.values());
            clientType.setConverter(converter(ClientType::getDisplayName, ""));
            FormShell.choose(clientType);
            documentType.setConverter(converter(code -> CLIENT_DOCUMENTS.getOrDefault(code, code), ""));
            FormShell.choose(documentType);
            document.setPromptText("Solo números");
            FormShell.document(document, documentType::getValue, 11);
            names.setPromptText("Nombres");
            lastnames.setPromptText("Apellidos");
            phone.setPromptText("3001234567");
            FormShell.phone(phone);
            email.setPromptText("nombre@gmail.com");
            address.setPromptText("Opcional");
            for (ComboBox<?> box : List.of(clientType, documentType)) {
                box.getStyleClass().add("form-combo");
            }
            clientType.valueProperty().addListener((obs, old, type) -> {
                boolean corporate = type != null && type.isCorporate();
                documentType.getItems().setAll(corporate ? List.of(ClientService.NIT) : ClientService.NATURAL_DOCUMENT_TYPES);
                documentType.setValue(corporate ? ClientService.NIT : null);
                names.setPromptText(corporate ? "Razón social" : "Nombres");
                lastnames.setDisable(corporate);
            });

            fee.setPromptText("Se llena al elegir el plan");
            lockFee(fee);
            start.setEditable(false); // se elige en el calendario; el texto escrito no se interpreta
            start.setConverter(dateConverter());
            for (PlanOffer offer : offers) {
                PlanOfferCard card = new PlanOfferCard(offer.name(), feeText(offer.monthlyFee()), offer.includes());
                HBox.setHgrow(card, Priority.ALWAYS);
                card.setPrefWidth(10);
                card.setOnMouseClicked(e -> pickPlan(offer));
                cards.add(card);
                planCards.getChildren().add(card);
            }

            benName.setPromptText("Nombres");
            benLastname.setPromptText("Apellidos");
            benDocument.setPromptText("Solo números");
            FormShell.document(benDocument, null, 11);
            relationship.getItems().setAll(Relationship.values());
            relationship.setConverter(converter(Relationship::getDisplayName, ""));
            FormShell.choose(relationship);
            relationship.getStyleClass().add("form-combo");
            addBeneficiary.getStyleClass().add("small-dark-button");
            addBeneficiary.setMinWidth(Region.USE_PREF_SIZE);
            addBeneficiary.setOnAction(e -> addBeneficiary());

            preview.getStyleClass().add("contract-preview");
            VBox totalCard = new VBox(label("CUOTA MENSUAL", "form-summary-title"), feeTotal);
            totalCard.getStyleClass().add("form-total");
            side = new VBox(12, label("Vista previa", "form-side-title"), preview, totalCard);

            holder.valueProperty().addListener((obs, old, value) -> loadPlan());
            for (TextField field : List.of(names, lastnames, document, email, fee)) {
                field.textProperty().addListener((obs, old, value) -> refresh());
            }
            documentType.valueProperty().addListener((obs, old, value) -> refresh());
            start.valueProperty().addListener((obs, old, value) -> refresh());
            refresh();
        }

        /** Secciones del cliente, el plan y los beneficiarios. */
        void addMain(Form form) {
            form.section("Cliente titular", 3)
                    .wide(null, modes)
                    .wide("TITULAR *", holder.node())
                    .field("TIPO DE CLIENTE *", clientType)
                    .field("TIPO DE DOCUMENTO *", documentType)
                    .field("N.° DE DOCUMENTO *", document)
                    .field("NOMBRES O RAZÓN SOCIAL *", names)
                    .field("APELLIDOS *", lastnames)
                    .field("TELÉFONO *", phone)
                    .field("CORREO *", email)
                    .wideFrom("DIRECCIÓN", address)
                    .section("Plan exequial", 2)
                    .wide(null, planCards)
                    .note(planHint)
                    .field("CUOTA MENSUAL (COP) *", new VBox(4, fee, feeHint))
                    .field("FECHA DE INICIO *", startBox)
                    .section("Beneficiarios", 4)
                    .wide(null, beneficiaryList)
                    .field("NOMBRES", benName)
                    .field("APELLIDOS", benLastname)
                    .field("DOCUMENTO", benDocument)
                    .field("PARENTESCO", relationship)
                    .note(new HBox(addBeneficiary))
                    .note(beneficiaryError)
                    .note(note("Los recargos (parentesco no directo o por encima del tope) salen de Parámetros."));
        }

        void preset(Option client) {
            if (client != null) {
                holder.setValue(client); // desde Clientes: ya está registrado
            }
        }

        boolean updating() {
            return current != null;
        }

        /** Por qué no se puede salir del paso (null si se puede). */
        String check(String step) {
            if ("Cliente".equals(step)) {
                if (existing.isSelected() && holder.getValue() == null) {
                    return "Busque y elija el cliente titular, o elija «Cliente nuevo».";
                }
                if (existing.isSelected() && loading != null) {
                    return "Buscando el plan del cliente…";
                }
            }
            if ("Plan y beneficiarios".equals(step) && planName[0] == null) {
                return "Elija el plan: Normal o Premium.";
            }
            return null;
        }

        /** Afilia o guarda los cambios del plan; devuelve el id del plan. */
        Long save() {
            if (planName[0] == null) {
                throw new InvalidDataException("Elija el plan: Normal o Premium.");
            }
            if (current != null) {
                Long planId = current.card().id();
                planService.updatePlan(planId, planName[0], List.copyOf(removed), List.copyOf(added));
                return planId;
            }
            ClientData newClient = fresh.isSelected() ? new ClientData(clientType.getValue(), documentType.getValue(),
                    document.getText(), names.getText(), lastnames.isDisable() ? null : lastnames.getText(),
                    phone.getText(), email.getText(), address.getText()) : null;
            return planService.affiliate(new FuneralPlanService.AffiliationData(
                    fresh.isSelected() ? null : idOf(holder.getValue()), newClient, planName[0],
                    start.getValue(), List.copyOf(added))).getId();
        }

        String savedTitle() {
            return current != null ? "Plan actualizado" : "Plan afiliado";
        }

        String savedMessage(Long planId) {
            return (current != null ? "Se guardaron los cambios del plan " : "Se afilió el plan ")
                    + FuneralPlanService.code(planId) + ". Lo encuentra en la pestaña Afiliados a planes.";
        }

        /** Con el titular elegido se busca si ya tiene plan: si lo tiene, se actualiza en vez de afiliar. */
        private void loadPlan() {
            current = null;
            removed.clear();
            Option client = fresh.isSelected() ? null : holder.getValue();
            loading = client;
            if (client == null) {
                pickPlan(null);
                onChange.run();
                return;
            }
            refresh();
            async("affiliation-current-plan", () -> planService.activePlanOf(client.id()), found -> {
                if (client != loading) {
                    return;
                }
                loading = null;
                current = found.orElse(null);
                added.clear();
                surcharges.clear();
                pickPlan(current == null ? null : offerOf(offers, current.card().planName()));
                onChange.run();
            }, failure -> {
                if (client == loading) {
                    loading = null;
                    planHint.setText(message(failure, "No se pudo revisar si el cliente ya tiene plan."));
                    onChange.run();
                }
            });
        }

        private void pickPlan(PlanOffer offer) {
            planName[0] = offer == null ? null : offer.name();
            for (int i = 0; i < cards.size(); i++) {
                cards.get(i).setSelected(offer != null && offers.get(i) == offer);
            }
            if (offer == null) {
                fee.clear();
                feeHint.setText("");
            } else {
                fillFee(fee, feeHint, offer);
            }
            refresh();
        }

        private void addBeneficiary() {
            beneficiaryError.setText("");
            String doc = benDocument.getText() == null ? "" : benDocument.getText().trim();
            if (benName.getText().isBlank() || benLastname.getText().isBlank()) {
                beneficiaryError.setText("Escriba los nombres y los apellidos del beneficiario.");
                return;
            }
            if (!doc.matches("\\d{5,11}")) {
                beneficiaryError.setText("El documento del beneficiario debe tener entre 5 y 11 dígitos.");
                return;
            }
            if (relationship.getValue() == null) {
                beneficiaryError.setText("Seleccione el parentesco con el titular.");
                return;
            }
            boolean repeated = added.stream().anyMatch(b -> b.documentNumber().equals(doc)) || (current != null
                    && current.beneficiaries().stream().anyMatch(b -> doc.equals(b.documentNumber())
                    && !removed.contains(b.id())));
            if (repeated) {
                beneficiaryError.setText("Ya hay un beneficiario con ese documento en el plan.");
                return;
            }
            FuneralPlanService.NewBeneficiary b = new FuneralPlanService.NewBeneficiary(benName.getText().trim(),
                    benLastname.getText().trim(), doc, relationship.getValue());
            boolean corporate = current != null ? current.card().corporate()
                    : fresh.isSelected() && clientType.getValue() != null && clientType.getValue().isCorporate();
            long kept = current == null ? 0 : current.beneficiaries().size() - removed.size();
            long position = kept + added.size() + 1L;
            async("affiliation-surcharge", () -> planService.previewNewSurcharge(position, corporate, b.relationship()),
                    surcharge -> {
                        added.add(b);
                        surcharges.add(surcharge.applies() ? "Recargo " + pesos(surcharge.total()) : "Sin recargo");
                        benName.clear();
                        benLastname.clear();
                        benDocument.clear();
                        relationship.setValue(null);
                        refresh();
                    }, failure -> beneficiaryError.setText(message(failure, "No se pudo calcular el recargo.")));
        }

        /** Muestra los campos que tocan y vuelve a dibujar la lista de personas y la vista previa. */
        void refresh() {
            boolean isNew = fresh.isSelected();
            showField(holder.node(), !isNew);
            for (Node n : List.of(clientType, documentType, document, names, lastnames, phone, email, address)) {
                showField(n, isNew);
            }
            showField(startBox, current == null);
            String holderName = isNew ? (names.getText() + " " + lastnames.getText()).trim()
                    : holder.getValue() == null ? "" : holder.getValue().label();
            String holderDoc = isNew ? (documentType.getValue() == null ? "" : documentType.getValue() + " ")
                    + document.getText() : holder.getValue() == null ? "" : orEmpty(holder.getValue().detail());
            if (current != null) {
                String was = current.card().planName();
                planHint.setText("Ya tiene el " + was + " · " + current.card().code() + ". Puede cambiarlo de tipo, "
                        + "agregar o quitar personas; la carencia y las cuotas pagadas se conservan. Las cuotas que "
                        + "se paguen desde ahora usan la cuota del plan elegido.");
            } else {
                planHint.setText(loading != null ? "Buscando si el cliente ya tiene plan…" : "");
            }
            // El titular va antes que los demás; luego los que ya están en el plan y los nuevos
            beneficiaryList.getChildren().clear();
            beneficiaryList.getChildren().add(personRow(holderName.isBlank() ? "Titular sin elegir" : holderName,
                    (holderDoc.isBlank() ? "" : holderDoc.trim() + " · ") + "Titular", null, null));
            if (current != null) {
                for (FuneralPlanService.BeneficiaryInfo b : current.beneficiaries()) {
                    if (removed.contains(b.id())) {
                        continue;
                    }
                    String detail = "Doc. " + b.documentNumber() + " · " + b.relationshipLabel();
                    beneficiaryList.getChildren().add(personRow(b.fullName(), detail,
                            b.isDeceased() ? "Difunto del plan" : null, b.isDeceased() ? null : () -> {
                                removed.add(b.id());
                                refresh();
                            }));
                }
            }
            for (int i = 0; i < added.size(); i++) {
                FuneralPlanService.NewBeneficiary b = added.get(i);
                int index = i;
                beneficiaryList.getChildren().add(personRow(PersonNames.titleCase(b.name() + " " + b.lastname()),
                        "Doc. " + b.documentNumber() + " · " + b.relationship().getDisplayName() + " · nuevo",
                        surcharges.get(i), () -> {
                            added.remove(index);
                            surcharges.remove(index);
                            refresh();
                        }));
            }
            LocalDate from = start.getValue();
            startHint.setText(current == null && from == null ? "Ingrese la fecha de inicio." : "");
            String money = feeMoney(fee.getText());
            feeTotal.setText(money == null ? "—" : money);
            if (current != null) {
                drawUpdatePreview();
            } else {
                drawPreview(preview, holderName, holderDoc, isNew ? email.getText() : null, planName[0], fee.getText(),
                        from, added);
            }
        }

        /** Vista previa de los cambios a un plan que ya existe. */
        private void drawUpdatePreview() {
            FuneralPlanService.PlanCard card = current.card();
            preview.getChildren().clear();
            Label brand = label(BrandTheme.brand() == null ? "" : BrandTheme.brand().commercialName(),
                    "contract-preview-brand");
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            HBox head = new HBox(brand, gap, label(card.code() + " · " + DATE.format(today()), "contract-preview-meta"));
            head.setAlignment(Pos.CENTER_LEFT);
            head.getStyleClass().add("contract-preview-head");
            boolean changed = planName[0] != null && !planName[0].equalsIgnoreCase(card.planName());
            preview.getChildren().addAll(head, wrapped("ACTUALIZACIÓN DEL PLAN EXEQUIAL", "contract-preview-title"),
                    label("TITULAR", "contract-preview-section"), previewRow("Nombre", card.holderName()),
                    label("PLAN", "contract-preview-section"),
                    previewRow("Tipo", changed ? card.planName() + " → " + planName[0] : card.planName()),
                    previewRow("Cuota mensual", changed ? feeMoney(fee.getText()) : pesos(card.monthlyFee())),
                    label("PERSONAS", "contract-preview-section"),
                    previewRow(card.holderName(), "Titular"));
            for (FuneralPlanService.BeneficiaryInfo b : current.beneficiaries()) {
                preview.getChildren().add(previewRow(b.fullName(),
                        removed.contains(b.id()) ? "Se quita" : b.relationshipLabel()));
            }
            for (FuneralPlanService.NewBeneficiary b : added) {
                preview.getChildren().add(previewRow(PersonNames.titleCase(b.name() + " " + b.lastname()),
                        b.relationship().getDisplayName() + " · nuevo"));
            }
            if (!changed && removed.isEmpty() && added.isEmpty()) {
                preview.getChildren().add(label("Sin cambios todavía", "contract-preview-blank"));
            }
        }
    }

    /** Fila de una persona del plan (el titular primero). onRemove en null no muestra la ✕. */
    private static HBox personRow(String name, String detail, String surcharge, Runnable onRemove) {
        VBox text = new VBox(2, label(name, "person-card-name"), label(detail, "person-card-detail"));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(10, text, gap);
        if (surcharge != null) {
            Label chip = label(surcharge, surcharge.startsWith("Sin") ? "person-card-chip-on" : "plan-board-chip-warn");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(chip);
        }
        if (onRemove != null) {
            Button remove = new Button("✕");
            remove.getStyleClass().add("chip-remove");
            remove.setOnAction(e -> onRemove.run());
            row.getChildren().add(remove);
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("person-row");
        return row;
    }

    /** Vista previa del contrato de afiliación: se va llenando con cada paso. */
    private static void drawPreview(VBox box, String holder, String document, String email, String plan,
                                    String fee, LocalDate start, List<FuneralPlanService.NewBeneficiary> added) {
        box.getChildren().clear();
        // Se muestran como quedarán guardados: con mayúscula inicial
        holder = PersonNames.titleCase(holder);
        Label brand = label(BrandTheme.brand() == null ? "" : BrandTheme.brand().commercialName(), "contract-preview-brand");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(brand, gap, label("N.° — · " + DATE.format(LocalDate.now(AccessLogService.APP_ZONE)),
                "contract-preview-meta"));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("contract-preview-head");
        Label title = wrapped("CONTRATO DE AFILIACIÓN A PLAN EXEQUIAL", "contract-preview-title");
        box.getChildren().addAll(head, title, label("TITULAR", "contract-preview-section"),
                previewRow("Nombre", holder), previewRow("Documento", document));
        if (email != null) {
            box.getChildren().add(previewRow("Correo", email));
        }
        String money = feeMoney(fee);
        box.getChildren().addAll(label("PLAN", "contract-preview-section"), previewRow("Tipo", plan),
                previewRow("Cuota mensual", money),
                previewRow("Fecha de inicio", start == null ? null : DATE.format(start)),
                label("BENEFICIARIOS", "contract-preview-section"));
        // El titular va antes que los beneficiarios
        box.getChildren().add(previewRow(holder == null || holder.isBlank() ? "Titular" : holder, "Titular"));
        for (FuneralPlanService.NewBeneficiary b : added) {
            box.getChildren().add(previewRow(PersonNames.titleCase(b.name() + " " + b.lastname()), b.relationship().getDisplayName()));
        }
        if (added.isEmpty()) {
            box.getChildren().add(label("Sin beneficiarios todavía", "contract-preview-blank"));
        }
    }

    private static HBox previewRow(String caption, String value) {
        boolean empty = value == null || value.isBlank();
        Label left = label(caption, "contract-preview-label");
        left.setMinWidth(Region.USE_PREF_SIZE);
        Label right = label(empty ? "________" : value, empty ? "contract-preview-blank" : "contract-preview-value");
        right.setWrapText(true);
        right.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        right.setAlignment(Pos.CENTER_RIGHT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(8, left, gap, right);
        row.getStyleClass().add("contract-preview-row");
        return row;
    }

    /** La cuota del campo ("$ 35.000" o "35000") escrita en pesos; null si está vacía. */
    private static String feeMoney(String text) {
        try {
            BigDecimal value = parseAmount(text, "La cuota");
            return value == null ? null : pesos(value);
        } catch (BusinessException notANumber) {
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Si "Nuevo contrato" se abrió desde otro módulo (Clientes o Planes), al cerrarlo el usuario vuelve
     * a ese módulo en vez de quedarse en Contratos. message, si no es null, avisa lo que se guardó.
     */
    private void savedFromLink(String message) {
        if (message != null && openedFromLink) {
            Notice.success(window(), MODULE_NAME, "Venta registrada", message);
        }
    }

    /** Documentos del cliente nuevo en la afiliación (los mismos de "Nuevo cliente"). */
    private static final Map<String, String> CLIENT_DOCUMENTS = Map.of(
            "CC", "CC · Cédula de ciudadanía",
            "CE", "CE · Cédula de extranjería",
            "PA", "PA · Pasaporte",
            ClientService.NIT, "NIT · Número de identificación tributaria");

    private static void showQuote(Quote quote, VBox box, Label total) {
        box.getChildren().setAll(linesGrid(quote.lines(), quote.total()));
        total.setText(pesos(quote.total()));
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
        FormShell.window(dialog, "Contratos y ventas");
        FormShell.money(amount);
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
        if (!Notice.confirmDanger(window(), MODULE_NAME, "¿Anular la venta N.° " + r.number() + "?",
                "La venta queda como anulada (no se borra), sus abonos se anulan en Finanzas, los productos vuelven "
                        + "al inventario y, si tiene contrato, el contrato se cancela. Esta acción no se puede deshacer.",
                "Anular venta")) {
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
        ComboBox<LocalTime> time = timeCombo(null);
        Form form = new Form()
                .section("Preparación del cuerpo", 3)
                .field("CONTRATO", readOnly(c.number()))
                .field("DIFUNTO", readOnly(orDash(c.deceasedName())))
                .field("TANATÓLOGO *", thanatopractor)
                .field("FECHA EN QUE TERMINÓ *", date)
                .field("HORA *", time)
.note(note("La preparación la registra el administrador o la secretaria."));
        Dialog<ButtonType> dialog = formDialog("Registrar preparación", "Los campos con * son obligatorios.", form, 760);
        FormShell.window(dialog, "Contratos y ventas");
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

        /** Campo que ocupa toda la fila; sin etiqueta si label es null. */
        Form wide(String label, Node input) {
            if (column > 0) {
                column = 0;
                row++;
            }
            column = 0;
            spanning(label, input, columns);
            column = 0;
            row++;
            return this;
        }

        /** Campo que ocupa desde la columna actual hasta el final de la fila. */
        Form wideFrom(String label, Node input) {
            if (column >= columns) {
                column = 0;
                row++;
            }
            spanning(label, input, columns - column);
            column = 0;
            row++;
            return this;
        }

        /** Otra cuadrícula en la misma sección, debajo de lo último que se agregó (por ejemplo, un aviso). */
        Form grid(int columns) {
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

        private void spanning(String label, Node input, int span) {
            VBox box = new VBox(input);
            if (label != null) {
                Label caption = new Label(label);
                caption.getStyleClass().add("field-label");
                box.getChildren().add(0, caption);
            }
            box.getStyleClass().add("form-field");
            if (input instanceof Region region && !(input instanceof Label)) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            grid.add(box, column, row, Math.max(span, 1), 1);
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
                // En el formulario por pasos el mensaje de abajo no se alcanza a ver: también se avisa en una ventana
                String why = message(failure, "No se pudo guardar. Revise la conexión e intente de nuevo.");
                form.error.setText(why);
                Notice.warning(window(), MODULE_NAME, "No se pudo guardar", why);
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
        box.setVisibleRowCount(8);
        box.setConverter(converter(HOUR::format, ""));
        // Al registrar no se pone una hora: el usuario la elige. Al editar se muestra la guardada.
        if (initial == null) {
            FormShell.choose(box, "Seleccione la hora");
        } else {
            box.setValue(initial);
        }
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
        Notice.warning(window(), MODULE_NAME, "Atención", message);
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
