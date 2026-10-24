package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.service.ClientService;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
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

import java.util.List;
import java.util.function.Function;

/**
 * Pantalla de Clientes (mockup 11): lista paginada con búsqueda por nombre o documento,
 * filtro Natural / Empresa y las acciones Nuevo cliente, Ver y Editar (HU-01).
 * Un cliente no se elimina: después tendrá planes, contratos y ventas.
 */
@Component
@Scope("prototype")
public class ClientController {

    /** Filas por página, igual que Personal. */
    private static final int PAGE_SIZE = 10;

    private final ClientService clientService;
    private final FxmlViewLoader viewLoader;
    /** Espera a que el usuario deje de escribir para no consultar la BD en cada tecla. */
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(300));

    @FXML
    private TextField searchField;
    @FXML
    private ToggleGroup typeGroup;
    @FXML
    private ToggleButton naturalToggle;
    @FXML
    private ToggleButton corporateToggle;
    @FXML
    private Label countLabel;
    @FXML
    private Label showingLabel;
    @FXML
    private TableView<Client> clientTable;
    @FXML
    private TableColumn<Client, String> documentColumn;
    @FXML
    private TableColumn<Client, Client> nameColumn;
    @FXML
    private TableColumn<Client, Client> typeColumn;
    @FXML
    private TableColumn<Client, String> phoneColumn;
    @FXML
    private TableColumn<Client, String> addressColumn;
    @FXML
    private TableColumn<Client, String> registrationColumn;
    @FXML
    private TableColumn<Client, Client> actionsColumn;
    @FXML
    private Pagination pagination;

    /** Cliente recién guardado que se resalta cuando llegue su página. */
    private Long selectAfterLoad;

    public ClientController(ClientService clientService, FxmlViewLoader viewLoader) {
        this.clientService = clientService;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        documentColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(documentLabel(c.getValue())));
        phoneColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(orDash(c.getValue().getPhone())));
        addressColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(orDash(c.getValue().getAddress())));
        registrationColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getRegistrationDate() == null
                ? "—" : EmployeeController.DATE.format(c.getValue().getRegistrationDate())));
        for (TableColumn<Client, Client> column : List.of(nameColumn, typeColumn, actionsColumn)) {
            column.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        }
        nameColumn.setCellFactory(column -> graphicCell(ClientController::nameCell));
        typeColumn.setCellFactory(column -> graphicCell(ClientController::typeBadge));
        actionsColumn.setCellFactory(column -> graphicCell(this::actionButtons));
        addressColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                // La dirección completa al pasar el mouse, porque la columna la corta.
                setTooltip(empty || item == null ? null : new Tooltip(item));
            }
        });

        // Doble clic en una fila = Ver.
        clientTable.setOnMouseClicked(event -> {
            Client selected = clientTable.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && selected != null) {
                openForm(selected, ClientFormController.Mode.VIEW);
            }
        });

        searchDelay.setOnFinished(e -> goToFirstPage());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());
        typeGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                old.setSelected(true); // siempre queda un filtro elegido
            } else {
                goToFirstPage();
            }
        });

        // La paginación solo aporta los botones; cada cambio de página consulta esa página.
        pagination.setPageFactory(index -> new Region());
        pagination.currentPageIndexProperty().addListener((obs, oldIndex, newIndex) -> loadPage(newIndex.intValue()));
        loadPage(0);
    }

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

    /** Igual que la bitácora y Personal: la consulta corre en otro hilo para que la ventana no se congele. */
    private void loadPage(int pageIndex) {
        String text = searchField.getText();
        ClientType type = selectedType();
        showingLabel.setText("Cargando clientes…");
        pagination.setDisable(true);
        Task<PageResult> task = new Task<>() {
            @Override
            protected PageResult call() {
                return new PageResult(clientService.search(text, type, pageIndex, PAGE_SIZE),
                        clientService.count(null), clientService.count(ClientType.NATURAL));
            }
        };
        task.setOnSucceeded(e -> showPage(pageIndex, task.getValue()));
        task.setOnFailed(e -> {
            pagination.setDisable(false);
            showingLabel.setText("");
            showError(task.getException() instanceof BusinessException business
                    ? business.getMessage()
                    : "No se pudieron cargar los clientes. Intente de nuevo.");
        });
        Thread thread = new Thread(task, "client-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private void showPage(int pageIndex, PageResult result) {
        Page<Client> page = result.page();
        int pageCount = Math.max(page.getTotalPages(), 1);
        pagination.setPageCount(pageCount);
        pagination.setDisable(false);
        if (page.getContent().isEmpty() && pageIndex > 0) {
            pagination.setCurrentPageIndex(pageCount - 1);
            return;
        }
        clientTable.setItems(FXCollections.observableArrayList(page.getContent()));
        clientTable.scrollTo(0);
        if (selectAfterLoad != null) {
            page.getContent().stream()
                    .filter(client -> client.getId().equals(selectAfterLoad))
                    .findFirst()
                    .ifPresent(client -> clientTable.getSelectionModel().select(client));
            selectAfterLoad = null;
        }

        long corporate = result.total() - result.natural();
        countLabel.setText(count(result.total(), "cliente", "clientes") + " · "
                + count(result.natural(), "natural", "naturales") + " · "
                + count(corporate, "empresa", "empresas"));
        if (page.getTotalElements() == 0) {
            showingLabel.setText("");
        } else {
            long first = (long) pageIndex * PAGE_SIZE + 1;
            long last = first + page.getNumberOfElements() - 1;
            showingLabel.setText("Mostrando " + first + "–" + last + " de "
                    + count(page.getTotalElements(), "cliente", "clientes"));
        }
    }

    /** Página de la tabla más los totales del resumen de arriba. */
    private record PageResult(Page<Client> page, long total, long natural) {
    }

    private ClientType selectedType() {
        if (naturalToggle.isSelected()) {
            return ClientType.NATURAL;
        }
        return corporateToggle.isSelected() ? ClientType.CORPORATE : null;
    }

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
        view.controller().init(client, mode, saved -> {
            selectAfterLoad = saved.getId();
            if (client == null) {
                goToFirstPage(); // el cliente nuevo queda de primero (más reciente)
            } else {
                refresh();
            }
        });
        stage.showAndWait();
    }

    // ---------- celdas ----------

    private static VBox nameCell(Client c) {
        Label name = new Label(ClientService.displayName(c));
        name.getStyleClass().add("cell-strong");
        Label email = new Label(c.getEmail() == null ? "Sin correo" : c.getEmail());
        email.getStyleClass().add("cell-muted");
        return new VBox(1, name, email);
    }

    static Label typeBadge(Client c) {
        ClientType type = c.getClientType() == null ? ClientType.NATURAL : c.getClientType();
        Label badge = new Label(type.getDisplayName());
        badge.getStyleClass().addAll("type-badge", type.isCorporate() ? "type-corporate" : "type-natural");
        return badge;
    }

    private HBox actionButtons(Client c) {
        Button view = new Button("Ver");
        view.getStyleClass().add("row-button");
        view.setOnAction(event -> openForm(c, ClientFormController.Mode.VIEW));
        Button edit = new Button("✎  Editar");
        edit.getStyleClass().add("row-button");
        edit.setOnAction(event -> openForm(c, ClientFormController.Mode.EDIT));
        HBox box = new HBox(8, view, edit);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    private static <T extends javafx.scene.Node> TableCell<Client, Client> graphicCell(Function<Client, T> factory) {
        return new TableCell<>() {
            @Override
            protected void updateItem(Client item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : factory.apply(item));
            }
        };
    }

    // ---------- utilidades ----------

    /** "1.065.832.417" o, para una empresa, "NIT 800.197.268-4". */
    static String documentLabel(Client c) {
        String document = c.getDocumentNumber();
        if ("NIT".equals(c.getDocumentType()) && document != null && document.matches("\\d{2,}")) {
            String base = document.substring(0, document.length() - 1);
            return EmployeeController.formatDocument(base) + "-" + document.charAt(document.length() - 1);
        }
        return EmployeeController.formatDocument(document);
    }

    /** "1 cliente", "5 clientes", "1 natural", "2 naturales". */
    private static String count(long amount, String singular, String plural) {
        return amount + " " + (amount == 1 ? singular : plural);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.initOwner(window());
        alert.setHeaderText(null);
        EmployeeController.styleDialog(alert);
        alert.showAndWait();
    }

    private Window window() {
        return clientTable.getScene() == null ? null : clientTable.getScene().getWindow();
    }
}
