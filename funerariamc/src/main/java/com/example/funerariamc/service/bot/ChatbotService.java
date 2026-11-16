package com.example.funerariamc.service.bot;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.service.AccessLogService;
import com.example.funerariamc.service.FuneralPlanService;
import com.example.funerariamc.service.FuneralPlanService.BeneficiaryInfo;
import com.example.funerariamc.service.FuneralPlanService.PaymentInfo;
import com.example.funerariamc.service.FuneralPlanService.PlanCard;
import com.example.funerariamc.service.FuneralPlanService.PlanDetail;
import com.example.funerariamc.service.SystemParameterService;
import com.example.funerariamc.utils.enums.BotTopic;
import com.example.funerariamc.utils.enums.PlanPaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversación del bot de Telegram con el titular de un plan, como los bots de las empresas:
 * 1) pide la cédula, 2) envía un código al correo registrado, 3) con el código correcto muestra el menú.
 * Cada consulta pide cédula y código otra vez: al salir (0) o tras los minutos de vigencia del código
 * sin escribir, la sesión se cierra. Solo consulta: nunca cambia datos del plan.
 */
@Service
public class ChatbotService {

    public static final String FUNERAL_HOME_ADDRESS = "FUNERAL_HOME_ADDRESS";
    public static final String FUNERAL_HOME_PHONE = "FUNERAL_HOME_PHONE";
    public static final String FUNERAL_HOME_HOURS = "FUNERAL_HOME_HOURS";
    static final String RESEND = "Enviar otro código";
    static final String CANCEL = "Cancelar";
    static final List<List<String>> MENU_OPTIONS = List.of(
            List.of("1 · Mi plan", "2 · Beneficiarios"),
            List.of("3 · Próxima cuota", "4 · Mis pagos"),
            List.of("5 · Contacto", "0 · Salir"));
    static final List<List<String>> CODE_OPTIONS = List.of(List.of(RESEND), List.of(CANCEL));
    static final List<List<String>> NO_OPTIONS = List.of();
    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);
    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", ES);
    private static final String MORE = "\n\n¿Algo más? Escoja otra opción o escriba 0 para salir.";
    private static final String ASK_DOCUMENT = "Para consultar su plan escriba su número de cédula, solo números"
            + " y sin puntos. Le enviaremos un código a su correo registrado.";

    /** Lo que el bot contesta: texto, tema (para la pantalla) y botones (null deja los que había). */
    public record Reply(String text, BotTopic topic, List<List<String>> options) {
    }

    /**
     * Resultado de un mensaje: cómo se guarda lo que escribió el titular (el código nunca se guarda),
     * el titular ya identificado (o null) y las respuestas en orden.
     */
    public record Answer(String loggedText, BotTopic incomingTopic, Long clientId, List<Reply> replies) {
    }

    enum Step {
        DOCUMENT,
        CODE,
        MENU
    }

    static final class Session {
        Step step = Step.DOCUMENT;
        Long clientId;
        Instant lastSeen;
    }

    private final ClientRepository clientRepository;
    private final FuneralPlanService planService;
    private final BotVerificationService verification;
    private final SystemParameterService parameterService;
    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public ChatbotService(ClientRepository clientRepository, FuneralPlanService planService,
                          BotVerificationService verification, SystemParameterService parameterService) {
        this(clientRepository, planService, verification, parameterService, Clock.system(AccessLogService.APP_ZONE));
    }

    ChatbotService(ClientRepository clientRepository, FuneralPlanService planService,
                   BotVerificationService verification, SystemParameterService parameterService, Clock clock) {
        this.clientRepository = clientRepository;
        this.planService = planService;
        this.verification = verification;
        this.parameterService = parameterService;
        this.clock = clock;
    }

    /** Atiende un mensaje de texto del chat y devuelve las respuestas. Nunca lanza excepción. */
    public Answer handle(String chatId, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        Session session = currentSession(chatId);
        String safeLog = session.step == Step.CODE ? "Código escrito (no se guarda)" : text;
        try {
            return switch (session.step) {
                case DOCUMENT -> onDocument(chatId, session, text);
                case CODE -> onCode(chatId, session, text);
                case MENU -> onMenu(chatId, session, text);
            };
        } catch (RuntimeException failure) {
            log.warn("El bot no pudo atender el mensaje ({}).", failure.getClass().getSimpleName());
            sessions.remove(chatId);
            return new Answer(safeLog, BotTopic.UNKNOWN, session.clientId, List.of(new Reply(
                    "Tuvimos un problema para consultar su información. Intente de nuevo en unos minutos.",
                    BotTopic.UNKNOWN, NO_OPTIONS)));
        }
    }

    // ---------- pasos ----------

    private Answer onDocument(String chatId, Session session, String text) {
        String digits = text.replaceAll("\\D", "");
        if (digits.isEmpty() || text.startsWith("/")) {
            return answer(text, BotTopic.IDENTIFICATION, null, new Reply(
                    "Hola, soy Lily, la asistente de Funeraria El Edén.\n\n" + ASK_DOCUMENT, BotTopic.IDENTIFICATION, NO_OPTIONS));
        }
        if (digits.length() < 5 || digits.length() > 15 || digits.length() != text.replaceAll("[\\s.\\-]", "").length()) {
            return answer(text, BotTopic.IDENTIFICATION, null, new Reply(
                    "Escriba solo los números de su cédula, sin puntos ni letras.", BotTopic.IDENTIFICATION, NO_OPTIONS));
        }
        Optional<Client> found = clientRepository.findByDocumentNumber(digits);
        boolean holder = found.isPresent() && planService.holderPlan(found.get().getId()).isPresent();
        if (!holder) {
            // El mismo mensaje si no existe o si no tiene plan activo: el bot no revela quién es cliente.
            return answer(digits, BotTopic.IDENTIFICATION, null, new Reply(
                    "Esa cédula no aparece como titular de un plan activo. Si cree que es un error, acérquese a la sede.",
                    BotTopic.IDENTIFICATION, NO_OPTIONS));
        }
        Client client = found.get();
        return answer(digits, BotTopic.IDENTIFICATION, client.getId(), sendCode(chatId, session, client));
    }

    private Answer onCode(String chatId, Session session, String text) {
        if (CANCEL.equalsIgnoreCase(text) || "0".equals(text)) {
            sessions.remove(chatId);
            return answer(text, BotTopic.VERIFICATION, session.clientId,
                    new Reply("Consulta cancelada.\n\n" + ASK_DOCUMENT, BotTopic.IDENTIFICATION, NO_OPTIONS));
        }
        if (RESEND.equalsIgnoreCase(text)) {
            Client client = clientRepository.findById(session.clientId).orElse(null);
            if (client == null) {
                sessions.remove(chatId);
                return answer(text, BotTopic.VERIFICATION, null, new Reply(ASK_DOCUMENT, BotTopic.IDENTIFICATION, NO_OPTIONS));
            }
            return answer(text, BotTopic.VERIFICATION, client.getId(), sendCode(chatId, session, client));
        }
        BotVerificationService.Check check = verification.verify(chatId, text);
        String hidden = "Código escrito (no se guarda)";
        return switch (check.outcome()) {
            case VALID -> {
                session.step = Step.MENU;
                session.clientId = check.clientId();
                String name = clientRepository.findById(check.clientId()).map(Client::getName).orElse("");
                yield answer(hidden, BotTopic.VERIFICATION, check.clientId(), new Reply(
                        "Listo" + (name.isBlank() ? "" : ", " + name) + ". Ya puede consultar su plan.\n\n" + menuText(),
                        BotTopic.MENU, MENU_OPTIONS));
            }
            case WRONG -> answer(hidden, BotTopic.VERIFICATION, session.clientId, new Reply(
                    "Código incorrecto. Le quedan " + check.attemptsLeft() + " intento(s).", BotTopic.VERIFICATION, null));
            case EXHAUSTED, EXPIRED -> {
                Long clientId = session.clientId;
                sessions.remove(chatId);
                yield answer(hidden, BotTopic.VERIFICATION, clientId, new Reply(
                        "El código venció o superó los intentos permitidos.\n\n" + ASK_DOCUMENT,
                        BotTopic.VERIFICATION, NO_OPTIONS));
            }
        };
    }

    private Answer onMenu(String chatId, Session session, String text) {
        Long clientId = session.clientId;
        char key = text.isEmpty() ? ' ' : text.charAt(0);
        if (key == '0') {
            String name = clientRepository.findById(clientId).map(Client::getName).orElse("");
            sessions.remove(chatId);
            return answer(text, BotTopic.EXIT, clientId, new Reply("Gracias por escribirnos"
                    + (name.isBlank() ? "" : ", " + name) + ". Su sesión se cerró. La próxima vez le pediremos"
                    + " la cédula y un código nuevo.\n\nHasta pronto, Lily.", BotTopic.EXIT, NO_OPTIONS));
        }
        BotTopic topic = switch (key) {
            case '1' -> BotTopic.PLAN;
            case '2' -> BotTopic.BENEFICIARIES;
            case '3' -> BotTopic.NEXT_PAYMENT;
            case '4' -> BotTopic.PAYMENTS;
            case '5' -> BotTopic.CONTACT;
            default -> BotTopic.UNKNOWN;
        };
        if (topic == BotTopic.UNKNOWN) {
            return answer(text, BotTopic.MENU, clientId, new Reply(
                    "No entendí esa opción. Escriba un número del 0 al 5.\n\n" + menuText(), BotTopic.UNKNOWN, MENU_OPTIONS));
        }
        if (topic == BotTopic.CONTACT) {
            return answer(text, BotTopic.MENU, clientId, new Reply(contactText() + MORE, topic, MENU_OPTIONS));
        }
        Optional<PlanDetail> plan = planService.holderPlan(clientId);
        if (plan.isEmpty()) {
            sessions.remove(chatId);
            return answer(text, BotTopic.MENU, clientId, new Reply(
                    "Su plan ya no aparece activo. Para más información acérquese a la sede.", topic, NO_OPTIONS));
        }
        String body = switch (topic) {
            case PLAN -> planText(plan.get());
            case BENEFICIARIES -> beneficiariesText(plan.get());
            case NEXT_PAYMENT -> nextPaymentText(plan.get());
            default -> paymentsText(plan.get());
        };
        return answer(text, BotTopic.MENU, clientId, new Reply(body + MORE, topic, MENU_OPTIONS));
    }

    private Reply sendCode(String chatId, Session session, Client client) {
        if (client.getEmail() == null || client.getEmail().isBlank()) {
            sessions.remove(chatId);
            return new Reply("No tenemos un correo registrado para enviarle el código. Acérquese a la sede para"
                    + " actualizar sus datos.", BotTopic.VERIFICATION, NO_OPTIONS);
        }
        try {
            verification.sendCode(chatId, client);
        } catch (RuntimeException failure) {
            log.warn("No se pudo enviar el código del bot ({}).", failure.getClass().getSimpleName());
            sessions.remove(chatId);
            return new Reply("No pudimos enviar el código en este momento. Intente de nuevo en unos minutos.",
                    BotTopic.VERIFICATION, NO_OPTIONS);
        }
        session.step = Step.CODE;
        session.clientId = client.getId();
        return new Reply("Le enviamos un código de 6 dígitos a " + BotVerificationService.maskEmail(client.getEmail())
                + ". Escríbalo aquí. Vence en " + verification.expirationMinutes() + " minutos.",
                BotTopic.VERIFICATION, CODE_OPTIONS);
    }

    // ---------- textos ----------

    static String menuText() {
        return "¿Qué desea consultar? Toque una opción o escriba el número:\n"
                + "1 · Mi plan\n2 · Mis beneficiarios\n3 · Próxima cuota y carencia\n4 · Mis pagos\n5 · Contacto\n0 · Salir";
    }

    static String planText(PlanDetail detail) {
        PlanCard card = detail.card();
        return "Mi plan\n"
                + "Plan " + card.planName() + " (" + card.code() + ")\n"
                + "Estado: " + (card.isActive() ? "activo" : "inactivo") + "\n"
                + (card.startDate() == null ? "" : "Afiliado desde: " + card.startDate().format(DAY) + "\n")
                + "Cuota mensual: " + money(card.monthlyFee()) + "\n"
                + "Beneficiarios: " + detail.beneficiaries().size();
    }

    static String beneficiariesText(PlanDetail detail) {
        StringBuilder text = new StringBuilder("Mis beneficiarios\n");
        if (detail.beneficiaries().isEmpty()) {
            text.append("Su plan aún no tiene beneficiarios registrados.");
        } else {
            for (BeneficiaryInfo b : detail.beneficiaries()) {
                text.append("• ").append(b.fullName()).append(" · ").append(b.relationshipLabel());
                if (b.isDeceased()) {
                    text.append(" · ya usó el plan");
                }
                text.append('\n');
            }
            text.setLength(text.length() - 1);
        }
        return text.append("\n\n").append(waitingText(detail.card())).toString();
    }

    static String nextPaymentText(PlanDetail detail) {
        PlanCard card = detail.card();
        StringBuilder text = new StringBuilder("Próxima cuota y carencia\n");
        YearMonth next = detail.nextDue();
        if (next != null) {
            text.append("Próxima cuota: ").append(next.atDay(1).format(MONTH)).append(" (")
                    .append(money(card.monthlyFee())).append(")\n");
        }
        if (card.overdueMonths() > 0) {
            text.append("Cuotas atrasadas: ").append(card.overdueMonths()).append(" (")
                    .append(money(card.overdueAmount())).append(")\n");
        } else {
            text.append("Está al día con sus cuotas.\n");
        }
        return text.append(waitingText(card)).toString();
    }

    static String paymentsText(PlanDetail detail) {
        List<PaymentInfo> paid = new ArrayList<>(detail.payments().stream()
                .filter(p -> p.status() == PlanPaymentStatus.PAID)
                .toList());
        paid.sort((a, b) -> b.period().compareTo(a.period()));
        StringBuilder text = new StringBuilder("Mis pagos\n");
        if (paid.isEmpty()) {
            text.append("Aún no hay pagos registrados.");
        } else {
            text.append("Últimos pagos:\n");
            for (PaymentInfo p : paid.subList(0, Math.min(3, paid.size()))) {
                text.append("• ").append(p.period().atDay(1).format(MONTH)).append(": ").append(money(p.amount()));
                if (p.paidAt() != null) {
                    text.append(", pagado el ").append(p.paidAt().toLocalDate().format(DAY));
                }
                text.append('\n');
            }
            text.setLength(text.length() - 1);
        }
        long overdue = detail.card().overdueMonths();
        text.append("\n\n").append(overdue > 0 ? "Cuotas atrasadas: " + overdue + " (" + money(detail.card().overdueAmount()) + ")"
                : "Está al día con sus cuotas.");
        return text.toString();
    }

    static String waitingText(PlanCard card) {
        if (card.inWaiting() && card.waitingEnds() != null) {
            return "Carencia: el plan está en periodo de espera hasta el " + card.waitingEnds().format(DAY) + ".";
        }
        return "Carencia: ya se cumplió el periodo de espera del plan.";
    }

    private String contactText() {
        String address = parameter(FUNERAL_HOME_ADDRESS);
        String phone = parameter(FUNERAL_HOME_PHONE);
        String hours = parameter(FUNERAL_HOME_HOURS);
        if (address == null && phone == null && hours == null) {
            return "Contacto\nPara cualquier trámite acérquese a la sede de Funeraria El Edén.";
        }
        return "Contacto\n"
                + (address == null ? "" : "Dirección: " + address + "\n")
                + (phone == null ? "" : "Teléfono: " + phone + "\n")
                + (hours == null ? "" : "Horario: " + hours + "\n")
                + "Funeraria El Edén";
    }

    /** null si el parámetro no existe o no tiene valor: el bot no inventa datos de la sede. */
    private String parameter(String name) {
        try {
            String value = parameterService.getString(name);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException missing) {
            return null;
        }
    }

    static String money(BigDecimal amount) {
        if (amount == null) {
            return "$ 0";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(ES);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        return "$ " + new DecimalFormat("#,##0", symbols).format(amount);
    }

    // ---------- sesiones ----------

    /** La sesión se cierra si pasan los minutos de vigencia del código sin que el titular escriba. */
    private Session currentSession(String chatId) {
        Instant now = clock.instant();
        Duration idle = Duration.ofMinutes(verification.expirationMinutes());
        sessions.values().removeIf(s -> s.lastSeen != null && Duration.between(s.lastSeen, now).compareTo(idle) >= 0);
        Session session = sessions.computeIfAbsent(chatId, ignored -> new Session());
        session.lastSeen = now;
        return session;
    }

    /** Hay sesiones abiertas (para pruebas). */
    int openSessions() {
        return sessions.size();
    }

    private static Answer answer(String logged, BotTopic topic, Long clientId, Reply reply) {
        return new Answer(logged, topic, clientId, List.of(reply));
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }
}
