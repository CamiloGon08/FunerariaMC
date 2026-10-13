package com.example.funerariamc.service.notification;

/** Contrato común de los canales de notificación (RF-BOV-03/04, RN-04/05). */
@FunctionalInterface
public interface NotificationSender {

    /**
     * Envía un mensaje al destinatario propio del canal: correo o chat de Telegram.
     * El retorno normal indica que el proveedor aceptó el envío, no que fue leído.
     *
     * @param recipient destinatario del canal seleccionado
     * @param message texto de la notificación
     * @throws RuntimeException si el envío falla; el servicio decide el reintento
     *                          por otro canal y registra el resultado
     */
    void send(String recipient, String message);
}
