package com.example.funerariamc.service.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** ACT-06: envío por correo a {@code person.email} con spring-boot-starter-mail. */
@Component
public class EmailNotificationSender implements NotificationSender {

    private static final String SUBJECT = "Funeraria MC";

    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationSender(JavaMailSender mailSender,
                                   @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /** @param recipient correo del destinatario; lanza MailException si el envío falla */
    @Override
    public void send(String recipient, String message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            mail.setFrom(from);
        }
        mail.setTo(recipient);
        mail.setSubject(SUBJECT);
        mail.setText(message);
        mailSender.send(mail);
    }
}
