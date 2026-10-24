package com.example.funerariamc.service;

import com.example.funerariamc.model.enums.ClientType;

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
        String address) {
}
