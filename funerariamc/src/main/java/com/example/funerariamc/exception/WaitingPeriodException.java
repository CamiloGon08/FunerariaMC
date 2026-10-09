package com.example.funerariamc.exception;

/** RN-08: el plan todavía no ha completado su período de carencia. */
public class WaitingPeriodException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final long daysRemaining;

    public WaitingPeriodException(long daysRemaining) {
        super("El plan está en período de carencia. Días restantes: " + daysRemaining);
        this.daysRemaining = daysRemaining;
    }

    public long getDaysRemaining() {
        return daysRemaining;
    }
}
