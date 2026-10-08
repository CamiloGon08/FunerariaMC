package com.example.funerariamc.exception;

/** RN-10: no se puede completar una operación que excede el límite autorizado. */
public class BeneficiaryLimitExceededException extends BusinessException {

    private static final long serialVersionUID = 1L;

    private final long maximumBeneficiaries;

    public BeneficiaryLimitExceededException(long maximumBeneficiaries) {
        super("Se ha superado el límite de beneficiarios permitido: " + maximumBeneficiaries);
        this.maximumBeneficiaries = maximumBeneficiaries;
    }

    public long getMaximumBeneficiaries() {
        return maximumBeneficiaries;
    }
}
