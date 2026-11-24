package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.utils.enums.AlliedPaymentStatus;
import com.example.funerariamc.utils.enums.PaymentMethod;
import com.example.funerariamc.service.AlliedHomeData;
import com.example.funerariamc.service.AlliedNetworkService;
import com.example.funerariamc.service.AlliedNetworkService.ContractOption;
import com.example.funerariamc.service.AlliedNetworkService.FeeFrequency;
import com.example.funerariamc.service.AlliedNetworkService.HomeCard;
import com.example.funerariamc.service.AlliedNetworkService.HomeDetail;
import com.example.funerariamc.service.AlliedNetworkService.HomeState;
import com.example.funerariamc.service.AlliedNetworkService.Overview;
import com.example.funerariamc.service.AlliedNetworkService.PaymentRow;
import com.example.funerariamc.service.AlliedPaymentData;
import com.example.funerariamc.service.SaleParameters;
import com.example.funerariamc.ui.Pager;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.Notice;
import com.example.funerariamc.ui.FormShell;
import com.example.funerariamc.ui.DetailShell;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import java.time.YearMonth;
import java.time.format.TextStyle;
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

    /** Nombre del módulo que se muestra arriba del título en los avisos. */
    private static final String MODULE_NAME = "Red de funerarias aliadas";

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", ES);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("d MMM yyyy", ES);
    private static final String ALL_CITIES = "Todas";
    private final SaleParameters saleParameters;
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
    @FXML private Label toast;

    private List<HomeCard> homes = List.of();
    private Long drawerHomeId;
    private DetailShell detail;
    private List<HomeCard> shownHomes = List.of();
    private Pager<HomeCard> pager;
    /** Filtros con que se armó la página actual (para saber si hay que volver a la página 1). */
    private String lastFilter;
    private int generation;
    private SequentialTransition toastMotion;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));

    public AlliedNetworkController(AlliedNetworkService service, SaleParameters saleParameters) {
        this.service = service;
        this.saleParameters = saleParameters;
    }

    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        // Paginación como la bitácora: 12 tarjetas por página, debajo de la cuadrícula.
        pager = Pager.<HomeCard>local(Pager.CARD_PAGE, "funeraria", "funerarias", page -> {
            List<Node> cards = new ArrayList<>();
            for (HomeCard h : page) {
                cards.add(card(h));
            }
            cardsPane.getChildren().setAll(cards);
            resizeCards();
        }).scrollTopOf(cardsScroll).below(cardsScroll.getParent());
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

        detail = DetailShell.install(drawer, veil, DetailShell.Kind.PANEL);
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
        String last = o.month().minusMonths(1).getMonth().getDisplayName(TextStyle.FULL, ES)
                .toLowerCase(Locale.ROOT);
        servicesDetail.setText(diff == 0 ? "igual que " + last : (diff > 0 ? "+" : "") + diff + " frente a " + last);
        pendingValue.setText(shortPesos(o.pending()));
        pendingDetail.setText(o.homesWithBalance() == 0 ? "nada por pagar"
                : "en " + o.homesWithBalance() + (o.homesWithBalance() == 1 ? " funeraria" : " funerarias"));
        openValue.setText(String.valueOf(o.pendingPayments()));
        // Un pago viejo puede no tener fecha: entonces solo se dice que hay pagos por hacer
        openDetail.setText(o.pendingPayments() == 0 ? "sin pagos pendientes"
                : o.oldestPending() == null ? "por pagar a las aliadas"
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
        shownHomes = shown;
        // Un filtro nuevo vuelve a la página 1; recargar tras guardar se queda en la misma página.
        String filterKey = text + "|" + city + "|" + state;
        if (filterKey.equals(lastFilter)) {
            pager.setItems(shown);
        } else {
            pager.setItemsFromStart(shown);
        }
        lastFilter = filterKey;
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
        // Servicio: lo que prestó la aliada. Pago: salda sus servicios pendientes.
        Button service = new Button("Registrar servicio");
        service.getStyleClass().add("card-button");
        service.setDisable(!h.active());
        service.setOnAction(e -> registerService(h));
        Button pay = new Button("Registrar pago");
        pay.getStyleClass().addAll("card-button", "strong");
        pay.setDisable(h.pendingCount() == 0);
        pay.setOnAction(e -> payServices(h));
        for (Button b : List.of(detail, service, pay)) {
            HBox.setHgrow(b, Priority.ALWAYS);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox buttons = new HBox(8, detail, service, pay);

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
            String name = homes.stream().filter(h -> h.id().equals(homeId)).map(HomeCard::name).findFirst().orElse("");
            detail.show(homeId, new DetailShell.Hero("Funeraria aliada", name).onClose(this::closeDrawer),
                    List.of(DetailShell.empty("Cargando el convenio y los pagos…")), List.of(), null);
        }
        markSelected();
        async("allied-detail", () -> service.detail(homeId), loaded -> {
            if (homeId.equals(drawerHomeId)) {
                fillDrawer(loaded);
            }
        }, failure -> {
            if (homeId.equals(drawerHomeId)) {
                closeDrawer();
                showError(message(failure, "No se pudo cargar el detalle de la funeraria."));
            }
        });
    }

    private void markSelected() {
        for (int i = 0; i < cardsPane.getChildren().size(); i++) {
            cardsPane.getChildren().get(i).getStyleClass().remove("selected");
        }
        applyFilter();
    }

    private void fillDrawer(HomeDetail d) {
        HomeCard h = d.home();
        String place = (h.city() == null ? "" : h.city() + " · ") + "NIT " + h.nit();
        List<HomeCard> list = shownHomes.isEmpty() ? homes : shownHomes;
        DetailShell.Hero hero = new DetailShell.Hero("Funeraria aliada", h.name())
                .leading(DetailShell.initials(h.name()))
                .subtitle(place)
                .chips(h.state() == HomeState.WITH_BALANCE ? DetailShell.chip("● Con saldo", DetailShell.Tone.WARN) : null,
                        DetailShell.chip(h.active() ? "● Convenio activo" : "Convenio inactivo",
                                h.active() ? DetailShell.Tone.OK : DetailShell.Tone.NEUTRAL))
                .navigation(DetailShell.neighbor(list, x -> x.id().equals(h.id()), -1, x -> openDrawer(x.id())),
                        DetailShell.neighbor(list, x -> x.id().equals(h.id()), 1, x -> openDrawer(x.id())))
                .onClose(this::closeDrawer);

        List<Node> sections = new ArrayList<>();
        sections.add(DetailShell.section("Convenio", DetailShell.card(DetailShell.rows(
                "Convenio desde", h.agreementStart() == null ? "—" : clean(h.agreementStart().format(LONG_DAY)),
                "Teléfono", h.phone(),
                "Correo", h.email() == null ? "—" : h.email(),
                "Servicios prestados en " + d.year(), String.valueOf(d.servicesThisYear())))));

        List<DetailShell.Event> events = new ArrayList<>();
        for (PaymentRow p : d.payments()) {
            events.add(paymentEvent(h, p));
        }
        sections.add(DetailShell.section("Pagos por servicios prestados", events.isEmpty()
                ? DetailShell.empty("Aún no hay pagos registrados a esta funeraria.")
                : DetailShell.timeline(events)));

        Label balance = DetailShell.value(pesos(h.pending()),
                h.pending().signum() > 0 ? DetailShell.Tone.ALERT : DetailShell.Tone.OK);
        balance.getStyleClass().add("detail-big");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox balanceBox = new HBox(new VBox(2, label("SALDO POR PAGAR", "detail-field-key"), balance), gap,
                label(h.pendingCount() == 0 ? "sin pagos abiertos"
                        : h.pendingCount() + (h.pendingCount() == 1 ? " pago abierto" : " pagos abiertos"),
                        "detail-person-line"));
        balanceBox.setAlignment(Pos.CENTER_LEFT);
        sections.add(DetailShell.card(balanceBox));

        Button toggle = h.active() ? DetailShell.danger("Desactivar", () -> toggleActive(h))
                : DetailShell.secondary("Activar", () -> toggleActive(h));
        Button edit = DetailShell.secondary("Editar", () -> editHome(h));
        Button service = DetailShell.secondary("Registrar servicio", () -> registerService(h));
        service.setDisable(!h.active());
        Button pay = DetailShell.primary("Registrar pago", () -> payServices(h));
        pay.setDisable(h.pendingCount() == 0);
        detail.show(h.id(), hero, sections, List.of(toggle, edit, service, pay),
                h.active() ? null : "Registrar servicio: active el convenio primero");
    }

    /** Un pago a la aliada en el historial: valor, servicio, estado y sus enlaces (marcar pagado, anular). */
    private DetailShell.Event paymentEvent(HomeCard h, PaymentRow p) {
        String service = "Servicio" + (p.serviceDate() == null ? "" : " " + clean(p.serviceDate().format(SHORT_DAY)))
                + (p.contractNumber() == null ? "" : " · " + p.contractNumber())
                + (p.deceasedName() == null || p.deceasedName().isBlank() ? "" : " · " + p.deceasedName());
        if (p.status() == AlliedPaymentStatus.PAID && p.paidAt() != null) {
            service += "\nPagado el " + p.paidAt().format(DAY) + " · egreso " + p.movementCode();
        }
        DetailShell.Tone tone = switch (p.status()) {
            case PENDING -> DetailShell.Tone.ALERT;
            case PAID -> DetailShell.Tone.OK;
            case VOIDED -> DetailShell.Tone.NEUTRAL;
        };
        Label chip = DetailShell.chip(p.status().getDisplayName(), switch (p.status()) {
            case PENDING -> DetailShell.Tone.WARN;
            case PAID -> DetailShell.Tone.OK;
            case VOIDED -> DetailShell.Tone.NEUTRAL;
        });
        HBox links = new HBox(4);
        links.setAlignment(Pos.CENTER_RIGHT);
        if (p.status() == AlliedPaymentStatus.PENDING) {
            links.getChildren().add(DetailShell.link("Marcar pagado", () -> markPaid(h, p)));
        }
        if (p.status() != AlliedPaymentStatus.VOIDED) {
            Button voidIt = DetailShell.link("Anular", () -> voidPayment(h, p));
            voidIt.getStyleClass().add("detail-link-danger");
            links.getChildren().add(voidIt);
        }
        VBox aside = new VBox(4, chip, links);
        aside.setAlignment(Pos.TOP_RIGHT);
        aside.setMinWidth(Region.USE_PREF_SIZE);
        return new DetailShell.Event(pesos(p.amount()), service, tone, aside);
    }

    private static Node pair(String caption, String value) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label v = label(value == null ? "—" : value, "info-value");
        v.setMinWidth(0);
        return new HBox(10, label(caption, "info-caption"), gap, v);
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    private void closeDrawer() {
        drawerHomeId = null;
        markSelected();
        detail.close(null);
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
        FormShell.phone(phone);
        TextField city = new TextField(h == null || h.city() == null ? "" : h.city());
        city.setPromptText("Ej.: Santa Marta");
        TextField email = new TextField(h == null || h.email() == null ? "" : h.email());
        email.setPromptText("Ej.: contacto@funeraria.co");
        TextField address = new TextField(h == null || h.address() == null ? "" : h.address());
        address.setPromptText("Ej.: Calle 15 # 8-40");
        DatePicker since = new DatePicker(h == null ? null : h.agreementStart());
        since.setPromptText("dd/mm/aaaa");
        since.setConverter(dateConverter());
        form.section("Funeraria", 3)
                .field("NOMBRE *", name)
                .field("NIT *", nit)
                .field("CIUDAD", city)
                .field("DIRECCIÓN", address);
        form.section("Contacto y convenio", 3)
                .field("TELÉFONO DE CONTACTO *", phone)
                .field("CORREO", email)
                .field("CONVENIO DESDE *", since);
        form.note(note("El NIT no se puede repetir: dos funerarias con el mismo NIT son la misma."
                + (h == null ? " La funeraria queda activa y disponible para registrarle pagos." : "")));
        Dialog<ButtonType> dialog = formDialog(h == null ? "Nueva funeraria aliada" : "Editar funeraria aliada",
                "Los campos con * son obligatorios.", form, 760);
        FormShell.panel(dialog, "Red de funerarias aliadas");
        runForm(dialog, form, h == null ? "Registrar" : "Guardar cambios", () -> {
            AlliedHomeData data = new AlliedHomeData(name.getText(), nit.getText(), phone.getText(), city.getText(),
                    email.getText(), since.getValue(), address.getText());
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

    /**
     * Servicio que prestó la aliada: contrato, monto y fecha. Queda pendiente, o pagado de una vez si se
     * marca "Ya se le pagó" con el medio de pago.
     */
    private void registerService(HomeCard h) {
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
        Runnable reload = () -> async("allied-contracts", () -> service.searchContracts(h.id(), search.getText()), options -> {
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
        // Quién está en el contrato elegido (difunto y cliente), completo aunque el combo lo corte
        Label contractNote = note("Salen los contratos de esta funeraria (primero) y los que aún no tienen aliada; "
                + "los de otras aliadas no aparecen.");
        contractNote.setWrapText(true);
        contract.valueProperty().addListener((obs, old, o) -> contractNote.setText(o == null
                ? "Salen los contratos de esta funeraria (primero) y los que aún no tienen aliada; "
                + "los de otras aliadas no aparecen."
                : o.label()));
        TextField amount = new TextField();
        amount.setPromptText("$ 0");
        DatePicker date = new DatePicker(service.today());
        date.setConverter(dateConverter());
        CheckBox paidNow = new CheckBox("Ya se le pagó");
        paidNow.getStyleClass().add("form-check");
        ComboBox<PaymentMethod> method = methodCombo();
        method.disableProperty().bind(paidNow.selectedProperty().not());
        Label hint = note("");
        Runnable explain = () -> hint.setText(paidNow.isSelected()
                ? "Queda pagado y se registra el egreso en Finanzas con la fecha y hora de ahora."
                : "Queda pendiente en el saldo de la funeraria. Cuando se le pague, use «Registrar pago».");
        paidNow.selectedProperty().addListener((obs, old, on) -> explain.run());
        explain.run();
        form.section("Funeraria", 1)
                .field("FUNERARIA ALIADA", lockedField(h.name() + " · NIT " + h.nit()));
        form.section("Servicio prestado", 2)
                .field("BUSCAR CONTRATO", search)
                .field("CONTRATO *", contract)
                .note(contractNote)
                .field("MONTO *", amount)
                .field("FECHA DEL SERVICIO *", date);
        form.section("Pago", 2)
                .field("¿SE PAGÓ?", paidNow)
                .field("MEDIO DE PAGO", method);
        form.note(hint);
        Dialog<ButtonType> dialog = formDialog("Registrar servicio", "Los campos con * son obligatorios.", form, 700);
        FormShell.panel(dialog, "Red de funerarias aliadas");
        FormShell.money(amount);
        runForm(dialog, form, "Registrar servicio", () -> {
            BigDecimal value = parseAmount(amount.getText(), "El monto");
            if (value == null) {
                throw new InvalidDataException("Escriba el monto.");
            }
            if (paidNow.isSelected() && method.getValue() == null) {
                throw new InvalidDataException("Elija si se pagó por transferencia o por consignación.");
            }
            return service.registerService(new AlliedPaymentData(h.id(),
                    contract.getValue() == null ? null : contract.getValue().id(), value, date.getValue()),
                    paidNow.isSelected() ? method.getValue() : null);
        }, id -> {
            showToast(paidNow.isSelected() ? "Servicio registrado y pagado; el egreso quedó en Finanzas."
                    : "Servicio registrado como pendiente.");
            load();
            if (drawerHomeId != null) {
                openDrawer(drawerHomeId);
            }
        });
    }

    /**
     * Pagar a la aliada: se ven solo sus servicios pendientes (contrato, difunto, fecha y valor), se marcan
     * los que se pagan y quedan pagados, cada uno con su egreso en Finanzas.
     */
    private void payServices(HomeCard h) {
        Form form = new Form();
        VBox list = new VBox(8);
        Label total = new Label("$ 0");
        total.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        ComboBox<PaymentMethod> method = methodCombo();
        List<CheckBox> checks = new ArrayList<>();
        Runnable sum = () -> total.setText(pesos(checks.stream().filter(CheckBox::isSelected)
                .map(c -> ((PaymentRow) c.getUserData()).amount()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        list.getChildren().add(note("Cargando los servicios pendientes…"));
        async("allied-pending", () -> service.pendingServices(h.id()), rows -> {
            list.getChildren().clear();
            if (rows.isEmpty()) {
                list.getChildren().add(note("Esta funeraria no tiene servicios pendientes por pagar."));
                return;
            }
            for (PaymentRow r : rows) {
                String text = (r.contractNumber() == null ? "Sin contrato" : r.contractNumber())
                        + (r.deceasedName() == null || r.deceasedName().isBlank() ? "" : " · " + r.deceasedName())
                        + (r.serviceDate() == null ? "" : " · " + clean(r.serviceDate().format(SHORT_DAY)))
                        + "   " + pesos(r.amount());
                CheckBox check = new CheckBox(text);
                check.getStyleClass().add("form-check");
                check.setUserData(r);
                check.setSelected(rows.size() == 1); // si debe uno solo, ya viene marcado
                check.selectedProperty().addListener((obs, old, on) -> sum.run());
                checks.add(check);
                list.getChildren().add(check);
            }
            sum.run();
        }, failure -> {
            list.getChildren().setAll(note("No se pudieron cargar los servicios pendientes."));
        });
        form.section("Funeraria", 1)
                .field("FUNERARIA ALIADA", lockedField(h.name() + " · NIT " + h.nit()));
        form.section("Servicios pendientes", 1)
                .field("MARQUE LOS QUE SE PAGAN", list);
        form.section("Pago", 2)
                .field("TOTAL A PAGAR", total)
                .field("MEDIO DE PAGO *", method);
        form.note(note("Cada servicio marcado queda pagado y se registra su egreso en Finanzas con la fecha y hora"
                + " de ahora."));
        Dialog<ButtonType> dialog = formDialog("Registrar pago", "Los campos con * son obligatorios.", form, 700);
        FormShell.panel(dialog, "Red de funerarias aliadas");
        runForm(dialog, form, "Registrar pago", () -> {
            List<Long> ids = checks.stream().filter(CheckBox::isSelected)
                    .map(c -> ((PaymentRow) c.getUserData()).id()).toList();
            service.markPaid(ids, method.getValue());
            return ids.size();
        }, count -> {
            showToast(count == 1 ? "Pago registrado; el egreso quedó en Finanzas."
                    : count + " servicios pagados; sus egresos quedaron en Finanzas.");
            load();
            if (drawerHomeId != null) {
                openDrawer(drawerHomeId);
            }
        });
    }

    /** A la aliada se le paga por transferencia o consignando en su cuenta corriente. */
    private void markPaid(HomeCard h, PaymentRow p) {
        Form form = new Form();
        ComboBox<PaymentMethod> method = methodCombo();
        form.section("Pago", 2)
                .field("FUNERARIA ALIADA", lockedField(h.name() + " · " + pesos(p.amount())))
                .field("MEDIO DE PAGO *", method);
        form.note(note("Se registra un egreso en Finanzas con la fecha y hora de ahora. Si después hay un error,"
                + " anule el pago aquí y el egreso también se anula."));
        Dialog<ButtonType> dialog = formDialog("Marcar como pagado", "Los campos con * son obligatorios.", form, 620);
        FormShell.window(dialog, "Red de funerarias aliadas");
        runForm(dialog, form, "Marcar pagado", () -> {
            if (method.getValue() == null) {
                throw new InvalidDataException("Elija si se pagó por transferencia o por consignación.");
            }
            service.markPaid(p.id(), method.getValue());
            return Boolean.TRUE;
        }, ok -> {
            showToast("Pago marcado como pagado y egreso registrado en Finanzas.");
            load();
        });
    }

    private static ComboBox<PaymentMethod> methodCombo() {
        ComboBox<PaymentMethod> method = new ComboBox<>();
        method.getStyleClass().add("form-combo");
        method.getItems().setAll(PaymentMethod.values());
        FormShell.choose(method); // sin medio de pago puesto: el usuario lo elige
        method.setConverter(new StringConverter<>() {
            @Override
            public String toString(PaymentMethod m) {
                return m == null ? "" : m.getDisplayName();
            }

            @Override
            public PaymentMethod fromString(String text) {
                return null;
            }
        });
        return method;
    }

    /**
     * Cuota de la red de funerarias (Remanso): se escoge pagarla mensual, semestral o anual y cada
     * pago queda como egreso en Finanzas. Muestra también los pagos anteriores.
     */
    @FXML
    private void onNetworkFee() {
        Form form = new Form();
        ComboBox<FeeFrequency> frequency = new ComboBox<>();
        frequency.getStyleClass().add("form-combo");
        frequency.getItems().setAll(FeeFrequency.values());
        FormShell.choose(frequency); // sin periodicidad puesta: el usuario la elige
        frequency.setConverter(new StringConverter<>() {
            @Override
            public String toString(FeeFrequency f) {
                return f == null ? "" : f.getLabel();
            }

            @Override
            public FeeFrequency fromString(String text) {
                return null;
            }
        });
        ComboBox<YearMonth> from = new ComboBox<>();
        from.getStyleClass().add("form-combo");
        YearMonth now = YearMonth.from(service.today());
        for (YearMonth m = now.minusMonths(12); !m.isAfter(now.plusMonths(12)); m = m.plusMonths(1)) {
            from.getItems().add(m);
        }
        FormShell.choose(from); // sin mes puesto: el usuario elige desde qué mes paga
        from.setConverter(new StringConverter<>() {
            @Override
            public String toString(YearMonth m) {
                return m == null ? "" : m.getMonth().getDisplayName(TextStyle.FULL, ES) + " " + m.getYear();
            }

            @Override
            public YearMonth fromString(String text) {
                return null;
            }
        });
        TextField amount = new TextField();
        amount.setPromptText("$ 0");
        async("network-fee-amount", () -> saleParameters.suggested(SaleParameters.NETWORK_FEE_AMOUNT), value -> {
            if (value != null && amount.getText().isBlank()) {
                amount.setText(NumberFormat.getIntegerInstance(ES).format(value));
            }
        }, failure -> { });
        ComboBox<PaymentMethod> method = methodCombo();
        VBox history = new VBox(6);
        history.getChildren().add(label("Cargando pagos anteriores…", "card-subtitle"));
        async("network-fees", service::networkFees, fees -> {
            history.getChildren().clear();
            if (fees.isEmpty()) {
                history.getChildren().add(label("Todavía no hay pagos de la cuota registrados.", "card-subtitle"));
            }
            for (AlliedNetworkService.NetworkFee fee : fees.stream().limit(6).toList()) {
                history.getChildren().add(label(fee.period() + " · " + pesos(fee.amount()) + " · "
                        + DAY.format(fee.paidAt().toLocalDate()) + " · " + fee.movementCode(), "card-subtitle"));
            }
        }, failure -> history.getChildren().setAll(label("No se pudieron cargar los pagos anteriores.", "card-subtitle")));
        form.section("Cuota de la red", 2)
                .field("PERIODICIDAD *", frequency)
                .field("DESDE *", from)
                .field("VALOR *", amount)
                .field("MEDIO DE PAGO *", method);
        form.note(note("El valor sugerido sale de Parámetros del sistema y se puede cambiar. Un mismo período no se"
                + " paga dos veces; si hubo un error, anule el egreso en Finanzas › Movimientos."));
        form.section("Pagos anteriores", 1);
        form.note(history);
        Dialog<ButtonType> dialog = formDialog("Pagar cuota del Remanso", "Los campos con * son obligatorios.", form, 700);
        FormShell.panel(dialog, "Red de funerarias aliadas");
        FormShell.money(amount);
        runForm(dialog, form, "Registrar pago", () -> {
            BigDecimal value = parseAmount(amount.getText(), "El valor");
            return service.payNetworkFee(frequency.getValue(), from.getValue(), value, method.getValue());
        }, id -> {
            showToast("Cuota de la red registrada como egreso en Finanzas.");
            load();
        });
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
        return Notice.confirm(window(), MODULE_NAME, question, detail, actionText);
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
