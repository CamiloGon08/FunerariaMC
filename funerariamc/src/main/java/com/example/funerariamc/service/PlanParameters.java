package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Parámetros de Planes; si falta un valor se avisa, nunca se reemplaza por otro. */
@Component
@Transactional(readOnly = true)
public class PlanParameters {

    public static final String WAITING_DAYS = "PLAN_WAITING_DAYS";
    public static final String BENEFICIARY_LIMIT = "PLAN_BENEFICIARY_LIMIT";
    public static final String NON_DIRECT_SURCHARGE = "PLAN_NON_DIRECT_SURCHARGE";
    public static final String EXTRA_BENEFICIARY_SURCHARGE = "PLAN_EXTRA_BENEFICIARY_SURCHARGE";
    public static final String MAX_MONTHS_AHEAD = "PLAN_MAX_MONTHS_AHEAD";
    public static final String WAITING_SOON_DAYS = "PLAN_WAITING_SOON_DAYS";
    public static final String MAX_START_MONTHS_AHEAD = "PLAN_MAX_START_MONTHS_AHEAD";
    public static final String ARREARS_RESET_MONTHS = "PLAN_ARREARS_RESET_MONTHS";
    public static final String OVERDUE_LETTER_DAYS = "PLAN_OVERDUE_LETTER_DAYS";
    /** Cuota mensual de cada plan; al afiliar o cambiar de plan se llena sola con este valor. */
    public static final String NORMAL_MONTHLY_FEE = "PLAN_NORMAL_MONTHLY_FEE";
    public static final String PREMIUM_MONTHLY_FEE = "PLAN_PREMIUM_MONTHLY_FEE";

    /** Nombres de los dos planes que maneja la funeraria. */
    public static final String NORMAL_PLAN = "Plan Normal";
    public static final String PREMIUM_PLAN = "Plan Premium";
    public static final List<String> PLAN_NAMES = List.of(NORMAL_PLAN, PREMIUM_PLAN);

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

    public int maxMonthsAhead() {
        return nonNegativeInteger(MAX_MONTHS_AHEAD);
    }

    public int waitingSoonDays() {
        return nonNegativeInteger(WAITING_SOON_DAYS);
    }

    public int maxStartMonthsAhead() {
        return nonNegativeInteger(MAX_START_MONTHS_AHEAD);
    }

    /** Cuotas atrasadas a partir de las cuales, al ponerse al día, la carencia vuelve a empezar. */
    public int arrearsResetMonths() {
        return nonNegativeInteger(ARREARS_RESET_MONTHS);
    }

    /** Días de atraso en que se le manda carta al afiliado (por ejemplo "30,60,90"), de menor a mayor. */
    public List<Integer> overdueLetterDays() {
        String text = parameters.getString(OVERDUE_LETTER_DAYS);
        try {
            List<Integer> days = Arrays.stream(text.split("[,;\\s]+"))
                    .filter(part -> !part.isBlank())
                    .map(Integer::parseInt)
                    .distinct()
                    .sorted()
                    .toList();
            if (days.isEmpty() || days.stream().anyMatch(day -> day <= 0)) {
                throw new NumberFormatException();
            }
            return days;
        } catch (NumberFormatException e) {
            throw new InvalidDataException("Los días de las cartas de mora deben ser números separados por coma, por ejemplo 30,60,90.");
        }
    }

    /** Cuota mensual del plan (normal o premium) guardada en Parámetros. */
    public BigDecimal monthlyFee(String planName) {
        return nonNegativeAmount(isPremium(planName) ? PREMIUM_MONTHLY_FEE : NORMAL_MONTHLY_FEE);
    }

    /** Valor del ataúd que cubre el plan: el premium cubre uno más grande. */
    public BigDecimal coveredCoffinPrice(String planName) {
        return nonNegativeAmount(isPremium(planName) ? SaleParameters.PLAN_PREMIUM_COFFIN_PRICE
                : SaleParameters.PLAN_BASIC_COFFIN_PRICE);
    }

    /** Recargo de la sala especial; el plan premium no lo paga. */
    public BigDecimal specialRoomSurcharge() {
        return nonNegativeAmount(SaleParameters.WAKE_ROOM_SURCHARGE);
    }

    /** El plan premium permite un ataúd y una sala más grandes. */
    public static boolean isPremium(String planName) {
        return planName != null && planName.trim().equalsIgnoreCase(PREMIUM_PLAN);
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
            throw new InvalidDataException("Los valores de planes en Parámetros no pueden ser negativos.");
        }
        return value;
    }
}
