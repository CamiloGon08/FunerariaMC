package com.example.funerariamc.ui;

import javafx.beans.binding.Bindings;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Logo de la funeraria: el que subió en Parámetros o, si no hay, el emblema de El Edén
 * (círculo dorado con el lirio, sin letras). Cambia solo cuando se guarda un logo nuevo.
 */
public class LilyLogo extends Group {

    private static final double BASE = 64;
    private static final String EMBLEM = "/images/emblema.png";
    // Se carga una sola vez y se comparte entre todas las pantallas
    private static Image emblem;
    private final ImageView view = new ImageView();

    public LilyLogo() {
        getStyleClass().add("lily-logo");
        view.imageProperty().bind(Bindings.createObjectBinding(
                () -> BrandTheme.logoProperty().get() != null ? BrandTheme.logoProperty().get() : emblem(),
                BrandTheme.logoProperty()));
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

    /** Emblema del lirio de El Edén (lo usa el formulario de identidad para volver a él). */
    public static Image emblemImage() {
        return emblem();
    }

    private static synchronized Image emblem() {
        if (emblem == null) {
            emblem = new Image(LilyLogo.class.getResource(EMBLEM).toExternalForm(), true);
        }
        return emblem;
    }
}
