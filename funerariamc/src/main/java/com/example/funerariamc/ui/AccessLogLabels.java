package com.example.funerariamc.ui;

import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AppModule;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Traduce las acciones de access_log (guardadas en inglés) a un texto en español
 * para la columna "Acción" de la bitácora. En la BD no cambia nada.
 */
public final class AccessLogLabels {

    private static final Map<String, String> FIXED = Map.of(
            AccessLogService.LOGIN_SUCCESS, "Inicio de sesión",
            AccessLogService.LOGOUT, "Cierre de sesión",
            AccessLogService.LOGIN_FAILED_WRONG_PASSWORD, "Intento de ingreso fallido: contraseña incorrecta",
            AccessLogService.LOGIN_FAILED_DISABLED, "Intento de ingreso fallido: cuenta desactivada",
            AccessLogService.LOGIN_FAILED_INACTIVE, "Intento de ingreso fallido: empleado inactivo",
            AccessLogService.LOGIN_FAILED_NO_ROLE, "Intento de ingreso fallido: usuario sin rol",
            AccessLogService.PASSWORD_RESET_FAILED, "Recuperación de contraseña: código incorrecto",
            AccessLogService.PASSWORD_RESET_COMPLETED, "Contraseña restablecida",
            AccessLogService.PASSWORD_CHANGED, "Cambio de la contraseña temporal por una propia");

    /** Acciones del módulo de Personal: guardan el documento del empleado después del prefijo. */
    private static final Map<String, String> EMPLOYEE_ACTIONS = Map.of(
            AccessLogService.EMPLOYEE_CREATED, "Registro del empleado con documento ",
            AccessLogService.EMPLOYEE_UPDATED, "Actualización del empleado con documento ",
            AccessLogService.EMPLOYEE_DEACTIVATED, "Desactivación del empleado con documento ",
            AccessLogService.EMPLOYEE_ACTIVATED, "Activación del empleado con documento ");

    /** Acciones del módulo de Clientes: guardan el documento del cliente después del prefijo. */
    private static final Map<String, String> CLIENT_ACTIONS = Map.of(
            AccessLogService.CLIENT_CREATED, "Registro del cliente con documento ",
            AccessLogService.CLIENT_UPDATED, "Actualización del cliente con documento ");

    /** Cuentas de acceso creadas desde Personal: guardan el usuario después del prefijo. */
    private static final Map<String, String> ACCOUNT_ACTIONS = Map.of(
            AccessLogService.USER_ACCOUNT_CREATED, "Creación de la cuenta de acceso del usuario ");

    /** Acciones del módulo Cementerio y bóvedas: guardan el código de la bóveda después del prefijo. */
    private static final Map<String, String> VAULT_ACTIONS = Map.of(
            AccessLogService.VAULT_ASSIGNED, "Asignación de la bóveda ",
            AccessLogService.VAULT_LEASE_RENEWED, "Renovación del arriendo de la bóveda ",
            AccessLogService.EXHUMATION_REGISTERED, "Exhumación registrada en la bóveda ");

    /** Acciones del módulo Difuntos: guardan el código del difunto (DF-0042) y, a veces, un dato más. */
    private static final Map<String, String> DECEASED_ACTIONS = Map.of(
            AccessLogService.DECEASED_REGISTERED, "Registro del difunto ",
            AccessLogService.DEATH_DOCUMENT_ADDED, "Documento agregado al difunto ",
            AccessLogService.DECEASED_PLAN_LINKED, "Plan exequial vinculado al difunto ");

