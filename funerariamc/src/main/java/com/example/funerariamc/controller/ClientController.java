package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.config.ReadOnlyWork;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.utils.enums.ClientType;
import com.example.funerariamc.utils.enums.SaleType;
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
import com.example.funerariamc.service.ClientService.PlanFilter;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.service.ContractService;
import com.example.funerariamc.ui.DetailShell;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.Responsive;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.FormShell;
import com.example.funerariamc.ui.ModuleLink;
import com.example.funerariamc.ui.Pager;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
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
 * Pantalla de Clientes: tarjetas por titular con su plan exequial, contratos y saldo pendiente;
 * filtros por tipo y saldo; indicadores y un panel lateral con contacto, beneficiarios (con su
 * carencia), contratos y ventas, y difuntos atendidos.
 * Un cliente no se elimina: tiene planes, contratos y ventas asociados.
 */
@Component
@Scope("prototype")
public class ClientController implements RefreshableView {

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Clientes";

    /** Tarjetas por tanda: 4 filas de 3. Al bajar hasta el final se piden 12 más. */
    private static final int PAGE_SIZE = 12;
    private static final int COLUMNS = 3;
    /** Ancho mínimo de una tarjeta de cliente; si no caben 3, pasan a 2 por fila (el nombre no se parte letra por letra). */
    private static final double MIN_CARD_WIDTH = 320;
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
    private ComboBox<PlanFilter> planFilter;
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
    private Region veil;
    @FXML
    private VBox drawer;

