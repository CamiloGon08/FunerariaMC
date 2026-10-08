package com.example.funerariamc.exception;

/** Indica que no existe el recurso solicitado. */
public class ResourceNotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String entity, Long id) {
        super(entity + " con id " + id + " no existe.");
    }
}
