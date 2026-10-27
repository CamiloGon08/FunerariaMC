package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Lectura tipada y actualización de parámetros existentes (RF-FIN-07, RNF-LEG-01). */
@Service
public class SystemParameterService {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record CachedValue(String value, long loadedAtNanos) {}

    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final SystemParameterRepository parameterRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final LongSupplier nanoTime;

    @Autowired
    public SystemParameterService(SystemParameterRepository parameterRepository,
                                  AccessLogService accessLogService, SessionContext sessionContext) {
        this(parameterRepository, accessLogService, sessionContext, System::nanoTime);
    }

    SystemParameterService(SystemParameterRepository parameterRepository,
                           AccessLogService accessLogService, SessionContext sessionContext,
                           LongSupplier nanoTime) {
        this.parameterRepository = parameterRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.nanoTime = nanoTime;
    }

    /** Lectura interna para los servicios; nunca crea valores por defecto. */
    public String getString(String name) {
        String key = requiredName(name);
        // Una transacción de escritura debe ver sus propios cambios sin compartirlos en la caché.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return requiredValue(findParameter(key).getParamValue());
        }
        CachedValue observed = cache.computeIfAbsent(key, ignored -> new CachedValue(null, 0));
        if (observed.value() != null && nanoTime.getAsLong() - observed.loadedAtNanos() < CACHE_TTL.toNanos()) {
            return observed.value();
        }
        String value = requiredValue(findParameter(key).getParamValue());
        CachedValue loaded = new CachedValue(value, nanoTime.getAsLong());
        // Una invalidación durante la consulta cambia la entrada e impide publicar el valor anterior.
        cache.computeIfPresent(key, (ignored, current) -> current == observed ? loaded : current);
        return value;
    }

    public long getLong(String name) {
        try {
            return Long.parseLong(getString(name));
        } catch (NumberFormatException failure) {
            throw new InvalidDataException("El parámetro solicitado debe contener un número entero válido.");
        }
    }

    public BigDecimal getBigDecimal(String name) {
        try {
            return new BigDecimal(getString(name));
        } catch (NumberFormatException failure) {
            throw new InvalidDataException("El parámetro solicitado debe contener un número decimal válido.");
        }
    }

    /** Solo el Administrador Maestro modifica un parámetro ya registrado; conserva su identidad. */
    @Transactional
    public SystemParameter updateValue(String name, String newValue) {
        boolean allowed = sessionContext.getRoleName().map(AppModule.PARAMETROS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        String value = requiredValue(newValue);
        SystemParameter parameter = findParameter(name);
        forget(requiredName(name));
        parameter.setParamValue(value);
        parameter.setUpdatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
        parameter.setUpdatedBy(actor);
        accessLogService.record(actor.getId(), AccessLogService.SYSTEM_PARAMETER_UPDATED + parameter.getParamName());
        return parameter;
    }

    private void forget(String key) {
        invalidate(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // Puede haberse leído el valor anterior antes del commit o de la reversión.
                    invalidate(key);
                }
            });
        }
    }

    private void invalidate(String key) {
        cache.put(key, new CachedValue(null, 0));
    }

    private SystemParameter findParameter(String name) {
        return parameterRepository.findByParamName(requiredName(name))
                .orElseThrow(() -> new InvalidDataException(
                        "Falta un parámetro necesario para esta operación. Solicite su configuración al administrador."));
    }

    private static String requiredName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidDataException("El nombre del parámetro es obligatorio.");
        }
        return name.trim();
    }

    private static String requiredValue(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("El valor del parámetro es obligatorio.");
        }
        return value.trim();
    }
}
