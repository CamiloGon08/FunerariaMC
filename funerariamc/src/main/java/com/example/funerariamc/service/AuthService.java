package com.example.funerariamc.service;

import com.example.funerariamc.exception.AuthenticationFailedException;
import com.example.funerariamc.exception.InactiveEmployeeException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.persistence.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Inicio de sesión y control de acceso por rol (RF-ACC-01, 04, 05, 06; RN-21, RN-22). */
@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;

    public AuthService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder,
                       AccessLogService accessLogService, SessionContext sessionContext) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /**
     * Valida usuario y contraseña, las condiciones de la cuenta y deja la
     * sesión iniciada. Cada intento de un usuario existente queda en la bitácora.
     */
    @Transactional
    public AppUser login(String username, String rawPassword) {
        if (isBlank(username) || isBlank(rawPassword)) {
            throw AuthenticationFailedException.invalidCredentials();
        }

        // Usuario inexistente: mismo mensaje genérico y sin bitácora (access_log exige app_user_id).
        AppUser user = appUserRepository.findByUsernameWithEmployeeAndRole(username.trim())
                .orElseThrow(AuthenticationFailedException::invalidCredentials);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            accessLogService.record(user.getId(), AccessLogService.LOGIN_FAILED_WRONG_PASSWORD);
            throw AuthenticationFailedException.invalidCredentials();
        }
        // is_enabled admite nulo en la BD; nulo cuenta como habilitado.
        if (Boolean.FALSE.equals(user.getIsEnabled())) {
            accessLogService.record(user.getId(), AccessLogService.LOGIN_FAILED_DISABLED);
            throw AuthenticationFailedException.disabledAccount();
        }

        Employee employee = user.getEmployee();
        if (employee == null || !employee.isActive()) {
            accessLogService.record(user.getId(), AccessLogService.LOGIN_FAILED_INACTIVE);
            throw new InactiveEmployeeException();
        }
        if (employee.getRole() == null) {
            accessLogService.record(user.getId(), AccessLogService.LOGIN_FAILED_NO_ROLE);
            throw AuthenticationFailedException.withoutRole();
        }

        user.setLastLogin(LocalDateTime.now());
        accessLogService.record(user.getId(), AccessLogService.LOGIN_SUCCESS);
        startSessionAfterCommit(user);
        return user;
    }

    public void logout() {
        sessionContext.getCurrentUser()
                .ifPresent(user -> accessLogService.record(user.getId(), AccessLogService.LOGOUT));
        sessionContext.clear();
    }

    /** RF-ACC-04: módulos que se muestran en el menú del rol actual. */
    public List<AppModule> visibleModules() {
        return Arrays.stream(AppModule.values())
                .filter(this::canAccess)
                .toList();
    }

    public boolean canAccess(AppModule module) {
        if (module == null || sessionContext.getCurrentUser()
                .map(user -> Boolean.TRUE.equals(user.getMustChangePassword())).orElse(false)) {
            return false;
        }
        return sessionContext.getRoleName()
                .map(module::isAllowedFor)
                .orElse(false);
    }

    /**
     * RF-ACC-05: valida el permiso antes de abrir un módulo y deja la entrada
     * en la bitácora, tanto si se concede como si se niega.
     */
    public void checkAccess(AppModule module) {
        Objects.requireNonNull(module, "El módulo es obligatorio.");
        AppUser user = sessionContext.getCurrentUser()
                .orElseThrow(UnauthorizedActionException::new);
        if (!canAccess(module)) {
            accessLogService.record(user.getId(), AccessLogService.ACCESS_DENIED + module.name());
            throw new UnauthorizedActionException();
        }
        accessLogService.record(user.getId(), AccessLogService.OPEN_MODULE + module.name());
    }

    private void startSessionAfterCommit(AppUser user) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessionContext.start(user);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionContext.start(user);
            }
        });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
