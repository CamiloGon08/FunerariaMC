package com.example.funerariamc.ui;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/** Logo de Funeraria El Edén: emblema del círculo dorado con el lirio (sin letras). */
public class LilyLogo extends Group {

    private static final double BASE = 64;
    private static final String EMBLEM = "/images/emblema.png";
    // Se carga una sola vez y se comparte entre todas las pantallas
    private static Image emblem;
    private final ImageView view = new ImageView();

    public LilyLogo() {
        getStyleClass().add("lily-logo");
        view.setImage(emblem());
        view.setPreserveRatio(true);
        view.setSmooth(true);
        getChildren().add(view);
        setSize(BASE);
    }

    /* Tamaño en pixeles del lado del logo */
    public void setSize(double size) {
        view.setFitWidth(size);
        view.setFitHeight(size);
    }

    public double getSize() {
        return view.getFitWidth();
    }

    private static synchronized Image emblem() {
        if (emblem == null) {
            emblem = new Image(LilyLogo.class.getResource(EMBLEM).toExternalForm(), true);
        }
        return emblem;
    }
}
