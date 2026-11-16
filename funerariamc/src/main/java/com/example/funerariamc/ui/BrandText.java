package com.example.funerariamc.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Label;

/**
 * Etiqueta con el nombre o la frase de la funeraria. 
 */
public class BrandText extends Label {

    private final StringProperty template = new SimpleStringProperty(this, "template", "{name}");

    public BrandText() {
        textProperty().bind(Bindings.createStringBinding(() -> BrandTheme.fill(template.get(), BrandTheme.brand()),
                template, BrandTheme.brandProperty()));
        visibleProperty().bind(Bindings.createBooleanBinding(() -> !BrandTheme.hidesTagline(template.get(), BrandTheme.brand()),
                template, BrandTheme.brandProperty()));
        managedProperty().bind(visibleProperty());
    }

    public StringProperty templateProperty() {
        return template;
    }

    public String getTemplate() {
        return template.get();
    }

    public void setTemplate(String value) {
        template.set(value);
    }
}
