package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.service.EmployeeService;
import com.example.funerariamc.service.RoleNames;
import com.example.funerariamc.service.UserAccountService;
import com.example.funerariamc.service.UserAccountService.AccountSummary;
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
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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
 * Pantalla de Personal (propuesta P-08 / P-08.1): tarjetas por empleado con su cuenta de acceso,
 * filtros por área y estado, indicadores y un panel lateral de detalle.
 * Desde aquí el Administrador Maestro crea la cuenta y restablece la contraseña (HU-06, HU-07).
 * Un empleado nunca se elimina (RN-19).
 */
@Component
@Scope("prototype")
public class EmployeeController {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Tarjetas por tanda: 4 filas de 3. Al bajar hasta el final se piden 12 más. */
    private static final int PAGE_SIZE = 12;
    private static final int COLUMNS = 3;
    private static final double DRAWER_WIDTH = 440;
    private static final PseudoClass INACTIVE = PseudoClass.getPseudoClass("inactive");

    /** Áreas de la pantalla: cada chip filtra uno o varios cargos. */
    private record Area(String label, List<EmployeePosition> positions) {
    }

    private static final List<Area> AREAS = List.of(
            new Area("Todos", List.of()),
            new Area("Administración", List.of(EmployeePosition.MANAGER, EmployeePosition.RECEPTIONIST)),
            new Area("Ventas", List.of(EmployeePosition.FUNERAL_SALESPERSON)),
            new Area("Tanatopraxia", List.of(EmployeePosition.THANATOPRACTOR, EmployeePosition.THANATOPRAXY_ASSISTANT)),
            new Area("Conductores", List.of(EmployeePosition.DRIVER)),
            new Area("Cafetería", List.of(EmployeePosition.CAFETERIA_ATTENDANT)));

    private enum StatusOption {
        ACTIVE("Activos", EmployeeStatus.ACTIVE),
        INACTIVE("Inactivos", EmployeeStatus.INACTIVE),
        ALL("Todos", null);

        private final String label;
        private final EmployeeStatus status;

        StatusOption(String label, EmployeeStatus status) {
            this.label = label;
            this.status = status;
        }
    }

    private final EmployeeService employeeService;
    private final UserAccountService userAccountService;
    private final FxmlViewLoader viewLoader;
    /** Espera a que el usuario deje de escribir para no consultar la BD en cada tecla. */
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    @FXML
    private StackPane root;
    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup areaGroup;
    @FXML
    private HBox areaChips;
    @FXML
    private ComboBox<StatusOption> statusFilter;
    @FXML
    private Label activeValue;
    @FXML
    private Label activeDetail;
    @FXML
    private Label accountsValue;
    @FXML
    private Label expiringValue;
    @FXML
    private Label inactiveValue;
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

    /** Empleados ya cargados (en el orden de las tarjetas) y sus cuentas. */
    private final List<Employee> employees = new ArrayList<>();
    private final Map<Long, AccountSummary> accounts = new HashMap<>();
    private long totalMatches;
    private boolean loading;
    /** Cada búsqueda nueva invalida las respuestas que aún vienen en camino. */
    private int generation;
    /** Empleado abierto en el panel lateral (null si está cerrado). */
    private Long drawerEmployeeId;

