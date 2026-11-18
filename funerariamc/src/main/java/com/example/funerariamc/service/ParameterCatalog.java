package com.example.funerariamc.service;

import com.example.funerariamc.service.bot.ChatbotService;
import com.example.funerariamc.utils.enums.ParameterCategory;

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
            new Definition(PlanParameters.ARREARS_RESET_MONTHS, "Atraso que reinicia la carencia",
                    "Si el afiliado debe más de estas cuotas y se pone al día, la carencia vuelve a empezar.",
                    ParameterCategory.PLAN, "cuotas", Kind.WHOLE),
            new Definition(PlanParameters.OVERDUE_LETTER_DAYS, "Cartas por atraso",
                    "Días de atraso en que se le manda carta al afiliado, separados por coma.",
                    ParameterCategory.NOTIFICATION, "días", Kind.TEXT),
            new Definition(SaleParameters.PLAN_PREMIUM_COFFIN_PRICE, "Ataúd que cubre el plan premium",
                    "Valor del ataúd más grande que cubre el plan premium; uno mejor cobra la diferencia.",
                    ParameterCategory.PLAN, "COP", Kind.AMOUNT),
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
            new Definition(SaleParameters.INTERCITY_TRANSFER_PRICE_PER_KM, "Traslado desde otra ciudad",
                    "Valor por kilómetro para traer un difunto de otra ciudad; el plan no lo cubre.",
                    ParameterCategory.TRANSPORT, "COP/km", Kind.AMOUNT),
            new Definition(SaleParameters.VAULT_LEASE_PRICE_PER_YEAR, "Año de arriendo de bóveda",
                    "Valor de un año de arriendo; el arriendo inicial es de 4 años y se renueva por año.",
                    ParameterCategory.CEMETERY, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.DOUBLE_LOT_PRICE, "Lote doble de contado",
                    "Precio de un lote doble en el parque cementerio, pagado de contado.",
                    ParameterCategory.CEMETERY, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.LOT_CONSTRUCTION_PRICE, "Construcción por puesto",
                    "Valor de construir cada puesto del lote.",
                    ParameterCategory.CEMETERY, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.OSSUARY_PRICE, "Osario",
                    "Precio de un osario.",
                    ParameterCategory.CEMETERY, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.LOT_PREPARATION_FEE, "Adecuación del lote",
                    "Se cobra al usar un puesto: abrir, excavar e instalar el descensor.",
                    ParameterCategory.CEMETERY, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.CREMATION_PREPAID_PRICE, "Cremación prepagada",
                    "Precio de cada cremación que la funeraria compra por adelantado.",
                    ParameterCategory.GENERAL, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.NETWORK_FEE_AMOUNT, "Cuota del Remanso",
                    "Valor que se sugiere al registrar el pago de la cuota de la red de funerarias.",
                    ParameterCategory.GENERAL, "COP", Kind.AMOUNT),
            new Definition(SaleParameters.SELLER_COMMISSION_PERCENT, "Comisión del vendedor",
                    "Porcentaje sobre lo vendido que se sugiere al registrar un vendedor funerario.",
                    ParameterCategory.COMMISSION, "%", Kind.WHOLE),
            new Definition(SaleParameters.THANATOLOGIST_BONUS_PER_BODY, "Bono del tanatólogo por cuerpo",
                    "Valor fijo por cada cuerpo preparado que se sugiere al registrar un tanatólogo.",
                    ParameterCategory.COMMISSION, "COP", Kind.AMOUNT),
            new Definition(EmployeeService.DOC_EXPIRY_WARNING_DAYS, "Aviso de documentos por vencer",
                    "Días antes del vencimiento de licencias y documentos de vehículos en que se avisa.",
                    ParameterCategory.TRANSPORT, "días", Kind.WHOLE),
            new Definition(ChatbotService.FUNERAL_HOME_ADDRESS, "Dirección de la sede",
                    "Dirección que el bot de Telegram muestra en Contacto; el mapa de Transporte la usa como punto de salida de la sede.",
                    ParameterCategory.GENERAL, null, Kind.TEXT),
            new Definition(ChatbotService.FUNERAL_HOME_PHONE, "Teléfono de la sede",
                    "Teléfono que el bot de Telegram muestra en la opción Contacto.",
                    ParameterCategory.GENERAL, null, Kind.TEXT),
            new Definition(ChatbotService.FUNERAL_HOME_HOURS, "Horario de atención",
                    "Horario que el bot de Telegram muestra en la opción Contacto.",
                    ParameterCategory.GENERAL, null, Kind.TEXT));

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