    /** Acciones del módulo Planes exequiales: guardan el código del plan (PL-0412) y, a veces, un dato más. */
    private static final Map<String, String> PLAN_ACTIONS = Map.of(
            AccessLogService.PLAN_REGISTERED, "Afiliación del plan ",
            AccessLogService.BENEFICIARY_ADDED, "Beneficiario agregado al plan ",
            AccessLogService.PLAN_PAYMENT_REGISTERED, "Pago de cuota del plan ",
            AccessLogService.PLAN_PAYMENT_VOIDED, "Cuota anulada del plan ",
            AccessLogService.PLAN_SUSPENDED, "Suspensión del plan ",
            AccessLogService.PLAN_REACTIVATED, "Reactivación del plan ",
            AccessLogService.PLAN_CANCELLED, "Cancelación del plan ",
            AccessLogService.PLAN_HOLDER_CHANGED, "Cambio de titular del plan ");

    /** Acciones del módulo Salas de velación: guardan el nombre de la sala o el número del contrato y la sala. */
    private static final Map<String, String> WAKE_ROOM_ACTIONS = Map.of(
            AccessLogService.WAKE_ROOM_REGISTERED, "Registro de la sala de velación ",
            AccessLogService.WAKE_ROOM_ASSIGNED, "Sala de velación asignada al contrato ",
            AccessLogService.WAKE_ROOM_CHANGED, "Cambio de sala de velación del contrato ");

    /** Acciones del módulo Contratos y ventas: guardan el número del contrato o de la venta y, a veces, un dato más. */
    private static final Map<String, String> SALE_ACTIONS = Map.of(
            AccessLogService.CONTRACT_REGISTERED, "Registro del contrato ",
            AccessLogService.SALE_REGISTERED, "Registro de la venta N.° ",
            AccessLogService.SALE_PAYMENT_REGISTERED, "Abono a la venta N.° ",
            AccessLogService.SALE_VOIDED, "Anulación de la venta N.° ",
            AccessLogService.PREPARATION_REGISTERED, "Preparación del cuerpo registrada en el contrato ");

    /** Acciones del módulo Inventario: guardan el código del artículo (IN-0012) y, en los movimientos, el tipo y las unidades. */
    private static final Map<String, String> INVENTORY_ACTIONS = Map.of(
            AccessLogService.INVENTORY_ITEM_REGISTERED, "Registro del artículo de inventario ",
            AccessLogService.INVENTORY_ITEM_UPDATED, "Actualización del artículo de inventario ");
    /** Acciones del módulo Transporte: guardan la placa o el número del contrato y, a veces, las placas o la parada. */
    private static final Map<String, String> TRANSPORT_ACTIONS = Map.of(
            AccessLogService.VEHICLE_REGISTERED, "Registro del vehículo ",
            AccessLogService.VEHICLE_UPDATED, "Actualización del vehículo ",
            AccessLogService.VEHICLE_MAINTENANCE, "Vehículo enviado a mantenimiento ",
            AccessLogService.VEHICLE_AVAILABLE, "Vehículo disponible de nuevo ",
            AccessLogService.TRANSPORT_ASSIGNED, "Traslado asignado al contrato ",
            AccessLogService.TRANSPORT_STARTED, "Salida del traslado del contrato ",
            AccessLogService.TRANSPORT_ARRIVED, "Llegada a una parada del traslado del contrato ",
            AccessLogService.TRANSPORT_COMPLETED, "Traslado completado del contrato ",
            AccessLogService.TRANSPORT_CANCELLED, "Traslado cancelado del contrato ");
    /** Acciones del módulo Cremaciones: guardan el código del lote (L-0033) y, a veces, el del difunto y el certificado. */
    private static final Map<String, String> CREMATION_ACTIONS = Map.ofEntries(
            Map.entry(AccessLogService.CREMATION_BATCH_SCHEDULED, "Lote de cremación programado "),
            Map.entry(AccessLogService.CREMATION_BATCH_RESCHEDULED, "Lote de cremación reprogramado "),
            Map.entry(AccessLogService.CREMATION_ASSIGNED, "Difunto agregado al lote de cremación "),
            Map.entry(AccessLogService.CREMATION_REMOVED, "Difunto quitado del lote de cremación "),
            Map.entry(AccessLogService.CREMATION_BATCH_STARTED, "Lote de cremación entregado al horno "),
            Map.entry(AccessLogService.CREMATION_REGISTERED, "Certificado de cremación registrado en el lote "),
            Map.entry(AccessLogService.CREMATION_PROVIDER_SAVED, "Proveedor de cremación guardado "),
            Map.entry(AccessLogService.CREMATION_PURCHASE_REQUESTED, "Compra de cremaciones solicitada "),
            Map.entry(AccessLogService.CREMATION_PURCHASE_CONFIRMED, "Compra de cremaciones confirmada "),
            Map.entry(AccessLogService.CREMATION_PURCHASE_RECEIVED, "Compra de cremaciones recibida "),
            Map.entry(AccessLogService.CREMATION_PURCHASE_CANCELLED, "Compra de cremaciones anulada "));

