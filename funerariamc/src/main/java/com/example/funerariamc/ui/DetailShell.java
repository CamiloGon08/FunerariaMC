package com.example.funerariamc.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * Formato común de las pantallas de detalle (el panel que se abre al tocar un registro).
 * Arriba va la banda verde con el lirio: el módulo, el nombre del registro, sus datos principales,
 * sus estados y, según el módulo, una línea de pasos o una barra de avance. Al lado de la X están
 * las flechas para pasar al registro anterior o al siguiente sin cerrar el detalle.
 * Debajo van las secciones con su título y filete, cada una con sus datos en una tarjeta, y al final
 * los botones de acción. Hay dos formatos:
 * <ul>
 *   <li>Panel: flota a la derecha, de arriba abajo, sin tapar del todo la lista.</li>
 *   <li>Ventana: centrada y ancha, con el cuerpo en dos columnas; para expedientes con mucha información.</li>
 * </ul>
 * Los colores salen de las variables de la marca, así que cambian con la identidad de la funeraria.
 * Se usa sobre el panel que ya tiene cada módulo en su FXML: un VBox con un ScrollPane (el cuerpo)
 * y un HBox (los botones).
 */
public final class DetailShell {
    public enum Kind { PANEL, MODAL }

    /** Color de un estado: neutro, bien, atención, problema o dorado (dato destacado). */
    public enum Tone { NEUTRAL, OK, WARN, ALERT, GOLD }

    /** Estado de un paso en la línea de pasos de la banda. */
    public enum StepState { DONE, CURRENT, PENDING, BLOCKED }

    /** Estado de un documento o requisito: listo, no aplica, por hacer o faltante que bloquea. */
    public enum Mark { DONE, NOT_APPLICABLE, PENDING, MISSING }

    /** Casilla de una tira de meses o días: a tiempo, con retraso, sin pagar o sin dato. */
    public enum CellState { OK, LATE, MISSING, NONE }

    /** Casilla de la tira: rótulo debajo, texto al pasar el mouse y estado. */
    public record Cell(String label, String tooltip, CellState state) {
    }

    /** Un paso: nombre, detalle corto (fecha, responsable) y estado. */
    public record Step(String name, String detail, StepState state) {
    }

    /** Un hecho del historial: qué pasó, cuándo o quién, su color y, si hace falta, algo a la derecha (saldo, valor). */
    public record Event(String title, String detail, Tone tone, Node aside) {
        public Event(String title, String detail, Tone tone) {
            this(title, detail, tone, null);
        }
    }

    static final String SHEET = "/css/details.css";
    private static final double PANEL_WIDTH = 520;
    private static final double MODAL_WIDTH = 1000;
    private static final double MODAL_BUTTON_WIDTH = 220;
    private static final String CLOSE_ICON = "M1 1 L11 11 M11 1 L1 11";
    private static final String PREVIOUS_ICON = "M7 1 L2 6 L7 11";
    private static final String NEXT_ICON = "M2 1 L7 6 L2 11";
    private static final String INFO_ICON = "M7 0.8a6.2 6.2 0 1 1 0 12.4a6.2 6.2 0 0 1 0-12.4z M7 6v4 M7 3.9v0.2";

    private final VBox drawer;
    private final Region veil;
    private final Kind kind;
    private final VBox hero = new VBox();
    private final ScrollPane scroll;
    private final Pane body;
    private final HBox actions;
    private final Label hint = new Label();
    private Object shownKey;
    private int generation;

    private DetailShell(VBox drawer, Region veil, Kind kind, ScrollPane scroll, Pane body, HBox actions) {
        this.drawer = drawer;
        this.veil = veil;
        this.kind = kind;
        this.scroll = scroll;
        this.body = body;
        this.actions = actions;
    }

