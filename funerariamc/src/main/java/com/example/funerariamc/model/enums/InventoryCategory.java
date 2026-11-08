package com.example.funerariamc.model.enums;

public enum InventoryCategory {
    
    COFFIN("Ataúdes", true),
    SUPPLY("Insumos de tanatopraxia", false),
    PRODUCT("Artículos de venta", true);

    private final String displayName;
    private final boolean forSale;

    InventoryCategory(String displayName, boolean forSale) {
        this.displayName = displayName;
        this.forSale = forSale;
    }

    /** true si el vendedor lo puede vender en Contratos y ventas; false si es de uso de la funeraria. */
    public boolean isForSale() {
        return forSale;
    }

    /** Texto en español para la pantalla. */
    public String getDisplayName() {
        return displayName;
    }

    /** La categoría guardada en la base, o null si está vacía o no es una de estas. */
    public static InventoryCategory fromDb(String value) {
        if (value == null) {
            return null;
        }
        for (InventoryCategory category : values()) {
            if (category.name().equalsIgnoreCase(value.trim())) {
                return category;
            }
        }
        return null;
    }
}
