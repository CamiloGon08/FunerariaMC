package com.example.funerariamc.service;

import com.example.funerariamc.exception.DuplicateDocumentException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.EmployeeRepository;
import com.example.funerariamc.persistence.PersonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Módulo de Personal (HU-02, HU-03, HU-04; RF-PER-02, 03, 04).
 * Solo el Administrador Maestro lo usa. Un empleado nunca se borra:
 * se desactiva y conserva su historial (RN-19).
 */
@Service
public class EmployeeService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final EmployeeRepository employeeRepository;
    private final PersonRepository personRepository;
    private final AppUserRepository appUserRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;

    public EmployeeService(EmployeeRepository employeeRepository, PersonRepository personRepository,
                           AppUserRepository appUserRepository, AccessLogService accessLogService,
                           SessionContext sessionContext) {
        this.employeeRepository = employeeRepository;
        this.personRepository = personRepository;
        this.appUserRepository = appUserRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /** Máximo de filas por página que acepta la búsqueda. */
    public static final int MAX_PAGE_SIZE = 50;

    /**
     * Página de la lista de Personal. text busca por nombre o documento (vacío trae todos);
     * status y position en null no filtran.
     */
    @Transactional(readOnly = true)
    public Page<Employee> search(String text, EmployeeStatus status, EmployeePosition position, int page, int size) {
        requireMasterAdmin();
        Collection<EmployeeStatus> statuses = status == null ? List.of(EmployeeStatus.values()) : List.of(status);
        Collection<Class<? extends Employee>> types = position == null
                ? Arrays.stream(EmployeePosition.values()).<Class<? extends Employee>>map(EmployeePosition::getType).toList()
                : List.of(position.getType());
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return employeeRepository.search(searchPattern(text), statuses, types, PageRequest.of(safePage, safeSize));
    }

    /** Cuántos empleados hay en ese estado; status en null cuenta a todos. */
    @Transactional(readOnly = true)
    public long count(EmployeeStatus status) {
        requireMasterAdmin();
        return status == null ? employeeRepository.count() : employeeRepository.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public Employee findById(Long id) {
        requireMasterAdmin();
        return getEmployee(id);
    }

    /** HU-02 y HU-03: crea la persona, el empleado y la fila del cargo en una transacción. */
    @Transactional
    public Employee register(EmployeeData data) {
        requireMasterAdmin();
        Long actorId = currentUserId();
        validate(data);
        String document = normalizeDocument(data.documentNumber());
        if (personRepository.existsByDocumentNumber(document)) {
            throw new DuplicateDocumentException(document);
        }

        Employee employee = data.position().newEmployee();
        copyData(employee, data, document);
        employee.activate();
        Employee saved = employeeRepository.save(employee);

        log(actorId, AccessLogService.EMPLOYEE_CREATED, saved);
        return saved;
    }

    /** Actualiza los datos. El cargo no cambia: cambiarlo sería otra fila en otra tabla hija. */
    @Transactional
    public Employee update(Long id, EmployeeData data) {
        requireMasterAdmin();
        Long actorId = currentUserId();
        validate(data);
        Employee employee = getEmployee(id);
        if (data.position() != EmployeePosition.of(employee)) {
            throw new InvalidDataException("El cargo no se puede cambiar desde este formulario.");
        }
        String document = normalizeDocument(data.documentNumber());
        if (personRepository.existsByDocumentNumberAndIdNot(document, id)) {
            throw new DuplicateDocumentException(document);
        }

        copyData(employee, data, document);
        log(actorId, AccessLogService.EMPLOYEE_UPDATED, employee);
        return employee;
    }

    /** HU-04 / RN-19: pasa a INACTIVE y deshabilita su cuenta si la tiene. */
    @Transactional
    public Employee deactivate(Long id) {
        requireMasterAdmin();
        Long actorId = currentUserId();
        Employee employee = getEmployee(id);
        boolean isCurrentUser = sessionContext.getCurrentUser()
                .map(AppUser::getEmployee)
                .map(Employee::getId)
                .filter(id::equals)
                .isPresent();
        if (isCurrentUser) {
            throw new InvalidDataException("No puede desactivar al empleado con el que inició sesión.");
        }

        employee.deactivate();
        appUserRepository.findByEmployee_Id(id).ifPresent(user -> user.setIsEnabled(false));
        log(actorId, AccessLogService.EMPLOYEE_DEACTIVATED, employee);
        return employee;
    }

    /** Reactiva al empleado y vuelve a habilitar su cuenta si la tiene. */
    @Transactional
    public Employee activate(Long id) {
        requireMasterAdmin();
        Long actorId = currentUserId();
        Employee employee = getEmployee(id);
        employee.activate();
        appUserRepository.findByEmployee_Id(id).ifPresent(user -> user.setIsEnabled(true));
        log(actorId, AccessLogService.EMPLOYEE_ACTIVATED, employee);
        return employee;
    }

    /** RN-26: la pantalla avisa que un conductor con licencia vencida no puede asignarse a servicios. */
    public boolean hasExpiredLicense(Employee employee) {
        return employee instanceof Driver driver
                && driver.getLicenseExpiryDate() != null
                && driver.getLicenseExpiryDate().isBefore(LocalDate.now(AccessLogService.APP_ZONE));
    }

    private Employee getEmployee(Long id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", id));
    }

    private void requireMasterAdmin() {
        boolean allowed = sessionContext.getRoleName()
                .map(RoleNames.MASTER_ADMIN::equals)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private void copyData(Employee employee, EmployeeData data, String document) {
        employee.setName(data.name().trim());
        employee.setLastname(data.lastname().trim());
        employee.setDocumentType(data.documentType().trim());
        employee.setDocumentNumber(document);
        employee.setPhone(blankToNull(data.phone()));
        employee.setEmail(blankToNull(data.email()));
        employee.setAddress(blankToNull(data.address()));
        employee.setBirthDate(data.birthDate());
        employee.setHireDate(data.hireDate());
        employee.setSalary(data.salary());

        if (employee instanceof FuneralSalesperson salesperson) {
            salesperson.setCommissionRate(data.commissionRate());
        } else if (employee instanceof Thanatopractor thanatopractor) {
            thanatopractor.setTrainingInstitution(data.trainingInstitution().trim());
            thanatopractor.setCertificationDate(data.certificationDate());
            thanatopractor.setCertificateFileUrl(data.certificateFileUrl().trim());
            thanatopractor.setCommissionPerCase(data.commissionPerCase());
        } else if (employee instanceof Driver driver) {
            driver.setLicenseCategory(data.licenseCategory().trim());
            driver.setLicenseNumber(data.licenseNumber().trim());
            driver.setLicenseExpiryDate(data.licenseExpiryDate());
            driver.setLicenseFileUrl(data.licenseFileUrl().trim());
        }
    }

    private void validate(EmployeeData data) {
        if (data == null) {
            throw new InvalidDataException("Los datos del empleado son obligatorios.");
        }
        require(data.name(), "Los nombres son obligatorios.");
        require(data.lastname(), "Los apellidos son obligatorios.");
        require(data.documentType(), "El tipo de documento es obligatorio.");
        require(data.documentNumber(), "El número de documento es obligatorio.");
        String document = normalizeDocument(data.documentNumber());
        if (!document.matches("[A-Za-z0-9]+")) {
            throw new InvalidDataException("El número de documento debe contener letras o dígitos.");
        }
        maxLength(data.name(), 100, "Los nombres");
        maxLength(data.lastname(), 100, "Los apellidos");
        maxLength(data.documentType(), 20, "El tipo de documento");
        maxLength(document, 30, "El número de documento");
        maxLength(data.phone(), 20, "El teléfono");
        maxLength(data.email(), 120, "El correo");
        maxLength(data.address(), 200, "La dirección");
        if (data.position() == null) {
            throw new InvalidDataException("Seleccione el cargo.");
        }
        if (data.hireDate() == null) {
            throw new InvalidDataException("La fecha de ingreso es obligatoria.");
        }
        if (data.hireDate().isAfter(LocalDate.now(AccessLogService.APP_ZONE))) {
            throw new InvalidDataException("La fecha de ingreso no puede ser futura.");
        }
        if (data.salary() == null || data.salary().signum() <= 0) {
            throw new InvalidDataException("El salario debe ser mayor que cero.");
        }
        if (data.email() != null && !data.email().isBlank() && !data.email().trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new InvalidDataException("El correo no es válido.");
        }

        switch (data.position()) {
            case FUNERAL_SALESPERSON -> {
                BigDecimal rate = data.commissionRate();
                if (rate == null || rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
                    throw new InvalidDataException("El porcentaje de comisión debe estar entre 0 y 100.");
                }
            }
            case THANATOPRACTOR -> {
                // RN-18: se exige la formación, nunca un número de licencia.
                require(data.trainingInstitution(), "La institución de formación es obligatoria.");
                if (data.certificationDate() == null) {
                    throw new InvalidDataException("La fecha de certificación es obligatoria.");
                }
                require(data.certificateFileUrl(), "Cargue el archivo del certificado.");
                if (data.commissionPerCase() == null || data.commissionPerCase().signum() < 0) {
                    throw new InvalidDataException("La comisión por caso es obligatoria.");
                }
            }
            case DRIVER -> {
                require(data.licenseCategory(), "La categoría de la licencia es obligatoria.");
                require(data.licenseNumber(), "El número de licencia es obligatorio.");
                if (data.licenseExpiryDate() == null) {
                    throw new InvalidDataException("La fecha de vencimiento de la licencia es obligatoria.");
                }
                require(data.licenseFileUrl(), "Cargue el archivo de la licencia.");
            }
            default -> {
                // Los demás cargos no tienen datos propios.
            }
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser()
                .map(AppUser::getId)
                .orElseThrow(UnauthorizedActionException::new);
    }

    private void log(Long actorId, String action, Employee employee) {
        accessLogService.record(actorId, action + employee.getDocumentNumber());
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException(message);
        }
    }

    private static void maxLength(String value, int max, String what) {
        if (value != null && value.trim().length() > max) {
            throw new InvalidDataException(what + " no puede superar " + max + " caracteres.");
        }
    }

    /** "1.065.234.118" y "1065234118" son el mismo documento. */
    private static String normalizeDocument(String document) {
        return document.replaceAll("[.\\s-]", "").trim();
    }

    /** "%texto%" en minúsculas; "1.065.234" se busca como "1065234". */
    private static String searchPattern(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String trimmed = text.trim();
        String value = trimmed.matches("[\\d.\\s-]+") ? normalizeDocument(trimmed) : trimmed.toLowerCase(Locale.ROOT);
        return "%" + value + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
