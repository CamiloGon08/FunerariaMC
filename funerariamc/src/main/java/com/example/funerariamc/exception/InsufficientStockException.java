package com.example.funerariamc.exception;

/** RN-24: la cantidad solicitada supera las existencias disponibles. */
public class InsufficientStockException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final long availableQuantity;

    public InsufficientStockException(long availableQuantity) {
        super("Existencias insuficientes. Cantidad disponible: " + availableQuantity);
        this.availableQuantity = availableQuantity;
    }

    public long getAvailableQuantity() {
        return availableQuantity;
    }
}
