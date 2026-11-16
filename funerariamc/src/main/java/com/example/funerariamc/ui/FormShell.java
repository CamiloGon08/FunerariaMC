package com.example.funerariamc.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.collections.MapChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Formato común de los formularios de registro. 
 */
public final class FormShell {
    public enum Kind { WINDOW, PANEL, PAGE }
    static final String SHEET = "/css/forms.css";
    private static final String CLOSE_ICON = "M1 1 L11 11 M11 1 L1 11";
    private static final double MARGIN = 14;
    private static final Effect DIM = new ColorAdjust(0, -0.2, -0.38, 0);

    private FormShell() {
    }

    /**
     * Campos de dinero: mientras se escribe se ve "$ 4.000.000" (puntos de miles y coma decimal).
     * Los métodos que leen el valor ya quitan el signo y los puntos, así que el servicio recibe lo mismo.
     */
    public static void money(TextField... fields) {
        for (TextField field : fields) {
            String current = field.getText();
            field.setTextFormatter(new TextFormatter<String>(change -> {
                boolean whole = change.getRangeStart() == 0 && change.getRangeEnd() == change.getControlText().length();
                String raw = change.getControlNewText();
                // Un valor que pone el programa (por ejemplo "1800000.00") trae el punto como decimal
                if (whole && raw.matches("\\d+\\.\\d{1,2}")) {
                    raw = raw.replaceFirst("\\.0+$", "").replace('.', ',');
                }
                int caret = change.getCaretPosition();
                int digitsAfter = raw.substring(Math.min(caret, raw.length())).replaceAll("[^0-9,]", "").length();
                String formatted = pesos(raw);
                if (formatted == null) {
                    return null;
                }
                change.setRange(0, change.getControlText().length());
                change.setText(formatted);
                int position = formatted.length();
                for (int seen = 0; position > 0 && seen < digitsAfter; position--) {
                    char c = formatted.charAt(position - 1);
                    if (Character.isDigit(c) || c == ',') {
                        seen++;
                    }
                }
                change.setCaretPosition(position);
                change.setAnchor(position);
                return change;
            }));
            field.setText(current == null ? "" : current);
        }
    }

