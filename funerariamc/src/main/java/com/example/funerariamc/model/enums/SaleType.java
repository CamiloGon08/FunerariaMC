package com.example.funerariamc.model.enums;

/**
 * Defines sale types.
 */
public enum SaleType {

    CONTRACT("Contrato", "Servicio cubierto por un plan exequial o una pensión"),
    PRIVATE("Particular", "Servicio pagado directamente por la familia"),
    PRODUCT("Venta", "Venta de productos sin servicio funerario");

    private final String displayName;
    private final String description;

    SaleType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Checks whether a service contract is required.
     */
    public boolean requiresServiceContract() {
        return this != PRODUCT;
    }
}