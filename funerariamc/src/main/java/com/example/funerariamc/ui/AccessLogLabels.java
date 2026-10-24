package com.example.funerariamc.ui;

import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.AppModule;

import java.util.List;
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
            AccessLogService.PASSWORD_RESET_COMPLETED, "Contraseña restablecida");

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
        for (Map<String, String> actions : List.of(EMPLOYEE_ACTIONS, CLIENT_ACTIONS)) {
            for (Map.Entry<String, String> entry : actions.entrySet()) {
                if (action.startsWith(entry.getKey())) {
                    return entry.getValue() + action.substring(entry.getKey().length()).trim();
                }
            }
        }
        return action;
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
