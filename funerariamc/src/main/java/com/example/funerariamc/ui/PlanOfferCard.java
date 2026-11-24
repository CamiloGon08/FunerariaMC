package com.example.funerariamc.ui;

import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Tarjeta de un plan exequial con su cuota y lo que incluye (ataúd, sala, transporte,
 * beneficiarios, carencia). Se usa al afiliar y al cambiar de plan, para que el vendedor le
 * muestre al cliente qué trae cada plan. Puede marcarse como elegida.
 */
public final class PlanOfferCard extends VBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private final Label title = new Label();
    private final Label fee = new Label();
    private final VBox list = new VBox(5);

    /**
     * @param name     nombre del plan
     * @param feeText  cuota ya escrita, por ejemplo "$ 35.000 / mes"; null si no está definida
     * @param includes lo que incluye, una frase por línea
     */
    public PlanOfferCard(String name, String feeText, List<String> includes) {
        super(6);
        getStyleClass().add("plan-offer");
        title.getStyleClass().add("plan-offer-title");
        fee.getStyleClass().add("plan-offer-fee");
        list.getStyleClass().add("plan-offer-list");
        Label caption = new Label("INCLUYE");
        caption.getStyleClass().add("plan-offer-caption");
        getChildren().addAll(title, fee, caption, list);
        setMaxWidth(Double.MAX_VALUE);
        show(name, feeText, includes);
    }

    /** Cambia el plan que muestra la tarjeta (por ejemplo al elegir otro tipo en la lista). */
    public void show(String name, String feeText, List<String> includes) {
        title.setText(name == null ? "" : name);
        fee.setText(feeText == null ? "Cuota sin definir en Parámetros" : feeText);
        list.getChildren().clear();
        for (String line : includes == null ? List.<String>of() : includes) {
            Label check = new Label("✓");
            check.getStyleClass().add("plan-offer-check");
            check.setMinWidth(Region.USE_PREF_SIZE);
            Label text = new Label(line);
            text.getStyleClass().add("plan-offer-item");
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            list.getChildren().add(new HBox(8, check, text));
        }
    }

    public void setSelected(boolean selected) {
        pseudoClassStateChanged(SELECTED, selected);
    }
}
