package com.example.funerariamc.controller;

import com.example.funerariamc.exception.BusinessException;
import com.example.funerariamc.service.bot.BotMessageService;
import com.example.funerariamc.service.bot.BotMessageService.ConversationView;
import com.example.funerariamc.service.bot.BotMessageService.Kpis;
import com.example.funerariamc.service.bot.BotMessageService.MessageView;
import com.example.funerariamc.service.bot.BotMessageService.Overview;
import com.example.funerariamc.service.bot.TelegramBotPoller;
import com.example.funerariamc.ui.RefreshableView;
import com.example.funerariamc.utils.enums.NotificationChannel;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Pantalla "Mensajes del bot". Todo se carga en otro hilo (la pantalla aparece al instante con el
 * esqueleto de los indicadores) y se vuelve a cargar sola, con una pausa corta, cuando el bot guarda
 * un mensaje nuevo. La lista y el chat tienen su propio scroll y un tope de filas, para que no se ponga lenta.
 */
@Component
@Scope("prototype")
public class BotMessagesController implements RefreshableView {

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", ES);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private final BotMessageService service;
    private final TelegramBotPoller poller;
    @FXML private StackPane root;
    @FXML private ToggleGroup filterGroup;
    @FXML private ToggleButton allFilter;
    @FXML private ToggleButton queriesFilter;
    @FXML private ToggleButton welcomeFilter;
    @FXML private ToggleButton failedFilter;
    @FXML private TextField searchField;
    @FXML private Label todayValue;
    @FXML private Label todayDetail;
    @FXML private Label queriesValue;
    @FXML private Label queriesDetail;
    @FXML private Label welcomeValue;
    @FXML private Label welcomeDetail;
    @FXML private Label failedValue;
    @FXML private Label failedDetail;
    @FXML private Label botStatus;
    @FXML private Label countLabel;
    @FXML private ListView<ConversationView> conversationList;
    @FXML private Label listEmpty;
    @FXML private StackPane chatAvatar;
    @FXML private Label chatTitle;
    @FXML private Label chatSubtitle;
    @FXML private ScrollPane threadScroll;
    @FXML private VBox threadBox;
    @FXML private Label threadEmpty;
    private List<ConversationView> conversations = List.of();
    private String selectedKey;
    private int loadGeneration;
    private int threadGeneration;
    private boolean filling;
    private final PauseTransition searchDelay = new PauseTransition(Duration.millis(200));
    /** Varios mensajes seguidos del bot se juntan en una sola recarga. */
    private final PauseTransition liveDelay = new PauseTransition(Duration.millis(600));

    public BotMessagesController(BotMessageService service, TelegramBotPoller poller) {
        this.service = service;
        this.poller = poller;
    }

    @Override
    public void refresh() {
        load();
    }