    public EmployeeController(EmployeeService employeeService, UserAccountService userAccountService,
                              FxmlViewLoader viewLoader) {
        this.employeeService = employeeService;
        this.userAccountService = userAccountService;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        for (Area area : AREAS) {
            ToggleButton chip = new ToggleButton(area.label());
            chip.getStyleClass().addAll("chip", "area-chip");
            chip.setUserData(area);
            chip.setToggleGroup(areaGroup);
            chip.setMinWidth(Region.USE_PREF_SIZE);
            areaChips.getChildren().add(chip);
        }
        areaGroup.selectToggle(areaGroup.getToggles().get(0));
        areaGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un área elegida
            } else {
                reload();
            }
        });

        statusFilter.getItems().setAll(StatusOption.values());
        statusFilter.setValue(StatusOption.ACTIVE);
        statusFilter.setButtonCell(statusCell());
        statusFilter.setCellFactory(list -> statusCell());
        statusFilter.valueProperty().addListener((obs, old, value) -> reload());

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

    /** Resultado de una consulta: la tanda de empleados, sus cuentas y los indicadores. */
    private record Batch(Page<Employee> page, Map<Long, AccountSummary> accounts,
                         long total, long active, long withAccount, long expiring) {
    }

    /** Un filtro cambió o se guardó algo: vuelve a traer desde el principio lo que ya se veía. */
    private void reload() {
        int size = Math.min(Math.max(PAGE_SIZE, employees.size()), EmployeeService.MAX_PAGE_SIZE);
        query(0, size, true);
    }

    @FXML
    private void onLoadMore() {
        loadMore();
    }

    private void loadMore() {
        if (!loading) {
            query(employees.size() / PAGE_SIZE, PAGE_SIZE, false);
        }
    }

    private boolean hasMore() {
        return employees.size() < totalMatches;
    }

    /** Igual que la bitácora: la consulta corre en otro hilo para que la ventana no se congele. */
    private void query(int pageIndex, int size, boolean replace) {
        String text = searchField.getText();
        EmployeeStatus status = statusFilter.getValue() == null ? null : statusFilter.getValue().status;
        List<EmployeePosition> positions = selectedArea().positions();
        int requestGeneration = replace ? ++generation : generation;
        loading = true;
        showingLabel.setText("Cargando empleados…");
        Task<Batch> task = new Task<>() {
            @Override
            protected Batch call() {
                Page<Employee> page = employeeService.searchByPositions(text, status, positions, pageIndex, size);
                List<Long> ids = page.getContent().stream().map(Employee::getId).toList();
                return new Batch(page, userAccountService.findAccounts(ids),
                        employeeService.count(null), employeeService.count(EmployeeStatus.ACTIVE),
                        userAccountService.countActiveAccounts(), employeeService.countExpiringDocuments());
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
                    : "No se pudo cargar el personal. Intente de nuevo.");
        });
        Thread thread = new Thread(task, "employee-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(Batch batch, boolean replace) {
        loading = false;
        if (replace) {
            employees.clear();
            accounts.clear();
        }
        employees.addAll(batch.page().getContent());
        accounts.putAll(batch.accounts());
        totalMatches = batch.page().getTotalElements();
        renderCards();

        long inactive = batch.total() - batch.active();
        activeValue.setText(String.valueOf(batch.active()));
        activeDetail.setText("de " + count(batch.total(), "empleado"));
        accountsValue.setText(String.valueOf(batch.withAccount()));
        expiringValue.setText(String.valueOf(batch.expiring()));
        expiringValue.pseudoClassStateChanged(PseudoClass.getPseudoClass("warning"), batch.expiring() > 0);
        inactiveValue.setText(String.valueOf(inactive));
        showingLabel.setText(totalMatches == 0 ? "" : employees.size() + " de " + count(totalMatches, "empleado"));

        // El panel abierto se vuelve a dibujar con los datos recién guardados.
        if (drawerEmployeeId != null) {
            findLoaded(drawerEmployeeId).ifPresentOrElse(this::fillDrawer, this::closeDrawer);
        }
    }

    private void renderCards() {
        cardGrid.getChildren().clear();
        for (int i = 0; i < employees.size(); i++) {
            cardGrid.add(card(employees.get(i)), i % COLUMNS, i / COLUMNS);
        }
        boolean empty = employees.isEmpty();
        emptyLabel.setVisible(empty);
        emptyLabel.setManaged(empty);
        moreButton.setVisible(hasMore());
        moreButton.setManaged(hasMore());
    }

    private Optional<Employee> findLoaded(Long id) {
        return employees.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    private Area selectedArea() {
        return areaGroup.getSelectedToggle() == null ? AREAS.get(0) : (Area) areaGroup.getSelectedToggle().getUserData();
    }

    // ---------- tarjeta ----------

    private VBox card(Employee e) {
        EmployeePosition position = EmployeePosition.of(e);

        VBox names = new VBox(6, wrapped(fullName(e), "card-name"), pill(position.getDisplayName(e.getSex()), "pill-neutral"));
        HBox.setHgrow(names, Priority.ALWAYS);
        HBox header = new HBox(12, avatar(e, "avatar"), names, statusPill(e));
        header.setAlignment(Pos.TOP_LEFT);

        GridPane info = infoGrid();
        infoRow(info, 0, "Documento", formatDocument(e.getDocumentNumber()));
        infoRow(info, 1, "Teléfono", orDash(e.getPhone()));
        infoRow(info, 2, "Ingreso", e.getHireDate() == null ? "—" : DATE.format(e.getHireDate()));

        VBox card = new VBox(12, header, divider(), info, accountRow(e, position));
        warning(e).ifPresent(card.getChildren()::add);
        if (e instanceof FuneralSalesperson salesperson && salesperson.getCommissionRate() != null) {
            card.getChildren().add(band("Comisión " + plain(salesperson.getCommissionRate()) + " %", "band-info"));
        }

        Button detail = cardButton("Ver detalle");
        detail.setOnAction(event -> openDrawer(e));
        Button second;
        if (e.isActive()) {
            second = cardButton("Editar");
            second.setOnAction(event -> openForm(e));
        } else {
            second = cardButton("Activar");
            second.getStyleClass().add("card-button-activate");
            second.setOnAction(event -> onActivate(e));
        }
        HBox buttons = new HBox(10, detail, second);
        Pane spacer = new Pane();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        card.getChildren().addAll(spacer, buttons);

        card.getStyleClass().add("employee-card");
        card.pseudoClassStateChanged(INACTIVE, !e.isActive());
        card.setMaxHeight(Double.MAX_VALUE);
        card.setMinWidth(0); // la columna (33 %) manda; el nombre largo pasa a dos líneas
        card.setPrefWidth(0);
        GridPane.setFillHeight(card, true);
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openDrawer(e);
            }
        });
        return card;
    }

    /** Fila "Cuenta · usuario  [rol]", o la invitación a crearla, o que el cargo no ingresa al sistema. */
    private HBox accountRow(Employee e, EmployeePosition position) {
        AccountSummary account = accounts.get(e.getId());
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("account-row");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        if (account != null) {
            Label user = new Label("Cuenta · " + account.username());
            user.getStyleClass().add("account-user");
            row.getChildren().addAll(user, spacer, pill(RoleNames.displayName(account.roleName(), e.getSex()), "pill-role"));
            if (!account.enabled()) {
                row.getStyleClass().add("account-disabled");
            }
        } else if (position.canHaveUserAccount()) {
            Label none = new Label("Sin cuenta de acceso");
            none.getStyleClass().add("account-none");
            row.getChildren().addAll(none, spacer);
            if (e.isActive()) {
                Button create = new Button("Crear cuenta");
                create.getStyleClass().add("account-link");
                create.setOnAction(event -> onCreateAccount(e));
                row.getChildren().add(create);
            }
        } else {
            Label none = new Label("Cargo sin cuenta de acceso");
            none.getStyleClass().add("account-none");
            row.getChildren().add(none);
            row.getStyleClass().add("account-na");
        }
        return row;
    }

    /** Avisos de la tarjeta: licencia (RN-26) y contraseña temporal pendiente. */
    private Optional<Label> warning(Employee e) {
        if (employeeService.hasExpiredLicense(e)) {
            return Optional.of(band("Licencia vencida: no se asigna a servicios (RN-26)", "band-alert"));
        }
        if (employeeService.hasLicenseExpiringSoon(e) && e instanceof Driver driver) {
            return Optional.of(band("Licencia vence el " + DATE.format(driver.getLicenseExpiryDate()), "band-warning"));
        }
        AccountSummary account = accounts.get(e.getId());
        if (account != null && account.enabled() && account.mustChangePassword()) {
            return Optional.of(band("Debe cambiar la contraseña temporal al ingresar", "band-warning"));
        }
        return Optional.empty();
    }

    // ---------- panel lateral ----------

    private void openDrawer(Employee e) {
        drawerEmployeeId = e.getId();
        fillDrawer(e);
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
        drawerEmployeeId = null;
        if (!drawer.isVisible()) {
            return;
        }
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(DRAWER_WIDTH);
        FadeTransition fade = new FadeTransition(Duration.millis(180), veil);
        fade.setToValue(0);
        ParallelTransition out = new ParallelTransition(slide, fade);
        out.setOnFinished(event -> {
            if (drawerEmployeeId == null) {
                drawer.setVisible(false);
                veil.setVisible(false);
            }
        });
        out.play();
    }

    private void fillDrawer(Employee e) {
        EmployeePosition position = EmployeePosition.of(e);
        AccountSummary account = accounts.get(e.getId());

        Label caption = new Label("EMPLEADO");
        caption.getStyleClass().add("kpi-caption");
        Label subtitle = wrapped(position.getDisplayName(e.getSex()) + " · " + documentLabel(e)
                + (e.getHireDate() == null ? "" : " · desde " + DATE.format(e.getHireDate())), "card-subtitle");
        VBox titles = new VBox(2, caption, wrapped(fullName(e), "drawer-name"), subtitle);
        HBox.setHgrow(titles, Priority.ALWAYS);
        Button close = new Button("✕");
        close.getStyleClass().add("drawer-close");
        close.setOnAction(event -> closeDrawer());
        HBox header = new HBox(14, avatar(e, "avatar-large"), titles, close);
        header.setAlignment(Pos.TOP_LEFT);

        HBox pills = new HBox(8, statusPill(e));
        if (employeeService.hasExpiredLicense(e)) {
            pills.getChildren().add(pill("Licencia vencida", "pill-alert"));
        } else if (employeeService.hasLicenseExpiringSoon(e)) {
            pills.getChildren().add(pill("Licencia por vencer", "pill-warning"));
        }

        drawerBody.getChildren().setAll(header, pills,
                section("CONTACTO",
                        "Teléfono", orDash(e.getPhone()),
                        "Correo", orDash(e.getEmail()),
                        "Dirección", orDash(e.getAddress())),
                section("DATOS LABORALES",
                        "Cargo", position.getDisplayName(e.getSex()),
                        "Ingreso", e.getHireDate() == null ? "—" : DATE.format(e.getHireDate()),
                        "Salario", pesos(e.getSalary())));
        positionSection(e).ifPresent(drawerBody.getChildren()::add);
        drawerBody.getChildren().add(accountSection(e, position, account));

        Button toggle = new Button(e.isActive() ? "Desactivar" : "Activar");
        toggle.getStyleClass().addAll("drawer-button", e.isActive() ? "drawer-danger" : "drawer-activate");
        toggle.setOnAction(event -> {
            if (e.isActive()) {
                onDeactivate(e);
            } else {
                onActivate(e);
            }
        });
        Button edit = new Button("Editar");
        edit.getStyleClass().addAll("drawer-button", "drawer-primary");
        edit.setOnAction(event -> openForm(e));
        for (Button button : List.of(toggle, edit)) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }
        drawerActions.getChildren().setAll(toggle, edit);
    }

    /** Datos propios del cargo: licencia del conductor, certificación del tanatólogo o comisión del vendedor. */
    private Optional<VBox> positionSection(Employee e) {
        if (e instanceof Driver d) {
            String expiry = d.getLicenseExpiryDate() == null ? "—" : DATE.format(d.getLicenseExpiryDate());
            VBox box = section("LICENCIA DE CONDUCCIÓN",
                    "Categoría", orDash(d.getLicenseCategory()),
                    "Número", orDash(d.getLicenseNumber()),
                    "Vence", expiry,
                    "Archivo", fileName(d.getLicenseFileUrl()));
            if (employeeService.hasExpiredLicense(e) || employeeService.hasLicenseExpiringSoon(e)) {
                box.lookupAll(".info-value").stream()
                        .filter(node -> node instanceof Label label && label.getText().equals(expiry))
                        .forEach(node -> node.getStyleClass().add("info-warning"));
            }
            return Optional.of(box);
        }
        if (e instanceof Thanatopractor t) {
            return Optional.of(section("CERTIFICACIÓN",
                    "Institución", orDash(t.getTrainingInstitution()),
                    "Fecha", t.getCertificationDate() == null ? "—" : DATE.format(t.getCertificationDate()),
                    "Comisión por caso", pesos(t.getCommissionPerCase()),
                    "Archivo", fileName(t.getCertificateFileUrl())));
        }
        if (e instanceof FuneralSalesperson s) {
            return Optional.of(section("VENTAS",
                    "Comisión", s.getCommissionRate() == null ? "—" : plain(s.getCommissionRate()) + " %"));
        }
        return Optional.empty();
    }

    /** HU-06 / HU-07: la cuenta se crea y se restablece aquí mismo, sin otro módulo. */
    private VBox accountSection(Employee e, EmployeePosition position, AccountSummary account) {
        Label title = new Label("CUENTA DE ACCESO");
        title.getStyleClass().add("kpi-caption");
        VBox box = new VBox(10);
        box.getStyleClass().add("info-box");
        if (!position.canHaveUserAccount()) {
            GridPane grid = infoGrid();
            infoRow(grid, 0, "Cuenta", "No aplica: este cargo no ingresa al sistema (RN-23)");
            box.getChildren().add(grid);
        } else if (account == null) {
            box.getChildren().add(wrapped(e.isActive()
                    ? "Aún no tiene cuenta. Al crearla se genera una contraseña temporal que "
                    + e.getName() + " cambia en su primer ingreso."
                    : "Sin cuenta. Active al empleado para poder crearle una.", "account-hint"));
            if (e.isActive()) {
                Button create = new Button("Crear cuenta de acceso");
                create.getStyleClass().add("primary-button");
                create.setOnAction(event -> onCreateAccount(e));
                box.getChildren().add(create);
            }
        } else {
            GridPane grid = infoGrid();
            infoRow(grid, 0, "Usuario", account.username());
            infoRow(grid, 1, "Rol", RoleNames.displayName(account.roleName(), e.getSex()));
            infoRow(grid, 2, "Estado", EmployeeFormController.accountState(account));
            box.getChildren().addAll(grid, wrapped("Para cambiar la contraseña más adelante, el empleado usa "
                    + "“¿Olvidó su contraseña?” en el inicio de sesión.", "account-hint"));
        }
        return new VBox(8, title, box);
    }

    // ---------- acciones ----------

    @FXML
    private void onRegister() {
        openForm(null);
    }

    private void openForm(Employee employee) {
        FxmlViewLoader.View<EmployeeFormController> view = viewLoader.loadView(FxmlViewLoader.EMPLOYEE_FORM);
        Stage stage = new Stage();
        stage.initOwner(window());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(employee == null ? "Registrar empleado" : "Actualizar empleado");
        stage.setScene(new Scene(view.root()));
        view.controller().init(employee, saved -> reload());
        stage.showAndWait();
    }

    private void onCreateAccount(Employee e) {
        try {
            AccountDialogs.createAccount(window(), e, userAccountService).ifPresent(credentials -> {
                AccountDialogs.showCredentials(window(), e, credentials);
                reload();
            });
        } catch (BusinessException ex) {
            showError(ex.getMessage());
        }
    }

    /** HU-04: pide confirmación y explica que no se borra. */
    private void onDeactivate(Employee employee) {
        boolean hasAccount = accounts.containsKey(employee.getId());
        String consequence = hasAccount
                ? "Su cuenta de acceso se deshabilita y no podrá iniciar sesión."
                : "No podrá asignarse a servicios.";
        ButtonType deactivate = new ButtonType("Desactivar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE, "", cancel, deactivate);
        alert.initOwner(window());
        alert.setTitle("Desactivar empleado");
        alert.setHeaderText("¿Desactivar a " + fullName(employee) + "?");
        alert.setContentText(EmployeePosition.displayNameOf(employee) + ", " + documentLabel(employee)
                + ", pasará a INACTIVO. " + consequence
                + "\n\nNo se borra: conserva su historial y puede activarse de nuevo (RN-19).");
        styleDialog(alert);
        alert.getDialogPane().lookupButton(deactivate).getStyleClass().add("danger-button");
        alert.showAndWait().filter(deactivate::equals).ifPresent(answer -> {
            try {
                employeeService.deactivate(employee.getId());
                reload();
            } catch (BusinessException e) {
                showError(e.getMessage());
            }
        });
    }

    private void onActivate(Employee employee) {
        try {
            employeeService.activate(employee.getId());
            reload();
        } catch (BusinessException e) {
            showError(e.getMessage());
        }
    }

    // ---------- piezas ----------

    /** Círculo con las iniciales ("JA" para Jorge Araújo). */
    private static StackPane avatar(Employee e, String styleClass) {
        Label initials = new Label(initial(e.getName()) + initial(e.getLastname()));
        initials.getStyleClass().add("avatar-text");
        StackPane circle = new StackPane(initials);
        circle.getStyleClass().add(styleClass);
        circle.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        circle.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return circle;
    }

    static Label statusBadge(Employee e) {
        return statusPill(e);
    }

    private static Label statusPill(Employee e) {
        Label pill = pill(e.isActive() ? "●  Activo" : "●  Inactivo", e.isActive() ? "status-active" : "status-inactive");
        pill.getStyleClass().add("status-badge");
        return pill;
    }

    private static Label pill(String text, String styleClass) {
        Label pill = new Label(text);
        pill.getStyleClass().addAll("pill", styleClass);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        return pill;
    }

    private static Label band(String text, String styleClass) {
        Label band = wrapped(text, "band");
        band.getStyleClass().add(styleClass);
        band.setMaxWidth(Double.MAX_VALUE);
        return band;
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

    private static Button cardButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("card-button");
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
        val.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        GridPane.setHgrow(val, Priority.ALWAYS);
        grid.add(key, 0, row);
        grid.add(val, 1, row);
    }

    /** Sección del panel: título y un recuadro con pares etiqueta / valor. */
    private static VBox section(String title, String... pairs) {
        Label caption = new Label(title);
        caption.getStyleClass().add("kpi-caption");
        GridPane grid = infoGrid();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            infoRow(grid, i / 2, pairs[i], pairs[i + 1]);
        }
        VBox box = new VBox(grid);
        box.getStyleClass().add("info-box");
        return new VBox(8, caption, box);
    }

    private static ListCell<StatusOption> statusCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(StatusOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label);
            }
        };
    }

    // ---------- utilidades ----------

    /** "C.C. 1.065.234.118": tipo abreviado y número con puntos. */
    static String documentLabel(Employee e) {
        String type = e.getDocumentType() == null ? "" : switch (e.getDocumentType()) {
            case "CC" -> "C.C. ";
            case "CE" -> "C.E. ";
            case "PA" -> "Pasaporte ";
            default -> e.getDocumentType() + " ";
        };
        return type + formatDocument(e.getDocumentNumber());
    }

    /** 1065234118 → 1.065.234.118 (solo números; pasaportes se muestran tal cual). */
    static String formatDocument(String document) {
        if (document == null || !document.matches("\\d+")) {
            return orDash(document);
        }
        return String.format("%,d", Long.parseLong(document)).replace(',', '.');
    }

    /** 1850000 → "$ 1.850.000". */
    private static String pesos(BigDecimal value) {
        return value == null ? "—" : "$ " + String.format("%,d", value.longValue()).replace(',', '.');
    }

    /** 5.00 → "5", 2.50 → "2,5". */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String fileName(String url) {
        if (url == null || url.isBlank()) {
            return "Sin archivo";
        }
        return url.substring(url.replace('\\', '/').lastIndexOf('/') + 1);
    }

    /** "1 activo", "5 activos". */
    private static String count(long amount, String word) {
        return amount + " " + word + (amount == 1 ? "" : "s");
    }

    private static String initial(String text) {
        return text == null || text.isBlank() ? "" : text.trim().substring(0, 1).toUpperCase();
    }

    static String fullName(Employee e) {
        return e.getName() + " " + e.getLastname();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static void styleDialog(Alert alert) {
        alert.getDialogPane().getStylesheets().addAll(
                EmployeeController.class.getResource("/css/app.css").toExternalForm(),
                EmployeeController.class.getResource("/css/personal.css").toExternalForm());
        alert.getDialogPane().getStyleClass().add("personal-dialog");
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(window());
        alert.setHeaderText(null);
        styleDialog(alert);
        alert.showAndWait();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    static boolean isPast(LocalDate date) {
        return date != null && date.isBefore(LocalDate.now());
    }
}
