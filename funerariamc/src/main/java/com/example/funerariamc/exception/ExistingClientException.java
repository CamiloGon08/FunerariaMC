package com.example.funerariamc.exception;

import com.example.funerariamc.model.Client;

/**
 * RF-PER-01 2a: el documento ya es de un cliente registrado. No se crea un duplicado
 * y la pantalla muestra el cliente existente, por eso la excepción lo trae.
 * El mensaje no repite el documento (RNF-SEC-02).
 */
public class ExistingClientException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final transient Client existingClient;

    public ExistingClientException(Client existingClient) {
        super("Ya existe un cliente registrado con ese documento.");
        this.existingClient = existingClient;
    }

    public Client getExistingClient() {
        return existingClient;
    }
}
