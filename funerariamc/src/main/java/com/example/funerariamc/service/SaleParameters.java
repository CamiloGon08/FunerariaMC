package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Parámetros de Contratos y ventas definidos por el equipo, sin valores de negocio alternativos. */
@Component
@Transactional(readOnly = true)
public class SaleParameters {

    public static final String WAKE_ROOM_SURCHARGE = "WAKE_ROOM_SURCHARGE";
    public static final String EXTRA_BUS_FEE = "EXTRA_BUS_FEE";
    public static final String PLAN_BASIC_COFFIN_PRICE = "PLAN_BASIC_COFFIN_PRICE";
    public static final String MINIMUM_WAGE = "MINIMUM_WAGE";

    private final SystemParameterService parameters;

    public SaleParameters(SystemParameterService parameters) {
        this.parameters = parameters;
    }

    public BigDecimal wakeRoomSurcharge() {
        return amount(WAKE_ROOM_SURCHARGE, "el recargo de sala especial");
    }

    public BigDecimal extraBusFee() {
        return amount(EXTRA_BUS_FEE, "la tarifa del bus adicional");
    }

    public BigDecimal basicCoffinPrice() {
        return amount(PLAN_BASIC_COFFIN_PRICE, "el precio del ataúd básico del plan");
    }

    public BigDecimal minimumWage() {
        return amount(MINIMUM_WAGE, "el salario mínimo");
    }

    private BigDecimal amount(String name, String description) {
        BigDecimal value;
        try {
            value = parameters.getBigDecimal(name);
        } catch (InvalidDataException invalidConfiguration) {
            throw new InvalidDataException("No se pudo leer " + description
                    + ". Revise su configuración en Parámetros del sistema (" + name + ").");
        }
        if (value.signum() < 0) {
            throw new InvalidDataException("El parámetro " + name + " no puede ser negativo.");
        }
        return value;
    }
}
