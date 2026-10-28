package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.config.ReadOnlyWork;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.service.ClientOverviewService;
import com.example.funerariamc.service.ClientOverviewService.BeneficiaryInfo;
import com.example.funerariamc.service.ClientOverviewService.ClientDetail;
import com.example.funerariamc.service.ClientOverviewService.ClientKpis;
import com.example.funerariamc.service.ClientOverviewService.ClientSummary;
import com.example.funerariamc.service.ClientOverviewService.DeceasedInfo;
import com.example.funerariamc.service.ClientOverviewService.PlanInfo;
import com.example.funerariamc.service.ClientOverviewService.SaleInfo;
import com.example.funerariamc.service.ClientService;
import com.example.funerariamc.service.ClientService.BalanceFilter;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pantalla de Clientes (propuesta P-11 / P-11.1): tarjetas por titular con su plan exequial,
 * contratos y saldo pendiente; filtros por tipo y saldo; indicadores y un panel lateral con
 * contacto, beneficiarios (carencia RN-08), contratos y ventas, y difuntos atendidos (HU-01).
 * Un cliente no se elimina: tiene planes, contratos y ventas asociados.
 */
@Component
@Scope("prototype")
public class ClientController implements RefreshableView {

    /** Tarjetas por tanda: 4 filas de 3. Al bajar hasta el final se piden 12 más. */
    private static final int PAGE_SIZE = 12;
    private static final int COLUMNS = 3;
    private static final double DRAWER_WIDTH = 460;
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final DateTimeFormatter DATE = EmployeeController.DATE;

    private final ClientService clientService;
    private final ClientOverviewService overviewService;
    private final FxmlViewLoader viewLoader;
    private final ReadOnlyWork readOnlyWork;
    /** Espera a que el usuario deje de escribir para no consultar la BD en cada tecla. */
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    @FXML
    private StackPane root;
    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup typeGroup;
    @FXML
    private ToggleButton naturalToggle;
    @FXML
    private ToggleButton corporateToggle;
    @FXML
    private ComboBox<BalanceFilter> balanceFilter;
    @FXML
    private Label totalValue;
    @FXML
    private Label totalDetail;
    @FXML
    private Label planValue;
    @FXML
    private Label planDetail;
    @FXML
    private Label receivableValue;
    @FXML
    private Label receivableDetail;
    @FXML
    private Label newValue;
    @FXML
    private Label newDetail;
    @FXML
    private Label showingLabel;
    @FXML
    private ScrollPane cardScroll;
    @FXML
    private GridPane cardGrid;
    @FXML
    private Label emptyLabel;
    @FXML
    private Button moreButton;
    @FXML
    private Region veil;
    @FXML
    private VBox drawer;
    @FXML
    private VBox drawerBody;
    @FXML
    private HBox drawerActions;

    /** Clientes ya cargados (en el orden de las tarjetas) y su resumen. */
    private final List<Client> clients = new ArrayList<>();
    private final Map<Long, ClientSummary> summaries = new HashMap<>();
    private final Map<Long, VBox> cards = new HashMap<>();
    private long totalMatches;
    private boolean loading;
    /** Cada búsqueda nueva invalida las respuestas que aún vienen en camino. */
    private int generation;
    /** Cliente abierto en el panel lateral (null si está cerrado). */
    private Long drawerClientId;

    public ClientController(ClientService clientService, ClientOverviewService overviewService,
                            FxmlViewLoader viewLoader, ReadOnlyWork readOnlyWork) {
        this.clientService = clientService;
        this.overviewService = overviewService;
        this.viewLoader = viewLoader;
        this.readOnlyWork = readOnlyWork;
    }

    /** El menú guarda la pantalla abierta: al volver a Clientes se traen los datos nuevos. */
    @Override
    public void refresh() {
        reload();
    }

