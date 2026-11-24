package com.example.funerariamc.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Un solo campo para buscar y elegir un registro (por ejemplo el titular de un plan): al escribir
 * se abre debajo la lista de lo que coincide, con lo escrito resaltado. Se elige con clic o con
 * las flechas y Enter. Si se cambia el texto después de elegir, la elección se borra, así el
 * formulario nunca guarda un registro distinto al que se ve escrito.
 * La consulta corre en otro hilo para que la ventana no se congele.
 */
public final class SearchField<T> {

    private static final int VISIBLE_ROWS = 8;
    private final TextField field = new TextField();
    private final Function<String, List<T>> search;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    private final String plural;
    private final ObjectProperty<T> value = new SimpleObjectProperty<>();
    private final Popup popup = new Popup();
    private final VBox list = new VBox(2);
    private final Label count = new Label();
    private final ScrollPane scroll = new ScrollPane(list);
    private final PauseTransition delay = new PauseTransition(Duration.millis(250));
    private final List<HBox> rows = new ArrayList<>();
    private List<T> results = List.of();
    private int highlighted = -1;
    private int request;
    /** Evita que poner el texto de la elección se tome como una búsqueda nueva. */
    private boolean choosing;

    /**
     * @param prompt texto gris del campo vacío
     * @param plural cómo se llaman los resultados en el conteo, por ejemplo "clientes"
     * @param search consulta (corre en otro hilo) con el texto escrito
     * @param label  texto principal de cada resultado
     * @param detail texto pequeño al lado (documento, etc.); puede devolver null
     */
    public SearchField(String prompt, String plural, Function<String, List<T>> search,
                       Function<T, String> label, Function<T, String> detail) {
        this.search = search;
        this.label = label;
        this.detail = detail;
        this.plural = plural;
        field.setPromptText(prompt);
        field.getStyleClass().add("search-field-input");
        count.getStyleClass().add("search-field-count");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("search-field-scroll");
        VBox card = new VBox(2, count, scroll);
        card.getStyleClass().add("search-field-popup");
        popup.getContent().add(card);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        delay.setOnFinished(e -> runSearch());
        field.textProperty().addListener((obs, old, text) -> {
            if (choosing) {
                return;
            }
            value.set(null); // el texto ya no es el de la elección
            delay.playFromStart();
        });
        // Al entrar al campo vacío se muestran los primeros resultados
        field.focusedProperty().addListener((obs, old, focused) -> {
            if (focused && value.get() == null) {
                delay.playFromStart();
            }
        });
        field.setOnMouseClicked(e -> {
            if (!popup.isShowing() && value.get() == null) {
                delay.playFromStart();
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** El campo, para ponerlo en el formulario. */
    public Node node() {
        return field;
    }

    public TextField field() {
        return field;
    }

    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    /** Pone una elección hecha por código (por ejemplo al llegar desde otra pantalla). */
    public void setValue(T item) {
        choosing = true;
        field.setText(item == null ? "" : text(item));
        choosing = false;
        value.set(item);
        popup.hide();
    }

    public void clear() {
        setValue(null);
    }

    private void runSearch() {
        String text = field.getText() == null ? "" : field.getText();
        int mine = ++request;
        Thread thread = new Thread(() -> {
            List<T> found;
            try {
                found = search.apply(text);
            } catch (RuntimeException failure) {
                Platform.runLater(() -> {
                    if (mine == request) {
                        showMessage("No se pudo buscar. Intente de nuevo.");
                    }
                });
                return;
            }
            Platform.runLater(() -> {
                if (mine == request && field.isFocused()) {
                    show(text, found == null ? List.of() : found);
                }
            });
        }, "search-field");
        thread.setDaemon(true);
        thread.start();
    }

    private void show(String text, List<T> found) {
        results = List.copyOf(found);
        rows.clear();
        list.getChildren().clear();
        String query = normalize(text).trim();
        if (results.isEmpty()) {
            showMessage(query.isEmpty() ? "No hay " + plural + " registrados." : "Ningún resultado con “" + text.trim() + "”.");
            return;
        }
        count.setText(query.isEmpty() ? "Escriba para buscar · " + results.size() + " " + plural
                : results.size() + " " + plural + " con “" + text.trim() + "”");
        for (int i = 0; i < results.size(); i++) {
            T item = results.get(i);
            TextFlow main = marked(label.apply(item), query, "search-field-text", "search-field-mark");
            HBox row = new HBox(6, main);
            String small = detail == null ? null : detail.apply(item);
            if (small != null && !small.isBlank()) {
                row.getChildren().add(marked("· " + small, query, "search-field-detail", "search-field-mark"));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("search-field-row");
            int index = i;
            row.setOnMouseEntered(e -> highlight(index));
            row.setOnMouseClicked(e -> choose(index));
            rows.add(row);
            list.getChildren().add(row);
        }
        highlight(0);
        open();
    }

    private void showMessage(String message) {
        results = List.of();
        rows.clear();
        list.getChildren().clear();
        count.setText(message);
        highlighted = -1;
        open();
    }

    private void open() {
        scroll.setPrefViewportHeight(Math.min(Math.max(rows.size(), 1), VISIBLE_ROWS) * 36.0);
        if (field.getScene() == null || field.getScene().getWindow() == null) {
            return;
        }
        Bounds bounds = field.localToScreen(field.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        VBox card = (VBox) popup.getContent().get(0);
        card.setPrefWidth(bounds.getWidth());
        if (card.getStylesheets().isEmpty()) {
            // La lista es otra ventana: toma las hojas de estilo del formulario donde está el campo
            List<String> sheets = new ArrayList<>(field.getScene().getStylesheets());
            for (javafx.scene.Parent parent = field.getParent(); parent != null; parent = parent.getParent()) {
                sheets.addAll(parent.getStylesheets());
            }
            card.getStylesheets().setAll(sheets.stream().distinct().toList());
        }
        if (popup.isShowing()) {
            popup.setX(bounds.getMinX());
            popup.setY(bounds.getMaxY() + 4);
        } else {
            popup.show(field, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case DOWN -> {
                if (!popup.isShowing()) {
                    delay.playFromStart();
                } else {
                    highlight(Math.min(highlighted + 1, rows.size() - 1));
                }
                event.consume();
            }
            case UP -> {
                highlight(Math.max(highlighted - 1, 0));
                event.consume();
            }
            case ENTER -> {
                if (popup.isShowing() && highlighted >= 0) {
                    choose(highlighted);
                    event.consume();
                }
            }
            case ESCAPE -> popup.hide();
            default -> {
            }
        }
    }

    private void highlight(int index) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        if (highlighted >= 0 && highlighted < rows.size()) {
            rows.get(highlighted).getStyleClass().remove("search-field-row-on");
        }
        highlighted = index;
        HBox row = rows.get(index);
        row.getStyleClass().add("search-field-row-on");
        // Mantiene a la vista la fila resaltada al bajar con las flechas
        double y = row.getBoundsInParent().getMinY();
        double height = list.getHeight() - scroll.getViewportBounds().getHeight();
        if (height > 0) {
            double top = scroll.getVvalue() * height;
            double bottom = top + scroll.getViewportBounds().getHeight();
            if (y < top || y + row.getHeight() > bottom) {
                scroll.setVvalue(Math.min(1, y / height));
            }
        }
    }

    private void choose(int index) {
        if (index < 0 || index >= results.size()) {
            return;
        }
        setValue(results.get(index));
    }

    private String text(T item) {
        String small = detail == null ? null : detail.apply(item);
        return label.apply(item) + (small == null || small.isBlank() ? "" : " · " + small);
    }

    /** Resalta, en cada palabra del texto, el comienzo que coincide con lo escrito. */
    static TextFlow marked(String text, String query, String textClass, String markClass) {
        TextFlow flow = new TextFlow();
        String value = text == null ? "" : text;
        if (query.isEmpty()) {
            flow.getChildren().add(styled(value, textClass));
            return flow;
        }
        String plain = normalize(value);
        int from = 0;
        int at = 0;
        while (at < value.length()) {
            boolean wordStart = at == 0 || !Character.isLetterOrDigit(plain.charAt(at - 1));
            if (wordStart && plain.startsWith(query, at)) {
                if (at > from) {
                    flow.getChildren().add(styled(value.substring(from, at), textClass));
                }
                int end = Math.min(at + query.length(), value.length());
                flow.getChildren().add(styled(value.substring(at, end), textClass, markClass));
                from = end;
                at = end;
            } else {
                at++;
            }
        }
        if (from < value.length()) {
            flow.getChildren().add(styled(value.substring(from), textClass));
        }
        return flow;
    }

    private static Text styled(String text, String... classes) {
        Text node = new Text(text);
        node.getStyleClass().addAll(classes);
        return node;
    }

    /** Minúsculas y sin tildes, del mismo largo que el texto original. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            out.append(base.isEmpty() ? c : base.charAt(0));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
