package com.example.funerariamc.service;

import com.example.funerariamc.model.AccessLog;
import com.example.funerariamc.persistence.AccessLogRepository;
import com.example.funerariamc.persistence.AppUserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
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

    private String localIpAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
