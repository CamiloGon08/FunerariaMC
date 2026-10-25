package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.AuthService;
import com.example.funerariamc.service.SessionContext;
import com.example.funerariamc.service.UserAccountService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Primer ingreso (change-password.fxml): la cuenta tiene una contraseña temporal
 * y el usuario debe cambiarla antes de ver el menú. Si sale, se cierra la sesión.
 */
@Component
@Scope("prototype")
public class ChangePasswordController {

    private final UserAccountService userAccountService;
    private final AuthService authService;
    private final SessionContext sessionContext;
    private final FxmlViewLoader viewLoader;

    @FXML
    private Label greetingLabel;
    @FXML
    private PasswordField currentField;
    @FXML
    private PasswordField newField;
    @FXML
    private PasswordField confirmField;
    @FXML
    private Label errorLabel;
    @FXML
    private Button saveButton;

    public ChangePasswordController(UserAccountService userAccountService, AuthService authService,
                                    SessionContext sessionContext, FxmlViewLoader viewLoader) {
        this.userAccountService = userAccountService;
        this.authService = authService;
        this.sessionContext = sessionContext;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        greetingLabel.setText("Hola, " + sessionContext.getDisplayName()
                + ". Escriba la contraseña temporal y luego la nueva, que solo usted conocerá.");
        errorLabel.setText("");
        for (PasswordField field : new PasswordField[]{currentField, newField, confirmField}) {
            field.textProperty().addListener((obs, old, value) -> errorLabel.setText(""));
        }
    }

    @FXML
    private void onSave() {
        saveButton.setDisable(true);
        try {
            userAccountService.changeOwnPassword(currentField.getText(), newField.getText(), confirmField.getText());
            viewLoader.switchScene(saveButton, FxmlViewLoader.MAIN, FxmlViewLoader.MAIN_TITLE);
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
        } catch (RuntimeException e) {
            errorLabel.setText("No se pudo conectar con el sistema. Intente de nuevo.");
            e.printStackTrace();
        } finally {
            saveButton.setDisable(false);
        }
    }

    /** Sin cambiar la contraseña no se entra: vuelve al inicio de sesión. */
    @FXML
    private void onCancel() {
        authService.logout();
        viewLoader.switchScene(saveButton, FxmlViewLoader.LOGIN, FxmlViewLoader.LOGIN_TITLE);
    }
}
