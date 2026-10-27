package com.example.funerariamc.model.enums;

public enum PlanPaymentStatus {

    PAID("Pagada"),
    VOIDED("Anulada");

    private final String displayName;

    PlanPaymentStatus(String displayName) {
        this.displayName = displayName;
    }

    
    public String getDisplayName() {
        return displayName;
    }
    
}
