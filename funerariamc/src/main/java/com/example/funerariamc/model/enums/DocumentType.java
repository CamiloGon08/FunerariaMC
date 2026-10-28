package com.example.funerariamc.model.enums;

/**
 * Type of legal document of a deceased person (column death_certificate.document_type).
 * MEDICAL_CERTIFICATE is the death certificate required to register the deceased (RN-02);
 * the other two are the additional documents of a non-natural death (RF-DIF-03, RN-03).
 */
public enum DocumentType {

    MEDICAL_CERTIFICATE("Certificado de defunción"),
    FORENSIC_REPORT("Informe forense"),
    BODY_RELEASE_ORDER("Orden de entrega del cuerpo");

    private final String displayName;

    DocumentType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the display name shown on screen.
     */
    public String getDisplayName() {
        return displayName;
    }
}
