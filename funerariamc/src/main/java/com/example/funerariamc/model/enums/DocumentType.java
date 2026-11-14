package com.example.funerariamc.model.enums;

/**
 * Type of legal document of a deceased person (column death_certificate.document_type).
 * MEDICAL_CERTIFICATE es el certificado de defunción, obligatorio para registrar al difunto;
 * FORENSIC_REPORT y BODY_RELEASE_ORDER son los documentos adicionales de una muerte no natural;
 * CIVIL_DEATH_REGISTRY es el registro civil de defunción que la funeraria tramita en la notaría.
 */
public enum DocumentType {

    MEDICAL_CERTIFICATE("Certificado de defunción"),
    FORENSIC_REPORT("Informe forense"),
    BODY_RELEASE_ORDER("Orden de entrega del cuerpo"),
    CIVIL_DEATH_REGISTRY("Registro civil de defunción");

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
