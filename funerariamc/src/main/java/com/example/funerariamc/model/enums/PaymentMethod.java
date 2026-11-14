package com.example.funerariamc.model.enums;

/**
 * Medio con el que se le paga a una funeraria aliada.
 * La funeraria paga por transferencia o consignando en la cuenta corriente de la aliada.
 */
public enum PaymentMethod {

    TRANSFER("Transferencia"),
    DEPOSIT("Consignación");

    private final String displayName;

    PaymentMethod(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Devuelve el nombre en español que se muestra en pantalla.
     */
    public String getDisplayName() {
        return displayName;
    }
}
