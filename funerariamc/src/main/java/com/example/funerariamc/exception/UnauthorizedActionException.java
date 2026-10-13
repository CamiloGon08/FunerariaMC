package com.example.funerariamc.exception;

/** RN-21 y RN-22: el rol de la sesión no autoriza el acceso solicitado. */
public class UnauthorizedActionException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedActionException() {
        super("No tiene permiso para acceder a este módulo");
    }
}
