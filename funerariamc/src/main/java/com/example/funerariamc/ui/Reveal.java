package com.example.funerariamc.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga suave de una pantalla: mientras llegan los datos, los indicadores se ven como bloques
 * con brillo (esqueleto); al llegar, tarjetas e indicadores entran uno tras otro y los números
 * cuentan desde 0. Solo usa animaciones de JavaFX, no cambia los datos de la pantalla.
 */
public final class Reveal {

    /** Tarjetas e indicadores que entran escalonados. */
    private static final Set<String> CARDS = Set.of("hero-kpi", "kpi-card", "card", "panel-card", "home-card");
    /** Etiquetas con el número grande de un indicador. */
    private static final Set<String> VALUES = Set.of("hero-kpi-value", "kpi-value");
    /** Texto que muestran los indicadores mientras no han llegado los datos. */
    private static final String PLACEHOLDER = "—";
    private static final String SKELETON = "skeleton";
    private static final Duration STEP = Duration.millis(40);
    private static final Duration ENTER = Duration.millis(320);
    private static final Duration COUNT = Duration.millis(650);
    private static final Duration SKELETON_LIMIT = Duration.seconds(8);
    /** Primer número del texto: "1.200.000", "58,4", "3"... (formato colombiano). */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?");

    // Un solo brillo compartido por todos los esqueletos que estén esperando datos
    private static final List<Label> SKELETONS = new ArrayList<>();
    private static Timeline shimmer;

    private Reveal() {
    }

    /** Anima la entrada de la pantalla: esqueleto en los indicadores vacíos, escalonado y contadores. */
    public static void play(Node root) {
        if (root == null) {
            return;
        }
        root.applyCss();
        List<Node> cards = new ArrayList<>();
        List<Label> values = new ArrayList<>();
        collect(root, cards, values);
        stagger(cards);
        for (Label value : values) {
            countWhenReady(value);
        }
    }

    private static void collect(Node node, List<Node> cards, List<Label> values) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Label label && hasAny(label, VALUES)) {
            values.add(label);
        }
        if (hasAny(node, CARDS)) {
            cards.add(node);
        }
        if (node instanceof ScrollPane scroll) {
            // Pantallas que se desplazan completas: se recorre su contenido una sola vez
            if (scroll.getContent() != null) {
                collect(scroll.getContent(), cards, values);
            }
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, cards, values);
            }
        }
    }

    private static boolean hasAny(Node node, Set<String> classes) {
        for (String styleClass : node.getStyleClass()) {
            if (classes.contains(styleClass)) {
                return true;
            }
        }
        return false;
    }

    /** Entran de arriba hacia abajo y de izquierda a derecha, 40 ms entre una y otra. */
    private static void stagger(List<Node> nodes) {
        nodes.sort(Comparator.comparingDouble((Node n) -> top(n)).thenComparingDouble(Reveal::left));
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            node.setOpacity(0);
            node.setTranslateY(8);
            FadeTransition fade = new FadeTransition(ENTER, node);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(ENTER, node);
            slide.setToY(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            ParallelTransition enter = new ParallelTransition(fade, slide);
            enter.setDelay(STEP.multiply(Math.min(i, 12)));
            enter.play();
        }
    }

    private static double top(Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds == null ? 0 : Math.round(bounds.getMinY() / 20);
    }

    private static double left(Node node) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        return bounds == null ? 0 : bounds.getMinX();
    }

    /** Si el dato ya está, cuenta de una vez; si no, muestra el esqueleto hasta que llegue. */
    private static void countWhenReady(Label label) {
        if (!isPending(label.getText())) {
            count(label, label.getText());
            return;
        }
        label.getStyleClass().add(SKELETON);
        startShimmer(label);
        ChangeListener<String> whenLoaded = new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String oldText, String newText) {
                if (isPending(newText)) {
                    return;
                }
                label.textProperty().removeListener(this);
                label.getStyleClass().remove(SKELETON);
                stopShimmer(label);
                count(label, newText);
            }
        };
        label.textProperty().addListener(whenLoaded);
        // Si la consulta falla o tarda demasiado, se quita el esqueleto y queda el texto de espera.
        PauseTransition limit = new PauseTransition(SKELETON_LIMIT);
        limit.setOnFinished(e -> {
            if (label.getStyleClass().remove(SKELETON)) {
                label.textProperty().removeListener(whenLoaded);
                stopShimmer(label);
            }
        });
        limit.play();
    }

    private static boolean isPending(String text) {
        return text == null || text.isBlank() || PLACEHOLDER.equals(text.trim());
    }

    /** Cuenta desde 0 hasta el primer número del texto */
    private static void count(Label label, String finalText) {
        Matcher matcher = NUMBER.matcher(finalText);
        if (!matcher.find()) {
            return;
        }
        String token = matcher.group();
        boolean grouped = token.contains(".");
        int comma = token.indexOf(',');
        int decimals = comma < 0 ? 0 : token.length() - comma - 1;
        double target = Double.parseDouble(token.replace(".", "").replace(',', '.'));
        if (target == 0) {
            return;
        }
        String before = finalText.substring(0, matcher.start());
        String after = finalText.substring(matcher.end());
        Timeline counter = new Timeline();
        int frames = 20;
        for (int i = 1; i <= frames; i++) {
            double progress = (double) i / frames;
            double eased = 1 - Math.pow(1 - progress, 3);
            String text = i == frames ? finalText : before + format(target * eased, decimals, grouped) + after;
            counter.getKeyFrames().add(new KeyFrame(COUNT.multiply(progress), e -> label.setText(text)));
        }
        label.setText(before + format(0, decimals, grouped) + after);
        counter.play();
    }

    private static String format(double value, int decimals, boolean grouped) {
        String plain = String.format(Locale.ROOT, "%." + decimals + "f", value);
        String integer = decimals > 0 ? plain.substring(0, plain.indexOf('.')) : plain;
        String fraction = decimals > 0 ? "," + plain.substring(plain.indexOf('.') + 1) : "";
        if (grouped) {
            StringBuilder withDots = new StringBuilder(integer);
            for (int i = withDots.length() - 3; i > 0; i -= 3) {
                withDots.insert(i, '.');
            }
            integer = withDots.toString();
        }
        return integer + fraction;
    }

    /** Brillo de los esqueletos: sube y baja su opacidad; se detiene cuando ya no queda ninguno. */
    private static void startShimmer(Label label) {
        SKELETONS.add(label);
        if (shimmer != null) {
            return;
        }
        DoubleProperty level = new SimpleDoubleProperty(1);
        level.addListener((obs, oldValue, value) -> SKELETONS.forEach(node -> node.setOpacity(value.doubleValue())));
        shimmer = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(level, 1)),
                new KeyFrame(Duration.millis(600), new KeyValue(level, 0.45, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1200), new KeyValue(level, 1, Interpolator.EASE_BOTH)));
        shimmer.setCycleCount(Animation.INDEFINITE);
        shimmer.play();
    }

    private static void stopShimmer(Label label) {
        SKELETONS.remove(label);
        label.setOpacity(1);
        if (SKELETONS.isEmpty() && shimmer != null) {
            shimmer.stop();
            shimmer = null;
        }
    }
}
