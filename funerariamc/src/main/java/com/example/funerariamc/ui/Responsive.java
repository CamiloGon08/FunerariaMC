package com.example.funerariamc.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ajuste de cada pantalla al tamaño de la ventana. */

public final class Responsive {

    /** Ancho mínimo cómodo de un indicador; por debajo, los indicadores pasan a dos líneas. */
    private static final double KPI_MIN_WIDTH = 180;
    private static final Set<String> KPI_CARDS = Set.of("hero-kpi", "kpi-card");
    private static final Set<String> WRAP_ROWS = Set.of("filter-card", "wrap-row");
    /** Espacio entre los controles de una barra de filtros (igual al -fx-spacing de .filter-card). */
    private static final double FILTER_GAP = 16;
    /** Marca para no volver a recorrer una pantalla que ya se ajustó (las vistas se reutilizan). */
    private static final String APPLIED = "responsive-applied";
    /** Niveles desde la raíz donde se busca el ScrollPane de la pantalla completa. */
    private static final int PAGE_SCROLL_LEVELS = 2;
    /** Marco que MainController pone alrededor de cada módulo: su ancho es el espacio real del módulo. */
    private static final String MODULE_FRAME = "module-frame";

    private Responsive() {
    }

    /** Cambia las filas de indicadores y filtros de la pantalla por filas que bajan de línea. */
    public static void apply(Node root) {
        if (root == null || root.getProperties().containsKey(APPLIED)) {
            return;
        }
        root.getProperties().put(APPLIED, Boolean.TRUE);
        // Con el CSS aplicado ya se conocen el espacio y la alineación de cada fila
        root.applyCss();
        List<HBox> kpiRows = new ArrayList<>();
        List<HBox> filterRows = new ArrayList<>();
        collect(root, kpiRows, filterRows);
        ScrollPane page = pageScroll(root, 0);
        if (page != null && page.isFitToWidth() && page.getHbarPolicy() == ScrollPane.ScrollBarPolicy.NEVER) {
            page.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        }
        kpiRows.forEach(row -> replace(row, KPI_MIN_WIDTH));
        filterRows.forEach(row -> replace(row, 0));
    }

    /**
     * Verdadero mientras el área del módulo (el marco que le da MainController) mide menos de
     * {@code width} px. Sirve para ocultar columnas de una tabla de anchos fijos en ventana angosta:
     * se mide el marco y no la tabla, así la tabla no se queda ancha por sí misma.
     */
    public static ObservableBooleanValue narrowerThan(Node node, double width) {
        BooleanProperty narrow = new SimpleBooleanProperty(false);
        ScrollPane[] frame = new ScrollPane[1];
        ChangeListener<Number> measure = (obs, old, value) ->
                narrow.set(value.doubleValue() > 0 && value.doubleValue() < width);
        Runnable attach = () -> {
            ScrollPane found = node.getScene() == null ? null : moduleFrame(node);
            if (found == frame[0]) {
                return;
            }
            if (frame[0] != null) {
                frame[0].widthProperty().removeListener(measure);
            }
            frame[0] = found;
            if (found != null) {
                found.widthProperty().addListener(measure);
                measure.changed(null, null, found.getWidth());
            }
        };
        // El módulo entra y sale del marco cada vez que se abre (las vistas se reutilizan)
        node.sceneProperty().addListener((obs, old, scene) -> attach.run());
        node.parentProperty().addListener((obs, old, parent) -> attach.run());
        attach.run();
        return narrow;
    }

    /** Oculta los nodos (y les quita el espacio) mientras {@code hidden} sea verdadero. */
    public static void hideWhen(ObservableBooleanValue hidden, Node... nodes) {
        for (Node node : nodes) {
            node.visibleProperty().bind(javafx.beans.binding.Bindings.not(hidden));
            node.managedProperty().bind(node.visibleProperty());
        }
    }

