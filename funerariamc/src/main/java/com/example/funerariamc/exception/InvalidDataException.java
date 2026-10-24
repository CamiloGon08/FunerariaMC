package com.example.funerariamc.exception;

/** Un dato obligatorio falta o no es válido; el mensaje se muestra tal cual en pantalla. */
public class InvalidDataException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public InvalidDataException(String message) {
        super(message);
    }
}
