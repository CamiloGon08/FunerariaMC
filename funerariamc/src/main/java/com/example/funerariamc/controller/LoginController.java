package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.service.AuthService;
import com.example.funerariamc.ui.EyeToggle;
import javafx.beans.InvalidationListener;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * RF-ACC-01: pantalla de inicio de sesión (login.fxml).
 * Si el login es correcto abre el menú principal; si no, muestra el mensaje del servicio.
 */
@Component
@Scope("prototype")
public class LoginController {

    private static final String EMPTY_FIELDS = "Ingrese su usuario y su contraseña.";
    private static final String CONNECTION_ERROR = "No se pudo conectar con el sistema. Intente de nuevo.";
    /** Margen mínimo del formulario al borde derecho, el del diseño original. */
    private static final double FORM_MARGIN = 80;

    private final AuthService authService;
    private final FxmlViewLoader viewLoader;

    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private TextField passwordVisibleField;
    @FXML
    private ToggleButton showPasswordButton;
    @FXML
    private Hyperlink forgotPasswordLink;
    @FXML
    private Label errorLabel;
    @FXML
    private Button loginButton;
    @FXML
    private Region loginPhoto;
    @FXML
    private VBox loginForm;

    public LoginController(AuthService authService, FxmlViewLoader viewLoader) {
        this.authService = authService;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        clearError();
        keepFormCentered();
        usernameField.textProperty().addListener((obs, oldValue, newValue) -> clearError());
        passwordField.textProperty().addListener((obs, oldValue, newValue) -> clearError());
        forgotPasswordLink.setOnAction(e -> onForgotPassword());
        setupShowPassword();
    }

    /**
     * En ventana completa el formulario queda centrado en el espacio marfil a la derecha de la foto,
     * en vez de pegado al borde; en ventanas pequeñas conserva el margen de 80 px.
     */
    private void keepFormCentered() {
        if (!(loginForm.getParent() instanceof StackPane root)) {
            return;
        }
        InvalidationListener center = o -> {
            double photo = photoWidth(root.getHeight());
            double margin = Math.max(FORM_MARGIN, (root.getWidth() - photo - loginForm.getWidth()) / 2);
            StackPane.setMargin(loginForm, new Insets(0, margin, 0, 0));
        };
        root.widthProperty().addListener(center);
        root.heightProperty().addListener(center);
        loginForm.widthProperty().addListener(center);
    }

    /** Ancho visible de la foto: la imagen se escala al alto de la ventana; sin imagen, el panel de color. */
    private double photoWidth(double height) {
        Background background = loginPhoto.getBackground();
        if (background != null && !background.getImages().isEmpty()) {
            Image image = background.getImages().get(0).getImage();
            if (image != null && image.getHeight() > 0) {
                return height * image.getWidth() / image.getHeight();
            }
        }
        return loginPhoto.getMaxWidth() > 0 && loginPhoto.getMaxWidth() < Double.MAX_VALUE ? loginPhoto.getMaxWidth() : 0;
    }

    /** El ojito alterna entre el PasswordField (puntos) y un TextField con el mismo texto. */
    private void setupShowPassword() {
        passwordVisibleField.textProperty().bindBidirectional(passwordField.textProperty());
        EyeToggle.install(showPasswordButton, "Mostrar contraseña", "Ocultar contraseña");
        showPasswordButton.selectedProperty().addListener((obs, oldValue, shown) -> {
            passwordVisibleField.setVisible(shown);
            passwordVisibleField.setManaged(shown);
            passwordField.setVisible(!shown);
            passwordField.setManaged(!shown);
            TextField active = shown ? passwordVisibleField : passwordField;
            active.requestFocus();
            active.positionCaret(active.getText() == null ? 0 : active.getText().length());
        });
    }

    @FXML
    private void onLogin() {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (username.isEmpty() || password.isEmpty()) {
            showError(EMPTY_FIELDS);
            return;
        }
        loginButton.setDisable(true);
        try {
            AppUser user = authService.login(username, password);
            // Cuenta nueva o contraseña restablecida: primero debe elegir una propia.
            if (Boolean.TRUE.equals(user.getMustChangePassword())) {
                viewLoader.switchScene(loginButton, FxmlViewLoader.CHANGE_PASSWORD, FxmlViewLoader.CHANGE_PASSWORD_TITLE);
            } else {
                viewLoader.switchScene(loginButton, FxmlViewLoader.MAIN, FxmlViewLoader.MAIN_TITLE);
            }
        } catch (BusinessException e) {
            passwordField.clear();
            showError(e.getMessage());
        } catch (RuntimeException e) {
            showError(CONNECTION_ERROR);
            e.printStackTrace();
        } finally {
            loginButton.setDisable(false);
        }
    }

    /** RF-ACC-03: abre la pantalla de recuperación de contraseña (password-recovery.fxml). */
    private void onForgotPassword() {
        viewLoader.switchScene(forgotPasswordLink, FxmlViewLoader.PASSWORD_RECOVERY, FxmlViewLoader.PASSWORD_RECOVERY_TITLE);
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