    /**
     * Rejilla de tarjetas con tantas columnas como quepan (cada una de al menos {@code minCard} px),
     * hasta {@code maxColumns}. Las tarjetas se reacomodan en el mismo orden en que se agregaron, así
     * el controlador puede seguir usando {@code grid.add(card, i % 3, i / 3)}.
     */
    public static void cardColumns(GridPane grid, double minCard, int maxColumns) {
        int[] current = {0};
        boolean[] busy = {false};
        Runnable reflow = () -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            try {
                double width = grid.getWidth() - grid.getInsets().getLeft() - grid.getInsets().getRight();
                int columns = width <= 0 ? maxColumns
                        : (int) Math.max(1, Math.min(maxColumns, Math.floor((width + grid.getHgap()) / (minCard + grid.getHgap()))));
                if (columns != current[0]) {
                    current[0] = columns;
                    List<ColumnConstraints> constraints = new ArrayList<>();
                    for (int i = 0; i < columns; i++) {
                        ColumnConstraints column = new ColumnConstraints();
                        column.setPercentWidth(100.0 / columns);
                        column.setHgrow(Priority.ALWAYS);
                        column.setFillWidth(true);
                        constraints.add(column);
                    }
                    grid.getColumnConstraints().setAll(constraints);
                }
                int index = 0;
                for (Node child : grid.getChildren()) {
                    if (!child.isManaged()) {
                        continue;
                    }
                    Integer column = index % columns;
                    Integer row = index / columns;
                    if (!Objects.equals(GridPane.getColumnIndex(child), column)) {
                        GridPane.setColumnIndex(child, column);
                    }
                    if (!Objects.equals(GridPane.getRowIndex(child), row)) {
                        GridPane.setRowIndex(child, row);
                    }
                    index++;
                }
            } finally {
                busy[0] = false;
            }
        };
        grid.widthProperty().addListener((obs, old, value) -> reflow.run());
        grid.getChildren().addListener((ListChangeListener<Node>) change -> reflow.run());
        reflow.run();
    }

    /** El marco del módulo que contiene al nodo (null si todavía no está en uno). */
    private static ScrollPane moduleFrame(Node node) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getStyleClass().contains(MODULE_FRAME)) {
                return scroll;
            }
        }
        return null;
    }

    private static void collect(Node node, List<HBox> kpiRows, List<HBox> filterRows) {
        if (node instanceof HBox box && box.getId() == null) {
            if (isKpiRow(box)) {
                kpiRows.add(box);
                return;
            }
            if (box.getStyleClass().stream().anyMatch(WRAP_ROWS::contains)) {
                filterRows.add(box);
                return;
            }
        }
        if (node instanceof ScrollPane scroll) {
            if (scroll.getContent() != null) {
                collect(scroll.getContent(), kpiRows, filterRows);
            }
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, kpiRows, filterRows);
            }
        }
    }

    /** ScrollPane que desplaza la pantalla completa: el primero desde la raíz, a pocos niveles. */
    private static ScrollPane pageScroll(Node node, int depth) {
        if (node instanceof ScrollPane scroll) {
            return scroll;
        }
        if (depth >= PAGE_SCROLL_LEVELS || !(node instanceof Parent parent)) {
            return null;
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            ScrollPane found = pageScroll(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Fila de dos o más tarjetas de indicador y nada más. */
    private static boolean isKpiRow(HBox box) {
        List<Node> children = box.getChildren();
        if (children.size() < 2) {
            return false;
        }
        for (Node child : children) {
            if (child.getStyleClass().stream().noneMatch(KPI_CARDS::contains)) {
                return false;
            }
        }
        return true;
    }

    /** Pone una {@link WrapRow} en el lugar de la fila, con los mismos hijos, estilos y restricciones. */
    private static void replace(HBox box, double minItemWidth) {
        if (!(box.getParent() instanceof Pane parent)) {
            return;
        }
        // La barra de filtros deja siempre el mismo aire entre controles (el HBox aún no tiene su CSS aplicado aquí)
        double gap = box.getStyleClass().contains("filter-card") ? Math.max(FILTER_GAP, box.getSpacing()) : box.getSpacing();
        WrapRow row = new WrapRow(gap, box.getAlignment(), box.isFillHeight(), minItemWidth);
        row.getStyleClass().setAll(box.getStyleClass());
        row.setStyle(box.getStyle());
        row.setPadding(box.getPadding());
        row.setMinWidth(box.getMinWidth());
        row.setMinHeight(box.getMinHeight());
        row.setPrefWidth(box.getPrefWidth());
        row.setPrefHeight(box.getPrefHeight());
        row.setMaxWidth(box.getMaxWidth());
        row.setMaxHeight(box.getMaxHeight());
        row.setVisible(box.isVisible());
        row.setManaged(box.isManaged());
        // VBox.vgrow, márgenes y demás restricciones del padre viajan en las propiedades del nodo
        row.getProperties().putAll(box.getProperties());
        List<Node> children = new ArrayList<>(box.getChildren());
        int index = parent.getChildren().indexOf(box);
        box.getChildren().clear();
        row.getChildren().setAll(children);
        parent.getChildren().set(index, row);
    }

    /**
     * Fila que se comporta como un HBox mientras todo cabe (los hijos con HBox.hgrow="ALWAYS" se
     * estiran) y, si no cabe, sigue en la línea de abajo. 
     */
    public static final class WrapRow extends Pane {
        /** Ancho de partida de un hijo que se estira (buscador): luego crece con el espacio que sobre. */
        private static final double GROW_BASIS = 280;
        private final double spacing;
        private final Pos alignment;
        private final boolean fillHeight;
        private final double minItemWidth;

        public WrapRow(double spacing, Pos alignment, boolean fillHeight, double minItemWidth) {
            this.spacing = spacing;
            this.alignment = alignment == null ? Pos.TOP_LEFT : alignment;
            this.fillHeight = fillHeight;
            this.minItemWidth = minItemWidth;
        }

        @Override
        public Orientation getContentBias() {
            return Orientation.HORIZONTAL;
        }

        @Override
        protected double computeMinWidth(double height) {
            double widest = 0;
            for (Node child : managed()) {
                widest = Math.max(widest, basis(child));
            }
            return snappedLeftInset() + widest + snappedRightInset();
        }

        @Override
        protected double computePrefWidth(double height) {
            List<Node> children = managed();
            double total = 0;
            for (Node child : children) {
                total += basis(child);
            }
            total += spacing * Math.max(0, children.size() - 1);
            return snappedLeftInset() + total + snappedRightInset();
        }

        @Override
        protected double computeMinHeight(double width) {
            return computePrefHeight(width);
        }

        @Override
        protected double computePrefHeight(double width) {
            double available = (width < 0 ? computePrefWidth(-1) : width) - snappedLeftInset() - snappedRightInset();
            double height = 0;
            List<List<Node>> lines = lines(available);
            for (List<Node> line : lines) {
                height += lineHeight(line, widths(line, available));
            }
            height += spacing * Math.max(0, lines.size() - 1);
            return snappedTopInset() + height + snappedBottomInset();
        }

        @Override
        protected void layoutChildren() {
            double available = getWidth() - snappedLeftInset() - snappedRightInset();
            double y = snappedTopInset();
            for (List<Node> line : lines(available)) {
                double[] widths = widths(line, available);
                double used = spacing * (line.size() - 1);
                for (double w : widths) {
                    used += w;
                }
                double x = snappedLeftInset() + offset(available - used, alignment.getHpos());
                double lineHeight = lineHeight(line, widths);
                for (int i = 0; i < line.size(); i++) {
                    Node child = line.get(i);
                    Insets margin = margin(child);
                    double w = widths[i] - margin.getLeft() - margin.getRight();
                    double room = lineHeight - margin.getTop() - margin.getBottom();
                    double h = fillHeight ? Math.min(room, child.maxHeight(w)) : child.prefHeight(w);
                    h = Math.max(h, child.minHeight(w));
                    double childY = y + margin.getTop() + offset(room - h, alignment.getVpos());
                    child.resizeRelocate(snapPositionX(x + margin.getLeft()), snapPositionY(childY),
                            snapSizeX(w), snapSizeY(h));
                    x += widths[i] + spacing;
                }
                y += lineHeight + spacing;
            }
        }

        private List<Node> managed() {
            List<Node> children = new ArrayList<>();
            for (Node child : getChildren()) {
                if (child.isManaged()) {
                    children.add(child);
                }
            }
            return children;
        }

        /** Reparte los hijos en líneas: cada línea lleva los que caben en el ancho disponible. */
        private List<List<Node>> lines(double available) {
            List<Node> children = managed();
            List<List<Node>> lines = new ArrayList<>();
            if (children.isEmpty()) {
                return lines;
            }
            if (minItemWidth > 0) {
                // Indicadores: el mismo número por línea (4, o 2 y 2) para que queden parejos
                double item = 0;
                for (Node child : children) {
                    item = Math.max(item, basis(child));
                }
                int fit = (int) Math.max(1, Math.floor((available + spacing) / (item + spacing)));
                int count = (int) Math.ceil(children.size() / (double) fit);
                int perLine = (int) Math.ceil(children.size() / (double) count);
                for (int i = 0; i < children.size(); i += perLine) {
                    lines.add(new ArrayList<>(children.subList(i, Math.min(children.size(), i + perLine))));
                }
                return lines;
            }
            List<Node> line = new ArrayList<>();
            double used = 0;
            for (Node child : children) {
                double w = basis(child);
                double needed = line.isEmpty() ? w : used + spacing + w;
                if (!line.isEmpty() && needed > available) {
                    lines.add(line);
                    line = new ArrayList<>();
                    needed = w;
                }
                line.add(child);
                used = needed;
            }
            lines.add(line);
            return lines;
        }

        /** Ancho de cada hijo en su línea: el sobrante se reparte entre los que se estiran. */
        private double[] widths(List<Node> line, double available) {
            double[] widths = new double[line.size()];
            double free = available - spacing * (line.size() - 1);
            int growing = 0;
            for (int i = 0; i < line.size(); i++) {
                widths[i] = Math.min(basis(line.get(i)), Math.max(0, available));
                free -= widths[i];
                if (grows(line.get(i))) {
                    growing++;
                }
            }
            if (free > 0 && growing > 0) {
                double extra = free / growing;
                for (int i = 0; i < line.size(); i++) {
                    Node child = line.get(i);
                    if (grows(child)) {
                        Insets margin = margin(child);
                        double max = child.maxWidth(-1) + margin.getLeft() + margin.getRight();
                        widths[i] = Math.min(widths[i] + extra, max);
                    }
                }
            }
            return widths;
        }

        private double lineHeight(List<Node> line, double[] widths) {
            double height = 0;
            for (int i = 0; i < line.size(); i++) {
                Node child = line.get(i);
                Insets margin = margin(child);
                double w = widths[i] - margin.getLeft() - margin.getRight();
                height = Math.max(height, child.prefHeight(w) + margin.getTop() + margin.getBottom());
            }
            return height;
        }

        /** Ancho de partida de un hijo (con margen): el que pide, o el mínimo de indicador. */
        private double basis(Node child) {
            Insets margin = margin(child);
            double min = child.minWidth(-1);
            double w;
            if (minItemWidth > 0) {
                w = Math.max(minItemWidth, min);
            } else if (grows(child)) {
                w = Math.max(min, Math.min(child.prefWidth(-1), GROW_BASIS));
            } else {
                w = Math.max(min, child.prefWidth(-1));
            }
            return w + margin.getLeft() + margin.getRight();
        }

        private static boolean grows(Node child) {
            return HBox.getHgrow(child) == Priority.ALWAYS || HBox.getHgrow(child) == Priority.SOMETIMES;
        }

        private static Insets margin(Node child) {
            Insets margin = HBox.getMargin(child);
            return margin == null ? Insets.EMPTY : margin;
        }

        private static double offset(double space, HPos pos) {
            if (space <= 0 || pos == HPos.LEFT) {
                return 0;
            }
            return pos == HPos.CENTER ? space / 2 : space;
        }

        private static double offset(double space, VPos pos) {
            if (space <= 0 || pos == VPos.TOP || pos == VPos.BASELINE) {
                return 0;
            }
            return pos == VPos.CENTER ? space / 2 : space;
        }
    }
}
