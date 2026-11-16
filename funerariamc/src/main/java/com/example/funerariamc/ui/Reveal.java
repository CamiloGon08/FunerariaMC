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
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga suave de una pantalla: mientras llegan los datos, los indicadores y las zonas de registros
 * (tarjetas, filas, tablas y listas) se ven como bloques con brillo (esqueleto); al llegar, tarjetas,
 * filas e indicadores entran uno tras otro y los números cuentan desde 0. Solo usa animaciones de
 * JavaFX, no cambia los datos de la pantalla ni el código de los controladores.
 */
public final class Reveal {

    /** Tarjetas e indicadores que entran escalonados. */
    private static final Set<String> CARDS = Set.of("hero-kpi", "kpi-card", "card", "panel-card", "home-card");
    /** Etiquetas con el número grande de un indicador. */
    private static final Set<String> VALUES = Set.of("hero-kpi-value", "kpi-value");
    /** Texto que muestran los indicadores mientras no han llegado los datos. */
    private static final String PLACEHOLDER = "—";
    /** Los controladores escriben "Cargando…" mientras consultan; el esqueleto ocupa su lugar. */
    private static final String LOADING = "Cargando";
    private static final String SKELETON = "skeleton";
    /** Marca de los bloques de esqueleto que agrega esta clase, para no confundirlos con datos reales. */
    private static final String SKELETON_NODE = "reveal-skeleton";
    private static final Duration STEP = Duration.millis(40);
    private static final Duration ENTER = Duration.millis(320);
    private static final Duration COUNT = Duration.millis(650);
    private static final Duration SKELETON_LIMIT = Duration.seconds(8);
    /** Cuando ya llegó una parte de los datos, las zonas que siguen vacías esperan esto y se quitan. */
    private static final Duration GRACE = Duration.millis(1500);
    /** Más allá de este número, las filas aparecen sin animación (ya quedan fuera de la vista). */
    private static final int MAX_ANIMATED = 24;
    private static final int SKELETON_ROWS = 5;
    private static final int SKELETON_CARDS = 6;
    /** Primer número del texto: "1.200.000", "58,4", "3"... (formato colombiano). */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?");

    // Colores del esqueleto tomados de la paleta El Edén (app.css): verde musgo #465E47,
    // caqui dorado #C4AF84 y marfil #F4F1EC, con transparencia para que se mezclen con el fondo.
    // Sobre fondo claro (tarjetas, tablas): barras verde salvia y la primera en caqui.
    private static final String LIGHT_BAR = "rgba(110,140,100,0.28)";
    private static final String LIGHT_TITLE = "rgba(196,175,132,0.55)";
    private static final String LIGHT_LINE = "rgba(110,140,100,0.20)";
    private static final String LIGHT_CARD = "-fx-background-color: -color-card;";
    // Sobre la banda verde (indicadores, encabezados oscuros): barras marfil y caqui translúcidas.
    private static final String DARK_BAR = "rgba(244,241,236,0.16)";
    private static final String DARK_TITLE = "rgba(196,175,132,0.38)";
    private static final String DARK_LINE = "rgba(244,241,236,0.12)";
    private static final String DARK_CARD = "-fx-background-color: rgba(255,255,255,0.07);";
    /** Por debajo de esta luminosidad (0 negro, 1 blanco) el fondo se considera oscuro. */
    private static final double DARK_LIMIT = 0.45;

    // Un solo brillo compartido por todos los esqueletos que estén esperando datos
    private static final List<Node> SKELETONS = new ArrayList<>();
    private static Timeline shimmer;

    private Reveal() {
    }

    /** Anima la entrada de la pantalla: esqueleto en lo que aún no tiene datos, escalonado y contadores. */
    public static void play(Node root) {
        if (root == null) {
            return;
        }
        root.applyCss();
        Found found = new Found();
        collect(root, found);
        stagger(found.cards);
        Screen screen = new Screen();
        for (Label value : found.values) {
            countWhenReady(value, screen);
        }
        for (Pane zone : found.zones) {
            // Si el controlador ya muestra su aviso de "sin datos" junto a la zona, no se pone esqueleto
            if (!EmptyNotice.showing(zone)) {
                PaneZone waiter = new PaneZone(zone, screen);
                screen.waitFor(waiter);
                EmptyNotice.endWhenShown(zone, waiter, screen);
            }
        }
        for (Node list : found.lists) {
            if (!EmptyNotice.showing(list)) {
                ListZone waiter = new ListZone(list, screen);
                screen.waitFor(waiter);
                EmptyNotice.endWhenShown(list, waiter, screen);
            }
        }
        for (Label overlay : found.overlays) {
            screen.waitFor(new HiddenLoadingText(overlay, screen));
        }
        screen.start();
    }

