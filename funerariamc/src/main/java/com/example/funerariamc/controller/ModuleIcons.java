package com.example.funerariamc.controller;

import com.example.funerariamc.service.AppModule;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/**
 * Íconos de línea (24×24) de cada módulo del menú lateral, iguales a los del
 * mockup de Figma. Se buscan por {@code AppModule.name()} para no romper la
 * compilación si se agrega o renombra un módulo: en ese caso usa DEFAULT.
 */
final class ModuleIcons {

    private static final double SIZE = 20;

    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("SEGURIDAD", "M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z M9 11h6v5h-6z M10 11V9.5a2 2 0 0 1 4 0V11"),
            Map.entry("BITACORA", "M5 4h14v16H5z M9 8h6M9 12h6M9 16h3"),
            Map.entry("CLIENTES", "M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0 M2.5 20c0-3.5 3-5.5 6.5-5.5s6.5 2 6.5 5.5M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5c2 .6 3.5 2.4 3.5 5.5"),
            Map.entry("DIFUNTOS", "M12 3v18M7 8h10"),
            Map.entry("PLANES", "M12 21s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 5.5-7 10-7 10z"),
            Map.entry("CEMENTERIO", "M6 21V9a6 6 0 0 1 12 0v12z M12 9v6M9.5 11.5h5M3 21h18"),
            Map.entry("SALAS", "M3 10h18M5 10v9h14v-9M8 6h8l2 4H6z"),
            Map.entry("CONTRATOS", "M6 3h9l4 4v14H6z M9 11h7M9 15h7"),
            Map.entry("INVENTARIO", "M3 7l9-4 9 4v10l-9 4-9-4z M3 7l9 4 9-4M12 11v10"),
            Map.entry("TRANSPORTE", "M2 16V8h12v8M14 11h4l3 3v2h-7 M4.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0 M15.2 17.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0"),
            Map.entry("CREMACIONES", "M12 3c3 4 5 6 5 10a5 5 0 0 1-10 0c0-2 1-3.5 2-5 .5 2 1.5 3 3 3-1-3 0-6 0-8z"),
            Map.entry("FINANZAS", "M3 6h18v13h-18z M16 12.5h2M3 10h18"),
            Map.entry("RED_ALIADAS", "M9.8 5a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0 M2.8 19a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0 M16.8 19a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0 M12 7.2v4.3M12 11.5 6.5 17.3M12 11.5l5.5 5.8"),
            Map.entry("PARAMETROS", "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12 M14 6a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M8 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M16 18a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
            Map.entry("DEFAULT", "M3 3h7v9h-7z M14 3h7v5h-7z M14 12h7v9h-7z M3 16h7v5h-7z")
    );

    private ModuleIcons() {
    }

    /** Ícono del módulo escalado a 20 px; el color lo define la clase CSS {@code nav-icon}. */
    static Node of(AppModule module) {
        SVGPath path = new SVGPath();
        path.setContent(PATHS.getOrDefault(module.name(), PATHS.get("DEFAULT")));
        path.getStyleClass().add("nav-icon");
        path.setScaleX(SIZE / 24);
        path.setScaleY(SIZE / 24);
        // Caja fija de 20×20 para que todos los textos del menú queden alineados.
        StackPane box = new StackPane(new Group(path));
        box.setMinSize(SIZE, SIZE);
        box.setPrefSize(SIZE, SIZE);
        box.setMaxSize(SIZE, SIZE);
        return box;
    }
}