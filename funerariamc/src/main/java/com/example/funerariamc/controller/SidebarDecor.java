package com.example.funerariamc.controller;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;

import java.util.Objects;

/**
 * Foto del lirio detrás de los módulos del menú lateral (images/lirio-fondo.png).
 * Va como primera capa de un StackPane, no recibe clics y se recorta al tamaño
 * del menú; la intensidad se ajusta en app.css con la clase .sidebar-decor.
 */
public class SidebarDecor extends Pane {

    private static final String IMAGE = "/images/lirio-fondo.png";
    private static final double IMAGE_WIDTH = 380;
    private static final double OFFSET_X = -50;

    private final ImageView lily;

    public SidebarDecor() {
        getStyleClass().add("sidebar-decor");
        setMouseTransparent(true);
        setMinSize(0, 0);

        lily = new ImageView(new Image(Objects.requireNonNull(getClass().getResource(IMAGE)).toExternalForm()));
        lily.setPreserveRatio(true);
        lily.setSmooth(true);
        lily.setFitWidth(IMAGE_WIDTH);
        getChildren().add(lily);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    /** Ancla la flor abajo a la izquierda aunque el menú cambie de ancho o alto. */
    @Override
    protected void layoutChildren() {
        double height = lily.getBoundsInLocal().getHeight();
        lily.relocate(OFFSET_X, getHeight() - height);
    }
}