    /**
     * Prepara el panel del módulo con el formato de detalle. {@code drawer} es el VBox del FXML que tiene
     * el ScrollPane del cuerpo y el HBox de los botones; {@code veil} es el velo que oscurece la lista.
     */
    public static DetailShell install(VBox drawer, Region veil, Kind kind) {
        ScrollPane scroll = null;
        HBox actions = null;
        for (Node child : drawer.getChildren()) {
            if (child instanceof ScrollPane pane && scroll == null) {
                scroll = pane;
            } else if (child instanceof HBox box) {
                actions = box;
            }
        }
        if (scroll == null || !(scroll.getContent() instanceof Pane body) || actions == null) {
            throw new IllegalArgumentException("El panel de detalle necesita un ScrollPane con el cuerpo y un HBox con los botones");
        }
        DetailShell shell = new DetailShell(drawer, veil, kind, scroll, body, actions);
        drawer.getStylesheets().add(sheet());
        drawer.getStyleClass().addAll("detail-shell", kind == Kind.PANEL ? "detail-panel" : "detail-modal");
        body.getStyleClass().add("detail-body");
        scroll.getStyleClass().add("detail-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        actions.getStyleClass().add("detail-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        shell.hero.getStyleClass().addAll("detail-hero", "hero-band");
        drawer.getChildren().add(0, shell.hero);
        shell.hint.getStyleClass().add("detail-action-hint");
        shell.hint.setWrapText(true);
        shell.hint.managedProperty().bind(shell.hint.textProperty().isNotEmpty());
        shell.hint.visibleProperty().bind(shell.hint.managedProperty());
        drawer.getChildren().add(shell.hint);

        // Esquinas redondeadas también para la banda verde
        Rectangle clip = new Rectangle();
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        clip.widthProperty().bind(drawer.widthProperty());
        clip.heightProperty().bind(drawer.heightProperty());
        drawer.setClip(clip);

        if (kind == Kind.PANEL) {
            drawer.setMinWidth(Region.USE_PREF_SIZE);
            drawer.setPrefWidth(PANEL_WIDTH);
            drawer.setMaxWidth(PANEL_WIDTH);
            StackPane.setAlignment(drawer, Pos.CENTER_RIGHT);
            StackPane.setMargin(drawer, new Insets(12));
            // El cuerpo llena al menos el alto visible, para que "Qué sigue" quede abajo junto a los botones.
            ScrollPane pane = scroll;
            body.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> pane.getViewportBounds().getHeight(), pane.viewportBoundsProperty()));
        } else {
            drawer.setPrefWidth(MODAL_WIDTH);
            drawer.setMaxWidth(MODAL_WIDTH);
            drawer.setMaxHeight(Region.USE_PREF_SIZE);
            // La ventana mide lo que mide su contenido (ya acomodado al ancho real); si no cabe, se desplaza.
            scroll.prefViewportHeightProperty().bind(body.heightProperty());
            StackPane.setAlignment(drawer, Pos.CENTER);
            StackPane.setMargin(drawer, new Insets(28, 40, 28, 40));
            if (veil != null) {
                veil.getStyleClass().add("detail-veil-modal");
            }
        }
        drawer.setTranslateX(0);
        drawer.setVisible(false);
        if (veil != null) {
            veil.setVisible(false);
        }
        return shell;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isOpen() {
        return drawer.isVisible() && shownKey != null;
    }

    /** Clave del registro que se está mostrando (null si el detalle está cerrado). */
    public Object shownKey() {
        return shownKey;
    }

