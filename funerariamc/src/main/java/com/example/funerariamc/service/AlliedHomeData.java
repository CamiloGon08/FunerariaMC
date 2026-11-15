package com.example.funerariamc.service;

import java.time.LocalDate;

/** Datos del formulario de una funeraria aliada; la dirección es opcional. */
public record AlliedHomeData(String name, String nit, String phone, String city, String email,
                             LocalDate agreementStart, String address) {

    /** Sin dirección. */
    public AlliedHomeData(String name, String nit, String phone, String city, String email, LocalDate agreementStart) {
        this(name, nit, phone, city, email, agreementStart, null);
    }
}
