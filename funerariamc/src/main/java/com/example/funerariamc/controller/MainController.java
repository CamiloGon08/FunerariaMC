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
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.ui.SidebarAnimator;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    /** Pantallas ya abiertas en esta sesión; al cerrar sesión se descartan con este controlador. */
    private final Map<AppModule, FxmlViewLoader.View<Object>> openViews = new EnumMap<>(AppModule.class);
    /** Módulo que se está viendo, para no cerrar otro si falla la bitácora de uno anterior. */
    private AppModule currentModule;

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

    /** Pantalla de cada módulo ya construido: título, subtítulo y vista FXML. */
    private record ModuleScreen(String title, String subtitle, String fxml) {
    }

    /** Cada módulo nuevo agrega aquí su línea; los que no están muestran "próximo sprint". */
    private static final Map<AppModule, ModuleScreen> SCREENS = Map.ofEntries(
            Map.entry(AppModule.BITACORA, new ModuleScreen(AppModule.BITACORA.getLabel(),
                    "Registro de ingresos y acciones del sistema", FxmlViewLoader.ACCESS_LOG)),
            Map.entry(AppModule.SEGURIDAD, new ModuleScreen("Personal",
                    "Registre, consulte, active o desactive a los empleados de la funeraria", FxmlViewLoader.EMPLOYEES)),
            Map.entry(AppModule.CLIENTES, new ModuleScreen("Clientes",
                    "Titulares y empresas con su plan exequial, contratos y saldo pendiente", FxmlViewLoader.CLIENTS)),
            Map.entry(AppModule.DIFUNTOS, new ModuleScreen("Difuntos",
                    "Servicio en curso, documentos y destino final. Toque una tarjeta o fila para ver el expediente", FxmlViewLoader.DECEASED)),
            Map.entry(AppModule.PLANES, new ModuleScreen("Planes exequiales",
                    "Afiliados, beneficiarios y cuotas. Toque un plan para ver beneficiarios, pagos y servicios usados", FxmlViewLoader.FUNERAL_PLANS)));

    /**
     * El permiso se revisa en memoria y la pantalla se muestra al instante; la entrada de la
     * bitácora se escribe en otro hilo. Si no se puede escribir, el módulo se cierra (HU-11).
     */
    private void openModule(AppModule module, Button button) {
        if (!authService.canAccess(module)) {
            // checkAccess deja ACCESS_DENIED en la bitácora y lanza la excepción que se muestra.
            recordAccess(module);
            return;
        }
        sidebarAnimator.markActive(button);
        currentModule = module;
        ModuleScreen screen = SCREENS.get(module);
        if (screen == null) {
            showMessage(module.getLabel(), "Este módulo se implementa en un próximo sprint.");
        } else {
            showContent(screen.title(), screen.subtitle(), moduleView(module, screen));
        }
        recordAccess(module);
    }

    /** La primera vez arma la pantalla; las siguientes reutiliza la misma y le pide datos nuevos. */
    private Node moduleView(AppModule module, ModuleScreen screen) {
        FxmlViewLoader.View<Object> view = openViews.get(module);
        if (view == null) {
            view = viewLoader.loadView(screen.fxml());
            openViews.put(module, view);
        } else if (view.controller() instanceof RefreshableView refreshable) {
            refreshable.refresh();
        }
        return view.root();
    }

    private void recordAccess(AppModule module) {
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                authService.checkAccess(module);
                return null;
            }
        };
        task.setOnFailed(e -> accessFailed(module, task.getException()));
        Thread thread = new Thread(task, "access-log-writer");
        thread.setDaemon(true);
        thread.start();
    }

    private void accessFailed(AppModule module, Throwable failure) {
        if (module == currentModule) {
            onHome();
        }
        String message = failure instanceof BusinessException business
                ? business.getMessage()
                // HU-11: si la bitácora no se puede escribir, el módulo no queda abierto y se informa el error.
                : "No se pudo registrar el acceso en la bitácora, por eso el módulo no se abrió. Intente de nuevo.";
        Alert alert = new Alert(failure instanceof BusinessException ? Alert.AlertType.WARNING : Alert.AlertType.ERROR,
                message);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    @FXML
    private void onHome() {
        currentModule = null;
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
