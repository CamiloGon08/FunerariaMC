package com.example.funerariamc.controller;

import javafx.scene.Group;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**Logo de Funeraria El Edén (lirio) */
public class LilyLogo extends Group {

    private static final double BASE = 64;

    private static final String LEAVES =
            "M32 40 C27 46 18 50 7 52 C15 56 25 54 31 45 Z "
            + "M32 40 C37 46 46 50 57 52 C49 56 39 54 33 45 Z "
            + "M31.4 40 C31.6 47 31 55 30 62 L31.6 62 C32.6 55 32.8 47 32.6 40 Z";

    private static final String PETALS =
            "M32.00 36.00 C30.84 44.99 37.07 54.69 39.71 59.26 C43.75 53.05 39.89 40.31 32.00 36.00 Z "
            + "M32.00 36.00 C23.64 39.49 18.35 49.74 24.29 59.26 C23.11 54.70 32.21 44.99 32.00 36.00 Z "
            + "M32.00 36.00 C41.21 38.50 52.75 33.72 59.07 34.34 C52.45 26.80 38.22 28.76 32.00 36.00 Z "
            + "M32.00 36.00 C26.55 28.16 14.64 24.41 4.93 34.34 C10.42 29.90 22.58 37.55 32.00 36.00 Z "
            + "M32.00 36.00 C38.50 27.00 37.85 12.60 37.20 6.00 C30.05 10.20 26.48 26.40 32.00 36.00 Z "
            + "M32.00 36.00 C37.56 44.41 49.94 48.28 54.91 52.00 C54.44 42.25 41.86 34.16 32.00 36.00 Z "
            + "M32.00 36.00 C22.34 33.13 10.36 38.13 9.09 52.00 C10.80 45.63 25.63 43.75 32.00 36.00 Z";

    private static final String VEINS =
            "M33.50 38.60 Q35.37 45.14 39.81 52.28 "
            + "M30.50 38.60 Q25.77 43.49 21.81 50.90 "
            + "M34.82 34.97 Q42.34 33.99 50.79 30.62 "
            + "M29.18 34.97 Q22.79 30.89 14.15 28.04 "
            + "M32.00 33.00 Q33.95 24.00 33.62 13.50 "
            + "M34.82 37.03 Q41.53 41.39 50.52 44.34 "
            + "M29.18 37.03 Q21.24 38.00 12.46 41.52";

    private static final String STAMENS =
            "M32.00 35.00 Q26.11 27.74 24.02 20.99 "
            + "M32.00 35.00 Q28.33 25.78 28.05 17.42 "
            + "M32.00 35.00 Q31.59 25.61 33.99 17.10 "
            + "M32.00 35.00 Q34.00 27.33 38.37 20.24 "
            + "M32.00 35.00 Q35.03 29.77 40.23 24.67";

    private static final String ANTHERS =
            "M22.72 20.99 a1.3 0.8 0 1 0 2.6 0 a1.3 0.8 0 1 0 -2.6 0 Z "
            + "M26.75 17.42 a1.3 0.8 0 1 0 2.6 0 a1.3 0.8 0 1 0 -2.6 0 Z "
            + "M32.69 17.10 a1.3 0.8 0 1 0 2.6 0 a1.3 0.8 0 1 0 -2.6 0 Z "
            + "M37.07 20.24 a1.3 0.8 0 1 0 2.6 0 a1.3 0.8 0 1 0 -2.6 0 Z "
            + "M38.93 24.67 a1.3 0.8 0 1 0 2.6 0 a1.3 0.8 0 1 0 -2.6 0 Z";

    private final Group art = new Group();
    private final Scale scale = new Scale(1, 1, 0, 0);

    public LilyLogo() {
        getStyleClass().add("lily-logo");
        art.getTransforms().add(scale);
        getChildren().add(art);
        art.getChildren().addAll(
                path(LEAVES, "lily-leaves"),
                path(PETALS, "lily-petals"),
                path(VEINS, "lily-veins"),
                path(STAMENS, "lily-stamens"),
                path(ANTHERS, "lily-anthers"));
        setSize(BASE);
    }

    /*Tamaño en pixeles del lado del logo */
    public void setSize(double size) {
        scale.setX(size / BASE);
        scale.setY(size / BASE);
    }

    public double getSize() {
        return scale.getX() * BASE;
    }

    private static SVGPath path(String content, String styleClass) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add(styleClass);
        return path;
    }
}
