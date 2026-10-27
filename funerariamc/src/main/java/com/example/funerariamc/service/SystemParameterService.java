package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Lectura tipada y actualización de parámetros existentes (RF-FIN-07, RNF-LEG-01). */
@Service
public class SystemParameterService {

    private final SystemParameterRepository parameterRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;

    public SystemParameterService(SystemParameterRepository parameterRepository,
                                  AccessLogService accessLogService, SessionContext sessionContext) {
        this.parameterRepository = parameterRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /** Lectura interna para los servicios; nunca crea valores por defecto. */
    @Transactional(readOnly = true)
    public String getString(String name) {
        return requiredValue(findParameter(name).getParamValue());
    }

    @Transactional(readOnly = true)
    public long getLong(String name) {
        try {
            return Long.parseLong(getString(name));
        } catch (NumberFormatException failure) {
            throw new InvalidDataException("El parámetro solicitado debe contener un número entero válido.");
        }
    }

    @Transactional(readOnly = true)
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
        parameter.setParamValue(value);
        parameter.setUpdatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
        parameter.setUpdatedBy(actor);
        accessLogService.record(actor.getId(), AccessLogService.SYSTEM_PARAMETER_UPDATED + parameter.getParamName());
        return parameter;
    }

    private SystemParameter findParameter(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidDataException("El nombre del parámetro es obligatorio.");
        }
        return parameterRepository.findByParamName(name.trim())
                .orElseThrow(() -> new InvalidDataException(
                        "Falta un parámetro necesario para esta operación. Solicite su configuración al administrador."));
    }

    private static String requiredValue(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("El valor del parámetro es obligatorio.");
        }
        return value.trim();
    }
}
