package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.enums.AlliedPaymentStatus;
import com.example.funerariamc.service.AlliedHomeData;
import com.example.funerariamc.service.AlliedNetworkService;
import com.example.funerariamc.service.AlliedNetworkService.ContractOption;
import com.example.funerariamc.service.AlliedNetworkService.HomeCard;
import com.example.funerariamc.service.AlliedNetworkService.HomeDetail;
import com.example.funerariamc.service.AlliedNetworkService.HomeState;
import com.example.funerariamc.service.AlliedNetworkService.Overview;
import com.example.funerariamc.service.AlliedNetworkService.PaymentRow;
import com.example.funerariamc.service.AlliedPaymentData;
import com.example.funerariamc.ui.RefreshableView;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Pantalla Red de funerarias aliadas (solo el Administrador Maestro). Tarjetas de las funerarias con su
 * saldo por pagar, filtros por nombre o NIT, ciudad y estado, y un panel lateral con el convenio y los pagos.
 * Registrar un pago lo deja pendiente; marcarlo pagado registra el egreso en Finanzas; anular un pago pagado
 * también anula ese egreso. Las consultas corren en otro hilo.
 */
@Component
@Scope("prototype")
public class AlliedNetworkController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", ES);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", ES);
    private static final String ALL_CITIES = "Todas";
    private static final double DRAWER_WIDTH = 460;
    private static final double CARD_GAP = 16;

    private final AlliedNetworkService service;

    @FXML private StackPane root;
    @FXML private ToggleGroup stateGroup;
    @FXML private ToggleButton allState;
    @FXML private ToggleButton upToDateState;
    @FXML private ToggleButton balanceState;
    @FXML private ToggleButton inactiveState;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> cityFilter;
    @FXML private Label activeValue;
    @FXML private Label activeDetail;
    @FXML private Label servicesValue;
    @FXML private Label servicesDetail;
    @FXML private Label pendingValue;
    @FXML private Label pendingDetail;
    @FXML private Label openValue;
    @FXML private Label openDetail;
    @FXML private Label countLabel;
    @FXML private ScrollPane cardsScroll;
    @FXML private FlowPane cardsPane;
    @FXML private Label emptyLabel;
    @FXML private Region veil;
    @FXML private VBox drawer;
    @FXML private VBox drawerHead;
    @FXML private VBox drawerBody;
    @FXML private HBox drawerActions;
    @FXML private Label toast;

    private List<HomeCard> homes = List.of();
    private Long drawerHomeId;
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));

    public AlliedNetworkController(AlliedNetworkService service) {
        this.service = service;
    }

    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        stateGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                if (old != null) {
                    old.setSelected(true);
                }
            } else {
                applyFilter();
            }
        });
        searchDelay.setOnFinished(e -> applyFilter());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());
        cityFilter.setConverter(new StringConverter<>() {
            @Override
            public String toString(String city) {
                return city == null ? "" : "Ciudad: " + city;
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        cityFilter.getItems().setAll(ALL_CITIES);
        cityFilter.setValue(ALL_CITIES);
        cityFilter.valueProperty().addListener((obs, old, city) -> applyFilter());
        // Tres tarjetas por fila; dos si la ventana es angosta.
        cardsScroll.viewportBoundsProperty().addListener((obs, old, bounds) -> resizeCards());

        drawer.setTranslateX(DRAWER_WIDTH);
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        load();
    }

    // ---------- carga y filtros ----------

    private void load() {
        int request = ++generation;
        if (homes.isEmpty()) {
            setEmpty("Cargando funerarias…");
        }
        async("allied-load", service::overview, overview -> {
            if (request != generation) {
                return;
            }
            homes = overview.homes();
            fillKpis(overview);
            fillCities();
            applyFilter();
            if (drawerHomeId != null) {
                openDrawer(drawerHomeId);
            }
        }, failure -> {
            if (request == generation) {
                setEmpty(message(failure, "No se pudo cargar la red de funerarias aliadas."));
            }
        });
    }

    private void fillKpis(Overview o) {
        activeValue.setText(o.active() + " de " + o.total());
        long inactive = o.total() - o.active();
        activeDetail.setText(o.total() == 0 ? "aún no hay funerarias registradas"
                : inactive == 0 ? "todas con convenio activo" : inactive + (inactive == 1 ? " inactiva" : " inactivas"));
        servicesValue.setText(String.valueOf(o.servicesThisMonth()));
        long diff = o.servicesThisMonth() - o.servicesLastMonth();
        String last = o.month().minusMonths(1).getMonth().getDisplayName(java.time.format.TextStyle.FULL, ES)
                .toLowerCase(Locale.ROOT);
        servicesDetail.setText(diff == 0 ? "igual que " + last : (diff > 0 ? "+" : "") + diff + " frente a " + last);
        pendingValue.setText(shortPesos(o.pending()));
        pendingDetail.setText(o.homesWithBalance() == 0 ? "nada por pagar"
                : "en " + o.homesWithBalance() + (o.homesWithBalance() == 1 ? " funeraria" : " funerarias"));
        openValue.setText(String.valueOf(o.pendingPayments()));
        openDetail.setText(o.oldestPending() == null ? "sin pagos pendientes"
                : "el más antiguo del " + clean(o.oldestPending().format(LONG_DAY)));
    }

    private void fillCities() {
        TreeSet<String> cities = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (HomeCard h : homes) {
            if (h.city() != null && !h.city().isBlank()) {
                cities.add(h.city().trim());
            }
        }
        String current = cityFilter.getValue();
        List<String> items = new ArrayList<>();
        items.add(ALL_CITIES);
        items.addAll(cities);
        if (!items.equals(cityFilter.getItems())) {
            cityFilter.getItems().setAll(items);
            cityFilter.setValue(items.contains(current) ? current : ALL_CITIES);
        }
    }

    private void applyFilter() {
        String text = normalize(searchField.getText());
        String digits = text.replaceAll("[^0-9]", "");
        String city = cityFilter.getValue();
        Object state = stateGroup.getSelectedToggle();
        List<HomeCard> shown = homes.stream()
                .filter(h -> text.isEmpty() || normalize(h.name()).contains(text)
                        || (!digits.isEmpty() && h.nit() != null && h.nit().replaceAll("[^0-9]", "").contains(digits)))
                .filter(h -> city == null || ALL_CITIES.equals(city) || city.equalsIgnoreCase(h.city() == null ? "" : h.city().trim()))
                .filter(h -> state == allState
                        || (state == upToDateState && h.state() == HomeState.UP_TO_DATE)
                        || (state == balanceState && h.state() == HomeState.WITH_BALANCE)
                        || (state == inactiveState && h.state() == HomeState.INACTIVE))
                .toList();
        List<Node> cards = new ArrayList<>();
        for (HomeCard h : shown) {
            cards.add(card(h));
        }
        cardsPane.getChildren().setAll(cards);
        resizeCards();
        countLabel.setText(shown.size() + (shown.size() == 1 ? " funeraria" : " funerarias") + " · ordenadas por saldo");
        setEmpty(homes.isEmpty() ? "Aún no hay funerarias aliadas. Registre la primera con «Nueva funeraria aliada»."
                : shown.isEmpty() ? "Ninguna funeraria coincide con el filtro." : "");
    }

    private void resizeCards() {
        double width = cardsScroll.getViewportBounds().getWidth() - 8;
        if (width <= 0) {
            return;
        }
        int columns = width >= 900 ? 3 : 2;
        double cardWidth = Math.floor((width - CARD_GAP * (columns - 1)) / columns);
        for (Node node : cardsPane.getChildren()) {
            if (node instanceof Region region) {
                region.setPrefWidth(cardWidth);
                region.setMinWidth(cardWidth);
                region.setMaxWidth(cardWidth);
            }
        }
    }

    // ---------- tarjetas ----------

    private Node card(HomeCard h) {
        Label initials = label(h.initials(), "initials");
        Label name = label(h.name(), "home-name");
        name.setWrapText(true);
        VBox title = new VBox(2, name, label(h.city() == null ? "Sin ciudad" : h.city(), "home-city"));
        title.setMinWidth(0);
        HBox.setHgrow(title, Priority.ALWAYS);
        Label chip = stateChip(h.state());
        HBox head = new HBox(12, initials, title, chip);
        head.setAlignment(Pos.TOP_LEFT);

        GridPane info = new GridPane();
        info.setVgap(6);
        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        right.setHgrow(Priority.ALWAYS);
        right.setHalignment(javafx.geometry.HPos.RIGHT);
        info.getColumnConstraints().addAll(left, right);
        infoRow(info, 0, "NIT", h.nit());
        infoRow(info, 1, "Teléfono", h.phone());
        infoRow(info, 2, "Correo", h.email() == null ? "—" : h.email());
        infoRow(info, 3, "Convenio desde", h.agreementStart() == null ? "—" : clean(h.agreementStart().format(LONG_DAY)));

        VBox services = new VBox(2, label("SERVICIOS DEL MES", "stat-caption"), label(String.valueOf(h.servicesThisMonth()), "stat-value"));
        Label pending = label(shortPesos(h.pending()), "stat-value");
        if (h.pending().signum() > 0) {
            pending.getStyleClass().add("alert");
        }
        VBox balance = new VBox(2, label("SALDO POR PAGAR", "stat-caption"), pending);
        HBox.setHgrow(services, Priority.ALWAYS);
        HBox.setHgrow(balance, Priority.ALWAYS);
        services.setMaxWidth(Double.MAX_VALUE);
        balance.setMaxWidth(Double.MAX_VALUE);
        HBox stats = new HBox(services, balance);
        stats.getStyleClass().add("stat-box");

        Button detail = new Button("Ver detalle");
        detail.getStyleClass().add("card-button");
        detail.setOnAction(e -> openDrawer(h.id()));
        Button pay = new Button("Registrar pago");
        pay.getStyleClass().addAll("card-button", "strong");
        pay.setDisable(!h.active());
        pay.setOnAction(e -> registerPayment(h));
        HBox.setHgrow(detail, Priority.ALWAYS);
        HBox.setHgrow(pay, Priority.ALWAYS);
        detail.setMaxWidth(Double.MAX_VALUE);
        pay.setMaxWidth(Double.MAX_VALUE);
        HBox buttons = new HBox(10, detail, pay);

        Region rule = new Region();
        rule.getStyleClass().add("card-rule");
        VBox card = new VBox(head, rule, info, stats, buttons);
        card.getStyleClass().add("home-card");
        if (!h.active()) {
            card.getStyleClass().add("inactive");
        }
        if (h.id().equals(drawerHomeId)) {
            card.getStyleClass().add("selected");
        }
        card.setOnMouseClicked(e -> {
            if (e.getTarget() instanceof Node target && !(isInsideButton(target))) {
                openDrawer(h.id());
            }
        });
        card.setCursor(javafx.scene.Cursor.HAND);
        return card;
    }

    private static boolean isInsideButton(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n instanceof Button) {
                return true;
            }
        }
        return false;
    }

    private static void infoRow(GridPane grid, int row, String caption, String value) {
        Label v = label(value == null ? "—" : value, "info-value");
        v.setMinWidth(0);
        grid.add(label(caption, "info-caption"), 0, row);
        grid.add(v, 1, row);
    }

    private static Label stateChip(HomeState state) {
        Label chip = label(state.getLabel(), "state-chip");
        chip.getStyleClass().add(switch (state) {
            case UP_TO_DATE -> "state-ok";
            case WITH_BALANCE -> "state-warn";
            case INACTIVE -> "state-muted";
        });
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    // ---------- panel lateral ----------

    private void openDrawer(Long homeId) {
        boolean switching = !homeId.equals(drawerHomeId);
        drawerHomeId = homeId;
        if (switching) {
            drawerHead.getChildren().setAll(label("Cargando…", "drawer-sub"));
            drawerBody.getChildren().clear();
            drawerActions.getChildren().clear();
        }
        markSelected();
        async("allied-detail", () -> service.detail(homeId), detail -> {
            if (homeId.equals(drawerHomeId)) {
                fillDrawer(detail);
            }
        }, failure -> {
            if (homeId.equals(drawerHomeId)) {
                closeDrawer();
                showError(message(failure, "No se pudo cargar el detalle de la funeraria."));
            }
        });
        slideDrawerIn();
    }

    private void markSelected() {
        for (int i = 0; i < cardsPane.getChildren().size(); i++) {
            cardsPane.getChildren().get(i).getStyleClass().remove("selected");
        }
        applyFilter();
    }

    private void fillDrawer(HomeDetail d) {
        HomeCard h = d.home();
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(e -> closeDrawer());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(label("FUNERARIA ALIADA", "drawer-caption"), gap, close);
        top.setAlignment(Pos.TOP_LEFT);
        Label name = label(h.name(), "drawer-name");
        name.setWrapText(true);
        String place = (h.city() == null ? "" : h.city() + " · ") + "NIT " + h.nit();
        Label agreement = label(h.active() ? "Convenio activo" : "Convenio inactivo", "state-chip");
        agreement.getStyleClass().add(h.active() ? "state-ok" : "state-muted");
        HBox chips = new HBox(8);
        if (h.state() == HomeState.WITH_BALANCE) {
            chips.getChildren().add(stateChip(HomeState.WITH_BALANCE));
        }
        chips.getChildren().add(agreement);
        drawerHead.getChildren().setAll(top, name, label(place, "drawer-sub"), chips);

        VBox convenio = new VBox(8);
        convenio.getStyleClass().add("drawer-box");
        convenio.getChildren().addAll(
                pair("Convenio desde", h.agreementStart() == null ? "—" : clean(h.agreementStart().format(LONG_DAY))),
                pair("Teléfono", h.phone()),
                pair("Correo", h.email() == null ? "—" : h.email()),
                pair("Servicios prestados en " + d.year(), String.valueOf(d.servicesThisYear())));

        VBox payments = new VBox(0);
        if (d.payments().isEmpty()) {
            payments.getChildren().add(label("Aún no hay pagos registrados a esta funeraria.", "payment-sub"));
        }
        for (PaymentRow p : d.payments()) {
            payments.getChildren().add(paymentRow(h, p));
        }

        Label balance = label(pesos(h.pending()), "balance-value");
        if (h.pending().signum() > 0) {
            balance.getStyleClass().add("alert");
        }
        Region gap2 = new Region();
        HBox.setHgrow(gap2, Priority.ALWAYS);
        HBox balanceBox = new HBox(new VBox(2, label("SALDO POR PAGAR", "drawer-caption"), balance), gap2,
                label(h.pendingCount() == 0 ? "sin pagos abiertos"
                        : h.pendingCount() + (h.pendingCount() == 1 ? " pago abierto" : " pagos abiertos"), "payment-sub"));
        balanceBox.getStyleClass().add("balance-box");

        drawerBody.getChildren().setAll(label("CONVENIO", "drawer-section-title"), convenio,
                label("PAGOS POR SERVICIOS PRESTADOS", "drawer-section-title"), payments, balanceBox);

        Button toggle = new Button(h.active() ? "Desactivar" : "Activar");
        toggle.getStyleClass().add("drawer-button");
        if (h.active()) {
            toggle.getStyleClass().add("danger");
        }
        toggle.setOnAction(e -> toggleActive(h));
        Button edit = new Button("Editar");
        edit.getStyleClass().add("drawer-button");
        edit.setOnAction(e -> editHome(h));
        Button pay = new Button("Registrar pago");
        pay.getStyleClass().addAll("drawer-button", "strong");
        pay.setDisable(!h.active());
        pay.setOnAction(e -> registerPayment(h));
        for (Button b : List.of(toggle, edit, pay)) {
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setMaxWidth(Double.MAX_VALUE);
        }
        drawerActions.getChildren().setAll(toggle, edit, pay);
    }

    private Node paymentRow(HomeCard h, PaymentRow p) {
        Label amount = label(pesos(p.amount()), "payment-amount");
        if (p.status() == AlliedPaymentStatus.VOIDED) {
            amount.getStyleClass().add("voided");
        }
        String service = "Servicio " + (p.serviceDate() == null ? "" : clean(p.serviceDate().format(SHORT_DAY)))
                + (p.contractNumber() == null ? "" : " · " + p.contractNumber())
                + (p.deceasedName() == null || p.deceasedName().isBlank() ? "" : " · " + p.deceasedName());
        Label sub = label(service, "payment-sub");
        sub.setWrapText(true);
        VBox text = new VBox(2, amount, sub);
        if (p.status() == AlliedPaymentStatus.PAID && p.paidAt() != null) {
            text.getChildren().add(label("Pagado el " + p.paidAt().format(DAY) + " · egreso " + p.movementCode(), "payment-sub"));
        }
        HBox links = new HBox(4);
        if (p.status() == AlliedPaymentStatus.PENDING) {
            Button paid = new Button("Marcar pagado");
            paid.getStyleClass().add("link-button");
            paid.setOnAction(e -> markPaid(h, p));
            links.getChildren().add(paid);
        }
        if (p.status() != AlliedPaymentStatus.VOIDED) {
            Button voidIt = new Button("Anular");
            voidIt.getStyleClass().addAll("link-button", "danger");
            voidIt.setOnAction(e -> voidPayment(h, p));
            links.getChildren().add(voidIt);
        }
        text.getChildren().add(links);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label chip = label(p.status().getDisplayName(), "state-chip");
        chip.getStyleClass().add(switch (p.status()) {
            case PENDING -> "state-warn";
            case PAID -> "state-paid";
            case VOIDED -> "state-muted";
        });
        chip.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(10, text, chip);
        row.getStyleClass().add("payment-row");
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static Node pair(String caption, String value) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label v = label(value == null ? "—" : value, "info-value");
        v.setMinWidth(0);
        return new HBox(10, label(caption, "info-caption"), gap, v);
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
        drawerHomeId = null;
        markSelected();
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerHomeId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    // ---------- acciones ----------

    @FXML
    private void onNewHome() {
        homeForm(null);
    }

    private void editHome(HomeCard h) {
        homeForm(h);
    }

    /** Registrar o editar una funeraria: nombre, NIT, teléfono y fecha del convenio; ciudad y correo opcionales. */
    private void homeForm(HomeCard h) {
        Form form = new Form();
        TextField name = new TextField(h == null ? "" : h.name());
        name.setPromptText("Ej.: Funeraria La Esperanza");
        TextField nit = new TextField(h == null ? "" : h.nit());
        nit.setPromptText("Ej.: 900214556-3");
        TextField phone = new TextField(h == null ? "" : h.phone());
        phone.setPromptText("Ej.: 3005550142");
        TextField city = new TextField(h == null || h.city() == null ? "" : h.city());
        city.setPromptText("Ej.: Santa Marta");
        TextField email = new TextField(h == null || h.email() == null ? "" : h.email());
        email.setPromptText("Ej.: contacto@funeraria.co");
        DatePicker since = new DatePicker(h == null ? null : h.agreementStart());
        since.setPromptText("dd/mm/aaaa");
        since.setConverter(dateConverter());
        form.section("Funeraria", 3)
                .field("NOMBRE *", name)
                .field("NIT *", nit)
                .field("CIUDAD", city);
        form.section("Contacto y convenio", 3)
                .field("TELÉFONO DE CONTACTO *", phone)
                .field("CORREO", email)
                .field("CONVENIO DESDE *", since);
        form.note(note("El NIT no se puede repetir: dos funerarias con el mismo NIT son la misma."
                + (h == null ? " La funeraria queda activa y disponible para registrarle pagos." : "")));
        Dialog<ButtonType> dialog = formDialog(h == null ? "Nueva funeraria aliada" : "Editar funeraria aliada",
                "Los campos con * son obligatorios.", form, 760);
        runForm(dialog, form, h == null ? "Registrar" : "Guardar cambios", () -> {
            AlliedHomeData data = new AlliedHomeData(name.getText(), nit.getText(), phone.getText(), city.getText(),
                    email.getText(), since.getValue());
            if (h == null) {
                return service.registerHome(data);
            }
            service.updateHome(h.id(), data);
            return h.id();
        }, id -> {
            showToast(h == null ? "Funeraria aliada registrada." : "Cambios guardados.");
            load();
        });
    }

    /** Pago por un servicio prestado: contrato, monto y fecha. Queda pendiente. */
    private void registerPayment(HomeCard h) {
        Form form = new Form();
        TextField search = new TextField();
        search.setPromptText("Número del contrato, difunto o cliente");
        ComboBox<ContractOption> contract = new ComboBox<>();
        contract.getStyleClass().add("form-combo");
        contract.setPromptText("Elija el contrato");
        contract.setConverter(new StringConverter<>() {
            @Override
            public String toString(ContractOption o) {
                return o == null ? "" : o.label();
            }

            @Override
            public ContractOption fromString(String s) {
                return null;
            }
        });
        PauseTransition delay = new PauseTransition(Duration.millis(250));
        Runnable reload = () -> async("allied-contracts", () -> service.searchContracts(search.getText()), options -> {
            ContractOption chosen = contract.getValue();
            contract.getItems().setAll(options);
            if (chosen != null && options.stream().anyMatch(o -> o.id().equals(chosen.id()))) {
                contract.setValue(chosen);
            } else if (options.size() == 1) {
                contract.setValue(options.get(0));
            }
        }, failure -> { });
        delay.setOnFinished(e -> reload.run());
        search.textProperty().addListener((obs, old, text) -> delay.playFromStart());
        reload.run();
        TextField amount = new TextField();
        amount.setPromptText("$ 0");
        DatePicker date = new DatePicker(service.today());
        date.setConverter(dateConverter());
        form.section("Funeraria", 1)
                .field("FUNERARIA ALIADA", lockedField(h.name() + " · NIT " + h.nit()));
        form.section("Servicio prestado", 2)
                .field("BUSCAR CONTRATO", search)
                .field("CONTRATO *", contract)
                .field("MONTO *", amount)
                .field("FECHA DEL SERVICIO *", date);
        form.note(note("El pago queda pendiente. Cuando se le pague a la funeraria, márquelo como pagado en su"
                + " detalle: ahí se registra el egreso en Finanzas."));
        Dialog<ButtonType> dialog = formDialog("Registrar pago", "Los campos con * son obligatorios.", form, 700);
        runForm(dialog, form, "Registrar pago", () -> {
            BigDecimal value = parseAmount(amount.getText(), "El monto");
            if (value == null) {
                throw new InvalidDataException("Escriba el monto.");
            }
            return service.registerPayment(new AlliedPaymentData(h.id(),
                    contract.getValue() == null ? null : contract.getValue().id(), value, date.getValue()));
        }, id -> {
            showToast("Pago registrado como pendiente.");
            load();
            if (drawerHomeId != null) {
                openDrawer(drawerHomeId);
            }
        });
    }

    private void markPaid(HomeCard h, PaymentRow p) {
        if (!confirm("¿Marcar como pagado el pago de " + pesos(p.amount()) + " a " + h.name() + "?",
                "Se registra un egreso en Finanzas con la fecha y hora de ahora. Si después hay un error, anule el"
                        + " pago aquí y el egreso también se anula.", "Marcar pagado")) {
            return;
        }
        async("allied-paid", () -> {
            service.markPaid(p.id());
            return Boolean.TRUE;
        }, ok -> {
            showToast("Pago marcado como pagado y egreso registrado en Finanzas.");
            load();
        }, failure -> showError(message(failure, "No se pudo marcar el pago.")));
    }

    private void voidPayment(HomeCard h, PaymentRow p) {
        boolean paid = p.status() == AlliedPaymentStatus.PAID;
        if (!confirm("¿Anular el pago de " + pesos(p.amount()) + " a " + h.name() + "?",
                "No se borra: queda en la lista como anulado y el cambio queda en la bitácora."
                        + (paid ? " Como ya estaba pagado, su egreso " + p.movementCode()
                        + " también se anula en Finanzas." : ""), "Anular pago")) {
            return;
        }
        async("allied-void", () -> {
            service.voidPayment(p.id());
            return Boolean.TRUE;
        }, ok -> {
            showToast("Pago anulado.");
            load();
        }, failure -> showError(message(failure, "No se pudo anular el pago.")));
    }

    private void toggleActive(HomeCard h) {
        boolean activate = !h.active();
        String detail = activate ? "Vuelve a estar disponible para registrarle pagos."
                : "No se le podrán registrar pagos nuevos. Los pagos que ya tiene siguen igual y se pueden pagar o anular.";
        if (!confirm((activate ? "¿Activar el convenio con " : "¿Desactivar el convenio con ") + h.name() + "?", detail,
                activate ? "Activar" : "Desactivar")) {
            return;
        }
        async("allied-active", () -> {
            service.setActive(h.id(), activate);
            return Boolean.TRUE;
        }, ok -> {
            showToast(activate ? "Convenio activado." : "Convenio desactivado.");
            load();
        }, failure -> showError(message(failure, "No se pudo cambiar el estado del convenio.")));
    }

    private static TextField lockedField(String text) {
        TextField field = new TextField(text);
        field.setDisable(true);
        return field;
    }

    private static StringConverter<LocalDate> dateConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : date.format(DAY);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.trim(), DAY);
                } catch (java.time.format.DateTimeParseException e) {
                    return null;
                }
            }
        };
    }

    private void setEmpty(String text) {
        emptyLabel.setText(text);
        emptyLabel.setVisible(!text.isEmpty());
    }

    private static String clean(String text) {
        return text.replace(".", "");
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
            async("allied-save", save, result -> {
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
        alert.setTitle("Red de funerarias aliadas");
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

    /** Las ventanas cargan app.css, personal.css y allied-network.css (formulario El Edén y colores verdes). */
    private static void styleDialog(DialogPane pane) {
        pane.getStylesheets().addAll(
                AlliedNetworkController.class.getResource("/css/app.css").toExternalForm(),
                AlliedNetworkController.class.getResource("/css/personal.css").toExternalForm(),
                AlliedNetworkController.class.getResource("/css/allied-network.css").toExternalForm());
        pane.getStyleClass().addAll("personal-dialog", "allied-root");
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

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return java.text.Normalizer.normalize(text.trim().toLowerCase(ES), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
