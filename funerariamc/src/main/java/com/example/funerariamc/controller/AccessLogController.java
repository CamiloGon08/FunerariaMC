package com.example.funerariamc.controller;

import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.ui.AccessLogLabels;
import com.example.funerariamc.ui.EyeToggle;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * RF-ACC-07 y RF-ACC-08: pantalla de bitácora (access-log.fxml). Muestra access_log por páginas,
 * del registro más reciente al más antiguo, con filtros por rango de fechas y por usuario.
 * Solo la abre el rol con acceso al módulo BITACORA.
 */
@Component
@Scope("prototype")
public class AccessLogController {

    private static final int PAGE_SIZE = 50;
    private static final String MASKED_IP = "***.***.***.***";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String EMPTY_LOG = "Aún no hay registros en la bitácora.";
    private static final String EMPTY_FILTER = "No hay registros para los filtros seleccionados.";

    private final AccessLogService accessLogService;

    @FXML
    private TableView<AccessLog> logTable;
    @FXML
    private TableColumn<AccessLog, String> dateColumn;
    @FXML
    private TableColumn<AccessLog, String> userColumn;
    @FXML
    private TableColumn<AccessLog, String> employeeColumn;
    @FXML
    private TableColumn<AccessLog, String> actionColumn;
    @FXML
    private TableColumn<AccessLog, String> ipColumn;
    @FXML
    private Label countLabel;
    @FXML
    private Label emptyLabel;
    @FXML
    private Label filterErrorLabel;
    @FXML
    private DatePicker fromDatePicker;
    @FXML
    private DatePicker toDatePicker;
    @FXML
    private TextField userFilterField;
    @FXML
    private ToggleButton showIpButton;
    @FXML
    private Pagination pagination;

    /** Filtros aplicados con "Filtrar"; la paginación siempre usa estos, no lo que esté a medio escribir. */
    private LocalDate appliedFrom;
    private LocalDate appliedTo;
    private String appliedUser;

