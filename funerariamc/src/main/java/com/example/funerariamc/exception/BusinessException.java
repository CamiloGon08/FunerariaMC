package com.example.funerariamc.exception;

/** Base de las excepciones de negocio que captura la capa de presentación. */
public abstract class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected BusinessException(String message) {
        super(message);
    }
}
