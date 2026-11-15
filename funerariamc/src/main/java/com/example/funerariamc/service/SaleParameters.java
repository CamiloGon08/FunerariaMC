package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Valores en pesos que usan Contratos y ventas, Cementerio, Cremaciones, Red de aliadas y Personal.
 * Si un valor no está en Parámetros del sistema se avisa; nunca se reemplaza por otro.
 */
@Component
@Transactional(readOnly = true)
public class SaleParameters {

    public static final String WAKE_ROOM_SURCHARGE = "WAKE_ROOM_SURCHARGE";
    public static final String EXTRA_BUS_FEE = "EXTRA_BUS_FEE";
    public static final String PLAN_BASIC_COFFIN_PRICE = "PLAN_BASIC_COFFIN_PRICE";
    public static final String MINIMUM_WAGE = "MINIMUM_WAGE";
    public static final String PLAN_PREMIUM_COFFIN_PRICE = "PLAN_PREMIUM_COFFIN_PRICE";
    public static final String INTERCITY_TRANSFER_PRICE_PER_KM = "INTERCITY_TRANSFER_PRICE_PER_KM";
    public static final String VAULT_LEASE_PRICE_PER_YEAR = "VAULT_LEASE_PRICE_PER_YEAR";
    public static final String DOUBLE_LOT_PRICE = "DOUBLE_LOT_PRICE";
    public static final String LOT_CONSTRUCTION_PRICE = "LOT_CONSTRUCTION_PRICE";
    public static final String OSSUARY_PRICE = "OSSUARY_PRICE";
    public static final String LOT_PREPARATION_FEE = "LOT_PREPARATION_FEE";
    public static final String CREMATION_PREPAID_PRICE = "CREMATION_PREPAID_PRICE";
    public static final String NETWORK_FEE_AMOUNT = "NETWORK_FEE_AMOUNT";
    public static final String SELLER_COMMISSION_PERCENT = "SELLER_COMMISSION_PERCENT";
    public static final String THANATOLOGIST_BONUS_PER_BODY = "THANATOLOGIST_BONUS_PER_BODY";

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

    /** Con el plan premium el ataúd puede ser más grande: el plan cubre hasta este valor. */
    public BigDecimal premiumCoffinPrice() {
        return amount(PLAN_PREMIUM_COFFIN_PRICE, "el precio del ataúd que cubre el plan premium");
    }

    /** El traslado desde otra ciudad no lo cubre el plan: se cobra por kilómetro. */
    public BigDecimal intercityPricePerKm() {
        return amount(INTERCITY_TRANSFER_PRICE_PER_KM, "el precio por kilómetro del traslado desde otra ciudad");
    }

    /** Valor de un año de arriendo de bóveda; el arriendo inicial son 4 años y se renueva por año. */
    public BigDecimal vaultLeasePerYear() {
        return amount(VAULT_LEASE_PRICE_PER_YEAR, "el valor del año de arriendo de bóveda");
    }

    public BigDecimal doubleLotPrice() {
        return amount(DOUBLE_LOT_PRICE, "el precio del lote doble de contado");
    }

    public BigDecimal lotConstructionPrice() {
        return amount(LOT_CONSTRUCTION_PRICE, "el precio de construcción de cada puesto del lote");
    }

    public BigDecimal ossuaryPrice() {
        return amount(OSSUARY_PRICE, "el precio del osario");
    }

    /** Abrir, excavar e instalar el descensor cada vez que se usa un puesto del lote. */
    public BigDecimal lotPreparationFee() {
        return amount(LOT_PREPARATION_FEE, "el valor de la adecuación del lote");
    }

    /**
     * Valor guardado o null si todavía no está definido. Solo para sugerir un valor en un formulario
     * (precio de la cremación prepagada, cuota del Remanso, comisiones de una persona nueva).
     */
    public BigDecimal suggested(String name) {
        if (!parameters.exists(name)) {
            return null;
        }
        try {
            BigDecimal value = parameters.getBigDecimal(name);
            return value.signum() < 0 ? null : value;
        } catch (InvalidDataException invalidValue) {
            return null;
        }
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
