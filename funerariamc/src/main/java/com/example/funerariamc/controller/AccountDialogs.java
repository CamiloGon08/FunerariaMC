package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.service.RoleNames;
import com.example.funerariamc.service.UserAccountService;
import com.example.funerariamc.service.UserAccountService.AccountCredentials;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/**
 * Diálogos de la cuenta de acceso que se usan desde la lista y el formulario de Personal:
 * crear la cuenta y mostrar la contraseña temporal una sola vez.
 */
final class AccountDialogs {

    private AccountDialogs() {
    }

    /** Usuario sugerido y rol del cargo; al aceptar crea la cuenta. Vacío si canceló. */
    static Optional<AccountCredentials> createAccount(Window owner, Employee employee, UserAccountService service) {
        EmployeePosition position = EmployeePosition.of(employee);
        TextField username = new TextField(service.suggestUsername(employee.getName(), employee.getLastname()));
        ComboBox<String> role = roleCombo(position);
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        error.managedProperty().bind(error.textProperty().isNotEmpty());

        ButtonType create = new ButtonType("Crear cuenta", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.NONE, "", cancel, create);
        dialog.initOwner(owner);
        dialog.setTitle("Crear cuenta de acceso");
        dialog.setHeaderText("Cuenta de acceso para " + EmployeeController.fullName(employee));
        VBox content = new VBox(10,
                field("USUARIO *", username),
                field("ROL *", role),
                note("El usuario queda fijo; solo la contraseña es temporal. " + employee.getName()
                        + " deberá cambiarla la primera vez que ingrese al sistema."),
                error);
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        EmployeeController.styleDialog(dialog);
        Button createButton = (Button) dialog.getDialogPane().lookupButton(create);
        createButton.getStyleClass().add("primary-dialog-button");

        AccountCredentials[] result = new AccountCredentials[1];
        // Si el servicio rechaza el usuario, el diálogo queda abierto con el mensaje.
        createButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                result[0] = service.createAccount(employee.getId(), username.getText(), role.getValue());
            } catch (BusinessException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });
        return dialog.showAndWait().filter(create::equals).map(answer -> result[0]);
    }

    /** La contraseña temporal solo se ve aquí: en la BD queda el hash BCrypt. */
    static void showCredentials(Window owner, Employee employee, AccountCredentials credentials) {
        Alert dialog = new Alert(Alert.AlertType.NONE, "", new ButtonType("Listo", ButtonBar.ButtonData.OK_DONE));
        dialog.initOwner(owner);
        dialog.setTitle("Cuenta creada");
        dialog.setHeaderText("Cuenta creada para " + EmployeeController.fullName(employee));

        TextField user = readOnly(credentials.username());
        TextField password = readOnly(credentials.temporaryPassword());
        password.getStyleClass().add("temporary-password");
        Button copy = new Button("Copiar usuario y contraseña");
        copy.getStyleClass().add("secondary-button");
        copy.setOnAction(e -> {
            ClipboardContent text = new ClipboardContent();
            text.putString("Usuario: " + credentials.username()
                    + "\nContraseña temporal: " + credentials.temporaryPassword());
            Clipboard.getSystemClipboard().setContent(text);
            copy.setText("Copiado");
        });
        VBox content = new VBox(10,
                field("USUARIO", user),
                field("CONTRASEÑA TEMPORAL", password),
                copy,
                note("El usuario es definitivo; solo la contraseña es temporal. Entréguela en persona: no se "
                        + "vuelve a mostrar. En su primer ingreso el sistema le pedirá cambiarla; después, si la "
                        + "olvida, usa “¿Olvidó su contraseña?” en el inicio de sesión."));
        content.setPrefWidth(420);
        dialog.getDialogPane().setContent(content);
        EmployeeController.styleDialog(dialog);
        dialog.showAndWait();
    }

    static ComboBox<String> roleCombo(EmployeePosition position) {
        List<String> roles = UserAccountService.rolesFor(position);
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().setAll(roles);
        combo.getStyleClass().add("form-combo");
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setButtonCell(roleCell());
        combo.setCellFactory(list -> roleCell());
        if (!roles.isEmpty()) {
            combo.setValue(roles.get(0));
        }
        combo.setDisable(roles.size() < 2); // Secretaría y Vendedor tienen un solo rol posible
        return combo;
    }

    static ListCell<String> roleCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : RoleNames.displayName(item));
            }
        };
    }

    private static VBox field(String label, Node input) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        VBox box = new VBox(2, caption, input);
        box.getStyleClass().add("form-field");
        return box;
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("note-info");
        note.setWrapText(true);
        return note;
    }

    private static TextField readOnly(String value) {
        TextField field = new TextField(value);
        field.setEditable(false);
        HBox.setHgrow(field, Priority.ALWAYS);
        return field;
    }
}
