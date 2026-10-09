package com.example.funerariamc.exception;

/** RN-03: falta la autorización de la Fiscalía necesaria para la cremación. */
public class CremationNotAuthorizedException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public CremationNotAuthorizedException() {
        super("Cremación no autorizada: pendiente autorización de la Fiscalía");
    }
}