    /**
     * Pinta el detalle de un registro: banda, secciones y botones. {@code hint} va debajo de los botones
     * (por ejemplo, por qué una acción está deshabilitada); null o vacío si no hay.
     * Si es otro registro, el cuerpo vuelve arriba.
     */
    public void show(Object key, Hero heroContent, List<? extends Node> sections, List<Button> buttons, String hintText) {
        boolean other = shownKey == null || !shownKey.equals(key);
        shownKey = key;
        hero.getChildren().setAll(heroContent.build());
        body.getChildren().setAll(sections);
        if (kind == Kind.PANEL && !sections.isEmpty()
                && sections.get(sections.size() - 1).getStyleClass().contains("detail-next")) {
            // En el panel, el aviso "Qué sigue" final queda abajo, junto a los botones.
            Region push = new Region();
            VBox.setVgrow(push, Priority.ALWAYS);
            body.getChildren().add(body.getChildren().size() - 1, push);
        }
        // Todos los botones del pie con el mismo ancho: en el panel ocupan todo el pie;
        // en la ventana centrada van a la derecha.
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_COMPUTED_SIZE);
            button.setPrefWidth(kind == Kind.MODAL ? MODAL_BUTTON_WIDTH : 0);
            button.setMaxWidth(kind == Kind.MODAL ? MODAL_BUTTON_WIDTH : Double.MAX_VALUE);
            HBox.setHgrow(button, kind == Kind.MODAL ? Priority.NEVER : Priority.ALWAYS);
        }
        actions.getChildren().setAll(buttons);
        if (kind == Kind.MODAL) {
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            actions.getChildren().add(0, push);
        }
        actions.setVisible(!buttons.isEmpty());
        actions.setManaged(!buttons.isEmpty());
        hint.setText(hintText == null ? "" : hintText);
        if (other) {
            scroll.setVvalue(0);
        }
        open();
    }

    /**
     * Pinta el formato de detalle dentro de una tarjeta fija de la pantalla (no flotante), por ejemplo
     * el traslado elegido en el monitor de Transporte: banda, secciones y botones del mismo ancho.
     */
    public static void fill(VBox host, Hero heroContent, List<? extends Node> sections, List<Button> buttons) {
        if (!host.getStyleClass().contains("detail-shell")) {
            host.getStylesheets().add(sheet());
            host.getStyleClass().setAll("detail-shell", "detail-panel", "detail-inline");
            Rectangle clip = new Rectangle();
            clip.setArcWidth(40);
            clip.setArcHeight(40);
            clip.widthProperty().bind(host.widthProperty());
            clip.heightProperty().bind(host.heightProperty());
            host.setClip(clip);
        }
        VBox band = new VBox();
        band.getStyleClass().addAll("detail-hero", "hero-band");
        band.getChildren().setAll(heroContent.build());
        VBox content = new VBox();
        content.getStyleClass().add("detail-body");
        content.getChildren().setAll(sections);
        VBox.setVgrow(content, Priority.ALWAYS);
        host.getChildren().setAll(band, content);
        if (!buttons.isEmpty()) {
            HBox bar = new HBox();
            bar.getStyleClass().add("detail-actions");
            for (Button button : buttons) {
                button.setMinWidth(Region.USE_COMPUTED_SIZE);
                button.setPrefWidth(0);
                button.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(button, Priority.ALWAYS);
            }
            bar.getChildren().setAll(buttons);
            host.getChildren().add(bar);
        }
    }

    /** Deshabilita los botones del pie mientras se guarda algo (y los vuelve a habilitar). */
    public void setBusy(boolean busy) {
        actions.setDisable(busy);
    }

    /** Ícono dibujado (cruz, flor…) dentro de un círculo blanco, para filas de persona o registro. */
    public static StackPane icon(String svgPath) {
        SVGPath path = new SVGPath();
        path.setContent(svgPath);
        path.getStyleClass().add("detail-icon-path");
        StackPane circle = new StackPane(path);
        circle.getStyleClass().add("detail-icon-circle");
        circle.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return circle;
    }

    /** Abre el detalle con su animación (si ya está abierto no hace nada). */
    private void open() {
        if (drawer.isVisible() && drawer.getOpacity() > 0.99 && drawer.getTranslateX() == 0) {
            return;
        }
        generation++;
        drawer.setVisible(true);
        Duration time = Duration.millis(220);
        ParallelTransition in = new ParallelTransition();
        if (kind == Kind.PANEL) {
            drawer.setTranslateX(PANEL_WIDTH + 24);
            TranslateTransition slide = new TranslateTransition(time, drawer);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            in.getChildren().add(slide);
        } else {
            drawer.setOpacity(0);
            drawer.setScaleX(0.97);
            drawer.setScaleY(0.97);
            FadeTransition fade = new FadeTransition(time, drawer);
            fade.setToValue(1);
            ScaleTransition grow = new ScaleTransition(time, drawer);
            grow.setToX(1);
            grow.setToY(1);
            in.getChildren().addAll(fade, grow);
            blurBehind(true);
        }
        if (veil != null) {
            veil.setVisible(true);
            FadeTransition fade = new FadeTransition(time, veil);
            fade.setFromValue(0);
            fade.setToValue(1);
            in.getChildren().add(fade);
        }
        in.play();
    }

    /** Cierra el detalle con su animación; {@code afterClose} corre al terminar (puede ser null). */
    public void close(Runnable afterClose) {
        shownKey = null;
        if (!drawer.isVisible()) {
            if (afterClose != null) {
                afterClose.run();
            }
            return;
        }
        int closing = ++generation;
        Duration time = Duration.millis(180);
        ParallelTransition out = new ParallelTransition();
        if (kind == Kind.PANEL) {
            TranslateTransition slide = new TranslateTransition(time, drawer);
            slide.setToX(PANEL_WIDTH + 24);
            slide.setInterpolator(Interpolator.EASE_IN);
            out.getChildren().add(slide);
        } else {
            FadeTransition fade = new FadeTransition(time, drawer);
            fade.setToValue(0);
            out.getChildren().add(fade);
        }
        if (veil != null) {
            FadeTransition fade = new FadeTransition(time, veil);
            fade.setToValue(0);
            out.getChildren().add(fade);
        }
        out.setOnFinished(e -> {
            if (closing == generation) {
                drawer.setVisible(false);
                if (veil != null) {
                    veil.setVisible(false);
                }
                blurBehind(false);
            }
            if (afterClose != null) {
                afterClose.run();
            }
        });
        out.play();
    }

    /** En la ventana centrada, la pantalla del módulo de atrás se ve desenfocada. */
    private void blurBehind(boolean on) {
        if (!(drawer.getParent() instanceof StackPane stack) || stack.getChildren().isEmpty()) {
            return;
        }
        Node content = stack.getChildren().get(0);
        if (content != drawer && content != veil) {
            content.setEffect(on ? new GaussianBlur(6) : null);
        }
    }

    // ---------- banda superior ----------

    /** Contenido de la banda verde: se arma con sus métodos y se pasa a {@link #show}. */
    public static final class Hero {
        private final String eyebrow;
        private final String title;
        private String subtitle;
        private Node leading;
        private final List<Node> chips = new ArrayList<>();
        private final List<Node> extras = new ArrayList<>();
        private Runnable previous;
        private Runnable next;
        private Runnable close;

        public Hero(String eyebrow, String title) {
            this.eyebrow = eyebrow;
            this.title = title;
        }

        /** Línea de datos bajo el nombre (documento, fechas, lugar). */
        public Hero subtitle(String text) {
            this.subtitle = text;
            return this;
        }

        /** Iniciales, ícono o código a la izquierda del nombre. */
        public Hero leading(Node node) {
            this.leading = node;
            return this;
        }

        /** Estados del registro, en cápsulas (ver {@link DetailShell#chip}). */
        public Hero chips(Node... nodes) {
            for (Node node : nodes) {
                if (node != null) {
                    chips.add(node);
                }
            }
            return this;
        }

        /** Algo propio del módulo bajo los estados: línea de pasos, barra de avance, mini muro… */
        public Hero extra(Node node) {
            if (node != null) {
                extras.add(node);
            }
            return this;
        }

        /** Flechas para el registro anterior y el siguiente (null deja la flecha deshabilitada). */
        public Hero navigation(Runnable previousRecord, Runnable nextRecord) {
            this.previous = previousRecord;
            this.next = nextRecord;
            return this;
        }

        public Hero onClose(Runnable action) {
            this.close = action;
            return this;
        }

        private List<Node> build() {
            Label caption = label(spaced(eyebrow), "detail-eyebrow");
            Region push = new Region();
            HBox.setHgrow(push, Priority.ALWAYS);
            HBox top = new HBox(8, caption, push);
            if (previous != null || next != null || close != null) {
                top.getChildren().addAll(round(PREVIOUS_ICON, "Anterior", previous),
                        round(NEXT_ICON, "Siguiente", next),
                        round(CLOSE_ICON, "Cerrar", close));
            }
            top.setAlignment(Pos.CENTER_LEFT);
            Label name = label(title, "detail-title");
            name.setWrapText(true);
            VBox titles = new VBox(2, name);
            if (subtitle != null && !subtitle.isBlank()) {
                Label sub = label(subtitle, "detail-subtitle");
                sub.setWrapText(true);
                titles.getChildren().add(sub);
            }
            HBox.setHgrow(titles, Priority.ALWAYS);
            HBox head = new HBox(12, titles);
            head.setAlignment(Pos.CENTER_LEFT);
            if (leading != null) {
                head.getChildren().add(0, leading);
            }
            List<Node> rows = new ArrayList<>(List.of(top, head));
            if (!chips.isEmpty()) {
                FlowPane pills = new FlowPane(6, 6);
                pills.getChildren().setAll(chips);
                pills.getStyleClass().add("detail-chips");
                rows.add(pills);
            }
            rows.addAll(extras);
            return rows;
        }
    }

    /** Botón redondo de la banda (anterior, siguiente o cerrar). */
    private static Button round(String path, String tooltip, Runnable action) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("detail-round-icon");
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("detail-round");
        button.setAccessibleText(tooltip);
        button.setFocusTraversable(false);
        button.setDisable(action == null);
        if (action != null) {
            button.setOnAction(e -> action.run());
        }
        return button;
    }

    /**
     * Acción para la flecha "anterior" ({@code step} = -1) o "siguiente" (+1): abre el registro vecino
     * de la lista que se ve en pantalla. Devuelve null (flecha deshabilitada) si no hay vecino.
     */
    public static <T> Runnable neighbor(List<T> shown, Predicate<T> isCurrent, int step, Consumer<T> open) {
        if (shown == null) {
            return null;
        }
        List<T> items = List.copyOf(shown);
        for (int i = 0; i < items.size(); i++) {
            if (isCurrent.test(items.get(i))) {
                int target = i + step;
                if (target < 0 || target >= items.size()) {
                    return null;
                }
                T item = items.get(target);
                return () -> open.accept(item);
            }
        }
        return null;
    }

    // ---------- piezas de la banda ----------

    /** Cápsula de estado. */
    public static Label chip(String text, Tone tone) {
        Label chip = label(text, "detail-chip");
        chip.getStyleClass().add(toneClass(tone));
        return chip;
    }

    /** Círculo con las iniciales de una persona o empresa. */
    public static Label initials(String fullName) {
        // Primer nombre + primer apellido: "María Elena Castro Díaz" → MC; "Ana Rosa Quintero" → AQ.
        List<String> words = new ArrayList<>();
        if (fullName != null) {
            for (String part : fullName.trim().split("\\s+")) {
                if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                    words.add(part);
                }
            }
        }
        StringBuilder letters = new StringBuilder();
        if (!words.isEmpty()) {
            letters.append(Character.toUpperCase(words.get(0).charAt(0)));
            if (words.size() > 1) {
                String surname = words.size() >= 4 ? words.get(2) : words.get(words.size() - 1);
                letters.append(Character.toUpperCase(surname.charAt(0)));
            }
        }
        Label avatar = label(letters.isEmpty() ? "·" : letters.toString(), "detail-avatar");
        avatar.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return avatar;
    }

    /** Recuadro con un símbolo o un código corto (la cruz del difunto, "A-07 / Nivel 1"). */
    public static VBox badge(String main, String below) {
        VBox box = new VBox(label(main, "detail-badge-main"));
        if (below != null && !below.isBlank()) {
            box.getChildren().add(label(below, "detail-badge-sub"));
        }
        box.getStyleClass().add("detail-badge");
        box.setAlignment(Pos.CENTER);
        box.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }

    /** Línea de pasos de la banda: círculo (✓ si ya pasó), nombre y detalle de cada paso. */
    public static HBox steps(List<Step> steps) {
        HBox track = new HBox();
        track.getStyleClass().add("detail-steps");
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String state = step.state().name().toLowerCase(Locale.ROOT);
            Label dot = label(step.state() == StepState.DONE ? "✓" : "", "detail-step-dot");
            dot.getStyleClass().add(state);
            dot.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            HBox head = new HBox(dot);
            head.setAlignment(Pos.CENTER_LEFT);
            if (i < steps.size() - 1) {
                Region line = new Region();
                line.getStyleClass().addAll("detail-step-line", step.state() == StepState.DONE ? "done" : "pending");
                HBox.setHgrow(line, Priority.ALWAYS);
                head.getChildren().add(line);
            }
            Label name = label(step.name(), "detail-step-name");
            name.setWrapText(true);
            VBox item = new VBox(4, head, name);
            if (step.detail() != null && !step.detail().isBlank()) {
                Label detail = label(step.detail(), "detail-step-detail");
                detail.setWrapText(true);
                item.getChildren().add(detail);
            }
            item.getStyleClass().addAll("detail-step", state);
            HBox.setHgrow(item, Priority.ALWAYS);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setPrefWidth(0);
            track.getChildren().add(item);
        }
        return track;
    }

    /** Barra de avance de la banda con un texto a cada lado (arriendo, saldo, cupos, existencias). */
    public static VBox progress(String left, String right, double fraction, Tone tone) {
        Label leftText = label(left, "detail-progress-text");
        Label rightText = label(right, "detail-progress-text");
        rightText.getStyleClass().add("detail-progress-strong");
        Region push = new Region();
        HBox.setHgrow(push, Priority.ALWAYS);
        HBox texts = new HBox(leftText, push, rightText);
        return new VBox(6, texts, bar(fraction, tone));
    }

    /** Solo la barra de avance, para ponerla debajo de otros datos (por ejemplo total, abonado y saldo). */
    public static StackPane bar(double fraction, Tone tone) {
        Region fill = new Region();
        fill.getStyleClass().addAll("detail-progress-fill", toneClass(tone));
        Region track = new Region();
        track.getStyleClass().add("detail-progress-track");
        StackPane bar = new StackPane(track, fill);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("detail-progress");
        double share = Math.max(0, Math.min(1, fraction));
        fill.maxWidthProperty().bind(bar.widthProperty().multiply(share));
        fill.minWidthProperty().bind(fill.maxWidthProperty());
        return bar;
    }

    // ---------- cuerpo ----------

    /** Sección del cuerpo: título en mayúsculas con filete y su contenido. */
    public static VBox section(String title, Node... content) {
        return section(title, null, content);
    }

    /** Sección con un texto corto a la derecha del filete (por ejemplo "1 completo · 1 pendiente"). */
    public static VBox section(String title, String aside, Node... content) {
        Label caption = label(spaced(title), "detail-section-title");
        caption.setMinWidth(Region.USE_PREF_SIZE);
        Region rule = new Region();
        rule.getStyleClass().add("detail-section-rule");
        HBox.setHgrow(rule, Priority.ALWAYS);
        HBox legend = new HBox(10, caption, rule);
        legend.setAlignment(Pos.CENTER_LEFT);
        if (aside != null && !aside.isBlank()) {
            Label side = label(aside, "detail-section-aside");
            side.setMinWidth(Region.USE_PREF_SIZE);
            legend.getChildren().add(side);
        }
        VBox box = new VBox(8, legend);
        for (Node node : content) {
            if (node != null) {
                box.getChildren().add(node);
            }
        }
        box.getStyleClass().add("detail-section");
        return box;
    }

    /** Sección con una acción de texto a la derecha del filete (por ejemplo "+ Agregar"). */
    public static VBox sectionWithAction(String title, Button action, Node... content) {
        VBox box = section(title, (String) null, content);
        if (action != null) {
            action.getStyleClass().setAll("button", "detail-link");
            action.setMinWidth(Region.USE_PREF_SIZE);
            ((HBox) box.getChildren().get(0)).getChildren().add(action);
        }
        return box;
    }

    /** Tarjeta salvia donde van los datos de una sección. */
    public static VBox card(Node... content) {
        VBox card = new VBox(8);
        for (Node node : content) {
            if (node != null) {
                card.getChildren().add(node);
            }
        }
        card.getStyleClass().add("detail-card");
        return card;
    }

    /** Filas "dato … valor": el dato a la izquierda y el valor a la derecha. Los valores pueden ser texto o nodos. */
    public static GridPane rows(Object... labelValue) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-rows");
        grid.setHgap(14);
        grid.setVgap(6);
        ColumnConstraints key = new ColumnConstraints();
        key.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints value = new ColumnConstraints();
        value.setHgrow(Priority.ALWAYS);
        value.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().setAll(key, value);
        for (int i = 0; i + 1 < labelValue.length; i += 2) {
            grid.add(label(String.valueOf(labelValue[i]), "detail-key"), 0, i / 2);
            Node node = valueNode(labelValue[i + 1]);
            GridPane.setHalignment(node, HPos.RIGHT);
            grid.add(node, 1, i / 2);
        }
        return grid;
    }

    /** Datos en dos columnas, el nombre del dato encima del valor (cliente, servicio). */
    public static GridPane fields(Object... labelValue) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-fields");
        grid.setHgap(16);
        grid.setVgap(10);
        for (int c = 0; c < 2; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            grid.getColumnConstraints().add(column);
        }
        for (int i = 0; i + 1 < labelValue.length; i += 2) {
            int index = i / 2;
            Node node = valueNode(labelValue[i + 1]);
            VBox field = new VBox(1, label(String.valueOf(labelValue[i]), "detail-field-key"), node);
            grid.add(field, index % 2, index / 2);
        }
        return grid;
    }

    /** Persona o registro vinculado: círculo, nombre en negrita y una línea de datos; {@code aside} a la derecha. */
    public static HBox person(Node avatar, String name, String line, Node aside) {
        Label title = label(name, "detail-person-name");
        title.setWrapText(true);
        VBox text = new VBox(1, title);
        if (line != null && !line.isBlank()) {
            Label sub = label(line, "detail-person-line");
            sub.setWrapText(true);
            text.getChildren().add(sub);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("detail-person");
        if (avatar != null) {
            avatar.getStyleClass().add("detail-person-avatar");
            row.getChildren().add(0, avatar);
        }
        if (aside != null) {
            row.getChildren().add(aside);
        }
        return row;
    }

    /** Círculo de estado de un documento o requisito (✓, –, por hacer, falta). */
    public static Label mark(Mark mark) {
        Label icon = label(switch (mark) {
            case DONE -> "✓";
            case NOT_APPLICABLE -> "–";
            case MISSING -> "!";
            case PENDING -> "";
        }, "detail-mark");
        icon.getStyleClass().add(mark.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        icon.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return icon;
    }

    /** Fila de una lista: ícono, título, detalle y algo a la derecha (botón "Ver", valor). */
    public static HBox item(Node icon, String title, String line, Node aside) {
        HBox row = person(null, title, line, aside);
        if (icon != null) {
            row.getChildren().add(0, icon);
        }
        row.getStyleClass().add("detail-item");
        return row;
    }

    /** Tarjeta con una línea fina entre cada elemento (documentos, responsable y sus datos). */
    public static VBox list(Node... content) {
        VBox card = card();
        for (Node node : content) {
            if (node == null) {
                continue;
            }
            if (!card.getChildren().isEmpty()) {
                Region rule = new Region();
                rule.getStyleClass().add("detail-list-rule");
                card.getChildren().add(rule);
            }
            card.getChildren().add(node);
        }
        return card;
    }

    /** Enlace de texto pequeño dentro de una fila ("Ver", "Subir"). */
    public static Button link(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("detail-link");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> action.run());
        return button;
    }

    /**
     * Tira de casillas para la banda (cuotas de los últimos meses, por ejemplo) con su leyenda.
     * {@code legend} son pares estado / texto: CellState.OK, "A tiempo", ...
     */
    public static VBox strip(List<Cell> cells, Object... legend) {
        HBox row = new HBox(5);
        for (Cell cell : cells) {
            Region box = new Region();
            box.getStyleClass().addAll("detail-cell", cell.state().name().toLowerCase(Locale.ROOT));
            if (cell.tooltip() != null) {
                Tooltip.install(box, new Tooltip(cell.tooltip()));
            }
            VBox column = new VBox(4, box, label(cell.label(), "detail-cell-label"));
            column.setAlignment(Pos.CENTER);
            column.setPrefWidth(0);
            column.setMaxWidth(Double.MAX_VALUE);
            box.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(column, Priority.ALWAYS);
            row.getChildren().add(column);
        }
        HBox keys = new HBox(12);
        for (int i = 0; i + 1 < legend.length; i += 2) {
            Region swatch = new Region();
            swatch.getStyleClass().addAll("detail-cell-swatch",
                    ((CellState) legend[i]).name().toLowerCase(Locale.ROOT));
            HBox key = new HBox(5, swatch, label(String.valueOf(legend[i + 1]), "detail-cell-label"));
            key.setAlignment(Pos.CENTER_LEFT);
            keys.getChildren().add(key);
        }
        return new VBox(8, row, keys);
    }

    /** Línea de texto sobre la banda: "Próxima cuota: <b>octubre 2025, vence el 22/10/2025</b>". */
    public static HBox heroLine(String lead, String strong) {
        HBox line = new HBox(4, label(lead, "detail-hero-line"));
        if (strong != null && !strong.isBlank()) {
            line.getChildren().add(label(strong, "detail-hero-strong"));
        }
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    /**
     * Muro en miniatura para la banda: un cuadrito por bóveda (nivel 1 abajo) y la actual resaltada.
     * Cada posición es {nivel, columna}; {@code current} es la de la bóveda abierta (puede ser null).
     */
    public static GridPane miniWall(List<int[]> positions, int[] current) {
        int levels = positions.stream().mapToInt(p -> p[0]).max().orElse(1);
        int columns = positions.stream().mapToInt(p -> p[1]).max().orElse(1);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("detail-wall");
        grid.setHgap(3);
        grid.setVgap(3);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(column);
        }
        for (int[] p : positions) {
            Region cell = new Region();
            boolean mine = current != null && current[0] == p[0] && current[1] == p[1];
            cell.getStyleClass().addAll("detail-wall-cell", mine ? "current" : "other");
            cell.setMaxWidth(Double.MAX_VALUE);
            grid.add(cell, p[1] - 1, levels - p[0]);
        }
        return grid;
    }

    /**
     * Franja de un día para la banda (Salas): horas de {@code fromHour} a {@code toHour}, una barra con
     * texto entre {@code barStart} y {@code barEnd} (en horas del día, con decimales) y una marca de la
     * hora actual ({@code now}; null si no es este día).
     */
    public static VBox dayTrack(int fromHour, int toHour, double barStart, double barEnd, String barText,
                                Double now, String nowText) {
        double span = toHour - fromHour;
        Pane track = new Pane();
        track.getStyleClass().add("detail-day-track");
        track.setMinHeight(24);
        track.setPrefHeight(24);
        double start = Math.max(fromHour, Math.min(toHour, barStart));
        double end = Math.max(start, Math.min(toHour, barEnd));
        Label bar = label(barText, "detail-day-bar");
        bar.setMinWidth(0);
        bar.layoutXProperty().bind(track.widthProperty().multiply((start - fromHour) / span));
        bar.prefWidthProperty().bind(track.widthProperty().multiply((end - start) / span));
        bar.setPrefHeight(24);
        bar.setVisible(end > start);
        track.getChildren().add(bar);
        Pane overlay = new Pane();
        overlay.setPickOnBounds(false);
        if (now != null && now >= fromHour && now <= toHour) {
            Region line = new Region();
            line.getStyleClass().add("detail-day-now");
            line.setPrefSize(2, 34);
            line.setLayoutY(-6);
            line.layoutXProperty().bind(track.widthProperty().multiply((now - fromHour) / span));
            Label tag = label(nowText, "detail-day-now-tag");
            tag.setLayoutY(-22);
            tag.layoutXProperty().bind(line.layoutXProperty().subtract(tag.widthProperty().divide(2)));
            track.getChildren().addAll(line, tag);
        }
        HBox hours = new HBox();
        for (int h = fromHour; h <= toHour; h += 2) {
            Label hour = label(String.valueOf(h), "detail-cell-label");
            hours.getChildren().add(hour);
            if (h + 2 <= toHour) {
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                hours.getChildren().add(push);
            }
        }
        VBox box = new VBox(4, track, hours);
        box.setPadding(new Insets(16, 0, 0, 0));
        return box;
    }

    /** Tres datos repartidos a lo ancho de la banda (ingreso, lo que falta, salida). */
    public static HBox heroSpread(Node left, Node middle, Node right) {
        Region a = new Region();
        Region b = new Region();
        HBox.setHgrow(a, Priority.ALWAYS);
        HBox.setHgrow(b, Priority.ALWAYS);
        HBox row = new HBox(left, a, middle, b, right);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Historial: un punto por hecho con su título y detalle, unidos por una línea. */
    public static VBox timeline(List<Event> events) {
        VBox list = new VBox();
        list.getStyleClass().add("detail-timeline");
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            Region dot = new Region();
            dot.getStyleClass().addAll("detail-timeline-dot", toneClass(event.tone()));
            Region line = new Region();
            line.getStyleClass().add("detail-timeline-line");
            line.setVisible(i < events.size() - 1);
            VBox.setVgrow(line, Priority.ALWAYS);
            VBox rail = new VBox(dot, line);
            rail.setAlignment(Pos.TOP_CENTER);
            rail.setMinWidth(14);
            Label title = label(event.title(), "detail-timeline-title");
            title.setWrapText(true);
            VBox text = new VBox(1, title);
            if (event.detail() != null && !event.detail().isBlank()) {
                Label detail = label(event.detail(), "detail-timeline-detail");
                detail.setWrapText(true);
                text.getChildren().add(detail);
            }
            text.setPadding(new Insets(0, 0, 10, 0));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(10, rail, text);
            if (event.aside() != null) {
                row.getChildren().add(event.aside());
            }
            list.getChildren().add(row);
        }
        return list;
    }

    /** Texto suave dentro de una tarjeta, para cuando una sección todavía no tiene datos. */
    public static VBox empty(String text) {
        Label note = label(text, "detail-empty");
        note.setWrapText(true);
        return card(note);
    }

    /** Aviso "Qué sigue" al final del detalle: el siguiente paso o por qué falta algo. */
    public static HBox nextStep(String text, Tone tone) {
        SVGPath icon = new SVGPath();
        icon.setContent(INFO_ICON);
        icon.getStyleClass().add("detail-next-icon");
        Label body = label(text, "detail-next-text");
        body.setWrapText(true);
        VBox texts = new VBox(2, label(spaced("Qué sigue"), "detail-next-title"), body);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox box = new HBox(10, icon, texts);
        box.getStyleClass().addAll("detail-next", toneClass(tone));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** Ventana centrada: las secciones van en dos columnas del mismo ancho. */
    public static GridPane columns(List<? extends Node> left, List<? extends Node> right) {
        VBox one = new VBox(16);
        one.getChildren().setAll(left);
        VBox two = new VBox(16);
        two.getChildren().setAll(right);
        GridPane grid = new GridPane();
        grid.setHgap(24);
        for (int c = 0; c < 2; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            column.setMinWidth(0);
            grid.getColumnConstraints().add(column);
        }
        grid.add(one, 0, 0);
        grid.add(two, 1, 0);
        GridPane.setValignment(one, VPos.TOP);
        GridPane.setValignment(two, VPos.TOP);
        grid.getStyleClass().add("detail-columns");
        return grid;
    }

    /** Botón de acción del pie. */
    public static Button button(String text, Tone tone, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll("detail-button", switch (tone) {
            case OK, GOLD -> "detail-primary";
            case ALERT -> "detail-danger";
            default -> "detail-secondary";
        });
        button.setMinWidth(Region.USE_PREF_SIZE);
        if (action != null) {
            button.setOnAction(e -> action.run());
        }
        return button;
    }

    /** Botón principal (verde) del pie. */
    public static Button primary(String text, Runnable action) {
        return button(text, Tone.OK, action);
    }

    /** Botón secundario (blanco con borde) del pie. */
    public static Button secondary(String text, Runnable action) {
        return button(text, Tone.NEUTRAL, action);
    }

    /** Botón de acción delicada (anular, desactivar), con el texto en rojo. */
    public static Button danger(String text, Runnable action) {
        return button(text, Tone.ALERT, action);
    }

    // ---------- utilidades ----------

    private static Node valueNode(Object value) {
        if (value instanceof Node node) {
            return node;
        }
        Label label = label(value == null ? "—" : String.valueOf(value), "detail-value");
        label.setWrapText(true);
        label.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        return label;
    }

    /** Valor con color (por ejemplo una fecha vencida en rojo), para {@link #rows} y {@link #fields}. */
    public static Label value(String text, Tone tone) {
        Label label = label(text, "detail-value");
        label.getStyleClass().add(toneClass(tone));
        label.setWrapText(true);
        return label;
    }

    private static String toneClass(Tone tone) {
        return "tone-" + (tone == null ? Tone.NEUTRAL : tone).name().toLowerCase(Locale.ROOT);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text == null ? "" : text);
        label.getStyleClass().add(style);
        return label;
    }

    /** Texto en mayúsculas con las letras un poco separadas, como los títulos de la banda y las secciones. */
    static String spaced(String text) {
        return FormShell.spaced(text == null ? "" : text);
    }

    private static String sheet() {
        return DetailShell.class.getResource(SHEET).toExternalForm();
    }
}
