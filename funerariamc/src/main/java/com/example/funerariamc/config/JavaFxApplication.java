package com.example.funerariamc.config;

import com.example.funerariamc.FunerariamcApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Integra JavaFX con Spring: el contexto se crea antes de mostrar la ventana
 * y se cierra al salir de la aplicación.
 */
public class JavaFxApplication extends Application {

    private ConfigurableApplicationContext context;

    @Override
    public void init() {
        String[] args = getParameters().getRaw().toArray(new String[0]);
        context = new SpringApplicationBuilder(FunerariamcApplication.class)
                .headless(false)
                .run(args);
    }

    @Override
    public void start(Stage stage) {
        FxmlViewLoader viewLoader = context.getBean(FxmlViewLoader.class);
        stage.setTitle(FxmlViewLoader.LOGIN_TITLE);
        stage.setScene(new Scene(viewLoader.load(FxmlViewLoader.LOGIN)));
        stage.centerOnScreen();
        stage.show();
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
        Platform.exit();
    }
}