    /** "4000000,5" a "$ 4.000.000,5"; null si no es un valor en pesos (se rechaza la tecla). */
    private static String pesos(String raw) {
        String clean = raw.replaceAll("[$.\\s]", "");
        if (clean.isEmpty()) {
            return "";
        }
        if (!clean.matches("\\d*(,\\d{0,2})?")) {
            return null;
        }
        int comma = clean.indexOf(',');
        String whole = (comma < 0 ? clean : clean.substring(0, comma)).replaceFirst("^0+(?=\\d)", "");
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(whole.charAt(i));
        }
        return "$ " + (grouped.length() == 0 ? "0" : grouped) + (comma < 0 ? "" : clean.substring(comma));
    }

    /** Ventana pequeña centrada sobre el módulo. */
    public static void window(Dialog<?> dialog, String module) {
        dress(dialog, module, Kind.WINDOW);
    }

    /** Panel a la derecha que ocupa todo el alto. */
    public static void panel(Dialog<?> dialog, String module) {
        dress(dialog, module, Kind.PANEL);
    }

    /** Página que tapa el área del módulo y deja el menú a la vista. */
    public static void page(Dialog<?> dialog, String module) {
        dress(dialog, module, Kind.PAGE);
    }

    /** Lo mismo para un formulario hecho en FXML que se abre en su propia ventana (Stage). Llamar antes de mostrarla. */
    public static void stage(Stage stage, String module, Kind kind) {
        stage.initStyle(StageStyle.TRANSPARENT);
        Scene scene = stage.getScene();
        scene.setFill(Color.TRANSPARENT);
        Parent root = scene.getRoot();
        root.getStylesheets().add(sheet());
        root.getStyleClass().addAll("form-shell", styleOf(kind));
        if (root instanceof Pane pane) {
            dressHeader(pane, module, stage::close);
            if (kind != Kind.WINDOW) {
                scrollBody(pane);
            }
            if (kind == Kind.PANEL) {
                twoColumns(pane);
            }
        }
        Window owner = stage.getOwner();
        Effect[] previous = new Effect[1];
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> {
            previous[0] = dim(owner);
            if (root instanceof Region region) {
                place(stage, region, owner, kind);
            }
        });
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> undim(owner, previous[0]));
    }

    private static void dress(Dialog<?> dialog, String module, Kind kind) {
        dialog.initStyle(StageStyle.TRANSPARENT);
        DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().add(sheet());
        pane.getStyleClass().addAll("form-shell", styleOf(kind));
        Runnable close = () -> cancel(dialog);
        Node content = pane.getContent();
        if (content instanceof Pane box && box.lookup(".form-header") != null) {
            dressHeader(box, module, close);
        } else {
            // Diálogos sin encabezado propio (por ejemplo, una alerta): el título y el texto pasan a la banda.
            VBox header = new VBox(2, text(dialog.getTitle(), "form-title"));
            header.getStyleClass().add("form-header");
            if (dialog.getHeaderText() != null && !dialog.getHeaderText().isBlank()) {
                header.getChildren().add(text(dialog.getHeaderText(), "card-subtitle"));
            }
            dialog.setHeaderText(null);
            dialog.setGraphic(null);
            VBox body = new VBox(content == null ? new Region() : content);
            body.getStyleClass().add("form-body");
            VBox root = new VBox(header, body);
            root.getStyleClass().add("form-root");
            pane.setContent(root);
            dressHeader(root, module, close);
        }
        if (pane.getContent() instanceof Pane box) {
            // También en la ventana pequeña: si no cabe en la pantalla, el cuerpo se desplaza y los botones siguen a la vista
            scrollBody(box);
            if (kind == Kind.PANEL) {
                twoColumns(box);
            }
        }
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_LINUX);
        }
        Window owner = ownerOf(dialog);
        Effect[] previous = new Effect[1];
        EventHandler<DialogEvent> shown = dialog.getOnShown();
        dialog.setOnShowing(e -> {
            pane.getScene().setFill(Color.TRANSPARENT);
            previous[0] = dim(owner);
            sameWidthOff(pane);
        });
        dialog.setOnShown(e -> {
            if (shown != null) {
                shown.handle(e);
            }
            place(pane.getScene().getWindow(), pane, owner, kind);
            if (kind == Kind.WINDOW) {
                fitWindow(pane, owner);
            }
        });
        EventHandler<DialogEvent> hidden = dialog.getOnHidden();
        dialog.setOnHidden(e -> {
            undim(owner, previous[0]);
            if (hidden != null) {
                hidden.handle(e);
            }
        });
    }

    /** Un paso de un formulario largo: su nombre y las secciones del formulario que muestra. */
    public record Step(String name, String... sections) {
    }

    /**
     * Página por pasos: muestra las secciones de un paso a la vez, con la línea de pasos en la banda
     * y abajo "atrás" y "siguiente"; el botón de guardar aparece en el último paso. Lo escrito no se
     * pierde al ir y volver. Si se da {@code side}, va a la derecha como tarjeta de resumen siempre visible.
     * Se llama después de {@link #page}.
     */
    public static void steps(Dialog<?> dialog, Node side, Step... steps) {
        DialogPane pane = dialog.getDialogPane();
        if (!(pane.getContent() instanceof Pane root)) {
            return;
        }
        Wizard wizard = Wizard.of(root, side, steps);
        if (wizard == null) {
            return;
        }
        // Los botones se arman al mostrarse: el módulo define Cancelar y la acción justo antes de abrir.
        EventHandler<DialogEvent> showing = dialog.getOnShowing();
        dialog.setOnShowing(e -> {
            if (showing != null) {
                showing.handle(e);
            }
            ButtonType back = new ButtonType("Atrás", ButtonBar.ButtonData.BACK_PREVIOUS);
            ButtonType next = new ButtonType("Siguiente", ButtonBar.ButtonData.NEXT_FORWARD);
            pane.getButtonTypes().addAll(back, next);
            Button save = null;
            for (ButtonType type : pane.getButtonTypes()) {
                if (type.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                    save = (Button) pane.lookupButton(type);
                }
            }
            if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
                bar.setButtonOrder("+BCXO");
            }
            wizard.wire((Button) pane.lookupButton(back), (Button) pane.lookupButton(next), save);
            sameWidthOff(pane);
        });
    }

    /** Lo mismo para un formulario FXML en su propia ventana: los botones van en su fila {@code .form-footer}. */
    public static void steps(Stage stage, Node side, Step... steps) {
        if (!(stage.getScene().getRoot() instanceof Pane root)) {
            return;
        }
        Wizard wizard = Wizard.of(root, side, steps);
        if (wizard == null || !(root.lookup(".form-footer") instanceof Pane footer)) {
            return;
        }
        Button save = null;
        int cancelAt = -1;
        for (int i = 0; i < footer.getChildren().size(); i++) {
            if (footer.getChildren().get(i) instanceof Button button) {
                if (button.isDefaultButton() || button.getStyleClass().contains("primary-button")) {
                    save = button;
                } else if (cancelAt < 0) {
                    cancelAt = i;
                }
            }
        }
        Button back = new Button("Atrás");
        Button next = new Button("Siguiente");
        next.getStyleClass().add("primary-button");
        footer.getChildren().add(cancelAt < 0 ? 0 : cancelAt, back);
        footer.getChildren().add(next);
        if (save != null) {
            // Enter no guarda hasta llegar al último paso
            Button finish = save;
            next.visibleProperty().addListener((obs, old, visible) -> finish.setDefaultButton(!visible));
        }
        wizard.wire(back, next, save);
    }

    /** Estado de una página por pasos: qué paso se ve y cómo se dibujan la línea y los botones. */
    private static final class Wizard {
        private final List<VBox> pages = new ArrayList<>();
        private final HBox track = new HBox();
        private final Step[] steps;
        private final Pane body;
        private int at;

        private Wizard(Step[] steps, Pane body) {
            this.steps = steps;
            this.body = body;
        }

        static Wizard of(Pane root, Node side, Step[] steps) {
            if (!(bodyOf(root) instanceof Pane body)) {
                return null;
            }
            Wizard wizard = new Wizard(steps, body);
            // Agrupa lo que hay en el cuerpo por paso, según el título de cada sección
            List<List<Node>> groups = new ArrayList<>();
            for (int i = 0; i < steps.length; i++) {
                groups.add(new ArrayList<>());
            }
            int current = 0;
            for (Node child : new ArrayList<>(body.getChildren())) {
                Node title = child.getStyleClass().contains("section-title") ? child : child.lookup(".section-title");
                if (title instanceof Label label) {
                    int found = stepOf(steps, label.getText());
                    if (found >= 0) {
                        current = found;
                    }
                }
                groups.get(current).add(child);
            }
            // Cada paso va en su propio contenedor (algunos avisos ya controlan su propia visibilidad)
            double gap = body instanceof VBox box ? box.getSpacing() : 14;
            for (List<Node> group : groups) {
                VBox page = new VBox(gap);
                page.getChildren().setAll(group);
                wizard.pages.add(page);
            }
            body.getChildren().setAll(wizard.pages);
            wizard.track.getStyleClass().add("form-steps");
            if (root.lookup(".form-hero") instanceof Pane header) {
                header.getChildren().add(wizard.track);
            }
            if (side != null) {
                sideCard(root, side);
            }
            wizard.refresh(null, null, null);
            return wizard;
        }

        void wire(Button back, Button next, Button save) {
            back.getStyleClass().add("form-back");
            next.getStyleClass().add("primary-dialog-button");
            back.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                at = Math.max(0, at - 1);
                refresh(back, next, save);
            });
            next.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                event.consume();
                at = Math.min(steps.length - 1, at + 1);
                refresh(back, next, save);
            });
            refresh(back, next, save);
        }

        private void refresh(Button back, Button next, Button save) {
            for (int i = 0; i < pages.size(); i++) {
                pages.get(i).setVisible(i == at);
                pages.get(i).setManaged(i == at);
            }
            emptyNotice(pages.get(at));
            drawTrack(track, steps, at);
            boolean first = at == 0;
            boolean last = at == steps.length - 1;
            if (back != null) {
                back.setText(first ? "Atrás" : "←  " + steps[at - 1].name());
                showButton(back, !first);
            }
            if (next != null) {
                next.setText(last ? "Siguiente" : "Siguiente: " + steps[at + 1].name() + "  →");
                showButton(next, !last);
            }
            if (save != null) {
                showButton(save, last);
            }
            if (body.getParent() != null && body.getParent().getParent() instanceof ScrollPane scroll) {
                scroll.setVvalue(0);
            }
        }
    }

    /**
     * Diseño propio: una tarjeta visual (muro, agenda, vista previa) junto al formulario, que queda
     * angosto en una columna de {@code formWidth} px. {@code visualFirst} pone la tarjeta a la izquierda.
     * Se llama después de {@link #page}.
     */
    public static void visual(Dialog<?> dialog, Node visual, boolean visualFirst, double formWidth) {
        if (!(dialog.getDialogPane().getContent() instanceof Pane root)) {
            return;
        }
        for (int i = 0; i < root.getChildren().size(); i++) {
            if (root.getChildren().get(i) instanceof ScrollPane scroll) {
                twoColumns(scroll.getContent());
                scroll.setMinWidth(formWidth);
                scroll.setPrefWidth(formWidth);
                scroll.setMaxWidth(formWidth);
                ScrollPane visualScroll = new ScrollPane(visual);
                visualScroll.setFitToWidth(true);
                visualScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                visualScroll.setMinWidth(0);
                visualScroll.getStyleClass().addAll("form-scroll", "form-visual");
                HBox.setHgrow(visualScroll, Priority.ALWAYS);
                HBox center = visualFirst ? new HBox(visualScroll, scroll) : new HBox(scroll, visualScroll);
                center.getStyleClass().add("form-center");
                scroll.getStyleClass().add(visualFirst ? "form-column-right" : "form-column-left");
                VBox.setVgrow(center, Priority.ALWAYS);
                root.getChildren().set(i, center);
                return;
            }
        }
    }

    /**
     * Diseño propio en dos columnas: las secciones nombradas pasan a una columna derecha de
     * {@code width} px y el resto queda a la izquierda. Se llama después de {@link #page}.
     */
    public static void split(Dialog<?> dialog, double width, String... sideSections) {
        if (!(dialog.getDialogPane().getContent() instanceof Pane root) || !(bodyOf(root) instanceof VBox body)) {
            return;
        }
        Step[] marker = {new Step("", sideSections)};
        VBox side = new VBox(body.getSpacing());
        side.getStyleClass().add("form-body");
        boolean moving = false;
        for (Node child : new ArrayList<>(body.getChildren())) {
            Node title = child.getStyleClass().contains("section-title") ? child : child.lookup(".section-title");
            if (title instanceof Label label) {
                moving = stepOf(marker, label.getText()) == 0;
            }
            if (moving) {
                body.getChildren().remove(child);
                side.getChildren().add(child);
            }
        }
        ScrollPane sideScroll = new ScrollPane(side);
        sideScroll.setFitToWidth(true);
        sideScroll.getStyleClass().addAll("form-scroll", "form-column-right");
        sideScroll.setMinWidth(width);
        sideScroll.setPrefWidth(width);
        sideScroll.setMaxWidth(width);
        twoColumns(side);
        twoColumns(body);
        sideCard(root, null);
        for (int i = 0; i < root.getChildren().size(); i++) {
            if (root.getChildren().get(i) instanceof ScrollPane scroll) {
                HBox center = new HBox(scroll, sideScroll);
                center.getStyleClass().add("form-center");
                HBox.setHgrow(scroll, Priority.ALWAYS);
                VBox.setVgrow(center, Priority.ALWAYS);
                root.getChildren().set(i, center);
                return;
            }
        }
    }

    /** Si lo elegido antes deja el paso sin campos (por ejemplo, un cargo sin datos propios), lo dice. */
    private static void emptyNotice(VBox page) {
        page.getChildren().removeIf(node -> node.getStyleClass().contains("form-step-empty"));
        boolean empty = page.getChildren().stream().noneMatch(Node::isVisible);
        if (empty) {
            Label note = text("Con lo elegido en los pasos anteriores, este paso no tiene datos que llenar.", "note-info");
            note.getStyleClass().add("form-step-empty");
            note.setMaxWidth(Double.MAX_VALUE);
            page.getChildren().add(note);
        }
    }

    /** Los botones de abajo toman el ancho de su texto (ButtonBar los iguala por defecto). */
    private static void sameWidthOff(DialogPane pane) {
        for (ButtonType type : pane.getButtonTypes()) {
            if (pane.lookupButton(type) instanceof Button button) {
                ButtonBar.setButtonUniformSize(button, false);
            }
        }
    }

    /** El cuerpo del formulario, también cuando ya está dentro del desplazamiento (antes de mostrarse). */
    private static Node bodyOf(Pane root) {
        for (Node child : root.getChildren()) {
            if (child instanceof ScrollPane scroll && scroll.getContent() != null
                    && scroll.getContent().getStyleClass().contains("form-body")) {
                return scroll.getContent();
            }
        }
        return root.lookup(".form-body");
    }

    private static int stepOf(Step[] steps, String section) {
        for (int i = 0; i < steps.length; i++) {
            for (String name : steps[i].sections()) {
                if (name.equalsIgnoreCase(section.trim())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void showButton(Button button, boolean visible) {
        button.setVisible(visible);
        button.setManaged(visible);
    }

    /** Línea de pasos: círculo con el número (o ✓ si ya pasó), nombre y estado. */
    private static void drawTrack(HBox track, Step[] steps, int current) {
        track.getChildren().clear();
        for (int i = 0; i < steps.length; i++) {
            String state = i < current ? "done" : i == current ? "current" : "pending";
            Label dot = new Label(i < current ? "✓" : String.valueOf(i + 1));
            dot.getStyleClass().addAll("form-step-dot", state);
            dot.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            Region line = new Region();
            line.getStyleClass().addAll("form-step-line", i < current ? "done" : "pending");
            HBox.setHgrow(line, Priority.ALWAYS);
            HBox head = new HBox(8, dot);
            head.setAlignment(Pos.CENTER_LEFT);
            if (i < steps.length - 1) {
                head.getChildren().add(line);
            }
            Label name = text(steps[i].name(), "form-step-name");
            Label status = text(i < current ? "Completo" : i == current ? "En curso" : "Pendiente", "form-step-state");
            VBox item = new VBox(6, head, name, status);
            item.getStyleClass().addAll("form-step", state);
            HBox.setHgrow(item, Priority.ALWAYS);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setPrefWidth(0);
            track.getChildren().add(item);
        }
    }

    /** Pone la tarjeta de resumen a la derecha del cuerpo, fuera del desplazamiento. */
    private static void sideCard(Pane root, Node side) {
        if (side == null) {
            return;
        }
        for (int i = 0; i < root.getChildren().size(); i++) {
            Node child = root.getChildren().get(i);
            if (child instanceof ScrollPane scroll) {
                VBox card = new VBox(side);
                card.getStyleClass().add("form-side");
                card.setMinWidth(300);
                card.setPrefWidth(340);
                card.setMaxWidth(380);
                HBox center = new HBox(scroll, card);
                center.getStyleClass().add("form-center");
                HBox.setHgrow(scroll, Priority.ALWAYS);
                VBox.setVgrow(center, Priority.ALWAYS);
                root.getChildren().set(i, center);
                return;
            }
        }
    }

    /** Pone el módulo arriba del título, la banda con el lirio y la X de cerrar. */
    private static void dressHeader(Pane root, String module, Runnable close) {
        if (!(root.lookup(".form-header") instanceof Pane header) || header.getStyleClass().contains("form-hero")) {
            return;
        }
        header.getStyleClass().addAll("form-hero", "hero-band");
        if (module != null && !module.isBlank()) {
            header.getChildren().add(0, text(spaced(module), "form-eyebrow"));
        }
        SVGPath cross = new SVGPath();
        cross.setContent(CLOSE_ICON);
        cross.getStyleClass().add("form-close-icon");
        Button closeButton = new Button();
        closeButton.setGraphic(cross);
        closeButton.getStyleClass().add("form-close");
        closeButton.setFocusTraversable(false);
        closeButton.setOnAction(e -> close.run());
        Parent parent = header.getParent();
        if (parent instanceof Pane holder) {
            int index = holder.getChildren().indexOf(header);
            StackPane hero = new StackPane();
            hero.getStyleClass().add("form-hero-holder");
            holder.getChildren().set(index, hero);
            hero.getChildren().addAll(header, closeButton);
            StackPane.setAlignment(closeButton, Pos.TOP_RIGHT);
        }
    }

    /** Nombre del módulo en mayúsculas con las letras un poco separadas, como "F U N E R A R I A" del menú. */
    static String spaced(String module) {
        StringBuilder text = new StringBuilder();
        String upper = module.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            if (i > 0) {
                text.append('\u2009');
            }
            text.append(upper.charAt(i));
        }
        return text.toString();
    }

    /**
     * El panel es angosto: las cuadrículas de 3 o más columnas pasan a 2, en el mismo orden.
     * Lo que ocupaba varias columnas (títulos de sección, campos anchos) pasa a ocupar la fila entera.
     * Si el formulario cambia después el ancho de un campo, se vuelve a acomodar.
     */
    static void twoColumns(Node node) {
        if (node instanceof GridPane grid) {
            int columns = Math.max(grid.getColumnConstraints().size(), maxColumn(grid) + 1);
            if (columns > 2) {
                List<Node> order = new ArrayList<>(grid.getChildren());
                order.sort(Comparator.comparingInt((Node n) -> index(GridPane.getRowIndex(n)))
                        .thenComparingInt(n -> index(GridPane.getColumnIndex(n))));
                List<Boolean> wide = new ArrayList<>();
                for (Node child : order) {
                    Integer span = GridPane.getColumnSpan(child);
                    wide.add(span != null && span >= columns);
                }
                grid.getColumnConstraints().setAll(half(), half());
                Runnable reflow = () -> reflow(order, wide);
                reflow.run();
                for (Node child : order) {
                    child.getProperties().addListener((MapChangeListener<Object, Object>) change -> {
                        if ("gridpane-column-span".equals(change.getKey()) && !REFLOWING[0]) {
                            reflow.run();
                        }
                    });
                }
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                twoColumns(child);
            }
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            twoColumns(scroll.getContent());
        }
    }

    private static final boolean[] REFLOWING = new boolean[1];

    private static void reflow(List<Node> order, List<Boolean> wide) {
        REFLOWING[0] = true;
        try {
            int row = 0;
            int column = 0;
            for (int i = 0; i < order.size(); i++) {
                Node child = order.get(i);
                Integer current = GridPane.getColumnSpan(child);
                boolean full = wide.get(i) || (current != null && current >= 2);
                if (full) {
                    if (column > 0) {
                        row++;
                    }
                    GridPane.setConstraints(child, 0, row, 2, 1);
                    row++;
                    column = 0;
                } else {
                    GridPane.setConstraints(child, column, row, 1, 1);
                    column++;
                    if (column == 2) {
                        column = 0;
                        row++;
                    }
                }
            }
        } finally {
            REFLOWING[0] = false;
        }
    }

    private static ColumnConstraints half() {
        ColumnConstraints constraints = new ColumnConstraints();
        constraints.setPercentWidth(50);
        return constraints;
    }

    private static int maxColumn(GridPane grid) {
        int max = 0;
        for (Node child : grid.getChildren()) {
            Integer span = GridPane.getColumnSpan(child);
            max = Math.max(max, index(GridPane.getColumnIndex(child)) + (span == null ? 1 : span) - 1);
        }
        return max;
    }

    private static int index(Integer value) {
        return value == null ? 0 : value;
    }

    /** En el panel y en la página el cuerpo se desplaza y los botones quedan siempre abajo. */
    private static void scrollBody(Pane root) {
        for (Node child : root.getChildren()) {
            if (child instanceof ScrollPane scroll) {
                // Algunos formularios limitan el alto al 72 % de la pantalla; el panel y la página van de arriba abajo
                scroll.setMaxHeight(Double.MAX_VALUE);
                VBox.setVgrow(scroll, Priority.ALWAYS);
                return;
            }
        }
        for (int i = 0; i < root.getChildren().size(); i++) {
            Node child = root.getChildren().get(i);
            if (child.getStyleClass().contains("form-body")) {
                ScrollPane scroll = new ScrollPane();
                root.getChildren().set(i, scroll);
                scroll.setContent(child);
                scroll.setFitToWidth(true);
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scroll.getStyleClass().add("form-scroll");
                VBox.setVgrow(scroll, Priority.ALWAYS);
                return;
            }
        }
    }

    /** Ubica la ventana del formulario según su formato, sobre el área visible de la ventana principal. */
    static void place(Window window, Region content, Window owner, Kind kind) {
        if (window == null || owner == null || owner.getScene() == null) {
            return;
        }
        Scene scene = owner.getScene();
        double sx = owner.getX() + scene.getX();
        double sy = owner.getY() + scene.getY();
        double sw = scene.getWidth();
        double sh = scene.getHeight();
        switch (kind) {
            case WINDOW -> {
                double w = Math.min(window.getWidth(), sw - 2 * MARGIN);
                double h = Math.min(window.getHeight(), sh - 2 * MARGIN);
                window.setWidth(w);
                window.setHeight(h);
                window.setX(sx + (sw - w) / 2);
                window.setY(sy + (sh - h) / 2);
            }
            case PANEL -> {
                double w = panelWidth(sw);
                content.setPrefWidth(w);
                content.setPrefHeight(sh - 2 * MARGIN);
                window.setWidth(w);
                window.setHeight(sh - 2 * MARGIN);
                window.setX(sx + sw - w - MARGIN);
                window.setY(sy + MARGIN);
            }
            case PAGE -> {
                double left = sidebarRight(scene) + MARGIN;
                double w = sw - left - MARGIN;
                content.setPrefWidth(w);
                content.setPrefHeight(sh - 2 * MARGIN);
                window.setWidth(w);
                window.setHeight(sh - 2 * MARGIN);
                window.setX(sx + left);
                window.setY(sy + MARGIN);
            }
        }
    }

    /**
     * Ventana pequeña: el alto se calcula con el ancho real de la ventana y queda fijo, así la ventana
     * no se estira de más (los textos largos que se parten en varias líneas a veces se miden con un ancho
     * menor y el alto salía enorme, con los botones fuera de la pantalla). Si el formulario no cabe,
     * el cuerpo se desplaza y Cancelar y la acción quedan siempre abajo. Si después aparecen o se ocultan
     * campos, la ventana se vuelve a ajustar.
     */
    private static void fitWindow(DialogPane pane, Window owner) {
        if (!(pane.getContent() instanceof Pane root) || !(scrollOf(root) instanceof ScrollPane scroll)
                || !(scroll.getContent() instanceof Region body)) {
            return;
        }
        boolean[] fitting = new boolean[1];
        double[] fixed = {-1};
        Runnable fit = () -> {
            Window window = pane.getScene() == null ? null : pane.getScene().getWindow();
            if (window == null || fitting[0]) {
                return;
            }
            fitting[0] = true;
            try {
                double width = window.getWidth();
                double viewport = scroll.getViewportBounds().getWidth();
                scroll.setPrefViewportHeight(body.prefHeight(viewport > 0 ? viewport : -1));
                pane.setMinHeight(Region.USE_COMPUTED_SIZE);
                pane.setPrefHeight(Region.USE_COMPUTED_SIZE);
                pane.setMaxHeight(Region.USE_COMPUTED_SIZE);
                double height = Math.min(pane.prefHeight(width), maxHeight(owner, window));
                pane.setMinHeight(height);
                pane.setPrefHeight(height);
                pane.setMaxHeight(height);
                fixed[0] = height;
                double top = window.getY() + (window.getHeight() - height) / 2;
                window.setHeight(height);
                if (owner != null && owner.getScene() != null) {
                    top = owner.getY() + owner.getScene().getY() + (owner.getScene().getHeight() - height) / 2;
                }
                window.setY(top);
            } finally {
                fitting[0] = false;
            }
        };
        fit.run();
        body.heightProperty().addListener((obs, old, value) -> Platform.runLater(fit));
        // Si algo más cambia el alto de la ventana (el diálogo se reajusta solo), vuelve al alto calculado
        Window window = pane.getScene().getWindow();
        window.heightProperty().addListener((obs, old, value) -> {
            if (fixed[0] > 0 && Math.abs(value.doubleValue() - fixed[0]) > 1) {
                Platform.runLater(() -> {
                    if (window.isShowing() && Math.abs(window.getHeight() - fixed[0]) > 1) {
                        window.setHeight(fixed[0]);
                    }
                });
            }
        });
    }

    /** El desplazamiento que envuelve el cuerpo del formulario (null si no hay). */
    private static ScrollPane scrollOf(Pane root) {
        for (Node child : root.getChildren()) {
            if (child instanceof ScrollPane scroll) {
                return scroll;
            }
        }
        return null;
    }

    /** Alto máximo de la ventana pequeña: el área visible de la ventana principal (o de la pantalla) menos el margen. */
    private static double maxHeight(Window owner, Window window) {
        if (owner != null && owner.getScene() != null) {
            return owner.getScene().getHeight() - 2 * MARGIN;
        }
        return Screen.getScreensForRectangle(window.getX(), window.getY(), window.getWidth(), 1)
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds().getHeight() - 2 * MARGIN;
    }

    /** Ancho del panel lateral: un poco más de un tercio de la ventana, entre 560 y 760 px. */
    static double panelWidth(double sceneWidth) {
        return Math.max(Math.min(sceneWidth * 0.38, 760), Math.min(560, sceneWidth - 2 * MARGIN));
    }

    /** Borde derecho del menú lateral (si no hay menú, el formulario empieza en el borde de la ventana). */
    private static double sidebarRight(Scene scene) {
        Node sidebar = scene.getRoot().lookup(".sidebar");
        if (sidebar == null || !sidebar.isVisible()) {
            return 0;
        }
        Bounds bounds = sidebar.localToScene(sidebar.getBoundsInLocal());
        return bounds.getMaxX();
    }

    private static Effect dim(Window owner) {
        if (owner == null || owner.getScene() == null) {
            return null;
        }
        Parent root = owner.getScene().getRoot();
        Effect previous = root.getEffect();
        root.setEffect(DIM);
        return previous;
    }

    private static void undim(Window owner, Effect previous) {
        if (owner != null && owner.getScene() != null) {
            owner.getScene().getRoot().setEffect(previous);
        }
    }

    /** La X hace lo mismo que Cancelar (si no hay Cancelar, cierra). */
    private static void cancel(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE && pane.lookupButton(type) instanceof Button b) {
                b.fire();
                return;
            }
        }
        dialog.getDialogPane().getScene().getWindow().hide();
    }

    private static Window ownerOf(Dialog<?> dialog) {
        Window owner = dialog.getOwner();
        if (owner != null) {
            return owner;
        }
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isFocused() && stage.getOwner() == null) {
                return stage;
            }
        }
        return null;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value == null ? "" : value);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        return label;
    }

    private static String styleOf(Kind kind) {
        return switch (kind) {
            case WINDOW -> "form-window";
            case PANEL -> "form-panel";
            case PAGE -> "form-page";
        };
    }

    private static String sheet() {
        return FormShell.class.getResource(SHEET).toExternalForm();
    }
}
