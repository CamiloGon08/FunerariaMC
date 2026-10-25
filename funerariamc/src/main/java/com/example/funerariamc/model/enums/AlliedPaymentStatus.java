package com.example.funerariamc.model.enums;

/**
 * Represents the possible statuses of payments to allied funeral homes.
 * Payments can be voided, but they are never deleted.
 */
public enum AlliedPaymentStatus {

    PENDING("Pendiente"),
    PAID("Pagado"),
    VOIDED("Anulado");

    private final String displayName;

    AlliedPaymentStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the Spanish display name used for the payment status badge.
     *
     * @return the localized payment status name
     */
    public String getDisplayName() {
        return displayName;
    }
}