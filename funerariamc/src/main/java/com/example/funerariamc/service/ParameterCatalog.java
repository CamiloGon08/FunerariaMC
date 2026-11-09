package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.ParameterCategory;

import java.util.List;
import java.util.Optional;

/**
 * Parámetros que el sistema lee en sus cálculos, con el nombre que ve el administrador.
 * La pantalla de Parámetros muestra todos, aunque todavía no tengan valor en la BD ("Sin definir").
 * Aquí no hay valores: solo qué es cada parámetro y qué tipo de dato acepta.
 */
public final class ParameterCatalog {

    /** Tipo de dato que acepta el parámetro. */
    public enum Kind {
        /** Número entero sin decimales (días, meses, personas). */
        WHOLE,
        /** Valor en pesos. */
        AMOUNT,
        /** Texto libre (parámetros creados por el administrador). */
        TEXT
    }

    /** Un parámetro conocido por el sistema. */
    public record Definition(String name, String title, String description,
                             ParameterCategory category, String unit, Kind kind) {
    }

    public static final List<Definition> ALL = List.of(
            new Definition(PlanParameters.WAITING_DAYS, "Período de carencia",
                    "Días que deben pasar desde la afiliación para poder usar el plan.",
                    ParameterCategory.PLAN, "días", Kind.WHOLE),
            new Definition(PlanParameters.BENEFICIARY_LIMIT, "Tope de beneficiarios",
                    "Beneficiarios incluidos sin recargo por cantidad; se cobra al superar este número.",
                    ParameterCategory.PLAN, "personas", Kind.WHOLE),
            new Definition(PlanParameters.NON_DIRECT_SURCHARGE, "Recargo por familiar no directo",
                    "Valor adicional por un beneficiario que no es familiar directo.",
                    ParameterCategory.PLAN, "COP", Kind.AMOUNT),
            new Definition(PlanParameters.EXTRA_BENEFICIARY_SURCHARGE, "Recargo por beneficiario adicional",
                    "Valor adicional por cada beneficiario por encima del tope.",
                    ParameterCategory.PLAN, "COP", Kind.AMOUNT),
            new Definition(PlanParameters.MAX_MONTHS_AHEAD, "Cuotas adelantadas",
                    "Meses que se pueden pagar por adelantado en un plan.",
                    ParameterCategory.PLAN, "meses", Kind.WHOLE),
            new Definition(PlanParameters.WAITING_SOON_DAYS, "Aviso de carencia por terminar",
                    "Días antes del fin de la carencia en que el plan se marca como \"por terminar\".",
                    ParameterCategory.PLAN, "días", Kind.WHOLE),
            new Definition(PlanParameters.MAX_START_MONTHS_AHEAD, "Inicio del plan a futuro",
                    "Meses hacia adelante en que puede empezar un plan nuevo.",
                    ParameterCategory.PLAN, "meses", Kind.WHOLE),
            new Definition(SaleParameters.PLAN_BASIC_COFFIN_PRICE, "Precio del ataúd básico del plan",
                    "Valor que cubre el plan; un ataúd mejor cobra solo la diferencia.",
                    ParameterCategory.PLAN, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.WAKE_ROOM_SURCHARGE, "Recargo de sala especial",
                    "Valor adicional al contratar una sala de velación especial.",
                    ParameterCategory.GENERAL, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.MINIMUM_WAGE, "Salario mínimo",
                    "Salario mínimo vigente; lo usan las ventas por pensión.",
                    ParameterCategory.GENERAL, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.EXTRA_BUS_FEE, "Tarifa por bus adicional",
                    "Valor de cada bus que la familia pide además del incluido.",
                    ParameterCategory.TRANSPORT, "COP", Kind.AMOUNT),
            new Definition(EmployeeService.DOC_EXPIRY_WARNING_DAYS, "Aviso de documentos por vencer",
                    "Días antes del vencimiento de licencias y documentos de vehículos en que se avisa.",
                    ParameterCategory.TRANSPORT, "días", Kind.WHOLE));

    private ParameterCatalog() {
    }

    public static Optional<Definition> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim();
        return ALL.stream().filter(d -> d.name().equals(key)).findFirst();
    }
}
