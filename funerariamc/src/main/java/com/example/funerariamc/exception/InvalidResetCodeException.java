package com.example.funerariamc.exception;

/** Recuperación de contraseña: el código no sirve o no se pudo completar el cambio. */
public class InvalidResetCodeException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private InvalidResetCodeException(String message) {
        super(message);
    }

    /** Código equivocado; no dice cuántos intentos quedan para no dar pistas. */
    public static InvalidResetCodeException invalidCode() {
        return new InvalidResetCodeException("El código no es válido.");
    }

    /** No hay código pendiente, ya venció o se agotaron los 5 intentos. */
    public static InvalidResetCodeException expired() {
        return new InvalidResetCodeException("El código venció o ya no es válido. Solicite uno nuevo.");
    }

    public static InvalidResetCodeException weakPassword(int minLength) {
        return new InvalidResetCodeException(
                "La nueva contraseña debe tener al menos " + minLength + " caracteres.");
    }

    public static InvalidResetCodeException deliveryFailed() {
        return new InvalidResetCodeException(
                "No se pudo enviar el código. Intente de nuevo o comuníquese con el Administrador Maestro.");
    }
}
