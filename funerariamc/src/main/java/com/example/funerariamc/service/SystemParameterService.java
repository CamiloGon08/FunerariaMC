package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.model.enums.ParameterCategory;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Lectura tipada de los parámetros para los cálculos y su administración desde la pantalla
 * Parámetros del sistema (solo el Administrador Maestro).
 */
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

    /** Parámetro tal como lo muestra la pantalla; value es null si todavía no tiene valor ("Sin definir"). */
    public record ParameterView(String name, String title, String description, ParameterCategory category,
                                String unit, ParameterCatalog.Kind kind, String value,
                                LocalDateTime updatedAt, String updatedBy, boolean known) {

        public boolean defined() {
            return value != null;
        }
    }

    /**
     * Todos los parámetros para la pantalla: primero los que lee el sistema (en el orden del catálogo,
     * con o sin valor) y luego los que el administrador creó a mano. Solo el Administrador Maestro.
     */
    @Transactional(readOnly = true)
    public List<ParameterView> overview() {
        requireMaster();
        Map<String, SystemParameter> stored = new LinkedHashMap<>();
        for (SystemParameter parameter : parameterRepository.findAllWithEditor()) {
            stored.put(parameter.getParamName(), parameter);
        }
        List<ParameterView> views = new ArrayList<>();
        for (ParameterCatalog.Definition definition : ParameterCatalog.ALL) {
            SystemParameter parameter = stored.remove(definition.name());
            views.add(new ParameterView(definition.name(), definition.title(), definition.description(),
                    parameter == null ? definition.category() : parameter.getCategory(),
                    definition.unit(), definition.kind(),
                    parameter == null ? null : parameter.getParamValue(),
                    parameter == null ? null : parameter.getUpdatedAt(),
                    parameter == null ? null : editorName(parameter), true));
        }
        for (SystemParameter parameter : stored.values()) {
            views.add(new ParameterView(parameter.getParamName(), parameter.getParamName(), parameter.getDescription(),
                    parameter.getCategory(), parameter.getUnit(), ParameterCatalog.Kind.TEXT,
                    parameter.getParamValue(), parameter.getUpdatedAt(), editorName(parameter), false));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public boolean exists(String name) {
        return parameterRepository.existsByParamName(requiredName(name).toUpperCase(Locale.ROOT));
    }

    /**
     * Solo el Administrador Maestro cambia un valor. Si es un parámetro que el sistema lee y todavía
     * no está en la BD, se crea con los datos del catálogo. La bitácora guarda el valor anterior y el nuevo.
     */
    @Transactional
    public SystemParameter updateValue(String name, String newValue) {
        AppUser actor = requireMaster();
        String key = requiredName(name);
        Optional<ParameterCatalog.Definition> definition = ParameterCatalog.find(key);
        String value = checkedValue(definition.map(item -> item.kind()).orElse(ParameterCatalog.Kind.TEXT),
                newValue);
        Optional<SystemParameter> found = parameterRepository.findByParamName(key);
        SystemParameter parameter;
        String previous;
        if (found.isPresent()) {
            parameter = found.get();
            previous = parameter.getParamValue();
        } else {
            ParameterCatalog.Definition known = definition.orElseThrow(() -> new InvalidDataException(
                    "Falta un parámetro necesario para esta operación. Solicite su configuración al administrador."));
            parameter = new SystemParameter();
            parameter.setParamName(known.name());
            parameter.setDescription(known.description());
            parameter.setCategory(known.category());
            parameter.setUnit(known.unit());
            previous = null;
        }
        forget(key);
        parameter.setParamValue(value);
        parameter.setUpdatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
        parameter.setUpdatedBy(actor);
        if (parameter.getId() == null) {
            parameter = parameterRepository.save(parameter);
        }
        accessLogService.record(actor.getId(), AccessLogService.SYSTEM_PARAMETER_UPDATED
                + changeDetail(parameter.getParamName(), previous, value));
        return parameter;
    }

    /**
     * Crea un parámetro nuevo escrito por el administrador. Si ya existe uno con ese nombre no se
     * duplica: la pantalla ofrece cambiar su valor.
     */
    @Transactional
    public SystemParameter create(ParameterData data) {
        AppUser actor = requireMaster();
        if (data == null) {
            throw new InvalidDataException("Complete los datos del parámetro.");
        }
        String key = requiredName(data.name()).toUpperCase(Locale.ROOT);
        if (!key.matches("[A-Z][A-Z0-9_]{2,49}")) {
            throw new InvalidDataException("El nombre debe tener entre 3 y 50 caracteres: letras sin tilde, números"
                    + " y guion bajo, empezando por una letra (por ejemplo, TARIFA_BUS).");
        }
        if (parameterRepository.existsByParamName(key)) {
            throw new InvalidDataException("Ya existe un parámetro con ese nombre. Cambie su valor en lugar de crearlo de nuevo.");
        }
        if (data.category() == null) {
            throw new InvalidDataException("Elija la categoría del parámetro.");
        }
        Optional<ParameterCatalog.Definition> definition = ParameterCatalog.find(key);
        String value = checkedValue(definition.map(item -> item.kind()).orElse(ParameterCatalog.Kind.TEXT),
                data.value());
        String description = optional(data.description(), 150, "La descripción");
        String unit = optional(data.unit(), 10, "La unidad");
        SystemParameter parameter = new SystemParameter();
        parameter.setParamName(key);
        parameter.setParamValue(value);
        parameter.setDescription(description);
        parameter.setCategory(data.category());
        parameter.setUnit(unit);
        parameter.setUpdatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
        parameter.setUpdatedBy(actor);
        forget(key);
        SystemParameter saved = parameterRepository.save(parameter);
        accessLogService.record(actor.getId(), AccessLogService.SYSTEM_PARAMETER_CREATED + changeDetail(key, null, value));
        return saved;
    }

    /** Valida el valor según su tipo: los números se guardan sin puntos de miles ni signo de pesos. */
    static String checkedValue(ParameterCatalog.Kind kind, String raw) {
        String value = requiredValue(raw);
        switch (kind) {
            case WHOLE -> {
                String digits = value.replace(" ", "");
                if (!digits.matches("\\d+|\\d{1,3}(?:\\.\\d{3})+")) {
                    throw new InvalidDataException("El valor debe ser un número entero sin decimales.");
                }
                digits = digits.replace(".", "");
                if (!digits.matches("\\d{1,9}")) {
                    throw new InvalidDataException("El valor debe ser un número entero sin decimales.");
                }
                return String.valueOf(Long.parseLong(digits));
            }
            case AMOUNT -> {
                String clean = value.replace("$", "").replace(" ", "");
                if (clean.contains(",")) {
                    if (!clean.matches("-?(?:\\d+|\\d{1,3}(?:\\.\\d{3})+),\\d{1,2}")) {
                        throw new InvalidDataException("Revise el valor en pesos, por ejemplo 80.000,50.");
                    }
                    clean = clean.replace(".", "").replace(",", ".");
                } else if (clean.matches("-?\\d{1,3}(?:\\.\\d{3})+")) {
                    clean = clean.replace(".", "");
                } else if (!clean.matches("-?\\d+(?:\\.\\d{1,2})?")) {
                    throw new InvalidDataException("Revise el valor en pesos, por ejemplo 80.000,50 o 80000.50.");
                }
                BigDecimal amount;
                try {
                    amount = new BigDecimal(clean);
                } catch (NumberFormatException e) {
                    throw new InvalidDataException("El valor debe ser un valor en pesos, por ejemplo 80.000.");
                }
                if (amount.signum() < 0) {
                    throw new InvalidDataException("El valor en pesos no puede ser negativo.");
                }
                if (amount.scale() > 2) {
                    throw new InvalidDataException("El valor en pesos admite máximo dos decimales.");
                }
                return amount.stripTrailingZeros().toPlainString();
            }
            default -> {
                if (value.length() > 50) {
                    throw new InvalidDataException("El valor admite máximo 50 caracteres.");
                }
                return value;
            }
        }
    }

    /** "PLAN_WAITING_DAYS 62 -> 45" recortado para caber en la bitácora (100 caracteres en total). */
    static String changeDetail(String name, String previous, String value) {
        int room = 100 - AccessLogService.SYSTEM_PARAMETER_UPDATED.length() - name.length() - " ->  ".length();
        int half = Math.max(1, room / 2);
        return name + " " + cut(previous == null ? "-" : previous, half) + " -> " + cut(value, half);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private AppUser requireMaster() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.PARAMETROS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }

    private static String editorName(SystemParameter parameter) {
        AppUser user = parameter.getUpdatedBy();
        if (user == null) {
            return null;
        }
        if (user.getEmployee() != null) {
            return user.getEmployee().getName() + " " + user.getEmployee().getLastname();
        }
        return user.getUsername();
    }

    private static String optional(String text, int max, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.trim();
        if (clean.length() > max) {
            throw new InvalidDataException(what + " admite máximo " + max + " caracteres.");
        }
        return clean;
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