    @FXML
    private void initialize() {
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un tipo elegido
            } else {
                reload();
            }
        });

        balanceFilter.getItems().setAll(BalanceFilter.values());
        balanceFilter.setValue(BalanceFilter.ALL);
        balanceFilter.setButtonCell(balanceCell());
        balanceFilter.setCellFactory(list -> balanceCell());
        balanceFilter.valueProperty().addListener((obs, old, value) -> reload());

        searchDelay.setOnFinished(e -> reload());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        // Al llegar al final de la barra de desplazamiento se piden las siguientes 12 tarjetas.
        cardScroll.vvalueProperty().addListener((obs, old, value) -> {
            if (value.doubleValue() >= cardScroll.getVmax() - 0.02 && hasMore()) {
                loadMore();
            }
        });

        drawer.setTranslateX(DRAWER_WIDTH);
        reload();
    }

    // ---------- carga ----------

    /** Resultado de una consulta: la tanda de clientes, su resumen y los indicadores. */
    private record Batch(Page<Client> page, Map<Long, ClientSummary> summaries, ClientKpis kpis) {
    }

    /** Un filtro cambió o se guardó algo: vuelve a traer desde el principio lo que ya se veía. */
    private void reload() {
        int size = Math.min(Math.max(PAGE_SIZE, clients.size()), ClientService.MAX_PAGE_SIZE);
        query(0, size, true);
    }

    @FXML
    private void onLoadMore() {
        loadMore();
    }

    private void loadMore() {
        if (!loading) {
            query(clients.size() / PAGE_SIZE, PAGE_SIZE, false);
        }
    }

    private boolean hasMore() {
        return clients.size() < totalMatches;
    }

    /** Igual que Personal: la consulta corre en otro hilo para que la ventana no se congele. */
    private void query(int pageIndex, int size, boolean replace) {
        String text = searchField.getText();
        ClientType type = selectedType();
        BalanceFilter balance = balanceFilter.getValue();
        int requestGeneration = replace ? ++generation : generation;
        loading = true;
        showingLabel.setText("Cargando clientes…");
        Task<Batch> task = new Task<>() {
            @Override
            protected Batch call() {
                // Una sola transacción para las tarjetas, sus resúmenes y los indicadores.
                return readOnlyWork.run(() -> {
                    Page<Client> page = clientService.search(text, type, balance, pageIndex, size);
                    List<Long> ids = page.getContent().stream().map(Client::getId).toList();
                    return new Batch(page, overviewService.summaries(ids), overviewService.kpis());
                });
            }
        };
        task.setOnSucceeded(e -> {
            if (requestGeneration == generation) {
                show(task.getValue(), replace);
            }
        });
        task.setOnFailed(e -> {
            loading = false;
            showingLabel.setText("");
            showError(task.getException() instanceof BusinessException business
                    ? business.getMessage()
                    : "No se pudieron cargar los clientes. Intente de nuevo.");
        });
        start(task, "client-loader");
    }

    private void show(Batch batch, boolean replace) {
        loading = false;
        if (replace) {
            clients.clear();
            summaries.clear();
        }
        clients.addAll(batch.page().getContent());
        summaries.putAll(batch.summaries());
        totalMatches = batch.page().getTotalElements();
        renderCards();
        showKpis(batch.kpis());
        showingLabel.setText(totalMatches == 0 ? ""
                : clients.size() + " de " + count(totalMatches, "cliente", "clientes") + " · más recientes primero");

        // El panel abierto se vuelve a dibujar con los datos recién guardados.
        if (drawerClientId != null) {
            findLoaded(drawerClientId).ifPresentOrElse(this::loadDrawer, this::closeDrawer);
        }
    }

    private void showKpis(ClientKpis kpis) {
        totalValue.setText(String.valueOf(kpis.total()));
        totalDetail.setText(count(kpis.natural(), "natural", "naturales") + " · "
                + count(kpis.corporate(), "empresa", "empresas"));
        planValue.setText(String.valueOf(kpis.withActivePlan()));
        planDetail.setText(kpis.planPercent() + " % de los titulares");
        receivableValue.setText(shortPesos(kpis.receivable()));
        receivableDetail.setText(kpis.salesWithBalance() == 0 ? "sin ventas pendientes"
                : "en " + count(kpis.salesWithBalance(), "venta", "ventas") + " con abonos");
        newValue.setText(String.valueOf(kpis.newThisMonth()));
        newDetail.setText(monthLabel(ClientOverviewService.today()));
    }

    private void renderCards() {
        cardGrid.getChildren().clear();
        cards.clear();
        for (int i = 0; i < clients.size(); i++) {
            VBox card = card(clients.get(i));
            cards.put(clients.get(i).getId(), card);
            cardGrid.add(card, i % COLUMNS, i / COLUMNS);
        }
        markSelectedCard();
        boolean empty = clients.isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        moreButton.setVisible(hasMore());
        moreButton.setManaged(hasMore());
    }

    private Optional<Client> findLoaded(Long id) {
        return clients.stream().filter(c -> c.getId().equals(id)).findFirst();
    }

    private ClientType selectedType() {
        if (naturalToggle.isSelected()) {
            return ClientType.NATURAL;
        }
        return corporateToggle.isSelected() ? ClientType.CORPORATE : null;
    }

    // ---------- tarjeta ----------

    private VBox card(Client c) {
        ClientSummary summary = summaryOf(c);

        VBox names = new VBox(4, wrapped(ClientService.displayName(c), "card-name"),
                wrapped(documentLabel(c), "card-document"));
        HBox.setHgrow(names, Priority.ALWAYS);
        HBox header = new HBox(12, avatar(c, "avatar"), names, typePill(c));
        header.setAlignment(Pos.TOP_LEFT);

        GridPane info = infoGrid();
        infoRow(info, 0, "Teléfono", formatPhone(c.getPhone()));
        infoRow(info, 1, "Correo", orDash(c.getEmail()));
        infoRow(info, 2, "Cliente desde", c.getRegistrationDate() == null ? "—" : DATE.format(c.getRegistrationDate()));

        VBox card = new VBox(12, header, divider(), info, planRow(c, summary), balanceRow(summary));

        Button detail = cardButton("Ver detalle", "card-button");
        detail.setOnAction(event -> openDrawer(c));
        Button contract = cardButton("Nuevo contrato", "card-button-primary");
        contract.setOnAction(event -> onNewContract(c));
        HBox buttons = new HBox(10, detail, contract);
        Pane spacer = new Pane();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        card.getChildren().addAll(spacer, buttons);

        card.getStyleClass().addAll("employee-card", "client-card");
        card.setMaxHeight(Double.MAX_VALUE);
        card.setMinWidth(0); // la columna (33 %) manda; el nombre largo pasa a dos líneas
        card.setPrefWidth(0);
        GridPane.setFillHeight(card, true);
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openDrawer(c);
            }
        });
        return card;
    }

    /** "PL-0412 · 4 beneficiarios · 2 contratos", "Plan colectivo · 38 afiliados" o "Sin plan exequial". */
    private static HBox planRow(Client c, ClientSummary summary) {
        PlanInfo plan = summary.plan();
        String left;
        if (plan == null) {
            left = "Sin plan exequial";
        } else {
            boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
            String people = corporate
                    ? count(plan.beneficiaries(), "afiliado", "afiliados")
                    : count(plan.beneficiaries(), "beneficiario", "beneficiarios");
            left = plan.isActive()
                    ? (corporate ? plan.name() : plan.code()) + " · " + people
                    : plan.code() + " · Plan " + planStatusLabel(plan).toLowerCase();
        }
        Label planLabel = new Label(left);
        planLabel.getStyleClass().add("plan-text");
        Label contracts = new Label(summary.contracts() == 0 ? "Sin contratos"
                : count(summary.contracts(), "contrato", "contratos"));
        contracts.getStyleClass().add("plan-contracts");
        contracts.setMinWidth(Region.USE_PREF_SIZE);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, planLabel, spacer, contracts);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("plan-row");
        if (plan == null || !plan.isActive()) {
            row.getStyleClass().add("plan-row-none");
        }
        return row;
    }

    /** "Saldo pendiente $ 1.850.000" en caqui o "Al día · sin deudas" en salvia. */
    private static HBox balanceRow(ClientSummary summary) {
        boolean owes = summary.hasBalance();
        Label left = new Label(owes ? "Saldo pendiente" : "Al día");
        Label right = new Label(owes ? pesos(summary.balance()) : summary.sales() == 0 ? "sin compras" : "sin deudas");
        right.setMinWidth(Region.USE_PREF_SIZE);
        right.getStyleClass().add("balance-amount");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, left, spacer, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("balance-row", owes ? "balance-due" : "balance-clear");
        return row;
    }

    private ClientSummary summaryOf(Client c) {
        return summaries.getOrDefault(c.getId(), new ClientSummary(null, 0, 0, BigDecimal.ZERO));
    }

    private void markSelectedCard() {
        cards.forEach((id, card) -> card.pseudoClassStateChanged(SELECTED, id.equals(drawerClientId)));
    }

    // ---------- panel lateral (P-11.1) ----------

    private void openDrawer(Client c) {
        drawerClientId = c.getId();
        markSelectedCard();
        loadDrawer(c);
        if (drawer.isVisible()) {
            return;
        }
        veil.setVisible(true);
        drawer.setVisible(true);
        TranslateTransition slide = new TranslateTransition(Duration.millis(220), drawer);
        slide.setToX(0);
        FadeTransition fade = new FadeTransition(Duration.millis(220), veil);
        fade.setFromValue(0);
        fade.setToValue(1);
        new ParallelTransition(slide, fade).play();
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerClientId = null;
        markSelectedCard();
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerClientId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    /** Dibuja de una vez el encabezado y el contacto; beneficiarios, ventas y difuntos llegan del servicio. */
    private void loadDrawer(Client c) {
        drawerBody.getChildren().setAll(drawerHeader(c), contactSection(c), wrapped("Cargando plan, contratos y ventas…", "account-hint"));
        drawerActions.getChildren().setAll(drawerButtons(c));
        Long id = c.getId();
        Task<ClientDetail> task = new Task<>() {
            @Override
            protected ClientDetail call() {
                return overviewService.detail(id);
            }
        };
        task.setOnSucceeded(e -> {
            if (id.equals(drawerClientId)) {
                fillDrawer(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            if (id.equals(drawerClientId)) {
                drawerBody.getChildren().set(2, wrapped(task.getException() instanceof BusinessException business
                        ? business.getMessage()
                        : "No se pudo cargar el detalle del cliente. Intente de nuevo.", "account-hint"));
            }
        });
        start(task, "client-detail-loader");
    }

    private void fillDrawer(ClientDetail detail) {
        Client c = detail.client();
        ClientSummary summary = detail.summary();

        HBox pills = new HBox(8);
        PlanInfo plan = summary.plan();
        if (plan != null && plan.isActive()) {
            pills.getChildren().add(pill("●  Plan activo", "status-active"));
        } else {
            pills.getChildren().add(pill(plan == null ? "Sin plan exequial" : "Plan " + planStatusLabel(plan).toLowerCase(),
                    "status-inactive"));
        }
        pills.getChildren().add(summary.hasBalance()
                ? pill("●  Saldo pendiente", "pill-warning")
                : pill("●  Al día", "status-active"));

        drawerBody.getChildren().setAll(drawerHeader(c), pills, contactSection(c),
                planSection(c, plan, detail.beneficiaries()), salesSection(detail.sales()),
                deceasedSection(detail.deceased()));
    }

    private HBox drawerHeader(Client c) {
        boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
        Label caption = new Label(corporate ? "EMPRESA TITULAR" : "CLIENTE TITULAR");
        caption.getStyleClass().add("kpi-caption");
        String type = c.getClientType() == null ? "" : c.getClientType().getDescription() + " · ";
        String since = c.getRegistrationDate() == null ? "" : " · desde " + DATE.format(c.getRegistrationDate());
        VBox titles = new VBox(2, caption, wrapped(ClientService.displayName(c), "drawer-name"),
                wrapped(type + documentLabel(c) + since, "card-subtitle"));
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(event -> closeDrawer());
        HBox header = new HBox(14, avatar(c, "avatar-large"), titles, close);
        header.setAlignment(Pos.TOP_LEFT);
        return header;
    }

    private static VBox contactSection(Client c) {
        return section("CONTACTO",
                "Teléfono", formatPhone(c.getPhone()),
                "Correo", orDash(c.getEmail()),
                "Dirección", orDash(c.getAddress()));
    }

    /** Beneficiarios del plan con su carencia (RN-08): "Cubierto" o "Carencia hasta 15/01/2027". */
    private static VBox planSection(Client c, PlanInfo plan, List<BeneficiaryInfo> beneficiaries) {
        VBox box = new VBox(8);
        box.getStyleClass().add("info-box");
        if (plan == null) {
            box.getChildren().add(wrapped("No tiene plan exequial. Se afilia desde el módulo Planes exequiales.",
                    "account-hint"));
            return titled("PLAN EXEQUIAL", box);
        }
        boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
        LocalDate today = ClientOverviewService.today();
        if (beneficiaries.isEmpty()) {
            box.getChildren().add(wrapped(corporate ? "El plan aún no tiene afiliados." : "El plan aún no tiene beneficiarios.",
                    "account-hint"));
        }
        GridPane grid = infoGrid();
        for (int i = 0; i < beneficiaries.size(); i++) {
            BeneficiaryInfo b = beneficiaries.get(i);
            String who = b.relationship() == null || b.relationship().isBlank()
                    ? b.fullName() : b.fullName() + " · " + b.relationship();
            String coverage = !plan.isActive() ? "Plan " + planStatusLabel(plan).toLowerCase()
                    : plan.isCovered(today) ? "Cubierto"
                    : plan.waitingEnds() == null ? "Sin fecha de inicio"
                    : "Carencia hasta " + DATE.format(plan.waitingEnds());
            Label name = wrapped(who, "beneficiary-name");
            Label state = new Label(coverage);
            state.setMinWidth(Region.USE_PREF_SIZE);
            state.getStyleClass().addAll("beneficiary-state", plan.isActive() && plan.isCovered(today)
                    ? "state-covered" : "state-waiting");
            GridPane.setHgrow(name, Priority.ALWAYS);
            grid.add(name, 0, i);
            grid.add(state, 1, i);
        }
        if (!beneficiaries.isEmpty()) {
            box.getChildren().add(grid);
        }
        String title = "PLAN EXEQUIAL " + plan.code() + " · " + plan.name().toUpperCase();
        return titled(title, box);
    }

    /** Contratos y ventas con su saldo (abonos anulados no cuentan). */
    private static VBox salesSection(List<SaleInfo> sales) {
        VBox list = new VBox(10);
        if (sales.isEmpty()) {
            list.getChildren().add(wrapped("Aún no tiene contratos ni ventas.", "account-hint"));
        }
        for (SaleInfo sale : sales) {
            Label title = wrapped(sale.code() + " · " + saleTitle(sale.type()), "sale-title");
            String date = sale.date() == null ? "" : " · " + DATE.format(sale.date());
            Label subtitle = new Label(saleSubtitle(sale.type()) + date);
            subtitle.getStyleClass().add("sale-subtitle");
            VBox texts = new VBox(2, title, subtitle);
            HBox.setHgrow(texts, Priority.ALWAYS);
            boolean owes = sale.balance().signum() > 0;
            Label state = new Label(owes ? "Saldo " + pesos(sale.balance()) : "Pagado");
            state.setMinWidth(Region.USE_PREF_SIZE);
            state.getStyleClass().addAll("sale-state", owes ? "sale-due" : "sale-paid");
            HBox row = new HBox(10, texts, state);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        return titled("CONTRATOS Y VENTAS", list);
    }

    private static VBox deceasedSection(List<DeceasedInfo> deceased) {
        VBox box = new VBox(8);
        box.getStyleClass().add("info-box");
        if (deceased.isEmpty()) {
            box.getChildren().add(wrapped("No hay difuntos atendidos con sus contratos.", "account-hint"));
        }
        GridPane grid = infoGrid();
        for (int i = 0; i < deceased.size(); i++) {
            DeceasedInfo d = deceased.get(i);
            String where;
            if (d.wakeRoom() != null && d.wakeStartAt() != null) {
                where = "Velación " + d.wakeRoom() + " · " + DATE.format(d.wakeStartAt());
            } else if (d.contractDate() != null) {
                where = "Contrato " + d.contractNumber() + " · " + DATE.format(d.contractDate());
            } else {
                where = "Contrato " + d.contractNumber();
            }
            infoRow(grid, i, d.fullName(), where);
        }
        if (!deceased.isEmpty()) {
            box.getChildren().add(grid);
        }
        return titled("DIFUNTOS ATENDIDOS", box);
    }

    private List<Button> drawerButtons(Client c) {
        Button edit = new Button("Editar");
        edit.getStyleClass().addAll("drawer-button", "drawer-outline");
        edit.setOnAction(event -> openForm(c, ClientFormController.Mode.EDIT));
        Button payment = new Button("Registrar abono");
        payment.getStyleClass().addAll("drawer-button", "drawer-outline");
        payment.setOnAction(event -> onRegisterPayment(c));
        Button contract = new Button("Nuevo contrato");
        contract.getStyleClass().addAll("drawer-button", "drawer-primary");
        contract.setOnAction(event -> onNewContract(c));
        List<Button> buttons = List.of(edit, payment, contract);
        for (Button button : buttons) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }
        return buttons;
    }

    // ---------- acciones ----------

    @FXML
    private void onRegister() {
        openForm(null, ClientFormController.Mode.NEW);
    }

    private void openForm(Client client, ClientFormController.Mode mode) {
        FxmlViewLoader.View<ClientFormController> view = viewLoader.loadView(FxmlViewLoader.CLIENT_FORM);
        Stage stage = new Stage();
        stage.initOwner(window());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Clientes");
        stage.setScene(new Scene(view.root()));
        view.controller().init(client, mode, saved -> reload());
        stage.showAndWait();
    }

    /** Contratos y ventas (HU de EP-05) aún no está: se avisa en vez de abrir una pantalla vacía. */
    private void onNewContract(Client c) {
        showInfo("Nuevo contrato", "El contrato de " + ClientService.displayName(c)
                + " se registra en el módulo Contratos y ventas, que llega en un próximo sprint.");
    }

    private void onRegisterPayment(Client c) {
        showInfo("Registrar abono", "Los abonos de " + ClientService.displayName(c)
                + " se registran en Contratos y ventas, sobre la venta que tiene saldo. Ese módulo llega en un próximo sprint.");
    }

    // ---------- piezas ----------

    /** Iniciales en círculo ("MD" para Martha Durán); una empresa va en cuadro redondeado ("TC"). */
    private static StackPane avatar(Client c, String styleClass) {
        boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
        String initials;
        if (corporate) {
            // "Transportes del Cesar S.A.S." → "TC": las dos primeras palabras con mayúscula.
            String[] words = c.getName() == null ? new String[0] : c.getName().trim().split("\\s+");
            initials = java.util.Arrays.stream(words)
                    .filter(word -> !word.isEmpty() && Character.isUpperCase(word.charAt(0)))
                    .limit(2)
                    .map(ClientController::initial)
                    .reduce("", String::concat);
        } else {
            initials = initial(c.getName()) + initial(c.getLastname());
        }
        Label text = new Label(initials);
        text.getStyleClass().add("avatar-text");
        StackPane box = new StackPane(text);
        box.getStyleClass().add(styleClass);
        if (corporate) {
            box.getStyleClass().add("avatar-company");
        }
        box.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }

    private static Label typePill(Client c) {
        ClientType type = c.getClientType() == null ? ClientType.NATURAL : c.getClientType();
        return pill(type.isCorporate() ? "Empresa" : "Persona natural",
                type.isCorporate() ? "type-corporate" : "type-natural");
    }

    private static Label pill(String text, String styleClass) {
        Label pill = new Label(text);
        pill.getStyleClass().addAll("pill", styleClass);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        return pill;
    }

    private static Label wrapped(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("card-divider");
        return line;
    }

    private static Button cardButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    private static GridPane infoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.getStyleClass().add("info-grid");
        return grid;
    }

    private static void infoRow(GridPane grid, int row, String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("info-key");
        key.setMinWidth(Region.USE_PREF_SIZE);
        Label val = wrapped(value, "info-value");
        val.setMaxWidth(Double.MAX_VALUE);
        val.setAlignment(Pos.CENTER_RIGHT);
        val.setTextAlignment(TextAlignment.RIGHT);
        GridPane.setHgrow(val, Priority.ALWAYS);
        grid.add(key, 0, row);
        grid.add(val, 1, row);
    }

    /** Sección del panel: título y un recuadro con pares etiqueta / valor. */
    private static VBox section(String title, String... pairs) {
        GridPane grid = infoGrid();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            infoRow(grid, i / 2, pairs[i], pairs[i + 1]);
        }
        VBox box = new VBox(grid);
        box.getStyleClass().add("info-box");
        return titled(title, box);
    }

    private static VBox titled(String title, VBox content) {
        Label caption = wrapped(title, "kpi-caption");
        return new VBox(8, caption, content);
    }

    private static ListCell<BalanceFilter> balanceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(BalanceFilter item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        };
    }

    // ---------- utilidades ----------

    /** "CC 49.786.112" o, para una empresa, "NIT 900.412.377-5". */
    static String documentLabel(Client c) {
        String document = c.getDocumentNumber();
        String type = c.getDocumentType() == null ? "" : c.getDocumentType() + " ";
        if ("NIT".equals(c.getDocumentType()) && document != null && document.matches("\\d{2,}")) {
            String base = document.substring(0, document.length() - 1);
            return type + EmployeeController.formatDocument(base) + "-" + document.charAt(document.length() - 1);
        }
        return type + EmployeeController.formatDocument(document);
    }

    /** 3015562087 → "301 556 2087"; 5804411 → "580 4411". */
    static String formatPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "—";
        }
        if (phone.matches("\\d{10}")) {
            return phone.substring(0, 3) + " " + phone.substring(3, 6) + " " + phone.substring(6);
        }
        if (phone.matches("\\d{7}")) {
            return phone.substring(0, 3) + " " + phone.substring(3);
        }
        return phone;
    }

    /** 1850000 → "$ 1.850.000". */
    static String pesos(BigDecimal value) {
        return value == null ? "—" : "$ " + String.format("%,d", value.longValue()).replace(',', '.');
    }

    /** Para el indicador: 41.900.000 → "$ 41,9 M"; menos de un millón va completo. */
    static String shortPesos(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "$ 0";
        }
        if (value.compareTo(BigDecimal.valueOf(1_000_000)) < 0) {
            return pesos(value);
        }
        double millions = value.doubleValue() / 1_000_000;
        String text = millions >= 100 ? String.valueOf(Math.round(millions))
                : String.format(java.util.Locale.ROOT, "%.1f", millions).replace('.', ',').replace(",0", "");
        return "$ " + text + " M";
    }

    private static String saleTitle(SaleType type) {
        if (type == null) {
            return "Venta";
        }
        return switch (type) {
            case CONTRACT -> "Servicio exequial";
            case PRIVATE -> "Servicio particular";
            case PRODUCT -> "Venta de productos";
        };
    }

    private static String saleSubtitle(SaleType type) {
        if (type == null) {
            return "Venta";
        }
        return switch (type) {
            case CONTRACT -> "Venta por contrato";
            case PRIVATE -> "Servicio pagado por la familia";
            case PRODUCT -> "Venta de productos";
        };
    }

    private static String planStatusLabel(PlanInfo plan) {
        if (plan.status() == null) {
            return "Sin estado";
        }
        return switch (plan.status()) {
            case ACTIVE -> "Activo";
            case SUSPENDED -> "Suspendido";
            case CANCELLED -> "Cancelado";
        };
    }

    /** "octubre de 2026". */
    private static String monthLabel(LocalDate date) {
        return DateTimeFormatter.ofPattern("MMMM 'de' yyyy", new java.util.Locale("es", "CO")).format(date);
    }

    /** "1 cliente", "5 clientes", "1 natural", "2 naturales". */
    private static String count(long amount, String singular, String plural) {
        return amount + " " + (amount == 1 ? singular : plural);
    }

    private static String initial(String text) {
        return text == null || text.isBlank() ? "" : text.trim().substring(0, 1).toUpperCase();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.initOwner(window());
        alert.setTitle(title);
        alert.setHeaderText(null);
        EmployeeController.styleDialog(alert);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(window());
        alert.setHeaderText(null);
        EmployeeController.styleDialog(alert);
        alert.showAndWait();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
