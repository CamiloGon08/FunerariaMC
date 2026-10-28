package com.example.funerariamc.exception;

/** RN-02: un difunto, identificado o NN, no se registra sin su certificado de defunción. */
public class MissingDeathCertificateException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public MissingDeathCertificateException() {
        super("El certificado de defunción es obligatorio");
    }

    public MissingDeathCertificateException(String message) {
        super(message);
    }
}
