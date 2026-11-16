package com.example.funerariamc.service.bot;

import com.example.funerariamc.service.BrandPalette;
import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.config.TelegramProperties;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.PlanParameters;
import com.example.funerariamc.service.PlanRegisteredEvent;
import com.example.funerariamc.service.notification.EmailNotificationSender;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Correo de bienvenida automático al afiliar un plan: le confirma al titular que quedó inscrito
 * y le deja el enlace y el QR del bot para consultar su plan. Sale por correo porque Telegram no
 * deja que un bot le escriba primero a quien nunca le ha escrito. Se envía en otro hilo después de
 * guardar el plan, así el registro no espera al servidor de correo; el resultado queda en bot_message.
 */
@Service
public class PlanNotificationService {

    /** QR del enlace del bot: se genera una sola vez y se guarda en resources/images. */
    static final String QR_IMAGE = "images/bot-qr.png";
    static final String QR_ID = "botQr";
    private static final Logger log = LoggerFactory.getLogger(PlanNotificationService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy",
            Locale.forLanguageTag("es-CO"));

    /** Lo que se le envía al titular, ya armado. */
    record Welcome(Long clientId, String email, String subject, String text, String html) {
    }

    private final FuneralPlanRepository planRepository;
    private final PlanParameters parameters;
    private final EmailNotificationSender emailSender;
    private final BotMessageService messages;
    private final TelegramProperties telegram;
    private final TransactionTemplate readOnly;
    private final BrandingService brandingService;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "plan-welcome-mail");
        thread.setDaemon(true);
        return thread;
    });

    public PlanNotificationService(FuneralPlanRepository planRepository, PlanParameters parameters,
                                   EmailNotificationSender emailSender, BotMessageService messages,
                                   TelegramProperties telegram, PlatformTransactionManager transactionManager) {
        this(planRepository, parameters, emailSender, messages, telegram, transactionManager, null);
    }

    @Autowired
    public PlanNotificationService(FuneralPlanRepository planRepository, PlanParameters parameters,
                                   EmailNotificationSender emailSender, BotMessageService messages,
                                   TelegramProperties telegram, PlatformTransactionManager transactionManager,
                                   BrandingService brandingService) {
        this.planRepository = planRepository;
        this.parameters = parameters;
        this.emailSender = emailSender;
        this.messages = messages;
        this.telegram = telegram;
        this.brandingService = brandingService;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** Solo si el plan quedó guardado (después del commit) y sin frenar la pantalla. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanRegistered(PlanRegisteredEvent event) {
        sender.submit(() -> sendWelcome(event.planId()));
    }

    @PreDestroy
    public void shutdown() {
        sender.shutdown();
    }

    void sendWelcome(Long planId) {
        Welcome welcome;
        try {
            welcome = readOnly.execute(status -> planRepository.findDetailById(planId).map(this::compose).orElse(null));
        } catch (RuntimeException failure) {
            log.warn("No se pudo leer el plan para el correo de bienvenida ({}).", failure.getClass().getSimpleName());
            return;
        }
        if (welcome == null) {
            return;
        }
        if (welcome.email() == null || welcome.email().isBlank()) {
            messages.outgoing(null, welcome.clientId(), NotificationChannel.EMAIL,
                    "No se envió la confirmación de inscripción: el titular no tiene correo registrado.",
                    BotTopic.ENROLLMENT, false);
            return;
        }
        boolean sent;
        try {
            Resource qr = new ClassPathResource(QR_IMAGE);
            emailSender.sendHtml(welcome.email(), welcome.subject(), welcome.text(), welcome.html(),
                    telegram.hasLink() && qr.exists() ? qr : null, QR_ID);
            sent = true;
        } catch (RuntimeException failure) {
            log.warn("No se pudo enviar el correo de bienvenida ({}).", failure.getClass().getSimpleName());
            sent = false;
        }
        messages.outgoing(null, welcome.clientId(), NotificationChannel.EMAIL, welcome.text(), BotTopic.ENROLLMENT, sent);
    }

    Welcome compose(FuneralPlan plan) {
        Client holder = plan.getClient();
        String code = FuneralPlanService.code(plan.getId());
        LocalDate start = plan.getStartDate();
        LocalDate waitingEnds = start == null ? null
                : FuneralPlanService.waitingEnds(FuneralPlanService.waitingBase(start, plan.getWaitingRestartDate()),
                parameters.waitingDays());
        String fee = ChatbotService.money(plan.getMonthlyFee());
        boolean withBot = telegram.hasLink();
        boolean withQr = withBot && new ClassPathResource(QR_IMAGE).exists();

        String funeralHome = BrandingService.fullNameOf(brandingService);
        BrandPalette colors = brandingService == null ? BrandPalette.defaults() : brandingService.current().palette();
        StringBuilder text = new StringBuilder()
                .append("Hola, ").append(holder.getName()).append(".\n\n")
                .append("Le confirmamos que quedó inscrito como titular del Plan ").append(plan.getPlanName())
                .append(" (").append(code).append(") en ").append(funeralHome).append(".\n");
        if (start != null) {
            text.append("Fecha de afiliación: ").append(start.format(DAY)).append("\n");
        }
        text.append("Cuota mensual: ").append(fee).append("\n");
        if (waitingEnds != null) {
            text.append("Periodo de carencia: hasta el ").append(waitingEnds.format(DAY)).append("\n");
        }
        if (withBot) {
            text.append("\nConsulte su plan, sus beneficiarios y sus pagos en Telegram: ").append(telegram.link())
                    .append("\nEscriba su cédula y le enviaremos un código a este correo.\n");
        }
        text.append('\n').append(funeralHome);

        String name = HtmlUtils.htmlEscape(holder.getName() == null ? "" : holder.getName());
        StringBuilder html = new StringBuilder()
                .append("<div style=\"font-family:Arial,Helvetica,sans-serif;background:").append(colors.get("-color-background"))
                .append(";padding:24px;color:").append(colors.get("-color-ink")).append("\">")
                .append("<div style=\"max-width:560px;margin:0 auto;background:#FFFFFF;border-radius:18px;overflow:hidden;border:1px solid #DCD8CF\">")
                .append("<div style=\"background:").append(colors.get("-color-primary")).append(";padding:22px 26px\">")
                .append("<div style=\"color:").append(colors.get("-color-detail")).append(";font-size:11px;letter-spacing:2px;font-weight:bold\">CONFIRMACIÓN DE INSCRIPCIÓN</div>")
                .append("<div style=\"color:").append(colors.get("-color-background"))
                .append(";font-size:26px;font-family:Georgia,serif;margin-top:6px\">").append(HtmlUtils.htmlEscape(funeralHome))
                .append("</div></div>")
                .append("<div style=\"padding:24px 26px;font-size:15px;line-height:1.55\">")
                .append("<p style=\"margin:0 0 14px\">Hola, ").append(name).append(".</p>")
                .append("<p style=\"margin:0 0 16px\">Le confirmamos que quedó inscrito como <b>titular del Plan ")
                .append(HtmlUtils.htmlEscape(plan.getPlanName() == null ? "" : plan.getPlanName())).append("</b> (")
                .append(code).append(").</p>")
                .append("<table style=\"width:100%;border-collapse:collapse;font-size:14px\">");
        if (start != null) {
            html.append(row("Fecha de afiliación", start.format(DAY)));
        }
        html.append(row("Cuota mensual", fee));
        if (waitingEnds != null) {
            html.append(row("Periodo de carencia", "hasta el " + waitingEnds.format(DAY)));
        }
        html.append("</table>");
        if (withBot) {
            String link = HtmlUtils.htmlEscape(telegram.link());
            html.append("<div style=\"margin-top:20px;background:").append(colors.get("-color-surface")).append(";border-radius:14px;padding:16px 18px\">")
                    .append("<div style=\"font-weight:bold;color:").append(colors.get("-color-primary")).append(";margin-bottom:6px\">Consulte su plan en Telegram</div>")
                    .append("<div style=\"font-size:14px\">Vea sus beneficiarios, su próxima cuota y sus pagos. ")
                    .append("Escriba su cédula y le enviaremos un código a este correo.</div>")
                    .append("<a href=\"").append(link).append("\" style=\"display:inline-block;margin-top:12px;background:")
                    .append(colors.get("-color-accent")).append(";color:").append(colors.get("-color-background")).append(";")
                    .append("text-decoration:none;padding:10px 18px;border-radius:10px;font-weight:bold\">")
                    .append("Abrir el bot de ").append(HtmlUtils.htmlEscape(brandingService == null
                            ? BrandingService.DEFAULT_NAME : brandingService.current().commercialName())).append("</a>");
            if (withQr) {
                html.append("<div style=\"margin-top:14px\"><img src=\"cid:").append(QR_ID)
                        .append("\" alt=\"Código QR del bot\" width=\"150\" height=\"150\"></div>");
            }
            html.append("</div>");
        }
        html.append("</div></div></div>");
        return new Welcome(holder.getId(), holder.getEmail(), "Bienvenido al Plan " + plan.getPlanName()
                + " · " + funeralHome, text.toString(), html.toString());
    }

    private static String row(String label, String value) {
        return "<tr><td style=\"padding:6px 0;color:#4A524B\">" + label + "</td>"
                + "<td style=\"padding:6px 0;text-align:right;font-weight:bold\">" + HtmlUtils.htmlEscape(value) + "</td></tr>";
    }
}