    @FXML
    private void initialize() {
        filterGroup.selectedToggleProperty().addListener((obs, old, toggle) -> {
            if (toggle == null) {
                if (old != null) {
                    old.setSelected(true);
                }
            } else {
                applyFilter();
            }
        });
        searchDelay.setOnFinished(e -> applyFilter());
        searchField.textProperty().addListener((obs, old, text) -> searchDelay.playFromStart());

        conversationList.setCellFactory(list -> new ConversationCell());
        conversationList.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (!filling && item != null && !item.key().equals(selectedKey)) {
                selectedKey = item.key();
                loadThread(item);
            }
        });
        threadBox.heightProperty().addListener((obs, old, height) -> threadScroll.setVvalue(1));

        // Solo se recarga si la pantalla está a la vista; si no, al volver al módulo se pide refresh().
        liveDelay.setOnFinished(e -> {
            if (root.getScene() != null) {
                load();
            }
        });
        service.addListener(() -> Platform.runLater(liveDelay::playFromStart));
        showEmptyThread("Seleccione una conversación para ver los mensajes.");
        load();
    }

    @FXML
    private void onRefresh() {
        load();
    }

    // ---------- carga ----------

    private void load() {
        int request = ++loadGeneration;
        fillStatus();
        if (conversations.isEmpty()) {
            setListEmpty("Cargando conversaciones…");
        }
        async("bot-messages-load", service::overview, overview -> {
            if (request == loadGeneration) {
                show(overview);
            }
        }, failure -> {
            if (request == loadGeneration) {
                setListEmpty(message(failure, "No se pudieron cargar los mensajes del bot."));
            }
        });
    }

    private void show(Overview overview) {
        fillKpis(overview.kpis());
        conversations = overview.conversations();
        applyFilter();
    }

    private void fillKpis(Kpis k) {
        todayValue.setText(String.valueOf(k.messagesToday()));
        todayDetail.setText(k.chatsToday() == 1 ? "en 1 chat" : "en " + k.chatsToday() + " chats");
        queriesValue.setText(String.valueOf(k.queriesToday()));
        queriesDetail.setText("respuestas con datos del plan");
        welcomeValue.setText(String.valueOf(k.enrollmentsMonth()));
        welcomeDetail.setText("correos de inscripción enviados");
        failedValue.setText(String.valueOf(k.failedMonth()));
        failedDetail.setText(k.failedMonth() == 0 ? "todo se entregó" : "revise el correo o el bot");
    }

    private void fillStatus() {
        TelegramBotPoller.Status status = poller.status();
        botStatus.getStyleClass().removeAll("on", "warn", "off");
        switch (status.state()) {
            case RUNNING -> {
                botStatus.setText("Bot activo en este equipo");
                botStatus.getStyleClass().add("on");
            }
            case RETRYING -> {
                botStatus.setText("Bot reconectando…");
                botStatus.getStyleClass().add("warn");
            }
            case OTHER_COMPUTER -> {
                botStatus.setText("El bot responde desde otro equipo");
                botStatus.getStyleClass().add("warn");
            }
            default -> {
                botStatus.setText("Bot sin configurar");
                botStatus.getStyleClass().add("off");
            }
        }
    }

    private void applyFilter() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(ES);
        Object selected = filterGroup.getSelectedToggle();
        List<ConversationView> shown = conversations.stream()
                .filter(c -> selected != queriesFilter || c.queries() > 0)
                .filter(c -> selected != welcomeFilter || c.enrollments() > 0)
                .filter(c -> selected != failedFilter || c.failed() > 0)
                .filter(c -> text.isEmpty() || c.title().toLowerCase(ES).contains(text)
                        || (c.document() != null && c.document().contains(text)))
                .toList();
        filling = true;
        conversationList.getItems().setAll(shown);
        ConversationView keep = shown.stream().filter(c -> c.key().equals(selectedKey)).findFirst()
                .orElse(shown.isEmpty() ? null : shown.get(0));
        if (keep != null) {
            conversationList.getSelectionModel().select(keep);
        }
        filling = false;
        countLabel.setText(shown.size() == 1 ? "1 conversación" : shown.size() + " conversaciones");
        if (shown.isEmpty()) {
            setListEmpty(conversations.isEmpty()
                    ? "Aún no hay mensajes. Cuando un titular le escriba al bot o se afilie un plan, aparecerá aquí."
                    : "Ninguna conversación coincide con la búsqueda o el filtro.");
            selectedKey = null;
            clearChatHead();
            showEmptyThread("Sin conversación seleccionada.");
            return;
        }
        listEmpty.setVisible(false);
        // Siempre se vuelve a pedir el chat elegido: pudo llegar un mensaje nuevo.
        selectedKey = keep.key();
        loadThread(keep);
    }

    private void loadThread(ConversationView conversation) {
        int request = ++threadGeneration;
        fillChatHead(conversation);
        if (threadBox.getChildren().isEmpty()) {
            showEmptyThread("Cargando mensajes…");
        }
        async("bot-thread-load", () -> service.thread(conversation), messages -> {
            if (request == threadGeneration) {
                renderThread(messages);
            }
        }, failure -> {
            if (request == threadGeneration) {
                threadBox.getChildren().clear();
                showEmptyThread(message(failure, "No se pudieron cargar los mensajes de esta conversación."));
            }
        });
    }

    // ---------- chat ----------

    private void fillChatHead(ConversationView c) {
        chatAvatar.getChildren().setAll(avatar(c, 44));
        chatTitle.setText(c.title());
        String channel = c.emailOnly() ? "Correo" : "Telegram";
        String who = c.document() == null ? (c.validated() ? "Titular" : "Sin validar la cédula") : "Cédula " + c.document();
        chatSubtitle.setText(who + " · " + channel + " · " + (c.total() == 1 ? "1 mensaje" : c.total() + " mensajes"));
    }

    private void clearChatHead() {
        chatAvatar.getChildren().clear();
        chatTitle.setText("Seleccione una conversación");
        chatSubtitle.setText("");
    }

    private void renderThread(List<MessageView> messages) {
        threadBox.getChildren().clear();
        if (messages.isEmpty()) {
            showEmptyThread("Esta conversación no tiene mensajes.");
            return;
        }
        threadEmpty.setVisible(false);
        LocalDate day = null;
        for (MessageView m : messages) {
            LocalDate current = m.at() == null ? null : m.at().toLocalDate();
            if (current != null && !current.equals(day)) {
                day = current;
                Label chip = new Label(dayLabel(current));
                chip.getStyleClass().add("day-chip");
                HBox center = new HBox(chip);
                center.setAlignment(Pos.CENTER);
                threadBox.getChildren().add(center);
            }
            threadBox.getChildren().add(bubble(m));
        }
        Platform.runLater(() -> threadScroll.setVvalue(1));
    }

    private Node bubble(MessageView m) {
        VBox bubble = new VBox();
        bubble.getStyleClass().addAll("bubble", m.incoming() ? "holder" : "bot");
        if (m.failed()) {
            bubble.getStyleClass().add("failed");
        }
        String who = m.incoming() ? "TITULAR" : m.channel() == NotificationChannel.EMAIL ? "CORREO" : "BOT";
        Label topic = new Label(who + " · " + m.topic().getDisplayName().toUpperCase(ES));
        topic.getStyleClass().add("bubble-topic");
        Label text = new Label(m.text());
        text.setWrapText(true);
        text.getStyleClass().add("bubble-text");
        String time = m.at() == null ? "" : m.at().format(TIME);
        Label meta = new Label(m.incoming() ? time : m.failed() ? time + " · No se entregó" : time + " · Entregado");
        meta.getStyleClass().add("bubble-meta");
        if (m.failed()) {
            meta.getStyleClass().add("failed");
        }
        HBox metaRow = new HBox(meta);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().addAll(topic, text, metaRow);
        // Burbuja como máximo del 68 % del ancho del chat, como en los chats del celular.
        bubble.maxWidthProperty().bind(threadScroll.widthProperty().multiply(0.68));
        bubble.setMinHeight(Region.USE_PREF_SIZE);
        HBox row = new HBox(bubble);
        row.setAlignment(m.incoming() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private void showEmptyThread(String text) {
        threadEmpty.setText(text);
        threadEmpty.setVisible(true);
    }

    private void setListEmpty(String text) {
        listEmpty.setText(text);
        listEmpty.setVisible(true);
    }

    /** Fila de la lista: inicial del titular, nombre, hora, último mensaje y etiquetas. */
    private final class ConversationCell extends ListCell<ConversationView> {
        @Override
        protected void updateItem(ConversationView c, boolean empty) {
            super.updateItem(c, empty);
            setText(null);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            Label title = new Label(c.title());
            title.getStyleClass().add("row-title");
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            Label time = new Label(shortTime(c.lastAt()));
            time.getStyleClass().add("row-time");
            time.setMinWidth(Region.USE_PREF_SIZE);
            HBox top = new HBox(6, title, gap, time);
            top.setAlignment(Pos.CENTER_LEFT);

            Label preview = new Label((c.lastIncoming() ? "" : "Bot: ") + oneLine(c.lastText()));
            preview.getStyleClass().add("row-preview");
            preview.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox tags = new HBox(6);
            if (c.emailOnly()) {
                tags.getChildren().add(tag("Correo", "mail"));
            }
            if (c.failed() > 0) {
                tags.getChildren().add(tag(c.failed() == 1 ? "1 fallido" : c.failed() + " fallidos", "failed"));
            }
            if (c.queries() > 0) {
                tags.getChildren().add(tag(c.queries() == 1 ? "1 consulta" : c.queries() + " consultas", "ok"));
            }
            VBox text = new VBox(3, top, preview);
            if (!tags.getChildren().isEmpty()) {
                text.getChildren().add(tags);
            }
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(avatar(c, 40), text);
            row.getStyleClass().add("conversation-row");
            row.setMaxWidth(Double.MAX_VALUE);
            row.prefWidthProperty().bind(getListView().widthProperty().subtract(22));
            setGraphic(row);
        }
    }

    private static Node avatar(ConversationView c, double size) {
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("avatar");
        if (!c.validated()) {
            avatar.getStyleClass().add("unknown");
        } else if (c.emailOnly()) {
            avatar.getStyleClass().add("mail");
        }
        avatar.setMinSize(size, size);
        avatar.setPrefSize(size, size);
        avatar.setMaxSize(size, size);
        if (c.validated()) {
            Label initials = new Label(initials(c.title()));
            initials.getStyleClass().add("avatar-text");
            avatar.getChildren().add(initials);
        } else {
            SVGPath icon = new SVGPath();
            icon.setContent("M4 5h16v11H9l-5 4z");
            icon.setStyle("-fx-fill: transparent; -fx-stroke: #1E2A22; -fx-stroke-width: 1.5;");
            avatar.getChildren().add(icon);
        }
        return avatar;
    }

    private static Label tag(String text, String style) {
        Label tag = new Label(text);
        tag.getStyleClass().addAll("row-tag", style);
        return tag;
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        String first = parts[0].substring(0, 1);
        String second = parts.length > 1 ? parts[parts.length > 2 ? 2 : 1].substring(0, 1) : "";
        return (first + second).toUpperCase(ES);
    }

    static String shortTime(LocalDateTime at) {
        if (at == null) {
            return "";
        }
        LocalDate today = LocalDate.now();
        if (at.toLocalDate().equals(today)) {
            return at.format(TIME);
        }
        if (at.toLocalDate().equals(today.minusDays(1))) {
            return "Ayer";
        }
        return at.format(SHORT_DAY);
    }

    private static String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now();
        if (day.equals(today)) {
            return "Hoy";
        }
        if (day.equals(today.minusDays(1))) {
            return "Ayer";
        }
        String text = day.format(LONG_DAY);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ');
    }

    private static <T> void async(String threadName, Callable<T> work, Consumer<T> onDone, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onDone.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private static String message(Throwable failure, String fallback) {
        return failure instanceof BusinessException business ? business.getMessage() : fallback;
    }
}