    /** Clientes ya cargados (en el orden de las tarjetas) y su resumen. */
    private final List<Client> clients = new ArrayList<>();
    private final Map<Long, ClientSummary> summaries = new HashMap<>();
    private final Map<Long, VBox> cards = new HashMap<>();
    private Pager<Client> pager;
    /** Cada búsqueda nueva invalida las respuestas que aún vienen en camino. */
    private int generation;
    /** Cliente abierto en el panel lateral (null si está cerrado). */
    private Long drawerClientId;
    private DetailShell detail;

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
        // Columnas según el ancho: en ventana angosta las tarjetas pasan a 2 (o 1) por fila
        Responsive.cardColumns(cardGrid, MIN_CARD_WIDTH, COLUMNS);
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un tipo elegido
            } else {
                reloadFromStart();
            }
        });

        // Personas con plan exequial o sin plan (los planes cancelados cuentan como sin plan)
        planFilter.getItems().setAll(PlanFilter.values());
        planFilter.setValue(PlanFilter.ALL);
        planFilter.setButtonCell(planCell());
        planFilter.setCellFactory(list -> planCell());
        planFilter.valueProperty().addListener((obs, old, value) -> reloadFromStart());

        balanceFilter.getItems().setAll(BalanceFilter.values());
        balanceFilter.setValue(BalanceFilter.ALL);
        balanceFilter.setButtonCell(balanceCell());
        balanceFilter.setCellFactory(list -> balanceCell());
        balanceFilter.valueProperty().addListener((obs, old, value) -> reloadFromStart());

        searchDelay.setOnFinished(e -> reloadFromStart());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        // Paginación como la bitácora: cada página consulta sus 12 tarjetas en la BD.
        pager = Pager.<Client>remote(PAGE_SIZE, "cliente", "clientes", page -> query(page))
                .scrollTopOf(cardScroll).below(cardGrid.getParent());

        detail = DetailShell.install(drawer, veil, DetailShell.Kind.PANEL);
        reloadFromStart();
    }

    // ---------- carga ----------

    /** Resultado de una consulta: la tanda de clientes, su resumen y los indicadores. */
    private record Batch(Page<Client> page, Map<Long, ClientSummary> summaries, ClientKpis kpis) {
    }

    /** Se guardó algo o se volvió a la pantalla: vuelve a consultar la página que se está viendo. */
    private void reload() {
        query(pager.page());
    }

    /** Un filtro o la búsqueda cambió: vuelve a la página 1. */
    private void reloadFromStart() {
        query(0);
    }

    /** Igual que Personal: la consulta corre en otro hilo para que la ventana no se congele. */
    private void query(int pageIndex) {
        String text = searchField.getText();
        ClientType type = selectedType();
        BalanceFilter balance = balanceFilter.getValue();
        PlanFilter plan = planFilter.getValue();
        int requestGeneration = ++generation;
        pager.setBusy(true);
        showingLabel.setText("Cargando clientes…");
        Task<Batch> task = new Task<>() {
            @Override
            protected Batch call() {
                // Una sola transacción para las tarjetas, sus resúmenes y los indicadores.
                return readOnlyWork.run(() -> {
                    Page<Client> page = clientService.search(text, type, balance, plan, pageIndex, PAGE_SIZE);
                    List<Long> ids = page.getContent().stream().map(Client::getId).toList();
                    return new Batch(page, overviewService.summaries(ids), overviewService.kpis());
                });
            }
        };
        task.setOnSucceeded(e -> {
            if (requestGeneration == generation) {
                show(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            pager.setBusy(false);
            showingLabel.setText("");
            showError(task.getException() instanceof BusinessException business
                    ? business.getMessage()
                    : "No se pudieron cargar los clientes. Intente de nuevo.");
        });
        start(task, "client-loader");
    }

    private void show(Batch batch) {
        Page<Client> page = batch.page();
        // Si se borró o filtró lo último de una página, se pasa a la última que sí tiene datos.
        if (page.getContent().isEmpty() && page.getNumber() > 0 && page.getTotalPages() > 0) {
            query(page.getTotalPages() - 1);
            return;
        }
        pager.setBusy(false);
        clients.clear();
        summaries.clear();
        clients.addAll(page.getContent());
        summaries.putAll(batch.summaries());
        renderCards();
        showKpis(batch.kpis());
        pager.show(page.getNumber(), page.getTotalPages(), page.getTotalElements());
        showingLabel.setText(page.getTotalElements() == 0 ? "" : "Más recientes primero");

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

    // ---------- panel lateral ----------

    private void openDrawer(Client c) {
        drawerClientId = c.getId();
        markSelectedCard();
        loadDrawer(c);
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerClientId = null;
        markSelectedCard();
        detail.close(null);
    }

    /** Dibuja de una vez la banda y el contacto; beneficiarios, ventas y difuntos llegan del servicio. */
    private void loadDrawer(Client c) {
        detail.show(c.getId(), hero(c, null), List.of(contactSection(c),
                DetailShell.empty("Cargando plan, contratos y ventas…")), drawerButtons(c), null);
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
                detail.show(id, hero(c, null), List.of(contactSection(c),
                        DetailShell.empty(task.getException() instanceof BusinessException business
                                ? business.getMessage()
                                : "No se pudo cargar el detalle del cliente. Intente de nuevo.")),
                        drawerButtons(c), null);
            }
        });
        start(task, "client-detail-loader");
    }

    private void fillDrawer(ClientDetail data) {
        Client c = data.client();
        PlanInfo plan = data.summary().plan();
        detail.show(c.getId(), hero(c, data.summary()), List.of(contactSection(c),
                planSection(c, plan, data.beneficiaries()), salesSection(data.sales()),
                deceasedSection(data.deceased())), drawerButtons(c), null);
    }

    /** Banda verde: tipo de titular, iniciales, documento y, cuando ya llegó el resumen, plan y saldo. */
    private DetailShell.Hero hero(Client c, ClientSummary summary) {
        boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
        String type = c.getClientType() == null ? "" : c.getClientType().getDescription() + " · ";
        String since = c.getRegistrationDate() == null ? "" : " · desde " + DATE.format(c.getRegistrationDate());
        String name = ClientService.displayName(c);
        DetailShell.Hero hero = new DetailShell.Hero(corporate ? "Empresa titular" : "Cliente titular", name)
                .subtitle(type + documentLabel(c) + since)
                .leading(DetailShell.initials(name))
                .navigation(DetailShell.neighbor(clients, x -> x.getId().equals(c.getId()), -1, this::openDrawer),
                        DetailShell.neighbor(clients, x -> x.getId().equals(c.getId()), 1, this::openDrawer))
                .onClose(this::closeDrawer);
        if (summary != null) {
            PlanInfo plan = summary.plan();
            hero.chips(plan != null && plan.isActive()
                    ? DetailShell.chip("● Plan activo", DetailShell.Tone.OK)
                    : DetailShell.chip(plan == null ? "Sin plan exequial" : "Plan " + planStatusLabel(plan).toLowerCase(),
                            DetailShell.Tone.NEUTRAL));
            hero.chips(summary.hasBalance()
                    ? DetailShell.chip("● Saldo pendiente", DetailShell.Tone.WARN)
                    : DetailShell.chip("● Al día", DetailShell.Tone.OK));
        }
        return hero;
    }

    private static VBox contactSection(Client c) {
        return DetailShell.section("Contacto", DetailShell.card(DetailShell.rows(
                "Teléfono", formatPhone(c.getPhone()),
                "Correo", orDash(c.getEmail()),
                "Dirección", orDash(c.getAddress()))));
    }

    /** Beneficiarios del plan con su carencia: "Cubierto" o "Carencia hasta 15/01/2027". */
    private static VBox planSection(Client c, PlanInfo plan, List<BeneficiaryInfo> beneficiaries) {
        if (plan == null) {
            return DetailShell.section("Plan exequial",
                    DetailShell.empty("No tiene plan exequial. Se afilia desde el módulo Planes exequiales."));
        }
        String title = "Plan exequial " + plan.code() + " · " + plan.name();
        if (beneficiaries.isEmpty()) {
            boolean corporate = c.getClientType() != null && c.getClientType().isCorporate();
            return DetailShell.section(title, DetailShell.empty(corporate
                    ? "El plan aún no tiene afiliados." : "El plan aún no tiene beneficiarios."));
        }
        LocalDate today = ClientOverviewService.today();
        List<Object> pairs = new ArrayList<>();
        for (BeneficiaryInfo b : beneficiaries) {
            String who = b.relationship() == null || b.relationship().isBlank()
                    ? b.fullName() : b.fullName() + " · " + b.relationship();
            boolean covered = plan.isActive() && plan.isCovered(today);
            String coverage = !plan.isActive() ? "Plan " + planStatusLabel(plan).toLowerCase()
                    : plan.isCovered(today) ? "Cubierto"
                    : plan.waitingEnds() == null ? "Sin fecha de inicio"
                    : "Carencia hasta " + DATE.format(plan.waitingEnds());
            pairs.add(who);
            pairs.add(covered ? coverage : DetailShell.value(coverage, DetailShell.Tone.WARN));
        }
        return DetailShell.section(title, DetailShell.card(DetailShell.rows(pairs.toArray())));
    }

    /** Contratos y ventas con su saldo (abonos anulados no cuentan). */
    private static VBox salesSection(List<SaleInfo> sales) {
        if (sales.isEmpty()) {
            return DetailShell.section("Contratos y ventas", DetailShell.empty("Aún no tiene contratos ni ventas."));
        }
        List<DetailShell.Event> events = new ArrayList<>();
        for (SaleInfo sale : sales) {
            String date = sale.date() == null ? "" : " · " + DATE.format(sale.date());
            boolean owes = sale.balance().signum() > 0;
            events.add(new DetailShell.Event(sale.code() + " · " + saleTitle(sale.type()),
                    saleSubtitle(sale.type()) + date,
                    owes ? DetailShell.Tone.ALERT : DetailShell.Tone.OK,
                    owes ? DetailShell.chip("Saldo " + pesos(sale.balance()), DetailShell.Tone.ALERT)
                            : DetailShell.chip("Pagado", DetailShell.Tone.OK)));
        }
        return DetailShell.section("Contratos y ventas", DetailShell.timeline(events));
    }

    private static VBox deceasedSection(List<DeceasedInfo> deceased) {
        if (deceased.isEmpty()) {
            return DetailShell.section("Difuntos atendidos",
                    DetailShell.empty("No hay difuntos atendidos con sus contratos."));
        }
        List<Object> pairs = new ArrayList<>();
        for (DeceasedInfo d : deceased) {
            String where;
            if (d.wakeRoom() != null && d.wakeStartAt() != null) {
                where = "Velación " + d.wakeRoom() + " · " + DATE.format(d.wakeStartAt());
            } else if (d.contractDate() != null) {
                where = "Contrato " + d.contractNumber() + " · " + DATE.format(d.contractDate());
            } else {
                where = "Contrato " + d.contractNumber();
            }
            pairs.add(d.fullName());
            pairs.add(where);
        }
        return DetailShell.section("Difuntos atendidos", DetailShell.card(DetailShell.rows(pairs.toArray())));
    }

    private List<Button> drawerButtons(Client c) {
        return List.of(DetailShell.secondary("Editar", () -> openForm(c, ClientFormController.Mode.EDIT)),
                DetailShell.secondary("Registrar abono", () -> onRegisterPayment(c)),
                DetailShell.primary("Nuevo contrato", () -> onNewContract(c)));
    }

    // ---------- acciones ----------

    /**
     * Registrar cliente: al guardarlo se abre enseguida "Nuevo contrato" con ese cliente ya elegido,
     * para afiliarlo a un plan o hacerle un contrato o una venta. Si no hace falta, se cierra y el
     * usuario sigue en Clientes.
     */
    @FXML
    private void onRegister() {
        Client created = openForm(null, ClientFormController.Mode.NEW);
        if (created == null || !ModuleLink.canOpen(AppModule.CONTRATOS)) {
            return;
        }
        // Con el cliente ya guardado se pregunta si se afilia de una vez; la afiliación se abre aquí mismo
        String name = ClientService.displayName(created);
        boolean affiliate = Notice.confirm(window(), "Clientes", "Cliente registrado",
                "¿Desea afiliar a " + name + " a un plan exequial ahora?",
                "Sí, afiliar a un plan", "Ahora no", false);
        if (affiliate) {
            ModuleLink.openInPlace(AppModule.CONTRATOS, new ContractController.AffiliationLink(option(created)));
        }
    }

    /** El cliente como opción de los formularios de Contratos: nombre y documento. */
    private static ContractService.Option option(Client c) {
        String detail = (c.getClientType() == ClientType.CORPORATE ? "Empresa · " : "") + documentLabel(c);
        return new ContractService.Option(c.getId(), ClientService.displayName(c), detail);
    }

    /** Muestra el formulario y devuelve el cliente guardado, o null si se canceló. */
    private Client openForm(Client client, ClientFormController.Mode mode) {
        FxmlViewLoader.View<ClientFormController> view = viewLoader.loadView(FxmlViewLoader.CLIENT_FORM);
        Stage stage = new Stage();
        stage.initOwner(window());
        // Igual que los demás formularios: en Windows, con WINDOW_MODAL el calendario de la ventana transparente no recibía los clics
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Clientes");
        stage.setScene(new Scene(view.root()));
        Client[] saved = new Client[1];
        view.controller().init(client, mode, result -> {
            saved[0] = result;
            reload();
        });
        FormShell.stage(stage, "Clientes", FormShell.Kind.PANEL);
        stage.showAndWait();
        return saved[0];
    }

    /** Abre "Nuevo contrato" con este cliente ya elegido; al cerrarlo se vuelve a Clientes. */
    private void onNewContract(Client c) {
        openContracts(c, true);
    }

    /** Abre Contratos y ventas mostrando solo las ventas de este cliente que tienen saldo. */
    private void onRegisterPayment(Client c) {
        openContracts(c, false);
    }

    private void openContracts(Client c, boolean newSale) {
        if (!ModuleLink.canOpen(AppModule.CONTRATOS)) {
            showInfo("Contratos y ventas", "Su usuario no tiene acceso al módulo Contratos y ventas.");
            return;
        }
        ContractController.ClientLink link = new ContractController.ClientLink(option(c), newSale);
        if (newSale) {
            // "Nuevo contrato" se abre encima de Clientes; "Registrar abono" pasa a la pestaña de contratos
            ModuleLink.openInPlace(AppModule.CONTRATOS, link);
        } else {
            ModuleLink.open(AppModule.CONTRATOS, link);
        }
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

    private static ListCell<PlanFilter> planCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(PlanFilter item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getLabel());
            }
        };
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
        Notice.info(window(), MODULE_NAME, title, message);
    }

    private void showError(String message) {
        Notice.warning(window(), MODULE_NAME, "Atención", message);
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
