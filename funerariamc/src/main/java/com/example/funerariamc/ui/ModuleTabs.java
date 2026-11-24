package com.example.funerariamc.ui;

import com.example.funerariamc.service.AppModule;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Pestañas de un módulo que reúne varias pantallas, como en Transporte: por ejemplo Clientes con
 * Afiliados a planes, Difuntos y Contratos y ventas. Cada pantalla sigue siendo la misma de antes;
 * aquí solo se le agrega, debajo de su banda verde, la barra con las pestañas del grupo.
 */
public final class ModuleTabs {

    /**
     * Una pestaña: el módulo que muestra, la parte de ese módulo (section, null si es el módulo
     * entero; por ejemplo "movements" dentro de Finanzas) y el nombre que se ve.
     */
    public record Tab(AppModule module, String section, String label) {

        public Tab(AppModule module, String label) {
            this(module, null, label);
        }
    }

    private static final String STRIP = "module-tabs-strip";
    private static final String HERO_BAND = "hero-band";

    private ModuleTabs() {
    }

    /**
     * Pone (o actualiza) la barra de pestañas en la pantalla y marca la pestaña activa.
     * Si la pantalla no tiene banda verde, la barra va al comienzo de la pantalla.
     */
    public static void show(Node content, List<Tab> tabs, Tab active, Consumer<Tab> onSelect) {
        HBox strip = (HBox) content.getProperties().get(STRIP);
        if (strip == null) {
            strip = build(tabs, onSelect);
            if (!insert(content, strip)) {
                return;
            }
            content.getProperties().put(STRIP, strip);
        }
        select(strip, active);
    }

    private static HBox build(List<Tab> tabs, Consumer<Tab> onSelect) {
        ToggleGroup group = new ToggleGroup();
        HBox buttons = new HBox();
        buttons.getStyleClass().add("module-tab-group");
        for (Tab tab : tabs) {
            ToggleButton button = new ToggleButton(tab.label());
            button.getStyleClass().add("module-tab");
            button.setUserData(tab);
            button.setToggleGroup(group);
            button.setMinWidth(Pane.USE_PREF_SIZE);
            button.setOnAction(e -> {
                // Tocar la pestaña que ya está abierta no la apaga
                button.setSelected(true);
                onSelect.accept(tab);
            });
            buttons.getChildren().add(button);
        }
        HBox strip = new HBox(buttons);
        strip.getStyleClass().add("module-tabs");
        strip.getStylesheets().add(Objects.requireNonNull(
                ModuleTabs.class.getResource("/css/module-tabs.css")).toExternalForm());
        return strip;
    }

    private static void select(HBox strip, Tab active) {
        HBox buttons = (HBox) strip.getChildren().get(0);
        for (Node node : buttons.getChildren()) {
            ToggleButton button = (ToggleButton) node;
            button.setSelected(active.equals(button.getUserData()));
        }
    }

    /** La barra va justo debajo de la banda verde, dentro de la misma columna que se desplaza. */
    private static boolean insert(Node content, HBox strip) {
        Node hero = findByStyleClass(content, HERO_BAND);
        if (hero != null && hero.getParent() instanceof Pane column) {
            column.getChildren().add(column.getChildren().indexOf(hero) + 1, strip);
            return true;
        }
        return false;
    }

    /** Busca también dentro de los ScrollPane: su contenido no aparece entre los hijos hasta dibujarse. */
    private static Node findByStyleClass(Node node, String styleClass) {
        if (node.getStyleClass().contains(styleClass)) {
            return node;
        }
        if (node instanceof ScrollPane scroll) {
            return scroll.getContent() == null ? null : findByStyleClass(scroll.getContent(), styleClass);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findByStyleClass(child, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
