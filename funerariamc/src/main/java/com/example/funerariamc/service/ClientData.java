package com.example.funerariamc.service;

import com.example.funerariamc.utils.enums.ClientType;

import java.time.LocalDate;

/**
 * Datos del formulario de Clientes (mockup 12). Para una empresa, name es la
 * razón social y lastname llega vacío o en null.
 */
public record ClientData(
        ClientType clientType,
        String documentType,
        String documentNumber,
        String name,
        String lastname,
        String phone,
        String email,
        String address,
        LocalDate birthDate) {

    /** Sin fecha de nacimiento (una empresa, o una persona que no la dio). */
    public ClientData(ClientType clientType, String documentType, String documentNumber, String name, String lastname,
                      String phone, String email, String address) {
        this(clientType, documentType, documentNumber, name, lastname, phone, email, address, null);
    }
}
