package com.example.funerariamc.controller;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Menú lateral flotante que se contrae (solo íconos) y se expande (íconos y
 * nombres), con las animaciones del mockup "06/07 MASTER_ADMIN" de Figma:
 * cambio de ancho en 220 ms, rebote al presionar y aumento al pasar el mouse.
 */
final class SidebarAnimator {

    static final double COLLAPSED_WIDTH = 80;
    static final double EXPANDED_WIDTH = 288;

    private static final Duration WIDTH_DURATION = Duration.millis(220);
    private static final Duration SCALE_DURATION = Duration.millis(140);
    private static final String ANIMATING = "animating";
    private static final String PILLS_COLLAPSED = "pills-collapsed";
    private static final String SCALE_KEY = "sidebar.scale";
    private static final String TOOLTIP_KEY = "sidebar.tooltip";
    // El tooltip solo aparece al dejar el mouse sobre un ícono del menú contraído
    // y se oculta solo al segundo (o antes, si el mouse sale del ícono).
    private static final Duration TOOLTIP_SHOW_DELAY = Duration.millis(400);
    private static final Duration TOOLTIP_VISIBLE = Duration.seconds(1);

    private final Region sidebar;
    private final List<Node> expandedOnly;
    private final List<Labeled> pills = new ArrayList<>();
    private boolean expanded;
    private boolean pillsExpanded;
    private Timeline widthTimeline;
    private Pane frozenContent;
    private ImageView freezeImage;

    /**
     * @param sidebar      contenedor del menú (su ancho se anima)
     * @param expandedOnly nodos que solo se ven con el menú expandido
     */
    SidebarAnimator(Region sidebar, Node... expandedOnly) {
        this.sidebar = sidebar;
        this.expandedOnly = List.of(expandedOnly);
        setWidth(COLLAPSED_WIDTH);
        applyContentState(false);
    }

    /** Registra un botón del menú: tooltip en modo contraído y animaciones de hover y clic. */
    void register(Labeled pill, String tooltipText) {
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(TOOLTIP_SHOW_DELAY);
        tooltip.setShowDuration(TOOLTIP_VISIBLE);
        tooltip.setHideDelay(Duration.ZERO);
        pill.getProperties().put(TOOLTIP_KEY, tooltip);
        addHoverScale(pill, 1.08);
        addPressBounce(pill);
        pills.add(pill);
        applyPillState(pill);
    }

    /**
     * Mientras el menú cambia de ancho, el contenido de la derecha se muestra como una
     * imagen fija en lugar de recalcular su tamaño en cada cuadro (la tabla de la
     * bitácora, por ejemplo). Eso es lo que quita los "tirones".
     */
    void freezeDuringAnimation(Pane content) {
        this.frozenContent = content;
    }

    boolean isExpanded() {
        return expanded;
    }

    void toggle() {
        setExpanded(!expanded, null);
    }

    /** Cambia el estado y ejecuta {@code after} cuando termina la animación. */
    void setExpanded(boolean value, Runnable after) {
        if (value == expanded) {
            if (after != null) {
                after.run();
            }
            return;
        }
        expanded = value;
        if (value) {
            // Al expandir, los botones pasan a píldora desde el inicio y crecen con el menú,
            // así el texto se va descubriendo en vez de aparecer de golpe al final.
            setPillsExpanded(true);
        } else {
            // Al contraer se ocultan los textos de marca y perfil antes de achicar el menú.
            applyContentState(false);
        }
        if (widthTimeline != null) {
            widthTimeline.stop();
        }
        // Sin sombra mientras cambia el ancho (ver .sidebar.animating en app.css).
        if (!sidebar.getStyleClass().contains(ANIMATING)) {
            sidebar.getStyleClass().add(ANIMATING);
        }
        freezeContent();
        double target = value ? EXPANDED_WIDTH : COLLAPSED_WIDTH;
        widthTimeline = new Timeline(new KeyFrame(WIDTH_DURATION,
                new KeyValue(sidebar.prefWidthProperty(), target, Interpolator.EASE_BOTH),
                new KeyValue(sidebar.minWidthProperty(), target, Interpolator.EASE_BOTH),
                new KeyValue(sidebar.maxWidthProperty(), target, Interpolator.EASE_BOTH)));
        widthTimeline.setOnFinished(e -> {
            sidebar.getStyleClass().remove(ANIMATING);
            unfreezeContent();
            applyContentState(value);
            if (after != null) {
                after.run();
            }
        });
        widthTimeline.play();
    }

