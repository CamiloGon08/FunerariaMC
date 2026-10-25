package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.Sex;
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
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.List;

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
    /** Primer ítem del menú: vuelve al saludo de inicio. */
    private Button homePill;
    /** Grupo desplegable de Finanzas: Resumen financiero y Panel de análisis. */
    private Button financeGroup;
    private Node financeChevron;
    private VBox financeItems;

    public MainController(AuthService authService, SessionContext sessionContext, FxmlViewLoader viewLoader) {
        this.authService = authService;
        this.sessionContext = sessionContext;
        this.viewLoader = viewLoader;
    }

    @FXML
    private void initialize() {
        // "Secretario" o "Secretaria" según el sexo registrado en Personal.
        Sex sex = sessionContext.getCurrentUser().map(AppUser::getEmployee).map(Employee::getSex).orElse(null);
        String roleName = sessionContext.getRoleName().map(role -> RoleNames.displayName(role, sex)).orElse("");
        userNameLabel.setText(sessionContext.getDisplayName());
        roleLabel.setText(roleName);
        sessionBadge.setText("Sesión activa · " + roleName);
        avatarButton.setText(initials(sessionContext.getDisplayName()));

        sidebarAnimator = new SidebarAnimator(sidebar, brandText, collapseButton, profileText);
        sidebarAnimator.freezeDuringAnimation(contentPane);
        SidebarAnimator.addHoverScale(homeButton, 1.1);
        SidebarAnimator.addHoverScale(avatarButton, 1.1);
        SidebarAnimator.addPressBounce(avatarButton);

        homePill = new Button("Inicio", ModuleIcons.of("INICIO"));
        homePill.getStyleClass().add("nav-pill");
        homePill.setOnAction(e -> onHome());
        sidebarAnimator.register(homePill, "Inicio");
        menuBox.getChildren().add(homePill);

        List<AppModule> modules = authService.visibleModules();
        // El grupo solo tiene sentido si el rol ve también el Panel de análisis (Administrador Maestro).
        boolean financeGroupNeeded = modules.contains(AppModule.FINANZAS) && modules.contains(AppModule.ANALISIS);
        for (AppModule module : modules) {
            if (financeGroupNeeded && module == AppModule.FINANZAS) {
                addFinanceGroup();
            } else if (!(financeGroupNeeded && module == AppModule.ANALISIS)) {
                menuBox.getChildren().add(modulePill(module, module.getLabel(), ModuleIcons.of(module), "nav-pill"));
            }
        }
        onHome();
    }

    private Button modulePill(AppModule module, String text, Node icon, String styleClass) {
        Button button = new Button(text, icon);
        button.getStyleClass().add(styleClass);
        button.setOnAction(e -> onModuleSelected(module, button));
        sidebarAnimator.register(button, text);
        return button;
    }

    /** "Finanzas ›": al tocarlo baja la flecha y muestra Resumen financiero y Panel de análisis. */
    private void addFinanceGroup() {
        financeGroup = new Button(AppModule.FINANZAS.getLabel(), ModuleIcons.of(AppModule.FINANZAS));
        financeGroup.getStyleClass().add("nav-pill");
        sidebarAnimator.register(financeGroup, AppModule.FINANZAS.getLabel());
        financeChevron = ModuleIcons.of("CHEVRON");
        financeChevron.getStyleClass().add("nav-chevron");
        financeChevron.setMouseTransparent(true);
        StackPane groupRow = new StackPane(financeGroup, financeChevron);
        groupRow.getStyleClass().add("nav-group");
        StackPane.setAlignment(financeChevron, Pos.CENTER_RIGHT);

        financeItems = new VBox(3,
                modulePill(AppModule.FINANZAS, "Resumen financiero", ModuleIcons.of("RESUMEN"), "nav-sub-pill"),
                modulePill(AppModule.ANALISIS, AppModule.ANALISIS.getLabel(), ModuleIcons.of(AppModule.ANALISIS), "nav-sub-pill"));
        financeItems.setAlignment(Pos.TOP_RIGHT);
        financeItems.getStyleClass().add("nav-sub-items");
        financeItems.managedProperty().bind(financeItems.visibleProperty());
        financeItems.setVisible(false);

        // Con el menú contraído primero se expande y luego se despliega el grupo.
        financeGroup.setOnAction(e -> sidebarAnimator.setExpanded(true, () -> showFinanceItems(!financeItems.isVisible())));
        menuBox.getChildren().addAll(groupRow, financeItems);
    }

    private void showFinanceItems(boolean show) {
        if (financeItems == null) {
            return;
        }
        financeItems.setVisible(show);
        financeChevron.setRotate(show ? 90 : 0);
        financeGroup.getStyleClass().remove("open");
        if (show) {
            financeGroup.getStyleClass().add("open");
            SidebarAnimator.fadeIn(financeItems);
        }
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
            } else if (module == AppModule.SEGURIDAD) {
                showContent("Personal", "Registre, consulte, active o desactive a los empleados de la funeraria",
                        viewLoader.load(FxmlViewLoader.EMPLOYEES));
            } else if (module == AppModule.CLIENTES) {
                showContent("Clientes", "Registre y consulte a los clientes titulares, personas naturales o empresas",
                        viewLoader.load(FxmlViewLoader.CLIENTS));
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
        sidebarAnimator.markActive(homePill);
        showMessage("Hola, " + firstName(sessionContext.getDisplayName()),
                "Seleccione un módulo en el menú de la izquierda.");
    }

    @FXML
    private void onToggleSidebar() {
        // Contraído no caben los subítems: el grupo se cierra.
        if (sidebarAnimator.isExpanded()) {
            showFinanceItems(false);
        }
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
