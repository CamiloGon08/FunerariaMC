package com.example.funerariamc.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Group;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.QuadCurveTo;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.util.Duration;

/**
 * Botón "ojito" para mostrar u ocultar un dato sensible (contraseña en el login, IP en la bitácora).
 * Oculto: ojo cerrado con pestañas. Visible: ojo abierto. Al tocarlo el párpado se abre o se cierra
 * con una animación corta. El color lo define la clase CSS {@code eye-icon}.
 */
public final class EyeToggle {

    private static final double SIZE = 22;
    private static final Duration BLINK = Duration.millis(220);

    /** Punto de control del párpado superior: 4 = abierto (arco hacia arriba), 20 = cerrado (igual al inferior). */
    private static final double LID_OPEN = 4;
    private static final double LID_CLOSED = 20;

    private EyeToggle() {
    }

    /** Pone el ícono y el tooltip al botón y anima el ojo cada vez que se toca. */
    public static void install(ToggleButton button, String showText, String hideText) {
        button.getStyleClass().add("eye-toggle");
        button.setFocusTraversable(false);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        Tooltip tooltip = new Tooltip(showText);
        tooltip.setShowDelay(Duration.millis(400));
        button.setTooltip(tooltip);

        AnimatedEye eye = new AnimatedEye(button.isSelected() ? 1 : 0);
        button.setGraphic(eye.box);
        button.selectedProperty().addListener((obs, oldValue, shown) -> {
            tooltip.setText(shown ? hideText : showText);
            eye.animateTo(shown ? 1 : 0);
        });
    }

    /** Ojo dibujado con figuras; {@code openness} va de 0 (cerrado) a 1 (abierto). */
    private static final class AnimatedEye {

        private final DoubleProperty openness = new SimpleDoubleProperty();
        private final QuadCurveTo upperLid = new QuadCurveTo(12, LID_OPEN, 22, 12);
        private final Circle iris = new Circle(12, 12, 3.4);
        private final Group lashes = new Group(
                new Line(6.5, 14.8, 5, 17.6),
                new Line(12, 16, 12, 19.2),
                new Line(17.5, 14.8, 19, 17.6));
        private final StackPane box;
        private Timeline timeline;

        AnimatedEye(double initial) {
            Path lids = new Path(
                    new MoveTo(2, 12), upperLid,
                    new MoveTo(2, 12), new QuadCurveTo(12, LID_CLOSED, 22, 12));
            styleStroke(lids);

            // El iris solo se ve dentro del ojo: así el párpado lo "tapa" al cerrarse.
            QuadCurveTo clipLid = new QuadCurveTo(12, LID_OPEN, 22, 12);
            clipLid.controlYProperty().bind(upperLid.controlYProperty());
            Path eyeShape = new Path(new MoveTo(2, 12), clipLid, new QuadCurveTo(12, LID_CLOSED, 2, 12));
            eyeShape.setFill(Color.BLACK);
            iris.setClip(eyeShape);
            styleStroke(iris);
            lashes.getChildren().forEach(node -> styleStroke((Shape) node));

            // Marco fijo de 24×24 para que el ícono no salte de tamaño mientras se anima.
            Rectangle frame = new Rectangle(24, 24, Color.TRANSPARENT);
            Group drawing = new Group(frame, lids, iris, lashes);
            drawing.setScaleX(SIZE / 24);
            drawing.setScaleY(SIZE / 24);

            box = new StackPane(new Group(drawing));
            box.setMinSize(SIZE, SIZE);
            box.setPrefSize(SIZE, SIZE);
            box.setMaxSize(SIZE, SIZE);

            openness.addListener((obs, oldValue, value) -> apply(value.doubleValue()));
            openness.set(initial);
            apply(initial);
        }

        void animateTo(double target) {
            if (timeline != null) {
                timeline.stop();
            }
            timeline = new Timeline(new KeyFrame(BLINK, new KeyValue(openness, target, Interpolator.EASE_BOTH)));
            timeline.play();
        }

        private void apply(double open) {
            upperLid.setControlY(LID_CLOSED - (LID_CLOSED - LID_OPEN) * open);
            iris.setOpacity(open);
            lashes.setOpacity(1 - open);
        }

        private static void styleStroke(Shape shape) {
            shape.getStyleClass().add("eye-icon");
        }
    }
}


