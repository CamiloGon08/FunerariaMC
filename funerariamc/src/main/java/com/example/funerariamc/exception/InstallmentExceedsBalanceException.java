package com.example.funerariamc.exception;

import java.math.BigDecimal;
import java.util.Objects;

/** RF-CIV-07, RN-14: el abono solicitado supera el saldo pendiente de la venta. */
public class InstallmentExceedsBalanceException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final BigDecimal outstandingBalance;

    public InstallmentExceedsBalanceException(BigDecimal outstandingBalance) {
        super("El abono supera el saldo pendiente: "
                + Objects.requireNonNull(outstandingBalance,
                        "El saldo pendiente es obligatorio.").toPlainString());
        this.outstandingBalance = outstandingBalance;
    }

    public BigDecimal getOutstandingBalance() {
        return outstandingBalance;
    }
}
