package com.example.funerariamc.utils.enums;

/** Estado de una venta.*/
public enum SaleStatus {
    ACTIVE("Vigente"),
    VOIDED("Anulada");

    private final String displayName;

    SaleStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Texto en español para la pantalla. */
    public String getDisplayName() {
        return displayName;
    }
}
