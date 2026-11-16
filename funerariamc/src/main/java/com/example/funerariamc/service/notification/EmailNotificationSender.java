package com.example.funerariamc.service.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Envío por correo a {@code person.email} con spring-boot-starter-mail. */
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
        send(recipient, SUBJECT, message);
    }

    /** Correo de texto con asunto propio. */
    public void send(String recipient, String subject, String message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            mail.setFrom(from);
        }
        mail.setTo(recipient);
        mail.setSubject(subject);
        mail.setText(message);
        mailSender.send(mail);
    }

    /**
     * Correo con diseño (HTML) y su versión en texto para los lectores que no muestran HTML.
     * Si image no es null, va pegada dentro del correo y el HTML la muestra con {@code src="cid:<imageId>"}.
     */
    public void sendHtml(String recipient, String subject, String plainText, String html,
                         Resource image, String imageId) {
        try {
            MimeMessage mail = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mail, true, "UTF-8");
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(plainText, html);
            if (image != null && image.exists()) {
                helper.addInline(imageId, image, "image/png");
            }
            mailSender.send(mail);
        } catch (MessagingException failure) {
            throw new MailSendException("No se pudo armar el correo.", failure);
        }
    }
}
