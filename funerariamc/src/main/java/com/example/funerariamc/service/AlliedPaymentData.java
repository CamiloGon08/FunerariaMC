package com.example.funerariamc.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pago a una funeraria aliada: el contrato del servicio que prestó, el monto y la fecha del servicio. */
public record AlliedPaymentData(Long homeId, Long contractId, BigDecimal amount, LocalDate serviceDate) {
}
