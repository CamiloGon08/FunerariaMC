package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.PasswordResetService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * RF-ACC-03: "¿Olvidó su contraseña?" (password-recovery.fxml).
 * Paso 1 pide un código al correo o Telegram del empleado; paso 2 lo valida y guarda la nueva contraseña.
 * El envío y el BCrypt corren en otro hilo para que la ventana no se congele.
 */
@Component
@Scope("prototype")
public class PasswordRecoveryController {

    private static final String STEP_1_TEXT = "Escriba su usuario y le enviaremos un código de verificación.";
    private static final String STEP_2_TEXT = "Escriba el código que recibió y su nueva contraseña.";
    private static final String CODE_SENT = "Si el usuario existe y tiene correo o Telegram registrado, "
            + "le enviamos un código. Vence en 10 minutos.";
    private static final String CONNECTION_ERROR = "No se pudo conectar con el sistema. Intente de nuevo.";

    private final PasswordResetService passwordResetService;
    private final FxmlViewLoader viewLoader;

    @FXML
    private Label stepLabel;
    @FXML
    private VBox requestStep;
    @FXML
    private TextField usernameField;
    @FXML
    private Button sendCodeButton;
    @FXML
    private VBox resetStep;
    @FXML
    private TextField codeField;
    @FXML
    private PasswordField newPasswordField;
    @FXML
    private PasswordField confirmPasswordField;
    @FXML
    private Hyperlink resendLink;
    @FXML
    private Button changePasswordButton;
    @FXML
    private Label infoLabel;
    @FXML
    private Label errorLabel;

    public PasswordRecoveryController(PasswordResetService passwordResetService, FxmlViewLoader viewLoader) {
        this.passwordResetService = passwordResetService;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        stepLabel.setText(STEP_1_TEXT);
        showInfo("");
        clearError();
        // El código solo admite 6 dígitos.
        codeField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.matches("\\d{0,6}")) {
                codeField.setText(oldValue);
            }
        });
    }

    @FXML
    private void onSendCode() {
        String username = text(usernameField).trim();
        if (username.isEmpty()) {
            showError("Ingrese su usuario.");
            return;
        }
        requestCode(username);
    }

    @FXML
    private void onResend() {
        requestCode(text(usernameField).trim());
    }

    @FXML
    private void onChangePassword() {
        String code = text(codeField).trim();
        String newPassword = text(newPasswordField);
        if (code.length() != 6) {
            showError("El código tiene 6 dígitos.");
            return;
        }
        if (newPassword.length() < PasswordResetService.MIN_PASSWORD_LENGTH) {
            showError("La nueva contraseña debe tener al menos "
                    + PasswordResetService.MIN_PASSWORD_LENGTH + " caracteres.");
            return;
        }
        if (!newPassword.equals(text(confirmPasswordField))) {
            showError("Las contraseñas no coinciden.");
            return;
        }
        String username = text(usernameField).trim();
        run(changePasswordButton, () -> passwordResetService.resetPassword(username, code, newPassword), () -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "Su contraseña se cambió correctamente. Ya puede iniciar sesión con la nueva contraseña.");
            alert.setHeaderText(null);
            alert.setTitle("Contraseña actualizada");
            alert.showAndWait();
            onBackToLogin();
        });
    }

    @FXML
    private void onBackToLogin() {
        viewLoader.switchScene(stepLabel, FxmlViewLoader.LOGIN, FxmlViewLoader.LOGIN_TITLE);
    }

    private void requestCode(String username) {
        run(sendCodeButton, () -> passwordResetService.requestCode(username), () -> {
            requestStep.setVisible(false);
            requestStep.setManaged(false);
            resetStep.setVisible(true);
            resetStep.setManaged(true);
            stepLabel.setText(STEP_2_TEXT);
            showInfo(CODE_SENT);
            codeField.clear();
            codeField.requestFocus();
        });
    }

    /** Ejecuta la llamada al servicio en otro hilo; muestra el error del servicio si falla. */
    private void run(Button button, Runnable call, Runnable onSuccess) {
        clearError();
        button.setDisable(true);
        resendLink.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                call.run();
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            resendLink.setDisable(false);
            onSuccess.run();
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            resendLink.setDisable(false);
            Throwable error = task.getException();
            if (error instanceof BusinessException) {
                showError(error.getMessage());
            } else {
                showError(CONNECTION_ERROR);
                error.printStackTrace();
            }
        });
        Thread thread = new Thread(task, "password-recovery");
        thread.setDaemon(true);
        thread.start();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText();
    }

    private void showInfo(String message) {
        infoLabel.setText(message);
        infoLabel.setVisible(!message.isEmpty());
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    private void clearError() {
        errorLabel.setText("");
        errorLabel.setVisible(false);
    }
}
