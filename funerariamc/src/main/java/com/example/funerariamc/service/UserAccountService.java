package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.EmployeePosition;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.persistence.AppUserRepository;
import com.example.funerariamc.persistence.EmployeeRepository;
import com.example.funerariamc.persistence.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cuentas de acceso del personal (HU-06, HU-07; RN-21, RN-23).
 * El Administrador Maestro crea la cuenta desde Personal con una contraseña
 * temporal; el empleado la cambia obligatoriamente en su primer ingreso.
 */
@Service
public class UserAccountService {

    /** Mismo mínimo que la recuperación de contraseña. */
    public static final int MIN_PASSWORD_LENGTH = PasswordResetService.MIN_PASSWORD_LENGTH;
    /** app_user.username es varchar(25). */
    public static final int MAX_USERNAME_LENGTH = 25;

    /** Sin 0/O, 1/l/I para que la contraseña temporal se pueda dictar sin confusiones. */
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPER + LOWER + DIGITS;

    private final AppUserRepository appUserRepository;
    private final EmployeeRepository employeeRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final SecureRandom random = new SecureRandom();

    public UserAccountService(AppUserRepository appUserRepository, EmployeeRepository employeeRepository,
                              RoleRepository roleRepository, PasswordEncoder passwordEncoder,
                              AccessLogService accessLogService, SessionContext sessionContext) {
        this.appUserRepository = appUserRepository;
        this.employeeRepository = employeeRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /** Usuario y contraseña temporal que la pantalla muestra una sola vez. */
    public record AccountCredentials(String username, String temporaryPassword) {
    }

    /**
     * Lo que Personal muestra de una cuenta. Se arma dentro de la transacción porque
     * el rol (employee.role) es LAZY y no se puede leer después desde la pantalla.
     */
    public record AccountSummary(String username, String roleName, boolean enabled,
                                 boolean mustChangePassword, LocalDateTime lastLogin) {

        static AccountSummary of(AppUser user) {
            Role role = user.getEmployee().getRole();
            return new AccountSummary(user.getUsername(), role == null ? null : role.getName(),
                    !Boolean.FALSE.equals(user.getIsEnabled()),
                    Boolean.TRUE.equals(user.getMustChangePassword()), user.getLastLogin());
        }
    }

    /**
     * Roles que puede tener cada cargo. El primero es el sugerido.
     * Los cargos sin acceso al sistema no tienen ninguno (RN-23).
     */
    public static List<String> rolesFor(EmployeePosition position) {
        if (position == null) {
            return List.of();
        }
        return switch (position) {
            case MANAGER -> List.of(RoleNames.CEMETERY_ADMIN, RoleNames.MASTER_ADMIN);
            case RECEPTIONIST -> List.of(RoleNames.SECRETARY);
            case FUNERAL_SALESPERSON -> List.of(RoleNames.SALESPERSON);
            default -> List.of();
        };
    }

    /** "Facundo" "Liñán Pérez" → "flinan" (sin tildes ni eñes). Si ya existe, agrega 2, 3... */
    @Transactional(readOnly = true)
    public String suggestUsername(String name, String lastname) {
        requireMasterAdmin();
        String first = asciiLetters(name);
        String last = asciiLetters(lastname == null ? "" : lastname.trim().split("\\s+")[0]);
        String base = (first.isEmpty() ? "" : first.substring(0, 1)) + last;
        if (base.length() < 4) {
            base = first + last;
        }
        while (base.length() < 4) {
            base = base + "0";
        }
        base = base.substring(0, Math.min(base.length(), MAX_USERNAME_LENGTH - 2));
        String candidate = base;
        for (int n = 2; appUserRepository.existsByUsernameIgnoreCase(candidate); n++) {
            String suffix = Integer.toString(n);
            candidate = base.substring(0, Math.min(base.length(), MAX_USERNAME_LENGTH - suffix.length())) + suffix;
        }
        return candidate;
    }

    @Transactional(readOnly = true)
    public Optional<AccountSummary> findAccount(Long employeeId) {
        requireMasterAdmin();
        return appUserRepository.findByEmployee_Id(employeeId).map(AccountSummary::of);
    }

    /** Cuentas de varios empleados a la vez (una consulta por página de Personal). */
    @Transactional(readOnly = true)
    public Map<Long, AccountSummary> findAccounts(Collection<Long> employeeIds) {
        requireMasterAdmin();
        if (employeeIds == null || employeeIds.isEmpty()) {
            return Map.of();
        }
        return appUserRepository.findByEmployee_IdIn(employeeIds).stream()
                .collect(Collectors.toMap(user -> user.getEmployee().getId(), AccountSummary::of));
    }

    /** Indicador "Con cuenta de acceso": cuentas de empleados activos. */
    @Transactional(readOnly = true)
    public long countActiveAccounts() {
        requireMasterAdmin();
        return appUserRepository.countByEmployee_Status(EmployeeStatus.ACTIVE);
    }

    /**
     * Revisa usuario y rol antes de guardar al empleado, para que el formulario
     * no deje un empleado guardado con una cuenta que después falla.
     */
    @Transactional(readOnly = true)
    public void validateNewAccount(EmployeePosition position, String username, String roleName) {
        requireMasterAdmin();
        checkPositionAndRole(position, roleName);
        checkUsername(username);
        findRole(roleName);
    }

    /** HU-06: crea la cuenta con contraseña temporal y le asigna el rol al empleado. */
    @Transactional
    public AccountCredentials createAccount(Long employeeId, String username, String roleName) {
        requireMasterAdmin();
        Long actorId = currentUserId();
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", employeeId));
        if (!employee.isActive()) {
            throw new InvalidDataException("Active al empleado antes de crearle una cuenta.");
        }
        checkPositionAndRole(EmployeePosition.of(employee), roleName);
        if (appUserRepository.existsByEmployee_Id(employeeId)) {
            throw new InvalidDataException("Este empleado ya tiene una cuenta de acceso.");
        }
        String cleanUsername = checkUsername(username);
        Role role = findRole(roleName);

        String temporaryPassword = temporaryPassword();
        AppUser user = new AppUser();
        user.setEmployee(employee);
        user.setUsername(cleanUsername);
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setIsEnabled(true);
        user.setMustChangePassword(true);
        appUserRepository.save(user);
        // HU-07: el rol va en employee.role_id.
        employee.setRole(role);

        accessLogService.record(actorId, AccessLogService.USER_ACCOUNT_CREATED + cleanUsername);
        return new AccountCredentials(cleanUsername, temporaryPassword);
    }

    /** Primer ingreso: el usuario de la sesión cambia su contraseña temporal por una propia. */
    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword, String confirmation) {
        AppUser sessionUser = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        Long userId = currentUserId();
        AppUser user = appUserRepository.findById(userId)
                .orElseThrow(UnauthorizedActionException::new);
        if (Boolean.FALSE.equals(user.getIsEnabled()) || user.getEmployee() == null || !user.getEmployee().isActive()) {
            throw new UnauthorizedActionException();
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidDataException("La contraseña actual no es correcta.");
        }
        validateNewPassword(newPassword, confirmation);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidDataException("La nueva contraseña debe ser distinta de la temporal.");
        }
        String passwordHash = passwordEncoder.encode(newPassword);
        accessLogService.record(userId, AccessLogService.PASSWORD_CHANGED);
        user.setPasswordHash(passwordHash);
        user.setMustChangePassword(false);
        releaseSessionAfterCommit(sessionUser);
    }

    /** Reglas de la contraseña nueva: mínimo 8, con letras y números, y confirmada. */
    static void validateNewPassword(String password, String confirmation) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidDataException("La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres.");
        }
        if (!password.matches(".*\\p{L}.*") || !password.matches(".*\\d.*")) {
            throw new InvalidDataException("La contraseña debe tener letras y números.");
        }
        if (!password.equals(confirmation)) {
            throw new InvalidDataException("Las contraseñas no coinciden.");
        }
    }

    /** La sesión solo sale del cambio obligatorio cuando la transacción confirma la contraseña. */
    private void releaseSessionAfterCommit(AppUser originalSessionUser) {
        Runnable release = () -> sessionContext.getCurrentUser()
                .filter(current -> current == originalSessionUser)
                .ifPresent(current -> current.setMustChangePassword(false));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                release.run();
            }
        });
    }

    private Role findRole(String roleName) {
        return roleRepository.findByName(roleName)
                .orElseThrow(() -> new InvalidDataException(
                        "El rol seleccionado no está disponible. Contacte al administrador."));
    }

    private void checkPositionAndRole(EmployeePosition position, String roleName) {
        List<String> allowed = rolesFor(position);
        if (allowed.isEmpty()) {
            throw new InvalidDataException("Este cargo no tiene acceso al sistema (RN-23).");
        }
        if (roleName == null || !allowed.contains(roleName)) {
            throw new InvalidDataException("Seleccione un rol válido para el cargo " + position.getDisplayName() + ".");
        }
    }

    /** 4 a 25 caracteres: letras minúsculas, números, punto o guion bajo. Devuelve el usuario limpio. */
    private String checkUsername(String username) {
        String clean = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (clean.length() < 4 || clean.length() > MAX_USERNAME_LENGTH) {
            throw new InvalidDataException("El usuario debe tener entre 4 y " + MAX_USERNAME_LENGTH + " caracteres.");
        }
        if (!clean.matches("[a-z0-9._]+")) {
            throw new InvalidDataException("El usuario solo puede tener letras sin tilde, números, punto o guion bajo.");
        }
        if (appUserRepository.existsByUsernameIgnoreCase(clean)) {
            throw new InvalidDataException("Ese nombre de usuario ya existe. Elija otro.");
        }
        return clean;
    }

    /** Formato "Kp7m-Qx4w": ocho caracteres aleatorios y un separador, con mayúscula, minúscula y número. */
    String temporaryPassword() {
        char[] chars = new char[8];
        chars[0] = pick(UPPER);
        chars[1] = pick(LOWER);
        chars[2] = pick(DIGITS);
        for (int i = 3; i < chars.length; i++) {
            chars[i] = pick(ALL);
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        String value = new String(chars);
        return value.substring(0, 4) + "-" + value.substring(4);
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    private static String asciiLetters(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return plain.replaceAll("[^a-z0-9]", "");
    }

    private void requireMasterAdmin() {
        boolean allowed = sessionContext.getRoleName()
                .map(RoleNames.MASTER_ADMIN::equals)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser()
                .map(user -> user.getId())
                .orElseThrow(UnauthorizedActionException::new);
    }
}
