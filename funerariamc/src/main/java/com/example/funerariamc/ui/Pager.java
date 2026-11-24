package com.example.funerariamc.ui;

import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Barra de paginación de las listas del sistema (tablas y tarjetas), centrada debajo de la lista:
 * flechas ◀ ▶, a lo sumo 7 botones de página y, debajo, "Mostrando 21–40 de 386 clientes".
 * Siempre se ven la primera página, la última, la actual y sus vecinas; lo demás se resume en "…".
 * Al tocar "…" se puede escribir el número de página y saltar a ella.
 * Se usa de dos formas:
 * <ul>
 *   <li>En memoria ({@link #local}): la pantalla entrega con {@link #setItems} la lista ya filtrada y
 *   el Pager le devuelve solo los registros de la página que se está viendo.</li>
 *   <li>En la base de datos ({@link #remote}): la pantalla consulta una página y luego llama
 *   {@link #show}; cuando el usuario cambia de página, el Pager avisa el número de la página pedida.</li>
 * </ul>
 * Las tablas usan {@link #TABLE_PAGE} filas por página y las tarjetas {@link #CARD_PAGE}, que se
 * reparte parejo en 1, 2, 3 o 4 columnas.
 */
public final class Pager<T> {

    /** Filas por página en las tablas. */
    public static final int TABLE_PAGE = 20;
    /** Tarjetas por página en las cuadrículas. */
    public static final int CARD_PAGE = 12;
    /** Con estas páginas o menos se ven todos los números, sin "…". */
    private static final int ALL_NUMBERS_UP_TO = 5;
    private final int pageSize;
    private final String singular;
    private final String plural;
    private final Consumer<List<T>> render;
    private final IntConsumer onPage;
    private final VBox bar = new VBox(4);
    private final HBox buttons = new HBox(4);
    private final Label info = new Label();
    private final ContextMenu jumpMenu = new ContextMenu();
    private final TextField jumpField = new TextField();
    private final Label jumpOf = new Label();
    private List<T> items = List.of();
    private ScrollPane scroll;
    private int page;
    private int pages = 1;
    private long total;
    private boolean busy;

    private Pager(int pageSize, String singular, String plural, Consumer<List<T>> render, IntConsumer onPage) {
        this.pageSize = Math.max(pageSize, 1);
        this.singular = singular;
        this.plural = plural;
        this.render = render;
        this.onPage = onPage;
        info.getStyleClass().add("pager-info");
        buttons.getStyleClass().add("pager-buttons");
        buttons.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("list-pager");
        bar.setAlignment(Pos.CENTER);
        bar.getChildren().addAll(buttons, info);
        buildJumpMenu();
        setVisible(false);
    }

    /** Paginación en memoria: render recibe los registros de la página visible. */
    public static <T> Pager<T> local(int pageSize, String singular, String plural, Consumer<List<T>> render) {
        return new Pager<>(pageSize, singular, plural, render, null);
    }

    /** Paginación en la BD: onPage recibe la página que pidió el usuario (desde 0). */
    public static <T> Pager<T> remote(int pageSize, String singular, String plural, IntConsumer onPage) {
        return new Pager<>(pageSize, singular, plural, null, onPage);
    }

    /** La barra, para ponerla donde la pantalla quiera. */
    public Node bar() {
        return bar;
    }

    /** Pone la barra justo debajo de la lista, en el mismo contenedor (sin tocar el FXML). */
    public Pager<T> below(Node list) {
        if (list.getParent() instanceof Pane parent) {
            int index = parent.getChildren().indexOf(list);
            parent.getChildren().add(index + 1, bar);
        }
        return this;
    }

    /** Al cambiar de página, la barra de desplazamiento de las tarjetas vuelve arriba. */
    public Pager<T> scrollTopOf(ScrollPane scrollPane) {
        this.scroll = scrollPane;
        return this;
    }

    /** Lista completa (ya filtrada) en memoria. Si la página actual sigue existiendo, se queda en ella. */
    public void setItems(List<? extends T> all) {
        items = all == null ? List.of() : new ArrayList<>(all);
        int count = pageCount(items.size());
        drawLocal(Math.min(page, count - 1));
    }

    /** Igual que setItems, pero vuelve a la página 1 (un filtro o búsqueda nueva). */
    public void setItemsFromStart(List<? extends T> all) {
        page = 0;
        setItems(all);
    }

    /** Todos los registros filtrados (por ejemplo, para ir al anterior o siguiente desde el detalle). */
    public List<T> items() {
        return items;
    }

    /** Paginación en la BD: muestra la página que llegó de la consulta. */
    public void show(int pageIndex, int totalPages, long totalRecords) {
        update(pageIndex, Math.max(totalPages, 1), totalRecords);
    }

    /** Página que se está viendo (desde 0). */
    public int page() {
        return page;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Mientras llega una consulta, los botones quedan quietos para no pedir dos páginas a la vez. */
    public void setBusy(boolean busy) {
        this.busy = busy;
        buttons.setDisable(busy);
    }

    /** Lo que el usuario pidió con una flecha, un número o "Ir a la página". */
    private void goTo(int wanted) {
        int target = Math.max(0, Math.min(wanted, pages - 1));
        if (busy || target == page) {
            return;
        }
        if (onPage != null) {
            page = target;
            drawButtons();
            onPage.accept(target);
        } else {
            drawLocal(target);
        }
        if (scroll != null) {
            scroll.setVvalue(0);
        }
    }

    private void drawLocal(int pageIndex) {
        int count = pageCount(items.size());
        int current = Math.max(0, Math.min(pageIndex, count - 1));
        update(current, count, items.size());
        if (render == null) {
            return;
        }
        int from = Math.min(current * pageSize, items.size());
        int to = Math.min(from + pageSize, items.size());
        render.accept(List.copyOf(items.subList(from, to)));
    }

    private void update(int pageIndex, int pageCount, long totalRecords) {
        pages = Math.max(pageCount, 1);
        page = Math.max(0, Math.min(pageIndex, pages - 1));
        total = totalRecords;
        setVisible(total > 0);
        long from = total == 0 ? 0 : (long) page * pageSize + 1;
        long to = Math.min((long) (page + 1) * pageSize, total);
        info.setText("Mostrando " + from + "–" + to + " de " + total + " " + (total == 1 ? singular : plural));
        drawButtons();
    }

    /** Flechas, números y "…": con pocas páginas se ven todas; con muchas, a lo sumo 7 botones. */
    private void drawButtons() {
        buttons.getChildren().clear();
        // Con una sola página no hacen falta los botones, solo el conteo.
        buttons.setVisible(pages > 1);
        buttons.setManaged(pages > 1);
        if (pages <= 1) {
            return;
        }
        buttons.getChildren().add(arrow("◀", page - 1, page == 0));
        for (Integer number : visiblePages(page, pages)) {
            buttons.getChildren().add(number == null ? dots() : number(number));
        }
        buttons.getChildren().add(arrow("▶", page + 1, page == pages - 1));
    }

    /**
     * Números de página (desde 0) que se ven; null marca un "…". Siempre van la primera, la última,
     * la actual y sus vecinas; cerca de un extremo se muestran tres seguidas (1 2 3 … 20).
     */
    static List<Integer> visiblePages(int current, int count) {
        List<Integer> shown = new ArrayList<>();
        if (count <= ALL_NUMBERS_UP_TO) {
            for (int i = 0; i < count; i++) {
                shown.add(i);
            }
            return shown;
        }
        int from = Math.max(1, current - 1);
        int to = Math.min(count - 2, current + 1);
        if (current <= 1) {
            to = 2;
        }
        if (current >= count - 2) {
            from = count - 3;
        }
        shown.add(0);
        if (from > 1) {
            shown.add(null);
        }
        for (int i = from; i <= to; i++) {
            shown.add(i);
        }
        if (to < count - 2) {
            shown.add(null);
        }
        shown.add(count - 1);
        return shown;
    }

    private Button number(int index) {
        Button button = new Button(String.valueOf(index + 1));
        button.getStyleClass().add("pager-number");
        if (index == page) {
            button.getStyleClass().add("pager-current");
        }
        button.setOnAction(e -> goTo(index));
        return button;
    }

    private Button arrow(String text, int target, boolean off) {
        Button button = new Button(text);
        button.getStyleClass().add("pager-arrow");
        button.setDisable(off);
        button.setOnAction(e -> goTo(target));
        return button;
    }

    /** "…": abre el cuadrito para escribir la página. */
    private Button dots() {
        Button button = new Button("…");
        button.getStyleClass().add("pager-dots");
        button.setOnAction(e -> {
            jumpField.clear();
            jumpOf.setText("de " + pages);
            jumpMenu.show(button, Side.BOTTOM, 0, 6);
            jumpField.requestFocus();
        });
        return button;
    }

    private void buildJumpMenu() {
        Label title = new Label("Ir a la página");
        title.getStyleClass().add("pager-jump-label");
        jumpField.getStyleClass().add("pager-jump-field");
        jumpField.setPrefColumnCount(3);
        // Solo números
        jumpField.textProperty().addListener((obs, old, text) -> {
            if (text != null && !text.matches("\\d{0,4}")) {
                jumpField.setText(old);
            }
        });
        jumpOf.getStyleClass().add("pager-jump-label");
        Button go = new Button("Ir");
        go.getStyleClass().add("pager-jump-go");
        Runnable jump = () -> {
            String text = jumpField.getText();
            jumpMenu.hide();
            if (text != null && !text.isBlank()) {
                goTo(Integer.parseInt(text) - 1);
            }
        };
        go.setOnAction(e -> jump.run());
        jumpField.setOnAction(e -> jump.run());
        HBox row = new HBox(8, title, jumpField, go, jumpOf);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("pager-jump");
        CustomMenuItem item = new CustomMenuItem(row, false);
        item.getStyleClass().add("pager-jump-item");
        jumpMenu.getItems().add(item);
        jumpMenu.getStyleClass().add("pager-jump-menu");
        row.setMinWidth(Region.USE_PREF_SIZE);
    }

    private void setVisible(boolean visible) {
        bar.setVisible(visible);
        bar.setManaged(visible);
    }

    private int pageCount(int count) {
        return Math.max((count + pageSize - 1) / pageSize, 1);
    }
}