    // ---------- recorrido de la pantalla ----------

    /** Lo que se encontró al recorrer la pantalla. */
    private static final class Found {
        private final List<Node> cards = new ArrayList<>();
        private final List<Label> values = new ArrayList<>();
        private final List<Pane> zones = new ArrayList<>();
        private final List<Node> lists = new ArrayList<>();
        private final List<Label> overlays = new ArrayList<>();
    }

    private static void collect(Node node, Found found) {
        if (!node.isVisible()) {
            return;
        }
        if (node instanceof Label label) {
            if (hasAny(label, VALUES)) {
                found.values.add(label);
            } else if (isLoading(label.getText()) && label.getParent() instanceof StackPane) {
                // Aviso "Cargando…" encima de una lista: el esqueleto lo reemplaza mientras tanto
                found.overlays.add(label);
            }
        }
        if (hasAny(node, CARDS)) {
            found.cards.add(node);
        }
        if (node instanceof ComboBoxBase<?>) {
            // La lista interna de un ComboBox no es una zona de registros
            return;
        }
        if (node instanceof TableView<?> table) {
            if (!table.placeholderProperty().isBound() && (table.getItems() == null || table.getItems().isEmpty())) {
                found.lists.add(table);
            }
            return;
        }
        if (node instanceof ListView<?> list) {
            if (!list.placeholderProperty().isBound() && (list.getItems() == null || list.getItems().isEmpty())) {
                found.lists.add(list);
            }
            return;
        }
        if (isWaitingZone(node)) {
            found.zones.add((Pane) node);
            return;
        }
        if (node instanceof ScrollPane scroll) {
            // Pantallas que se desplazan completas: se recorre su contenido una sola vez
            if (scroll.getContent() != null) {
                collect(scroll.getContent(), found);
            }
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, found);
            }
        }
    }

    /**
     * Zona de registros que todavía espera datos: un contenedor con fx:id (lista de filas, grilla o
     * flujo de tarjetas) que está vacío o solo tiene el aviso "Cargando…".
     */
    private static boolean isWaitingZone(Node node) {
        boolean container = node instanceof VBox || node instanceof FlowPane
                || node instanceof GridPane || node instanceof TilePane;
        return container && node.getId() != null && onlyLoading((Pane) node);
    }

    private static boolean onlyLoading(Pane pane) {
        for (Node child : pane.getChildren()) {
            boolean waiting = isSkeleton(child)
                    || (child instanceof Label label && (isLoading(label.getText()) || label.getText().isBlank()));
            if (!waiting) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAny(Node node, Set<String> classes) {
        for (String styleClass : node.getStyleClass()) {
            if (classes.contains(styleClass)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoading(String text) {
        return text != null && text.trim().startsWith(LOADING);
    }

    private static boolean isSkeleton(Node node) {
        return node.getProperties().containsKey(SKELETON_NODE);
    }

    // ---------- espera de la pantalla ----------

    /** Una parte de la pantalla que muestra esqueleto hasta que lleguen sus datos. */
    private interface Waiter {
        /** Quita el esqueleto; con animate=true además anima la entrada de los datos. */
        void finish(boolean animate);
    }

    /**
     * Coordina las esperas de una pantalla: cuando llega la primera parte de los datos, lo que siga
     * vacío espera un momento más y se quita (consulta sin resultados). Nada queda con esqueleto
     * más de 8 segundos, aunque la consulta falle.
     */
    private static final class Screen {
        private final List<Waiter> waiting = new ArrayList<>();
        private boolean graceStarted;

        void waitFor(Waiter waiter) {
            waiting.add(waiter);
        }

        void done(Waiter waiter) {
            waiting.remove(waiter);
        }

        void loaded() {
            if (graceStarted || waiting.isEmpty()) {
                return;
            }
            graceStarted = true;
            PauseTransition grace = new PauseTransition(GRACE);
            grace.setOnFinished(e -> endAll());
            grace.play();
        }

        void start() {
            if (waiting.isEmpty()) {
                return;
            }
            PauseTransition limit = new PauseTransition(SKELETON_LIMIT);
            limit.setOnFinished(e -> endAll());
            limit.play();
        }

        private void endAll() {
            for (Waiter waiter : new ArrayList<>(waiting)) {
                waiter.finish(false);
            }
            waiting.clear();
        }
    }

    /** Contenedor de tarjetas o filas: se llena con bloques de esqueleto hasta que el controlador agregue datos. */
    private static final class PaneZone implements Waiter {
        private final Pane pane;
        private final Screen screen;
        private final List<Node> skeletons = new ArrayList<>();
        private final List<Label> hiddenTexts = new ArrayList<>();
        private final ListChangeListener<Node> onChange = change -> scheduleCheck();
        private boolean busy;
        private boolean checkScheduled;
        private boolean finished;

        PaneZone(Pane pane, Screen screen) {
            this.pane = pane;
            this.screen = screen;
            hideLoadingTexts();
            addSkeletons();
            pane.getChildren().addListener(onChange);
        }

        private void scheduleCheck() {
            // Se revisa después de que el controlador termina sus cambios (clear + add, setAll...)
            if (busy || checkScheduled || finished) {
                return;
            }
            checkScheduled = true;
            Platform.runLater(this::check);
        }

        private void check() {
            checkScheduled = false;
            if (finished) {
                return;
            }
            if (hasRealContent()) {
                finish(true);
                screen.loaded();
                return;
            }
            // El controlador volvió a poner "Cargando…" o vació la zona: se mantiene el esqueleto
            hideLoadingTexts();
            if (pane.getChildren().stream().noneMatch(Reveal::isSkeleton)) {
                addSkeletons();
            }
        }

        private boolean hasRealContent() {
            return !onlyLoading(pane);
        }

        private void hideLoadingTexts() {
            for (Node child : pane.getChildren()) {
                if (child instanceof Label label && !isSkeleton(label) && label.isVisible()) {
                    label.setVisible(false);
                    label.setManaged(false);
                    hiddenTexts.add(label);
                }
            }
        }

        private void addSkeletons() {
            busy = true;
            boolean dark = onDark(pane);
            skeletons.clear();
            if (pane instanceof GridPane grid) {
                int columns = Math.max(1, grid.getColumnConstraints().size());
                if (columns == 1) {
                    columns = 3;
                }
                for (int i = 0; i < SKELETON_CARDS; i++) {
                    Node card = skeletonCard(dark);
                    GridPane.setConstraints(card, i % columns, i / columns);
                    skeletons.add(card);
                }
            } else if (pane instanceof FlowPane || pane instanceof TilePane) {
                for (int i = 0; i < SKELETON_CARDS; i++) {
                    skeletons.add(skeletonCard(dark));
                }
            } else {
                for (int i = 0; i < SKELETON_ROWS; i++) {
                    skeletons.add(skeletonRow(i, dark));
                }
            }
            pane.getChildren().addAll(skeletons);
            skeletons.forEach(Reveal::startShimmer);
            busy = false;
        }

        @Override
        public void finish(boolean animate) {
            if (finished) {
                return;
            }
            finished = true;
            screen.done(this);
            pane.getChildren().removeListener(onChange);
            busy = true;
            pane.getChildren().removeIf(Reveal::isSkeleton);
            busy = false;
            skeletons.forEach(Reveal::stopShimmer);
            skeletons.clear();
            // Si la consulta no trajo nada, vuelve a verse el texto que dejó el controlador
            for (Label label : hiddenTexts) {
                if (label.getParent() == pane) {
                    label.setManaged(true);
                    label.setVisible(true);
                }
            }
            hiddenTexts.clear();
            if (animate) {
                pane.applyCss();
                pane.layout();
                stagger(new ArrayList<>(pane.getChildren().filtered(Node::isVisible)));
            }
        }
    }

    /** Tabla o lista: mientras no tiene filas, su espacio vacío muestra filas de esqueleto. */
    private static final class ListZone implements Waiter {
        private final Node control;
        private final Screen screen;
        private final ObjectProperty<Node> placeholder;
        private final ObservableValue<? extends ObservableList<?>> items;
        private final Node original;
        private final Node skeleton;
        private final InvalidationListener onNewList = obs -> listReplaced();
        private final ListChangeListener<Object> onItems = change -> schedule(false);
        private ObservableList<?> watched;
        private boolean scheduled;
        private boolean finished;

        ListZone(Node control, Screen screen) {
            this.control = control;
            this.screen = screen;
            if (control instanceof TableView<?> table) {
                placeholder = table.placeholderProperty();
                items = table.itemsProperty();
            } else {
                ListView<?> list = (ListView<?>) control;
                placeholder = list.placeholderProperty();
                items = list.itemsProperty();
            }
            original = placeholder.get();
            boolean dark = onDark(control);
            VBox rows = new VBox();
            rows.setFillWidth(true);
            rows.setAlignment(Pos.TOP_LEFT);
            for (int i = 0; i < SKELETON_ROWS; i++) {
                rows.getChildren().add(skeletonRow(i, dark));
            }
            rows.getProperties().put(SKELETON_NODE, Boolean.TRUE);
            skeleton = rows;
            placeholder.set(skeleton);
            startShimmer(skeleton);
            items.addListener(onNewList);
            watch(items.getValue());
        }

        @SuppressWarnings("unchecked")
        private void watch(ObservableList<?> list) {
            if (watched != null) {
                ((ObservableList<Object>) watched).removeListener(onItems);
            }
            watched = list;
            if (watched != null) {
                ((ObservableList<Object>) watched).addListener(onItems);
            }
        }

        private void listReplaced() {
            watch(items.getValue());
            // setItems(...) significa que la consulta terminó, aunque no haya traído filas
            schedule(true);
        }

        private void schedule(boolean loadEnded) {
            if (finished || scheduled) {
                return;
            }
            scheduled = true;
            Platform.runLater(() -> {
                scheduled = false;
                ObservableList<?> current = items.getValue();
                boolean hasRows = current != null && !current.isEmpty();
                if (hasRows || loadEnded) {
                    finish(hasRows);
                    screen.loaded();
                }
            });
        }

        @SuppressWarnings("unchecked")
        @Override
        public void finish(boolean animate) {
            if (finished) {
                return;
            }
            finished = true;
            screen.done(this);
            items.removeListener(onNewList);
            if (watched != null) {
                ((ObservableList<Object>) watched).removeListener(onItems);
            }
            stopShimmer(skeleton);
            // Solo se devuelve el aviso original si el controlador no puso otro mientras tanto. Si no
            // tenía aviso, se pone uno vacío: con null la lista seguía mostrando el esqueleto debajo
            // del mensaje "sin datos" del controlador.
            if (placeholder.get() == skeleton) {
                placeholder.set(original != null ? original : new Region());
            }
            if (animate) {
                Platform.runLater(this::animateRows);
            }
        }

        private void animateRows() {
            control.applyCss();
            if (control instanceof Parent parent) {
                parent.layout();
            }
            String cellClass = control instanceof TableView<?> ? ".table-row-cell" : ".list-cell";
            List<Node> rows = new ArrayList<>();
            for (Node cell : control.lookupAll(cellClass)) {
                if (cell instanceof IndexedCell<?> indexed && !indexed.isEmpty() && indexed.isVisible()) {
                    rows.add(cell);
                }
            }
            stagger(rows);
        }
    }

    /** Aviso "Cargando…" encima de una lista: se oculta mientras se ve el esqueleto. */
    private static final class HiddenLoadingText implements Waiter {
        private final Label label;
        private final Screen screen;
        private final ChangeListener<String> onText = (obs, oldText, newText) -> textChanged(newText);
        private boolean finished;

        HiddenLoadingText(Label label, Screen screen) {
            this.label = label;
            this.screen = screen;
            label.setOpacity(0);
            label.textProperty().addListener(onText);
        }

        private void textChanged(String text) {
            if (!isLoading(text)) {
                // Ya llegó la respuesta ("No hay ventas", vacío...)
                finish(false);
            }
        }

        @Override
        public void finish(boolean animate) {
            if (finished) {
                return;
            }
            finished = true;
            screen.done(this);
            label.textProperty().removeListener(onText);
            label.setOpacity(1);
        }
    }

    /**
     * Aviso de "sin datos" que el controlador pone encima de una lista o zona
     */
    private static final class EmptyNotice {
        /** Niveles que se suben para encontrar el ScrollPane que contiene la zona. */
        private static final int SCROLL_LEVELS = 4;

        private EmptyNotice() {
        }

        static boolean showing(Node zone) {
            return notices(zone).stream().anyMatch(EmptyNotice::isShown);
        }

        static void endWhenShown(Node zone, Waiter waiter, Screen screen) {
            boolean[] ended = {false};
            for (Label notice : notices(zone)) {
                InvalidationListener check = obs -> {
                    if (!ended[0] && isShown(notice)) {
                        ended[0] = true;
                        waiter.finish(false);
                        screen.loaded();
                    }
                };
                notice.visibleProperty().addListener(check);
                notice.textProperty().addListener(check);
            }
        }

        /** Etiquetas hermanas de la zona (o del ScrollPane que la contiene) dentro de un StackPane. */
        private static List<Label> notices(Node zone) {
            Node host = zone;
            Node parent = zone.getParent();
            for (int i = 0; i < SCROLL_LEVELS && parent != null; i++) {
                if (parent instanceof ScrollPane scroll) {
                    if (scroll.getContent() == zone) {
                        host = scroll;
                    }
                    break;
                }
                parent = parent.getParent();
            }
            List<Label> labels = new ArrayList<>();
            if (host.getParent() instanceof StackPane stack) {
                for (Node child : stack.getChildren()) {
                    if (child != host && child instanceof Label label && !isSkeleton(label)) {
                        labels.add(label);
                    }
                }
            }
            return labels;
        }

        private static boolean isShown(Label label) {
            String text = label.getText();
            return label.isVisible() && text != null && !text.isBlank() && !isLoading(text);
        }
    }

    // ---------- bloques de esqueleto ----------

    /** Fila con cuatro barras de distinto largo, como una fila de tabla, en los colores del fondo. */
    private static Node skeletonRow(int index, boolean dark) {
        HBox row = new HBox(18);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 16, 14, 16));
        row.setMinHeight(46);
        row.setStyle("-fx-border-color: transparent transparent " + (dark ? DARK_LINE : LIGHT_LINE)
                + " transparent; -fx-border-width: 0 0 1 0;");
        double[][] widths = {{190, 120, 90, 70}, {150, 140, 80, 60}, {210, 100, 100, 70}, {170, 130, 70, 80}};
        double[] pattern = widths[index % widths.length];
        for (int i = 0; i < pattern.length; i++) {
            Region bar = bar(pattern[i], i == 0 ? 13 : 11, dark, i == 0);
            row.getChildren().add(bar);
            if (i == 1) {
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                spacer.setMaxWidth(120);
                row.getChildren().add(spacer);
            }
        }
        row.getProperties().put(SKELETON_NODE, Boolean.TRUE);
        return row;
    }

    /** Tarjeta con título, subtítulo y dos líneas, del tamaño de una tarjeta real. */
    private static Node skeletonCard(boolean dark) {
        VBox card = new VBox(12);
        card.setPadding(new Insets(20));
        card.setPrefSize(260, 150);
        card.setMinHeight(150);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle((dark ? DARK_CARD : LIGHT_CARD) + " -fx-background-radius: 16; -fx-border-radius: 16;"
                + " -fx-border-width: 1; -fx-border-color: " + (dark ? DARK_LINE : LIGHT_LINE) + ";");
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        card.getChildren().addAll(bar(150, 16, dark, true), bar(100, 11, dark, false), spacer,
                bar(190, 11, dark, false), bar(120, 11, dark, false));
        card.getProperties().put(SKELETON_NODE, Boolean.TRUE);
        return card;
    }

    /** Barra del esqueleto; la primera de cada fila o tarjeta (el título) va en caqui. */
    private static Region bar(double width, double height, boolean dark, boolean title) {
        Region bar = new Region();
        bar.setPrefSize(width, height);
        bar.setMinSize(Math.min(width, 40), height);
        bar.setMaxSize(width, height);
        String color = title ? (dark ? DARK_TITLE : LIGHT_TITLE) : (dark ? DARK_BAR : LIGHT_BAR);
        bar.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 6;");
        return bar;
    }

    // ---------- entrada escalonada ----------

    /** Entran de arriba hacia abajo y de izquierda a derecha. */
    private static void stagger(List<Node> nodes) {
        nodes.sort(Comparator.comparingDouble((Node n) -> top(n)).thenComparingDouble(Reveal::left));
        for (int i = 0; i < Math.min(nodes.size(), MAX_ANIMATED); i++) {
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

    // ---------- indicadores ----------

    /** Si el dato ya está, cuenta de una vez; si no, muestra el esqueleto hasta que llegue. */
    private static void countWhenReady(Label label, Screen screen) {
        if (!isPending(label.getText())) {
            count(label, label.getText());
            return;
        }
        String ownStyle = label.getStyle();
        label.getStyleClass().add(SKELETON);
        // El color del bloque se toma del fondo: caqui sobre la banda verde, musgo sobre tarjeta clara
        label.setStyle((ownStyle == null || ownStyle.isBlank() ? "" : ownStyle + "; ") + "-fx-text-fill: transparent; -fx-background-color: "
                + (onDark(label) ? DARK_TITLE : LIGHT_BAR) + ";");
        startShimmer(label);
        screen.waitFor(new Waiter() {
            private final ChangeListener<String> whenLoaded = (obs, oldText, newText) -> {
                if (!isPending(newText)) {
                    finish(true);
                    screen.loaded();
                }
            };
            private boolean finished;

            {
                label.textProperty().addListener(whenLoaded);
            }

            @Override
            public void finish(boolean animate) {
                if (finished) {
                    return;
                }
                finished = true;
                screen.done(this);
                label.textProperty().removeListener(whenLoaded);
                label.getStyleClass().remove(SKELETON);
                label.setStyle(ownStyle);
                stopShimmer(label);
                // Si la consulta falla o tarda demasiado, se quita el esqueleto y queda el texto de espera.
                if (animate) {
                    count(label, label.getText());
                }
            }
        });
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

    // ---------- color del fondo ----------

    /**
     * Dice si el nodo está sobre un fondo oscuro o claro.
     * Sube por los contenedores hasta el primero que tenga un fondo casi opaco y mide su luminosidad.
     */
    static boolean onDark(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current instanceof Region region && region.getBackground() != null) {
                List<BackgroundFill> fills = region.getBackground().getFills();
                for (int i = fills.size() - 1; i >= 0; i--) {
                    Color color = colorOf(fills.get(i).getFill());
                    if (color != null && color.getOpacity() >= 0.6) {
                        return luminance(color) < DARK_LIMIT;
                    }
                }
            }
        }
        return false;
    }

    /** Color de un relleno; en un degradado, el promedio de sus paradas. */
    private static Color colorOf(Paint paint) {
        if (paint instanceof Color color) {
            return color;
        }
        List<Stop> stops = paint instanceof LinearGradient linear ? linear.getStops()
                : paint instanceof RadialGradient radial ? radial.getStops() : List.of();
        if (stops.isEmpty()) {
            return null;
        }
        double r = 0, g = 0, b = 0, a = 0;
        for (Stop stop : stops) {
            r += stop.getColor().getRed();
            g += stop.getColor().getGreen();
            b += stop.getColor().getBlue();
            a += stop.getColor().getOpacity();
        }
        int n = stops.size();
        return new Color(r / n, g / n, b / n, a / n);
    }

    private static double luminance(Color color) {
        return 0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue();
    }

    // ---------- brillo ----------

    /** Brillo de los esqueletos: sube y baja su opacidad. */
    private static void startShimmer(Node node) {
        SKELETONS.add(node);
        if (shimmer != null) {
            return;
        }
        DoubleProperty level = new SimpleDoubleProperty(1);
        level.addListener((obs, oldValue, value) -> SKELETONS.forEach(n -> n.setOpacity(value.doubleValue())));
        shimmer = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(level, 1)),
                new KeyFrame(Duration.millis(600), new KeyValue(level, 0.45, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1200), new KeyValue(level, 1, Interpolator.EASE_BOTH)));
        shimmer.setCycleCount(Animation.INDEFINITE);
        shimmer.play();
    }

    private static void stopShimmer(Node node) {
        SKELETONS.remove(node);
        node.setOpacity(1);
        if (SKELETONS.isEmpty() && shimmer != null) {
            shimmer.stop();
            shimmer = null;
        }
    }
}