    /** Parámetros del sistema: guardan el nombre del parámetro, el valor anterior y el nuevo ("PLAN_WAITING_DAYS 62 -> 45"). */
    private static final Map<String, String> PARAMETER_ACTIONS = Map.of(
            AccessLogService.SYSTEM_PARAMETER_UPDATED, "Cambio del parámetro ",
            AccessLogService.SYSTEM_PARAMETER_CREATED, "Creación del parámetro ");

    /** Tipos de venta que se guardan en inglés después del número. */
    private static final Map<String, String> SALE_KINDS = Map.of(
            "PLAN", "· contrato con plan",
            "PENSION", "· contrato por pensión",
            "PRIVATE", "· particular",
            "PRODUCT", "· venta de productos");

    private static final Map<String, String> CHANNELS = Map.of(
            "EMAIL", "correo",
            "TELEGRAM", "Telegram");

    private AccessLogLabels() {
    }

    /** Texto para mostrar; si la acción no se reconoce, se deja tal como está guardada. */
    public static String of(String action) {
        if (action == null) {
            return "";
        }
        String fixed = FIXED.get(action);
        if (fixed != null) {
            return fixed;
        }
        if (action.startsWith(AccessLogService.OPEN_MODULE)) {
            return "Ingreso a " + moduleLabel(action.substring(AccessLogService.OPEN_MODULE.length()));
        }
        if (action.startsWith(AccessLogService.ACCESS_DENIED)) {
            return "Acceso denegado a " + moduleLabel(action.substring(AccessLogService.ACCESS_DENIED.length()));
        }
        if (action.startsWith(AccessLogService.PASSWORD_RESET_REQUESTED)) {
            String channel = action.substring(AccessLogService.PASSWORD_RESET_REQUESTED.length()).trim();
            return "Solicitud de recuperación de contraseña por " + CHANNELS.getOrDefault(channel, channel);
        }
        for (Map<String, String> actions : List.of(EMPLOYEE_ACTIONS, CLIENT_ACTIONS, ACCOUNT_ACTIONS, VAULT_ACTIONS,
                DECEASED_ACTIONS, PLAN_ACTIONS, WAKE_ROOM_ACTIONS, CREMATION_ACTIONS)) {
            for (Map.Entry<String, String> entry : actions.entrySet()) {
                if (action.startsWith(entry.getKey())) {
                    return entry.getValue() + details(action.substring(entry.getKey().length()).trim());
                }
            }
        }
        // Inventario: artículos (código IN-0012) y movimientos (código, tipo y unidades).
        for (Map.Entry<String, String> entry : INVENTORY_ACTIONS.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                return entry.getValue() + action.substring(entry.getKey().length()).trim();
            }
        }
        if (action.startsWith(AccessLogService.INVENTORY_MOVEMENT_REGISTERED)) {
            return movementDetails(action.substring(AccessLogService.INVENTORY_MOVEMENT_REGISTERED.length()).trim());
        }
        for (Map.Entry<String, String> entry : SALE_ACTIONS.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                return entry.getValue() + saleDetails(action.substring(entry.getKey().length()).trim());
            }
        }
        for (Map.Entry<String, String> entry : PARAMETER_ACTIONS.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                return entry.getValue() + parameterDetails(action.substring(entry.getKey().length()).trim());
            }
        }
        String finance = financeDetails(action);
        if (finance != null) {
            return finance;
        }
        String allied = alliedDetails(action);
        if (allied != null) {
            return allied;
        }
        for (Map.Entry<String, String> entry : TRANSPORT_ACTIONS.entrySet()) {
            if (action.startsWith(entry.getKey())) {
                return entry.getValue() + action.substring(entry.getKey().length()).trim();
            }
        }
        return action;
    }

    /** "CT-2026-0001 PLAN" pasa a "CT-2026-0001 · contrato con plan"; el valor del abono va en pesos. */
    private static String saleDetails(String text) {
        String[] parts = text.split(" ");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            String kind = SALE_KINDS.get(part);
            if (kind != null) {
                result.append(' ').append(kind);
            } else if (part.matches("\\d+(\\.\\d+)?")) {
                java.math.BigDecimal value = new java.math.BigDecimal(part).setScale(0, java.math.RoundingMode.HALF_UP);
                result.append(" · $ ").append(java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO"))
                        .format(value));
            } else {
                result.append(' ').append(part);
            }
        }
        return result.toString();
    }

    /**
     * "PLAN_WAITING_DAYS 62 -> 45" pasa a "Período de carencia: de 62 a 45"; si el valor anterior es "-",
     * "Precio del ataúd básico del plan: 1200000". Los nombres que no están en el catálogo se dejan igual.
     */
    private static String parameterDetails(String text) {
        int space = text.indexOf(' ');
        String name = space < 0 ? text : text.substring(0, space);
        String title = com.example.funerariamc.service.ParameterCatalog.find(name)
                .map(com.example.funerariamc.service.ParameterCatalog.Definition::title).orElse(name);
        if (space < 0) {
            return title;
        }
        String[] values = text.substring(space + 1).split(" -> ", 2);
        if (values.length < 2) {
            return title + " " + text.substring(space + 1);
        }
        return "-".equals(values[0]) ? title + ": " + values[1] : title + ": de " + values[0] + " a " + values[1];
    }

    /**
     * Finanzas: "MV-0012 INCOME 150000.00" pasa a "Registro del movimiento MV-0012 · ingreso · $ 150.000";
     * la comisión guarda mes, documento y valor; la exportación, formato y fechas. Devuelve null si no es de Finanzas.
     */
    private static String financeDetails(String action) {
        if (action.startsWith(AccessLogService.FINANCIAL_TRANSACTION_REGISTERED)) {
            String[] parts = action.substring(AccessLogService.FINANCIAL_TRANSACTION_REGISTERED.length()).trim().split(" ");
            StringBuilder text = new StringBuilder("Registro del movimiento ").append(parts[0]);
            if (parts.length > 1) {
                text.append(" · ").append("EXPENSE".equals(parts[1]) ? "egreso" : "ingreso");
            }
            if (parts.length > 2) {
                text.append(money(parts[2]));
            }
            return text.toString();
        }
        if (action.startsWith(AccessLogService.FINANCIAL_TRANSACTION_VOIDED)) {
            return "Anulación del movimiento " + action.substring(AccessLogService.FINANCIAL_TRANSACTION_VOIDED.length()).trim();
        }
        if (action.startsWith(AccessLogService.COMMISSION_REGISTERED)) {
            String[] parts = action.substring(AccessLogService.COMMISSION_REGISTERED.length()).trim().split(" ");
            if (parts.length < 3) {
                return "Comisión registrada " + String.join(" ", parts);
            }
            return "Comisión de " + parts[0] + " del empleado con documento " + parts[1] + money(parts[2]);
        }
        if (action.startsWith(AccessLogService.FINANCIAL_REPORT_EXPORTED)) {
            String[] parts = action.substring(AccessLogService.FINANCIAL_REPORT_EXPORTED.length()).trim().split(" ");
            String format = parts[0].equals("EXCEL") ? "Excel" : parts[0];
            return parts.length < 3 ? "Exportación del resumen financiero en " + format
                    : "Exportación del resumen financiero en " + format + " del " + parts[1] + " al " + parts[2];
        }
        if (action.startsWith(AccessLogService.ANALYTICS_REPORT_EXPORTED)) {
            String[] parts = action.substring(AccessLogService.ANALYTICS_REPORT_EXPORTED.length()).trim().split(" ");
            return parts.length < 2 ? "Exportación del panel de análisis"
                    : "Exportación del panel de análisis del " + parts[0] + " al " + parts[1];
        }
        return null;
    }

    /** Funerarias aliadas: guardan el NIT y, en los pagos, el contrato o el egreso y el valor. Null si no es de la red. */
    private static String alliedDetails(String action) {
        String[][] homes = {
                {AccessLogService.ALLIED_HOME_REGISTERED, "Registro de la funeraria aliada con NIT "},
                {AccessLogService.ALLIED_HOME_UPDATED, "Actualización de la funeraria aliada con NIT "},
                {AccessLogService.ALLIED_HOME_DEACTIVATED, "Desactivación del convenio con la funeraria aliada con NIT "},
                {AccessLogService.ALLIED_HOME_ACTIVATED, "Activación del convenio con la funeraria aliada con NIT "}};
        for (String[] home : homes) {
            if (action.startsWith(home[0])) {
                return home[1] + action.substring(home[0].length()).trim();
            }
        }
        String[][] payments = {
                {AccessLogService.ALLIED_PAYMENT_REGISTERED, "Pago pendiente registrado a la funeraria aliada con NIT ", "contrato "},
                {AccessLogService.ALLIED_PAYMENT_PAID, "Pago a la funeraria aliada con NIT ", "pagado, egreso "},
                {AccessLogService.ALLIED_PAYMENT_VOIDED, "Pago anulado a la funeraria aliada con NIT ", ""}};
        for (String[] payment : payments) {
            if (action.startsWith(payment[0])) {
                String[] parts = action.substring(payment[0].length()).trim().split(" ");
                StringBuilder text = new StringBuilder(payment[1]).append(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    text.append(i == parts.length - 1 ? money(parts[i]) : " · " + payment[2] + parts[i]);
                }
                return text.toString();
            }
        }
        return null;
    }

    /** " · $ 150.000" a partir de "150000.00"; si no es un número se deja tal cual. */
    private static String money(String value) {
        if (!value.matches("\\d+(\\.\\d+)?")) {
            return " " + value;
        }
        java.math.BigDecimal amount = new java.math.BigDecimal(value).setScale(0, java.math.RoundingMode.HALF_UP);
        return " · $ " + java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO")).format(amount);
    }

    /** "IN-0012 EXIT 3" pasa a "Salida de 3 unidades del artículo IN-0012". */
    private static String movementDetails(String text) {
        String[] parts = text.split(" ");
        if (parts.length < 3) {
            return "Movimiento de inventario " + text;
        }
        String kind = switch (parts[1]) {
            case "ENTRY" -> "Entrada";
            case "EXIT" -> "Salida";
            default -> "Movimiento";
        };
        String units = "1".equals(parts[2]) ? " unidad" : " unidades";
        return kind + " de " + parts[2] + units + " del artículo " + parts[0];
    }

    /** Pasa a español los valores en inglés que van después del código (tipo de documento, PARTICULAR). */
    private static String details(String text) {
        String result = text.replace("PARTICULAR", "· particular (sin plan)");
        for (DocumentType type : DocumentType.values()) {
            result = result.replace(type.name(), "· " + type.getDisplayName().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** "CLIENTES" pasa a "Clientes" usando el mismo nombre del menú lateral. */
    private static String moduleLabel(String moduleName) {
        try {
            return AppModule.valueOf(moduleName.trim()).getLabel();
        } catch (IllegalArgumentException e) {
            return moduleName;
        }
    }
}