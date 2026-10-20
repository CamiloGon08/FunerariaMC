package com.example.funerariamc.controller;

import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.service.AccessLogService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * RF-ACC-07: pantalla de bitácora (access-log.fxml). Muestra access_log por páginas,
 * del registro más reciente al más antiguo; solo la abre el rol con acceso al módulo BITACORA.
 */
@Component
@Scope("prototype")
public class AccessLogController {

    private static final int PAGE_SIZE = 50;
    private static final String MASKED_IP = "***.***.***.***";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final AccessLogService accessLogService;

    @FXML
    private TableView<AccessLog> logTable;
    @FXML
    private TableColumn<AccessLog, String> dateColumn;
    @FXML
    private TableColumn<AccessLog, String> userColumn;
    @FXML
    private TableColumn<AccessLog, String> actionColumn;
    @FXML
    private TableColumn<AccessLog, String> ipColumn;
    @FXML
    private Label countLabel;
    @FXML
    private ToggleButton showIpButton;
    @FXML
    private Pagination pagination;

    public AccessLogController(AccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    @FXML
    private void initialize() {
        dateColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue().getTimestamp() == null ? "" : DATE_FORMAT.format(cell.getValue().getTimestamp())));
        userColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getAppUser().getUsername()));
        actionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getAction()));
        ipColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(maskIp(cell.getValue().getIpAddress())));
        EyeToggle.install(showIpButton, "Mostrar IP", "Ocultar IP");
        showIpButton.selectedProperty().addListener((obs, oldValue, shown) -> logTable.refresh());

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

    /** La consulta a Supabase corre en otro hilo para que la ventana no se congele mientras llega. */
    private void loadPage(int pageIndex) {
        countLabel.setText("Cargando registros…");
        pagination.setDisable(true);
        Task<Page<AccessLog>> task = new Task<>() {
            @Override
            protected Page<AccessLog> call() {
                return accessLogService.findPage(pageIndex, PAGE_SIZE);
            }
        };
        task.setOnSucceeded(e -> {
            Page<AccessLog> page = task.getValue();
            logTable.setItems(FXCollections.observableArrayList(page.getContent()));
            logTable.scrollTo(0);
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

    /** Mientras el ojito no esté activo, la IP se muestra con asteriscos. */
    private String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "";
        }
        return showIpButton.isSelected() ? ip : MASKED_IP;
    }
}
