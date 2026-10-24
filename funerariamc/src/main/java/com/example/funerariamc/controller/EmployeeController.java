package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.service.EmployeeService;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
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
import javafx.scene.control.Pagination;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Pantalla de Personal (mockup 08): lista con búsqueda por nombre o documento,
 * filtros por estado y cargo, y las acciones Registrar, Editar y Desactivar/Activar.
 * Un empleado nunca se elimina (RN-19).
 */
@Component
@Scope("prototype")
public class EmployeeController {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Filas por página de la lista de Personal (la bitácora usa 50 porque sus filas son más bajas). */
    private static final int PAGE_SIZE = 10;
    private static final PseudoClass INACTIVE = PseudoClass.getPseudoClass("inactive");

    private final EmployeeService employeeService;
    private final FxmlViewLoader viewLoader;
    /** Espera a que el usuario deje de escribir para no consultar la BD en cada tecla. */
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup statusGroup;
    @FXML
    private ToggleButton activeToggle;
    @FXML
    private ToggleButton inactiveToggle;
    @FXML
    private ComboBox<EmployeePosition> positionFilter;
    @FXML
    private Label countLabel;
    @FXML
    private Label showingLabel;
    @FXML
    private TableView<Employee> employeeTable;
    @FXML
    private TableColumn<Employee, String> documentColumn;
    @FXML
    private TableColumn<Employee, Employee> nameColumn;
    @FXML
    private TableColumn<Employee, Employee> positionColumn;
    @FXML
    private TableColumn<Employee, String> hireDateColumn;
    @FXML
    private TableColumn<Employee, Employee> statusColumn;
    @FXML
    private TableColumn<Employee, Employee> actionsColumn;
    @FXML
    private Pagination pagination;

    /** Empleado recién guardado que se resalta cuando llegue su página. */
    private Long selectAfterLoad;