    public AccessLogController(AccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    @FXML
    private void initialize() {
        // La BD guarda la hora en UTC: se muestra en hora de Colombia.
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().getTimestamp() == null ? ""
                        : DATE_FORMAT.format(AccessLogService.toAppTime(cell.getValue().getTimestamp()))));
        userColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getAppUser().getUsername()));
        employeeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(employeeName(cell.getValue())));
        actionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(AccessLogLabels.of(cell.getValue().getAction())));
        ipColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(maskIp(cell.getValue().getIpAddress())));
        EyeToggle.install(showIpButton, "Mostrar IP", "Ocultar IP");
        showIpButton.selectedProperty().addListener((obs, oldValue, shown) -> logTable.refresh());

        setupDatePicker(fromDatePicker, "Desde");
        setupDatePicker(toDatePicker, "Hasta");
        // El mensaje de error de filtros solo ocupa espacio cuando hay algo que decir.
        filterErrorLabel.managedProperty().bind(filterErrorLabel.textProperty().isNotEmpty());
        filterErrorLabel.visibleProperty().bind(filterErrorLabel.textProperty().isNotEmpty());

        // La paginación solo aporta los botones; cada cambio de página consulta esa página.
        pagination.setPageFactory(index -> new Region());
        pagination.currentPageIndexProperty().addListener((obs, oldIndex, newIndex) -> loadPage(newIndex.intValue()));
        loadPage(0);
    }

    /** Vuelve a consultar la página que se está viendo (por si entraron registros nuevos). */
    @FXML
    private void onRefresh() {
        loadPage(pagination.getCurrentPageIndex());
    }

    /** RF-ACC-08: aplica los filtros y vuelve a la primera página. También se llama con Enter. */
    @FXML
    private void onFilter() {
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();
        if (from != null && to != null && from.isAfter(to)) {
            filterErrorLabel.setText("La fecha inicial no puede ser posterior a la final.");
            return;
        }
        filterErrorLabel.setText("");
        appliedFrom = from;
        appliedTo = to;
        appliedUser = userFilterField.getText();
        goToFirstPage();
    }

    /** Quita todos los filtros y muestra la bitácora completa. */
    @FXML
    private void onClearFilters() {
        fromDatePicker.setValue(null);
        toDatePicker.setValue(null);
        userFilterField.clear();
        filterErrorLabel.setText("");
        appliedFrom = null;
        appliedTo = null;
        appliedUser = null;
        goToFirstPage();
    }

    private boolean hasFilters() {
        return appliedFrom != null || appliedTo != null || (appliedUser != null && !appliedUser.isBlank());
    }

    /** Si ya está en la página 1 el listener no se dispara, por eso se carga a mano. */
    private void goToFirstPage() {
        if (pagination.getCurrentPageIndex() == 0) {
            loadPage(0);
        } else {
            pagination.setCurrentPageIndex(0);
        }
    }

    /** La consulta a Supabase corre en otro hilo para que la ventana no se congele mientras llega. */
    private void loadPage(int pageIndex) {
        countLabel.setText("Cargando registros…");
        pagination.setDisable(true);
        LocalDate from = appliedFrom;
        LocalDate to = appliedTo;
        String user = appliedUser;
        Task<Page<AccessLog>> task = new Task<>() {
            @Override
            protected Page<AccessLog> call() {
                return accessLogService.findPage(pageIndex, PAGE_SIZE, from, to, user);
            }
        };
        task.setOnSucceeded(e -> {
            Page<AccessLog> page = task.getValue();
            logTable.setItems(FXCollections.observableArrayList(page.getContent()));
            logTable.scrollTo(0);
            emptyLabel.setText(hasFilters() ? EMPTY_FILTER : EMPTY_LOG);
            pagination.setPageCount(Math.max(page.getTotalPages(), 1));
            countLabel.setText(page.getTotalElements() == 0 ? ""
                    : "Página " + (pageIndex + 1) + " de " + Math.max(page.getTotalPages(), 1)
                    + " · " + page.getTotalElements() + " registros");
            pagination.setDisable(false);
        });
        task.setOnFailed(e -> {
            countLabel.setText("No se pudo cargar la bitácora. Intente de nuevo.");
            pagination.setDisable(false);
        });
        Thread thread = new Thread(task, "access-log-loader");
        thread.setDaemon(true);
        thread.start();
    }

    /** Calendario con formato dd/MM/yyyy; también deja escribir la fecha a mano. */
    private void setupDatePicker(DatePicker picker, String prompt) {
        picker.setPromptText(prompt);
        picker.setShowWeekNumbers(false);
        picker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate date) {
                return date == null ? "" : DAY_FORMAT.format(date);
            }

            @Override
            public LocalDate fromString(String text) {
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return LocalDate.parse(text.trim(), DAY_FORMAT);
                } catch (DateTimeParseException ex) {
                    filterErrorLabel.setText("Escriba la fecha como dd/mm/aaaa o elíjala en el calendario.");
                    return picker.getValue();
                }
            }
        });
        // No deja elegir días futuros (la bitácora no tiene registros después de hoy)
        // y resalta el rango elegido entre "Desde" y "Hasta" en ambos calendarios.
        picker.setDayCellFactory(p -> new DateCell() {
            @Override
            public void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("in-range", "range-end");
                if (empty || item == null) {
                    return;
                }
                setDisable(item.isAfter(LocalDate.now(AccessLogService.APP_ZONE)));
                LocalDate from = fromDatePicker.getValue();
                LocalDate to = toDatePicker.getValue();
                if (item.equals(from) || item.equals(to)) {
                    getStyleClass().add("range-end");
                } else if (from != null && to != null && item.isAfter(from) && item.isBefore(to)) {
                    getStyleClass().add("in-range");
                }
            }
        });
    }

    /** FUN-20: nombre completo del empleado dueño de la cuenta (ya viene cargado por el join fetch). */
    private String employeeName(AccessLog log) {
        var employee = log.getAppUser().getEmployee();
        if (employee == null) {
            return "";
        }
        return (employee.getName() + " " + employee.getLastname()).trim();
    }

    /** Mientras el ojito no esté activo, la IP se muestra con asteriscos. */
    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        return showIpButton.isSelected() ? ip : MASKED_IP;
    }
}
