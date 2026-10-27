package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.model.enums.ParameterCategory;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemParameterServiceTest {

    private final SystemParameterRepository parameters = mock(SystemParameterRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final SystemParameterService service = new SystemParameterService(parameters, audit, session);
    private SystemParameter stored;

    @BeforeEach
    void setUp() {
        Manager employee = new Manager();
        employee.setRole(new Role(1L, RoleNames.MASTER_ADMIN, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
        stored = new SystemParameter();
        stored.setId(7L);
        stored.setParamName(PlanParameters.WAITING_DAYS);
        stored.setParamValue("62");
        stored.setCategory(ParameterCategory.PLAN);
        stored.setUnit("días");
        when(parameters.findByParamName(PlanParameters.WAITING_DAYS)).thenReturn(Optional.of(stored));
    }

    @Test
    void internalReadsUseThePersistedValueWithoutCreatingDefaults() {
        session.clear();
        stored.setParamValue(" 31 ");
        assertEquals("31", service.getString(" " + PlanParameters.WAITING_DAYS + " "));
        assertEquals(31L, service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue("150000.50");
        assertEquals(new BigDecimal("150000.50"), service.getBigDecimal(PlanParameters.WAITING_DAYS));
        verify(parameters, never()).save(any());
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.5", "9999999999999999999999999", "valor inválido"})
    void integerReadsRejectFractionsOverflowAndText(String value) {
        stored.setParamValue(value);
        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.getLong(PlanParameters.WAITING_DAYS));
        assertFalse(error.getMessage().contains(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "150,50", "--"})
    void decimalReadsRejectInvalidNumbers(String value) {
        stored.setParamValue(value);
        assertThrows(InvalidDataException.class, () -> service.getBigDecimal(PlanParameters.WAITING_DAYS));
    }

    @Test
    void missingParametersRaiseBusinessErrorsInsteadOfCreatingDefaults() {
        assertThrows(InvalidDataException.class, () -> service.getString("MISSING"));
        assertThrows(InvalidDataException.class, () -> service.updateValue("MISSING", "10"));
        verify(parameters, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void blankNamesAndUpdateValuesAreRejectedBeforeRepositoryAccess() {
        assertThrows(InvalidDataException.class, () -> service.getString(null));
        assertThrows(InvalidDataException.class, () -> service.getString(" "));
        assertThrows(InvalidDataException.class, () -> service.updateValue(PlanParameters.WAITING_DAYS, null));
        assertThrows(InvalidDataException.class, () -> service.updateValue(PlanParameters.WAITING_DAYS, " "));
        verifyNoInteractions(parameters, audit);
    }

    @Test
    void emptyStoredValuesDoNotBecomeDefaults() {
        stored.setParamValue(" ");
        assertThrows(InvalidDataException.class, () -> service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue(null);
        assertThrows(InvalidDataException.class, () -> service.getBigDecimal(PlanParameters.WAITING_DAYS));
    }

    @Test
    void masterAdminUpdatesExistingValueWithActorAndColombianTime() {
        LocalDateTime before = LocalDateTime.now(AccessLogService.APP_ZONE);
        AppUser actor = session.getCurrentUser().orElseThrow();

        SystemParameter updated = service.updateValue(PlanParameters.WAITING_DAYS, " 45 ");

        assertSame(stored, updated);
        assertEquals(7L, updated.getId());
        assertEquals(PlanParameters.WAITING_DAYS, updated.getParamName());
        assertEquals(ParameterCategory.PLAN, updated.getCategory());
        assertEquals("días", updated.getUnit());
        assertEquals("45", updated.getParamValue());
        assertSame(actor, updated.getUpdatedBy());
        assertFalse(updated.getUpdatedAt().isBefore(before));
        assertFalse(updated.getUpdatedAt().isAfter(LocalDateTime.now(AccessLogService.APP_ZONE)));
        verify(audit).record(5L, AccessLogService.SYSTEM_PARAMETER_UPDATED + PlanParameters.WAITING_DAYS);
        verify(parameters, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {RoleNames.SECRETARY, RoleNames.SALESPERSON, RoleNames.CEMETERY_ADMIN})
    void otherRolesCannotUpdateParameters(String role) {
        session.getCurrentUser().orElseThrow().getEmployee().setRole(new Role(2L, role, null));
        assertThrows(UnauthorizedActionException.class, () -> service.updateValue(PlanParameters.WAITING_DAYS, "45"));
        verifyNoInteractions(parameters, audit);
    }

    @Test
    void anonymousOrUnidentifiedActorCannotUpdateParameters() {
        session.getCurrentUser().orElseThrow().setId(null);
        assertThrows(UnauthorizedActionException.class, () -> service.updateValue(PlanParameters.WAITING_DAYS, "45"));
        session.clear();
        assertThrows(UnauthorizedActionException.class, () -> service.updateValue(PlanParameters.WAITING_DAYS, "45"));
        verifyNoInteractions(parameters, audit);
    }

    @Test
    void auditFailureRollsBackTheParameterUpdate() {
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).record(anyLong(), anyString());
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        SystemParameterService proxy = (SystemParameterService) factory.getProxy();

        assertThrows(IllegalStateException.class, () -> proxy.updateValue(PlanParameters.WAITING_DAYS, "45"));

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }
}
