package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.exception.ExistingClientException;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.ClientData;
import com.example.funerariamc.service.ClientService;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Formulario de Clientes (mockup 12). Registra un cliente titular (HU-01), lo muestra
 * en modo lectura o lo edita. Las validaciones de negocio están en {@link ClientService}.
 */
@Component
@Scope("prototype")
public class ClientFormController {

    /** NEW = nuevo cliente, VIEW = solo lectura (botón Editar), EDIT = actualizar. */
    public enum Mode { NEW, VIEW, EDIT }

    /** Código guardado en person.document_type → texto en pantalla. */
    private static final Map<String, String> DOCUMENT_TYPES = new LinkedHashMap<>();

    static {
        DOCUMENT_TYPES.put("CC", "CC · Cédula de ciudadanía");
        DOCUMENT_TYPES.put("CE", "CE · Cédula de extranjería");
        DOCUMENT_TYPES.put("PA", "PA · Pasaporte");
        DOCUMENT_TYPES.put(ClientService.NIT, "NIT · Número de identificación tributaria");
    }

    private final ClientService clientService;

    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private GridPane formGrid;
    @FXML
    private ComboBox<ClientType> clientTypeCombo;
    @FXML
    private ComboBox<String> documentTypeCombo;
    @FXML
    private TextField documentField;
    @FXML
    private TextField registrationField;
    @FXML
    private VBox nameBox;
    @FXML
    private Label nameLabel;
    @FXML
    private TextField nameField;
    @FXML
    private VBox lastnameBox;
    @FXML
    private TextField lastnameField;
    @FXML
    private TextField phoneField;
    @FXML
    private TextField emailField;
    @FXML
    private TextField addressField;
    @FXML
    private Label typeNote;
    @FXML
    private HBox existingBox;
    @FXML
    private Label existingLabel;
    @FXML
    private Label errorLabel;
    @FXML
    private Button cancelButton;
    @FXML
    private Button saveButton;

    private Mode mode = Mode.NEW;
    private Client client;
    private Client existingClient;
    private Consumer<Client> onSaved;

    public ClientFormController(ClientService clientService) {
        this.clientService = clientService;
    }

