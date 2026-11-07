package com.example.funerariamc.service;

import com.example.funerariamc.model.Vault;
import com.example.funerariamc.model.enums.VaultStatus;

import java.time.LocalDate;

/**
 * Estado de una bóveda tal como se pinta en el muro (propuesta P-17). Sale de vault.status
 * y de la fecha de vencimiento del arriendo, sin escribir en la BD: el cambio a OVERDUE
 * lo hace el proceso diario de notificaciones (RF-BOV-03, RN-05).
 */
public enum VaultState {
    AVAILABLE("Libre"),
    OCCUPIED("Ocupada"),
    EXPIRING("Vence pronto"),
    OVERDUE("En mora");

    /** Días antes del vencimiento en que la bóveda se marca "Vence pronto" (RN-04). */
    public static final int EXPIRING_DAYS = 30;

    private final String label;

    VaultState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static VaultState of(Vault vault, LocalDate today) {
        return of(vault.getStatus(), vault.getLeaseEndDate(), today);
    }

    /** Lo mismo con los datos sueltos, para las filas que se leen como proyección. */
    public static VaultState of(VaultStatus status, LocalDate leaseEnd, LocalDate today) {
        if (status == null || status == VaultStatus.AVAILABLE) {
            return AVAILABLE;
        }
        if (status == VaultStatus.OVERDUE || status == VaultStatus.EXPIRED) {
            return OVERDUE;
        }
        if (leaseEnd == null) {
            return OCCUPIED;
        }
        if (leaseEnd.isBefore(today)) {
            return OVERDUE;
        }
        return leaseEnd.isAfter(today.plusDays(EXPIRING_DAYS)) ? OCCUPIED : EXPIRING;
    }
}
