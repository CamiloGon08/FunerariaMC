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
                DECEASED_ACTIONS, PLAN_ACTIONS)) {
            for (Map.Entry<String, String> entry : actions.entrySet()) {
                if (action.startsWith(entry.getKey())) {
                    return entry.getValue() + details(action.substring(entry.getKey().length()).trim());
                }
            }
        }
        return action;
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