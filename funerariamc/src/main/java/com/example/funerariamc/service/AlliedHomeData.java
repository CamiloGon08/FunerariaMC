package com.example.funerariamc.service;

import java.time.LocalDate;

/** Datos del formulario de una funeraria aliada. */
public record AlliedHomeData(String name, String nit, String phone, String city, String email,
                             LocalDate agreementStart) {
}
