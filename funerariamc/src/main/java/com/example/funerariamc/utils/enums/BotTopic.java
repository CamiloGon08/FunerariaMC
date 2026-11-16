package com.example.funerariamc.utils.enums;

/** Tema de conversacion para un bot. */
public enum BotTopic {
    ENROLLMENT("Confirmación de inscripción"),
    IDENTIFICATION("Identificación"),
    VERIFICATION("Código de verificación"),
    MENU("Menú principal"),
    PLAN("Mi plan"),
    BENEFICIARIES("Mis beneficiarios"),
    NEXT_PAYMENT("Próxima cuota"),
    PAYMENTS("Mis pagos"),
    CONTACT("Contacto"),
    EXIT("Salida"),
    UNKNOWN("Sin entender");

    private final String displayName;

    BotTopic(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
