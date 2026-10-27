package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Parámetros de Planes definidos en el contrato del equipo; no sustituye valores faltantes. */
@Component
@Transactional(readOnly = true)
public class PlanParameters {

    public static final String WAITING_DAYS = "PLAN_WAITING_DAYS";
    public static final String BENEFICIARY_LIMIT = "PLAN_BENEFICIARY_LIMIT";
    public static final String NON_DIRECT_SURCHARGE = "PLAN_NON_DIRECT_SURCHARGE";
    public static final String EXTRA_BENEFICIARY_SURCHARGE = "PLAN_EXTRA_BENEFICIARY_SURCHARGE";

    private final SystemParameterService parameters;

    public PlanParameters(SystemParameterService parameters) {
        this.parameters = parameters;
    }

    public int waitingDays() {
        return nonNegativeInteger(WAITING_DAYS);
    }

    public int beneficiaryLimit() {
        return nonNegativeInteger(BENEFICIARY_LIMIT);
    }

    public BigDecimal nonDirectSurcharge() {
        return nonNegativeAmount(NON_DIRECT_SURCHARGE);
    }

    public BigDecimal extraBeneficiarySurcharge() {
        return nonNegativeAmount(EXTRA_BENEFICIARY_SURCHARGE);
    }

    private int nonNegativeInteger(String name) {
        long value = parameters.getLong(name);
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new InvalidDataException("El parámetro de planes debe ser un entero no negativo dentro del rango permitido.");
        }
        return (int) value;
    }

    private BigDecimal nonNegativeAmount(String name) {
        BigDecimal value = parameters.getBigDecimal(name);
        if (value.signum() < 0) {
            throw new InvalidDataException("El recargo de planes no puede ser negativo.");
        }
        return value;
    }
}
