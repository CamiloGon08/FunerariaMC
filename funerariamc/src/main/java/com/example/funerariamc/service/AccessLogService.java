package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.persistence.AccessLogRepository;
import com.example.funerariamc.persistence.AppUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** RF-ACC-07 y RNF-AUD-01: registro y consulta de la bitácora de accesos. */
@Service
public class AccessLogService {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED_WRONG_PASSWORD = "LOGIN_FAILED: WRONG_PASSWORD";
    public static final String LOGIN_FAILED_DISABLED = "LOGIN_FAILED: ACCOUNT_DISABLED";
    public static final String LOGIN_FAILED_INACTIVE = "LOGIN_FAILED: INACTIVE_EMPLOYEE";
    public static final String LOGIN_FAILED_NO_ROLE = "LOGIN_FAILED: NO_ROLE";
    public static final String LOGOUT = "LOGOUT";
    public static final String OPEN_MODULE = "OPEN_MODULE: ";
    public static final String ACCESS_DENIED = "ACCESS_DENIED: ";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED: ";
    public static final String PASSWORD_RESET_FAILED = "PASSWORD_RESET_FAILED: INVALID_CODE";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String EMPLOYEE_CREATED = "EMPLOYEE_CREATED: ";
    public static final String EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED: ";
    public static final String EMPLOYEE_DEACTIVATED = "EMPLOYEE_DEACTIVATED: ";
    public static final String EMPLOYEE_ACTIVATED = "EMPLOYEE_ACTIVATED: ";
    public static final String CLIENT_CREATED = "CLIENT_CREATED: ";
    public static final String CLIENT_UPDATED = "CLIENT_UPDATED: ";

    /** Límite técnico de filas por página del contrato de bitácora EP-01. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * access_log.timestamp lo llena Supabase con su reloj, que está en UTC.
     * Colombia es UTC-5 (America/Bogota), así que se convierte al mostrar y al filtrar.
     */
    public static final ZoneId DB_ZONE = ZoneOffset.UTC;
    public static final ZoneId APP_ZONE = ZoneId.of("America/Bogota");

    /** Límites usados cuando el filtro de fecha queda vacío (la consulta no acepta null). */
    private static final LocalDateTime MIN_DATE = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime MAX_DATE = LocalDateTime.of(2100, 1, 1, 0, 0);

    private final AccessLogRepository accessLogRepository;
    private final AppUserRepository appUserRepository;

    public AccessLogService(AccessLogRepository accessLogRepository, AppUserRepository appUserRepository) {
        this.accessLogRepository = accessLogRepository;
        this.appUserRepository = appUserRepository;
    }

    /**
     * Guarda una entrada en su propia transacción: queda registrada aunque la
     * operación que la originó falle (por ejemplo, un login rechazado).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long appUserId, String action) {
        Objects.requireNonNull(appUserId, "El usuario de la bitácora es obligatorio.");
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("La acción de la bitácora es obligatoria.");
        }
        AccessLog entry = new AccessLog();
        entry.setAppUser(appUserRepository.getReferenceById(appUserId));
        entry.setAction(action);
        entry.setIpAddress(localIpAddress());
        accessLogRepository.save(entry);
    }

    /** Últimas entradas para la pantalla de bitácora (AccessLogController). */
    @Transactional(readOnly = true)
    public List<AccessLog> findRecent(int limit) {
        return accessLogRepository.findRecent(PageRequest.of(0, limit));
    }

    /**
     * Consulta una página con sus totales. La numeración empieza en cero y el
     * tamaño se ajusta al intervalo 1..100, según el contrato de la pantalla.
     * El repositorio entrega los registros ordenados y sus totales.
     */
    @Transactional(readOnly = true)
    public Page<AccessLog> findPage(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return accessLogRepository.findPage(PageRequest.of(safePage, safeSize));
    }

    /**
     * RF-ACC-08: página filtrada por fechas (días completos en hora de Colombia, ambos incluidos)
     * y por usuario (parte del nombre de usuario o del nombre del empleado). Lo que llegue
     * vacío o en null no filtra.
     */
    @Transactional(readOnly = true)
    public Page<AccessLog> findPage(int page, int size, LocalDate fromDate, LocalDate toDate, String userText) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidDataException("La fecha inicial no puede ser posterior a la fecha final.");
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDateTime from = fromDate == null ? MIN_DATE : startOfDayInDb(fromDate);
        LocalDateTime to = toDate == null ? MAX_DATE : startOfDayInDb(toDate.plusDays(1));
        String pattern = userText == null || userText.isBlank()
                ? "%"
                : "%" + userText.trim().toLowerCase(Locale.ROOT) + "%";
        return accessLogRepository.findPageFiltered(from, to, pattern, PageRequest.of(safePage, safeSize));
    }

    /** Pasa la hora guardada en la BD (UTC) a la hora de Colombia para mostrarla. */
    public static LocalDateTime toAppTime(LocalDateTime dbTimestamp) {
        if (dbTimestamp == null) {
            return null;
        }
        return dbTimestamp.atZone(DB_ZONE).withZoneSameInstant(APP_ZONE).toLocalDateTime();
    }

    /** Medianoche de ese día en Colombia, expresada en la hora de la BD (05:00 UTC). */
    private static LocalDateTime startOfDayInDb(LocalDate day) {
        return day.atStartOfDay(APP_ZONE).withZoneSameInstant(DB_ZONE).toLocalDateTime();
    }

    private String localIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