    @FXML
    private void initialize() {
        clientTypeCombo.getItems().addAll(ClientType.values());
        clientTypeCombo.setButtonCell(typeCell());
        clientTypeCombo.setCellFactory(list -> typeCell());
        clientTypeCombo.valueProperty().addListener((obs, old, type) -> showFieldsFor(type));

        documentTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return code == null ? "" : DOCUMENT_TYPES.getOrDefault(code, code);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });

        // RF-PER-01 2a: al salir del documento se avisa si ya es de un cliente, antes de llenar lo demás.
        documentField.focusedProperty().addListener((obs, wasFocused, focused) -> {
            if (!focused && mode != Mode.VIEW) {
                checkDocument();
            }
        });
        documentField.textProperty().addListener((obs, old, text) -> hideExisting());

        // Un elemento oculto no ocupa espacio.
        for (Node node : List.of(lastnameBox, existingBox, errorLabel)) {
            node.managedProperty().bind(node.visibleProperty());
        }
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        existingBox.setVisible(false);
        clientTypeCombo.setValue(ClientType.NATURAL);
    }

    /**
     * Prepara el formulario. {@code client} en null = nuevo cliente.
     * {@code onSaved} recibe el cliente guardado para refrescar la lista.
     */
    public void init(Client client, Mode mode, Consumer<Client> onSaved) {
        this.onSaved = onSaved;
        show(client, client == null ? Mode.NEW : mode);
    }

    private void show(Client client, Mode mode) {
        this.client = client;
        this.mode = mode;
        errorLabel.setText("");
        hideExisting();
        if (client == null) {
            titleLabel.setText("Nuevo cliente");
            registrationField.setText(EmployeeController.DATE.format(LocalDate.now(AccessLogService.APP_ZONE)));
            saveButton.setText("Guardar cliente");
            setEditable(true);
            return;
        }

        clientTypeCombo.setValue(client.getClientType() == null ? ClientType.NATURAL : client.getClientType());
        documentTypeCombo.setValue(client.getDocumentType());
        documentField.setText(ClientController.documentLabel(client)); // con puntos; el servicio los quita al guardar
        registrationField.setText(client.getRegistrationDate() == null
                ? "—" : EmployeeController.DATE.format(client.getRegistrationDate()));
        nameField.setText(client.getName());
        lastnameField.setText(client.getLastname());
        phoneField.setText(client.getPhone());
        emailField.setText(client.getEmail());
        addressField.setText(client.getAddress());
        hideExisting(); // cambiar el documento por código no es un documento repetido

        titleLabel.setText(mode == Mode.VIEW ? ClientService.displayName(client) : "Editar cliente");
        subtitleLabel.setText(mode == Mode.VIEW
                ? clientTypeCombo.getValue().getDescription() + " · " + ClientController.documentLabel(client)
                : "Los campos con * son obligatorios.");
        saveButton.setText(mode == Mode.VIEW ? "✎  Editar" : "Guardar cambios");
        cancelButton.setText(mode == Mode.VIEW ? "Cerrar" : "Cancelar");
        setEditable(mode != Mode.VIEW);
        fitWindow();
    }

    /** Persona natural: CC, CE o pasaporte, nombres y apellidos. Empresa: NIT y razón social. */
    private void showFieldsFor(ClientType type) {
        boolean corporate = type != null && type.isCorporate();
        String current = documentTypeCombo.getValue();
        documentTypeCombo.getItems().setAll(corporate
                ? List.of(ClientService.NIT)
                : ClientService.NATURAL_DOCUMENT_TYPES);
        documentTypeCombo.setValue(documentTypeCombo.getItems().contains(current)
                ? current : documentTypeCombo.getItems().get(0));

        nameLabel.setText(corporate ? "RAZÓN SOCIAL *" : "NOMBRES *");
        lastnameBox.setVisible(!corporate);
        GridPane.setColumnSpan(nameBox, corporate ? 2 : 1);
        documentField.setPromptText(corporate ? "800.197.268-4" : "1.065.832.417");
        typeNote.setText(corporate
                ? "Empresa o entidad: escriba el NIT con su dígito de verificación y la razón social como aparece en el RUT."
                : "Si el cliente es una empresa, elija el tipo Empresa: se registra con NIT y razón social.");
    }

    private void setEditable(boolean editable) {
        clientTypeCombo.setDisable(!editable);
        documentTypeCombo.setDisable(!editable);
        for (TextField field : List.of(documentField, nameField, lastnameField, phoneField, emailField, addressField)) {
            field.setEditable(editable);
            field.setFocusTraversable(editable);
        }
        typeNote.setVisible(editable);
        typeNote.setManaged(editable);
    }

    private void checkDocument() {
        try {
            clientService.findByDocument(documentField.getText())
                    .filter(found -> client == null || !found.getId().equals(client.getId()))
                    .ifPresent(this::showExisting);
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    private void showExisting(Client found) {
        existingClient = found;
        String since = found.getRegistrationDate() == null
                ? "" : ", cliente desde el " + EmployeeController.DATE.format(found.getRegistrationDate());
        existingLabel.setText(ClientService.displayName(found) + " · " + ClientController.documentLabel(found)
                + since + ". No se registra de nuevo.");
        existingBox.setVisible(true);
        fitWindow();
    }

    private void hideExisting() {
        existingClient = null;
        existingBox.setVisible(false);
    }

    /** Muestra el cliente que ya existe, en modo lectura (RF-PER-01 2a). */
    @FXML
    private void onShowExisting() {
        if (existingClient != null) {
            show(existingClient, Mode.VIEW);
        }
    }

    @FXML
    private void onSave() {
        if (mode == Mode.VIEW) {
            show(client, Mode.EDIT);
            return;
        }
        errorLabel.setText("");
        try {
            ClientData data = readForm();
            Client saved = mode == Mode.NEW
                    ? clientService.register(data)
                    : clientService.update(client.getId(), data);
            if (onSaved != null) {
                onSaved.accept(saved);
            }
            window().close();
        } catch (ExistingClientException e) {
            showExisting(e.getExistingClient());
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
        }
    }

    @FXML
    private void onCancel() {
        window().close();
    }

    private ClientData readForm() {
        boolean corporate = clientTypeCombo.getValue() != null && clientTypeCombo.getValue().isCorporate();
        return new ClientData(
                clientTypeCombo.getValue(),
                documentTypeCombo.getValue(),
                text(documentField),
                text(nameField),
                corporate ? null : text(lastnameField),
                text(phoneField),
                text(emailField),
                text(addressField));
    }

    private static String text(TextField field) {
        String value = field.getText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ListCell<ClientType> typeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ClientType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Seleccione" : item.getDescription());
            }
        };
    }

    /** La ventana crece cuando aparece el aviso del cliente existente o cambia de modo. */
    private void fitWindow() {
        if (saveButton.getScene() != null && saveButton.getScene().getWindow() != null) {
            saveButton.getScene().getWindow().sizeToScene();
        }
    }

    private Stage window() {
        return (Stage) saveButton.getScene().getWindow();
    }
}
