package com.example.funerariamc.controller;

import com.example.funerariamc.config.FxmlViewLoader;
import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.enums.Sex;
import com.example.funerariamc.service.AccessLogService;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.layout.VBox;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Menú principal con los módulos del rol de la sesión.
 * El menú lateral se contrae y se expande.
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
    @FXML
    private VBox centerBox;
    @FXML
    private HBox headerBar;

    /** Id del espacio para el encabezado dentro de la banda verde de un módulo. */
    private static final String HERO_HEADER_SLOT = "heroHeaderSlot";
    /** Clase que pone el encabezado en colores claros cuando está sobre la banda verde. */
    private static final String ON_HERO = "on-hero";

    private SidebarAnimator sidebarAnimator;
    /** Primer ítem del menú: vuelve al saludo de inicio. */
    private Button homePill;
    /** Grupo desplegable de Finanzas: Resumen financiero y Panel de análisis. */
    private Button financeGroup;
    private Node financeChevron;
    private VBox financeItems;
    /** Pantallas ya abiertas en esta sesión; al cerrar sesión se descartan con este controlador. */
    private final Map<AppModule, FxmlViewLoader.View<Object>> openViews = new EnumMap<>(AppModule.class);
    private static final DateTimeFormatter HOME_DATE =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM · HH:mm", Locale.forLanguageTag("es-CO"));
    private FxmlViewLoader.View<Object> homeView;
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
        // Punto verde antes del texto: la sesión está activa.
        Circle sessionDot = new Circle(4);
        sessionDot.getStyleClass().add("session-dot");
        sessionBadge.setGraphic(sessionDot);
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
                    "Afiliados, beneficiarios y cuotas. Toque un plan para ver beneficiarios, pagos y servicios usados", FxmlViewLoader.FUNERAL_PLANS)),
            Map.entry(AppModule.CEMENTERIO, new ModuleScreen(AppModule.CEMENTERIO.getLabel(),
                    "Muro de bóvedas por pabellón. Toque una bóveda para ver quién reposa, la familia y el arriendo", FxmlViewLoader.CEMETERY)),
            Map.entry(AppModule.SALAS, new ModuleScreen("Salas de velación",
                    "Ocupación de las salas por día. Toque una sala para ver quién está y el detalle del servicio", FxmlViewLoader.WAKE_ROOMS)),
            Map.entry(AppModule.TRANSPORTE, new ModuleScreen("Transporte",
                    "Monitor de la flota: dónde va cada vehículo, su ruta y los traslados del día", FxmlViewLoader.TRANSPORT)),
            Map.entry(AppModule.CONTRATOS, new ModuleScreen("Contratos y ventas",
                    "Filtre por tipo de venta: contrato con plan o pensión, servicio particular o venta de productos", FxmlViewLoader.CONTRACTS)),
            Map.entry(AppModule.INVENTARIO, new ModuleScreen(AppModule.INVENTARIO.getLabel(),
                    "Cada artículo con su foto para reconocerlo rápido. El sistema alerta cuando llega al nivel mínimo", FxmlViewLoader.INVENTORY)),
            Map.entry(AppModule.CREMACIONES, new ModuleScreen(AppModule.CREMACIONES.getLabel(),
                    "Estado de los hornos o proveedores, cremaciones del día y lotes programados, con control de la Fiscalía", FxmlViewLoader.CREMATIONS)),
            Map.entry(AppModule.PARAMETROS, new ModuleScreen(AppModule.PARAMETROS.getLabel(),
                    "Valores que usan los cálculos, por tema. Solo los cambia el Administrador Maestro", FxmlViewLoader.PARAMETERS)),
            Map.entry(AppModule.FINANZAS, new ModuleScreen("Resumen financiero",
                    "Ingresos, egresos y comisiones del período. Los movimientos se anulan, nunca se borran", FxmlViewLoader.FINANCES)),
            Map.entry(AppModule.RED_ALIADAS, new ModuleScreen(AppModule.RED_ALIADAS.getLabel(),
                    "Funerarias con convenio, servicios que nos prestan y saldos por pagar", FxmlViewLoader.ALLIED_NETWORK)),
            Map.entry(AppModule.ANALISIS, new ModuleScreen(AppModule.ANALISIS.getLabel(),
                    "Solo lectura. Ventas, ocupación y equipo del período", FxmlViewLoader.ANALYTICS)));

    /**
     * El permiso se revisa en memoria y la pantalla se muestra al instante; la entrada de la
     * bitácora se escribe en otro hilo. Si no se puede escribir, el módulo se cierra.
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
                // Si la bitácora no se puede escribir, el módulo no queda abierto y se informa el error.
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
        String greeting = "Hola, " + firstName(sessionContext.getDisplayName());
        if (!seesHomeBoard()) {
            showMessage(greeting, "Seleccione un módulo en el menú de la izquierda.");
            return;
        }
        // El Administrador Maestro y la Secretaría ven el resumen del día; se arma una sola vez y se refresca.
        if (homeView == null) {
            homeView = viewLoader.loadView(FxmlViewLoader.HOME);
        } else if (homeView.controller() instanceof RefreshableView refreshable) {
            refreshable.refresh();
        }
        String today = clean(LocalDateTime.now(AccessLogService.APP_ZONE).format(HOME_DATE));
        showContent(greeting, today + " · así va la funeraria en este momento", homeView.root());
    }

    private boolean seesHomeBoard() {
        return sessionContext.getRoleName()
                .map(role -> RoleNames.MASTER_ADMIN.equals(role) || RoleNames.SECRETARY.equals(role)).orElse(false);
    }

    /** "Domingo 8 de noviembre · 10:30": primera letra en mayúscula y sin el punto que agrega Java. */
    private static String clean(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
        placeHeader(content);
        SidebarAnimator.fadeIn(content);
    }

    /**
     * Las pantallas con banda verde (paleta El Edén verde) traen un espacio vacío con id
     * "heroHeaderSlot": el encabezado del menú (título, sesión y Cerrar sesión) se pasa ahí
     * para que quede dentro de la banda. Las demás pantallas lo siguen mostrando arriba.
     */
    private void placeHeader(Node content) {
        Node slot = content.lookup("#" + HERO_HEADER_SLOT);
        if (slot instanceof Pane heroSlot) {
            if (headerBar.getParent() != heroSlot) {
                centerBox.getChildren().remove(headerBar);
                heroSlot.getChildren().setAll(headerBar);
            }
            if (!headerBar.getStyleClass().contains(ON_HERO)) {
                headerBar.getStyleClass().add(ON_HERO);
            }
        } else if (headerBar.getParent() != centerBox) {
            if (headerBar.getParent() instanceof Pane previous) {
                previous.getChildren().remove(headerBar);
            }
            centerBox.getChildren().add(0, headerBar);
            headerBar.getStyleClass().remove(ON_HERO);
        }
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