package com.example.funerariamc.config;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;

/**
 * Carga las vistas FXML usando Spring como fábrica de controladores,
 * de modo que los controladores reciben sus servicios por constructor.
 */
@Component
public class FxmlViewLoader {

    public static final String LOGIN = "/fxml/login.fxml";
    public static final String MAIN = "/fxml/main.fxml";
    public static final String ACCESS_LOG = "/fxml/access-log.fxml";
    public static final String PASSWORD_RECOVERY = "/fxml/password-recovery.fxml";

    public static final String LOGIN_TITLE = "Funeraria MC — Iniciar sesión";
    public static final String MAIN_TITLE = "Funeraria MC";
    public static final String PASSWORD_RECOVERY_TITLE = "Funeraria MC — Recuperar contraseña";

    private final ApplicationContext context;

    public FxmlViewLoader(ApplicationContext context) {
        this.context = context;
    }

    /** Carga una vista y devuelve su nodo raíz. */
    public Parent load(String fxmlPath) {
        URL url = getClass().getResource(fxmlPath);
        if (url == null) {
            throw new IllegalArgumentException("No existe la vista " + fxmlPath);
        }
        FXMLLoader loader = new FXMLLoader(url);
        loader.setControllerFactory(context::getBean);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar la vista " + fxmlPath, e);
        }
    }

    /** Reemplaza la escena de la ventana donde está {@code source}. */
    public void switchScene(Node source, String fxmlPath, String title) {
        Stage stage = (Stage) source.getScene().getWindow();
        stage.setScene(new Scene(load(fxmlPath)));
        stage.setTitle(title);
        stage.sizeToScene();
        stage.centerOnScreen();
    }
}