package com.example.funerariamc.exception;

/** RN-25: el archivo de soporte no se pudo guardar; no se guarda nada y se pide reintentar. */
public class FileUploadException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public FileUploadException(String message) {
        super(message);
    }
}