    private void freezeContent() {
        if (frozenContent == null || freezeImage != null || frozenContent.getWidth() <= 0) {
            return;
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        freezeImage = new ImageView(frozenContent.snapshot(params, null));
        freezeImage.setManaged(false);
        for (Node child : frozenContent.getChildren()) {
            child.setManaged(false);
            child.setVisible(false);
        }
        frozenContent.getChildren().add(freezeImage);
        freezeImage.relocate(0, 0);
    }

    private void unfreezeContent() {
        if (freezeImage == null) {
            return;
        }
        frozenContent.getChildren().remove(freezeImage);
        freezeImage = null;
        for (Node child : frozenContent.getChildren()) {
            child.setManaged(true);
            child.setVisible(true);
        }
    }

    /** Marca el botón activo con un pequeño "pop" (crece y vuelve a 1.05). */
    void markActive(Labeled active) {
        for (Labeled pill : pills) {
            pill.getStyleClass().remove("active");
            if (pill != active) {
                scaleTo(pill, 1.0);
            }
        }
        if (active != null) {
            active.getStyleClass().add("active");
            ScaleTransition grow = scale(active, 1.14, 120);
            ScaleTransition settle = scale(active, 1.05, 160);
            new SequentialTransition(grow, settle).play();
        }
    }

    /** Entrada suave del contenido de un módulo. */
    static void fadeIn(Node content) {
        content.setOpacity(0);
        content.setTranslateY(10);
        FadeTransition fade = new FadeTransition(Duration.millis(260), content);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(260), content);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, slide).play();
    }

    /** Aumenta el nodo al pasar el mouse y lo devuelve a su tamaño al salir. */
    static void addHoverScale(Node node, double factor) {
        node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> scaleTo(node, factor));
        node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> scaleTo(node, restingScale(node)));
    }

    /** Efecto de "encogerse y agrandarse" al hacer clic. */
    static void addPressBounce(Node node) {
        node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> scaleTo(node, 0.9));
        node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> scaleTo(node, node.isHover() ? 1.08 : restingScale(node)));
    }

    private static double restingScale(Node node) {
        return node.getStyleClass().contains("active") ? 1.05 : 1.0;
    }

    private static void scaleTo(Node node, double value) {
        Object running = node.getProperties().get(SCALE_KEY);
        if (running instanceof ScaleTransition transition) {
            transition.stop();
        }
        ScaleTransition transition = scale(node, value, SCALE_DURATION.toMillis());
        node.getProperties().put(SCALE_KEY, transition);
        transition.play();
    }

    private static ScaleTransition scale(Node node, double value, double millis) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(millis), node);
        transition.setToX(value);
        transition.setToY(value);
        transition.setInterpolator(Interpolator.EASE_BOTH);
        return transition;
    }

    private void setWidth(double width) {
        sidebar.setPrefWidth(width);
        sidebar.setMinWidth(width);
        sidebar.setMaxWidth(width);
    }

    private void applyContentState(boolean showText) {
        sidebar.getStyleClass().removeAll("collapsed", "expanded");
        sidebar.getStyleClass().add(showText ? "expanded" : "collapsed");
        for (Node node : expandedOnly) {
            boolean wasVisible = node.isVisible();
            node.setVisible(showText);
            node.setManaged(showText);
            if (showText && !wasVisible) {
                fadeIn(node);
            }
        }
        setPillsExpanded(showText);
    }

    private void setPillsExpanded(boolean value) {
        pillsExpanded = value;
        sidebar.getStyleClass().remove(PILLS_COLLAPSED);
        if (!value) {
            sidebar.getStyleClass().add(PILLS_COLLAPSED);
        }
        pills.forEach(this::applyPillState);
    }

    private void applyPillState(Labeled pill) {
        pill.setContentDisplay(pillsExpanded ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
        pill.setTooltip(pillsExpanded ? null : (Tooltip) pill.getProperties().get(TOOLTIP_KEY));
    }
}