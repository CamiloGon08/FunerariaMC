package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.AppModule;
import com.example.funerariamc.service.AuthService;
import com.example.funerariamc.service.RoleNames;
import com.example.funerariamc.service.SessionContext;
import com.example.funerariamc.ui.ModuleIcons;
import com.example.funerariamc.ui.SidebarAnimator;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * RF-ACC-04/05/06: menú principal con los módulos del rol de la sesión.
 * El menú lateral se contrae y expande como en los mockups 06/07 de Figma.
 */
@Component
@Scope("prototype")
public class MainController {

    private final AuthService authService;
    private final SessionContext sessionContext;
    private final FxmlViewLoader viewLoader;

    @FXML
    private VBox sidebar;
    @FXML
    private Node brandText;
    @FXML
    private Node collapseButton;
    @FXML
    private Node profileText;
    @FXML
    private Button homeButton;
    @FXML
    private Button avatarButton;
    @FXML
    private Label userNameLabel;
    @FXML
    private Label roleLabel;
    @FXML
    private Label titleLabel;
    @FXML
    private Label subtitleLabel;
    @FXML
    private Label sessionBadge;
    @FXML
    private VBox menuBox;
    @FXML
    private StackPane contentPane;

    private SidebarAnimator sidebarAnimator;

    public MainController(AuthService authService, SessionContext sessionContext, FxmlViewLoader viewLoader) {
        this.authService = authService;
        this.sessionContext = sessionContext;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        String roleName = sessionContext.getRoleName().map(RoleNames::displayName).orElse("");
        userNameLabel.setText(sessionContext.getDisplayName());
        roleLabel.setText(roleName);
        sessionBadge.setText("Sesión activa · " + roleName);
        avatarButton.setText(initials(sessionContext.getDisplayName()));

        sidebarAnimator = new SidebarAnimator(sidebar, brandText, collapseButton, profileText);
        sidebarAnimator.freezeDuringAnimation(contentPane);
        SidebarAnimator.addHoverScale(homeButton, 1.1);
        SidebarAnimator.addHoverScale(avatarButton, 1.1);
        SidebarAnimator.addPressBounce(avatarButton);

        for (AppModule module : authService.visibleModules()) {
            Button button = new Button(module.getLabel(), ModuleIcons.of(module));
            button.getStyleClass().add("nav-pill");
            button.setOnAction(e -> onModuleSelected(module, button));
            sidebarAnimator.register(button, module.getLabel());
            menuBox.getChildren().add(button);
        }
        onHome();
    }

    /** Como en el diseño: si el menú está contraído, primero se expande y luego abre el módulo. */
    private void onModuleSelected(AppModule module, Button button) {
        sidebarAnimator.setExpanded(true, () -> openModule(module, button));
       
    }

    private void openModule(AppModule module, Button button) {
        try {
            authService.checkAccess(module);
            sidebarAnimator.markActive(button);
            if (module == AppModule.BITACORA) {
                showContent(module.getLabel(), "Registro de ingresos y acciones del sistema",
                        viewLoader.load(FxmlViewLoader.ACCESS_LOG));
            } else {
                showMessage(module.getLabel(), "Este módulo se implementa en un próximo sprint.");
            }
        } catch (BusinessException e) {
            Alert alert = new Alert(Alert.AlertType.WARNING, e.getMessage());
            alert.setHeaderText(null);
            alert.showAndWait();
        } catch (DataAccessException | TransactionException e) {
            // HU-11: si la bitácora no se puede escribir, el módulo no se abre y se informa el error.
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "No se pudo registrar el acceso en la bitácora, por eso el módulo no se abrió. Intente de nuevo.");
            alert.setHeaderText(null);
            alert.showAndWait();
        }
    }

    @FXML
    private void onHome() {
        sidebarAnimator.markActive(null);
        showMessage("Hola, " + firstName(sessionContext.getDisplayName()),
                "Seleccione un módulo en el menú de la izquierda.");
    }

    @FXML
    private void onToggleSidebar() {
        sidebarAnimator.toggle();
    }

    private void showMessage(String title, String text) {
        Label message = new Label(text);
        message.getStyleClass().add("placeholder");
        showContent(title, "", message);
    }

    private void showContent(String title, String subtitle, Node content) {
        titleLabel.setText(title);
        subtitleLabel.setText(subtitle);
        subtitleLabel.setVisible(!subtitle.isEmpty());
        subtitleLabel.setManaged(!subtitle.isEmpty());
        contentPane.getChildren().setAll(content);
        SidebarAnimator.fadeIn(content);
    }

    private static String firstName(String displayName) {
        return displayName.isBlank() ? "" : displayName.trim().split("\\s+")[0];
    }

    private static String initials(String displayName) {
        StringBuilder result = new StringBuilder();
        for (String part : displayName.trim().split("\\s+")) {
            if (!part.isEmpty() && result.length() < 2) {
                result.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return result.toString();
    }

    @FXML
    private void onLogout() {
        authService.logout();
        viewLoader.switchScene(contentPane, FxmlViewLoader.LOGIN, FxmlViewLoader.LOGIN_TITLE);
    }
}