    public EmployeeController(EmployeeService employeeService, FxmlViewLoader viewLoader) {
        this.employeeService = employeeService;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        positionFilter.getItems().add(null);
        positionFilter.getItems().addAll(EmployeePosition.values());
        positionFilter.setButtonCell(positionCell("Cargo: todos"));
        positionFilter.setCellFactory(list -> positionCell("Todos los cargos"));

        documentColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(formatDocument(c.getValue().getDocumentNumber())));
        hireDateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().getHireDate() == null ? "—" : DATE.format(c.getValue().getHireDate())));
        for (TableColumn<Employee, Employee> column : List.of(nameColumn, positionColumn, statusColumn, actionsColumn)) {
            column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        }
        nameColumn.setCellFactory(column -> graphicCell(this::nameCell));
        positionColumn.setCellFactory(column -> graphicCell(this::positionCell));
        statusColumn.setCellFactory(column -> graphicCell(EmployeeController::statusBadge));
        actionsColumn.setCellFactory(column -> graphicCell(this::actionButtons));

        // Filas de empleados inactivos en gris.
        employeeTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(Employee item, boolean empty) {
                super.updateItem(item, empty);
                pseudoClassStateChanged(INACTIVE, !empty && item != null && !item.isActive());
            }
        });

        searchDelay.setOnFinished(e -> goToFirstPage());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());
        statusGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un filtro elegido
            } else {
                goToFirstPage();
            }
        });
        positionFilter.valueProperty().addListener((obs, old, value) -> goToFirstPage());

        // La paginación solo aporta los botones; cada cambio de página consulta esa página.
        pagination.setPageFactory(index -> new Region());
        pagination.currentPageIndexProperty().addListener((obs, oldIndex, newIndex) -> loadPage(newIndex.intValue()));
        loadPage(0);
    }

    /** Vuelve a consultar la página que se está viendo (después de guardar, activar o desactivar). */
    private void refresh() {
        loadPage(pagination.getCurrentPageIndex());
    }

    /** Un filtro cambió: vuelve a la página 1. Si ya está en ella el listener no se dispara, por eso se carga a mano. */
    private void goToFirstPage() {
        if (pagination.getCurrentPageIndex() == 0) {
            loadPage(0);
        } else {
            pagination.setCurrentPageIndex(0);
        }
    }

    /** Igual que la bitácora: la consulta corre en otro hilo para que la ventana no se congele. */
    private void loadPage(int pageIndex) {
        String text = searchField.getText();
        EmployeeStatus status = selectedStatus();
        EmployeePosition position = positionFilter.getValue();
        showingLabel.setText("Cargando empleados…");
        pagination.setDisable(true);
        Task<PageResult> task = new Task<>() {
            @Override
            protected PageResult call() {
                return new PageResult(employeeService.search(text, status, position, pageIndex, PAGE_SIZE),
                        employeeService.count(null), employeeService.count(EmployeeStatus.ACTIVE));
            }
        };
        task.setOnSucceeded(e -> showPage(pageIndex, task.getValue()));
        task.setOnFailed(e -> {
            pagination.setDisable(false);
            showingLabel.setText("");
            showError(task.getException() instanceof BusinessException business
                    ? business.getMessage()
                    : "No se pudo cargar el personal. Intente de nuevo.");
        });
        Thread thread = new Thread(task, "employee-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private void showPage(int pageIndex, PageResult result) {
        Page<Employee> page = result.page();
        int pageCount = Math.max(page.getTotalPages(), 1);
        pagination.setPageCount(pageCount);
        pagination.setDisable(false);
        // Si la página quedó vacía (por ejemplo, se desactivó el único de la última página con el filtro Activos).
        if (page.getContent().isEmpty() && pageIndex > 0) {
            pagination.setCurrentPageIndex(pageCount - 1);
            return;
        }
        employeeTable.setItems(FXCollections.observableArrayList(page.getContent()));
        employeeTable.scrollTo(0);
        if (selectAfterLoad != null) {
            page.getContent().stream()
                    .filter(employee -> employee.getId().equals(selectAfterLoad))
                    .findFirst()
                    .ifPresent(employee -> employeeTable.getSelectionModel().select(employee));
            selectAfterLoad = null;
        }

        countLabel.setText(count(result.total(), "empleado") + " · " + count(result.active(), "activo")
                + " · " + count(result.total() - result.active(), "inactivo"));
        if (page.getTotalElements() == 0) {
            showingLabel.setText("");
        } else {
            long first = (long) pageIndex * PAGE_SIZE + 1;
            long last = first + page.getNumberOfElements() - 1;
            showingLabel.setText("Mostrando " + first + "–" + last + " de " + count(page.getTotalElements(), "empleado"));
        }
    }

    /** Página de la tabla más los totales del resumen de arriba. */
    private record PageResult(Page<Employee> page, long total, long active) {
    }

    private EmployeeStatus selectedStatus() {
        if (activeToggle.isSelected()) {
            return EmployeeStatus.ACTIVE;
        }
        return inactiveToggle.isSelected() ? EmployeeStatus.INACTIVE : null;
    }

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
        Scene scene = new Scene(view.root());
        stage.setScene(scene);
        view.controller().init(employee, saved -> {
            selectAfterLoad = saved.getId();
            refresh();
        });
        stage.showAndWait();
    }

    /** HU-04: pide confirmación y explica que no se borra. */
    private void onDeactivate(Employee employee) {
        EmployeePosition position = EmployeePosition.of(employee);
        String consequence = position.canHaveUserAccount()
                ? "Su cuenta de usuario se deshabilita y no podrá iniciar sesión."
                : "No podrá asignarse a servicios.";
        ButtonType deactivate = new ButtonType("Desactivar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE, "", cancel, deactivate);
        alert.initOwner(window());
        alert.setTitle("Desactivar empleado");
        alert.setHeaderText("¿Desactivar a " + fullName(employee) + "?");
        alert.setContentText(position.getDisplayName() + ", " + documentLabel(employee)
                + ", pasará a INACTIVO. " + consequence
                + "\n\nNo se borra: conserva su historial y puede activarse de nuevo (RN-19).");
        styleDialog(alert);
        alert.getDialogPane().lookupButton(deactivate).getStyleClass().add("danger-button");
        alert.showAndWait().filter(deactivate::equals).ifPresent(answer -> {
            try {
                employeeService.deactivate(employee.getId());
                refresh();
            } catch (BusinessException e) {
                showError(e.getMessage());
            }
        });
    }

    private void onActivate(Employee employee) {
        try {
            employeeService.activate(employee.getId());
            refresh();
        } catch (BusinessException e) {
            showError(e.getMessage());
        }
    }

    // ---------- celdas ----------

    private VBox nameCell(Employee e) {
        Label name = new Label(fullName(e));
        name.getStyleClass().add("cell-strong");
        // Debajo del nombre: correo y teléfono (el teléfono no tiene columna propia para que quepan las acciones).
        String contact = String.join(" · ", Stream.of(e.getEmail(), e.getPhone())
                .filter(value -> value != null && !value.isBlank()).toList());
        Label detail = new Label(contact.isEmpty() ? "Sin correo ni teléfono" : contact);
        detail.getStyleClass().add("cell-muted");
        return new VBox(1, name, detail);
    }

    private VBox positionCell(Employee e) {
        Label position = new Label(EmployeePosition.of(e).getDisplayName());
        position.setWrapText(true); // "Auxiliar de tanatopraxia" pasa a dos líneas en vez de cortarse
        VBox box = new VBox(1, position);
        box.setAlignment(Pos.CENTER_LEFT);
        if (employeeService.hasExpiredLicense(e)) {
            Label warning = new Label("Licencia vencida");
            warning.getStyleClass().add("cell-warning");
            box.getChildren().add(warning);
        }
        return box;
    }

    static Label statusBadge(Employee e) {
        Label badge = new Label(e.isActive() ? "●  ACTIVO" : "●  INACTIVO");
        badge.getStyleClass().addAll("status-badge", e.isActive() ? "status-active" : "status-inactive");
        return badge;
    }

    private HBox actionButtons(Employee e) {
        Button edit = new Button("✎  Editar");
        edit.getStyleClass().add("row-button");
        edit.setOnAction(event -> openForm(e));
        Button toggle = e.isActive() ? new Button("⊘  Desactivar") : new Button("✓  Activar");
        toggle.getStyleClass().addAll("row-button", e.isActive() ? "row-danger" : "row-activate");
        toggle.setOnAction(event -> {
            if (e.isActive()) {
                onDeactivate(e);
            } else {
                onActivate(e);
            }
        });
        HBox box = new HBox(8, edit, toggle);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private static <T extends javafx.scene.Node> TableCell<Employee, Employee> graphicCell(Function<Employee, T> factory) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Employee item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : factory.apply(item));
            }
        };
    }

    private static ListCell<EmployeePosition> positionCell(String emptyText) {
        return new ListCell<>() {
            @Override
            protected void updateItem(EmployeePosition item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item == null ? emptyText : item.getDisplayName());
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

    /** "1 activo", "5 activos". */
    private static String count(long amount, String word) {
        return amount + " " + word + (amount == 1 ? "" : "s");
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
        return employeeTable.getScene() == null ? null : employeeTable.getScene().getWindow();
    }

    static boolean isPast(LocalDate date) {
        return date != null && date.isBefore(LocalDate.now());
    }
}
