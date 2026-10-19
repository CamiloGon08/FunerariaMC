package com.example.funerariamc.exception;

/** RF-ACC-01 y RN-21: el inicio de sesión no se pudo completar. */
public class AuthenticationFailedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private AuthenticationFailedException(String message) {
        super(message);
    }

    /** No indica si falló el usuario o la contraseña (RF-ACC-01). */
    public static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("Usuario o contraseña incorrectos");
    }

    public static AuthenticationFailedException disabledAccount() {
        return new AuthenticationFailedException(
                "La cuenta está deshabilitada. Comuníquese con el Administrador Maestro.");
    }

    public static AuthenticationFailedException withoutRole() {
        return new AuthenticationFailedException(
                "El usuario no tiene un rol asignado y no puede ingresar al sistema.");
    }
}
