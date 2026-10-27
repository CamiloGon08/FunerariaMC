package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.SystemParameter;
import com.example.funerariamc.model.enums.ParameterCategory;
import com.example.funerariamc.persistence.SystemParameterRepository;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemParameterServiceTest {

    private final SystemParameterRepository parameters = mock(SystemParameterRepository.class);
    private final AccessLogService audit = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final AtomicLong nowNanos = new AtomicLong();
    private final SystemParameterService service = new SystemParameterService(parameters, audit, session, nowNanos::get);
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

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void internalReadsUseThePersistedValueWithoutCreatingDefaults() {
        session.clear();
        stored.setParamValue(" 31 ");
        assertEquals("31", service.getString(" " + PlanParameters.WAITING_DAYS + " "));
        assertEquals(31L, service.getLong(PlanParameters.WAITING_DAYS));
        when(parameters.findByParamName(PlanParameters.NON_DIRECT_SURCHARGE))
                .thenReturn(Optional.of(parameter(PlanParameters.NON_DIRECT_SURCHARGE, "150000.50")));
        assertEquals(new BigDecimal("150000.50"), service.getBigDecimal(PlanParameters.NON_DIRECT_SURCHARGE));
        verify(parameters).findByParamName(PlanParameters.WAITING_DAYS);
        verify(parameters, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void repeatedTypedReadsShareOneQueryAndDoNotOpenTransactions() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        SystemParameterService proxy = transactionalProxy(transactions);

        assertEquals("62", proxy.getString(PlanParameters.WAITING_DAYS));
        assertEquals(62L, proxy.getLong(" " + PlanParameters.WAITING_DAYS + " "));
        assertEquals(new BigDecimal("62"), proxy.getBigDecimal(PlanParameters.WAITING_DAYS));

        verify(parameters).findByParamName(PlanParameters.WAITING_DAYS);
        verifyNoInteractions(transactions, audit);
    }

    @Test
    void cachedValuesExpireAfterSixtySecondsWithoutExtendingTheirLifetimeOnReads() {
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue("45");
        nowNanos.set(SystemParameterService.CACHE_TTL.toNanos() - 1);
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        nowNanos.incrementAndGet();
        assertEquals(45L, service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue("30");
        nowNanos.addAndGet(SystemParameterService.CACHE_TTL.toNanos());
        assertEquals(30L, service.getLong(PlanParameters.WAITING_DAYS));
        verify(parameters, times(3)).findByParamName(PlanParameters.WAITING_DAYS);
    }

    @Test
    void expiredValuesAreNotUsedAsFallbackWhenTheParameterDisappears() {
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        nowNanos.set(SystemParameterService.CACHE_TTL.toNanos());
        when(parameters.findByParamName(PlanParameters.WAITING_DAYS)).thenReturn(Optional.empty());
        assertThrows(InvalidDataException.class, () -> service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue("45");
        when(parameters.findByParamName(PlanParameters.WAITING_DAYS)).thenReturn(Optional.of(stored));
        assertEquals(45L, service.getLong(PlanParameters.WAITING_DAYS));
    }

    @Test
    void updatesInvalidateTheChangedParameterWithoutDiscardingOtherParameters() {
        when(parameters.findByParamName(PlanParameters.BENEFICIARY_LIMIT))
                .thenReturn(Optional.of(parameter(PlanParameters.BENEFICIARY_LIMIT, "7")));
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        assertEquals(7L, service.getLong(PlanParameters.BENEFICIARY_LIMIT));

        service.updateValue(" " + PlanParameters.WAITING_DAYS + " ", "45");

        assertEquals(45L, service.getLong(PlanParameters.WAITING_DAYS));
        assertEquals(7L, service.getLong(PlanParameters.BENEFICIARY_LIMIT));
        verify(parameters, times(3)).findByParamName(PlanParameters.WAITING_DAYS);
        verify(parameters).findByParamName(PlanParameters.BENEFICIARY_LIMIT);
    }

    @Test
    void writeTransactionsSeeTheirOwnChangesWithoutSharingUncommittedValues() {
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        beginTransaction();
        stored.setParamValue("45");
        assertEquals(45L, service.getLong(PlanParameters.WAITING_DAYS));
        stored.setParamValue("46");
        assertEquals(46L, service.getLong(PlanParameters.WAITING_DAYS));
        TransactionSynchronizationManager.clear();
        stored.setParamValue("62");
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        verify(parameters, times(3)).findByParamName(PlanParameters.WAITING_DAYS);
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK})
    void transactionCompletionDiscardsValuesReadBeforeCommitOrRollback(int completion) throws Exception {
        SystemParameter committed = parameter(PlanParameters.WAITING_DAYS, "62");
        when(parameters.findByParamName(PlanParameters.WAITING_DAYS)).thenAnswer(invocation ->
                Optional.of(TransactionSynchronizationManager.isActualTransactionActive() ? stored : committed));
        assertEquals(62L, service.getLong(PlanParameters.WAITING_DAYS));
        beginTransaction();
        service.updateValue(PlanParameters.WAITING_DAYS, "45");
        // Otro lector aún ve el valor confirmado anterior y puede volver a guardarlo en la caché.
        assertEquals(62L, CompletableFuture.supplyAsync(() -> service.getLong(PlanParameters.WAITING_DAYS))
                .get(5, TimeUnit.SECONDS));
        String finalValue = completion == TransactionSynchronization.STATUS_COMMITTED ? "45" : "62";
        committed.setParamValue(finalValue);
        completeTransaction(completion);

        assertEquals(finalValue, service.getString(PlanParameters.WAITING_DAYS));
        verify(parameters, times(4)).findByParamName(PlanParameters.WAITING_DAYS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsStartedBeforeInvalidationCannotRestoreStaleValuesAfterCommit(boolean startAfterUpdate) throws Exception {
        Thread updatingThread = Thread.currentThread();
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch finishQuery = new CountDownLatch(1);
        AtomicBoolean blockFirstRead = new AtomicBoolean(true);
        when(parameters.findByParamName(PlanParameters.WAITING_DAYS)).thenAnswer(invocation -> {
            if (Thread.currentThread() != updatingThread && blockFirstRead.compareAndSet(true, false)) {
                SystemParameter oldSnapshot = parameter(PlanParameters.WAITING_DAYS, "62");
                queryStarted.countDown();
                assertTrue(finishQuery.await(5, TimeUnit.SECONDS), "La consulta debe liberarse.");
                return Optional.of(oldSnapshot);
            }
            return Optional.of(stored);
        });
        beginTransaction();
        if (startAfterUpdate) {
            service.updateValue(PlanParameters.WAITING_DAYS, "45");
        }
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pendingRead = executor.submit(() -> service.getString(PlanParameters.WAITING_DAYS));
            try {
                assertTrue(queryStarted.await(5, TimeUnit.SECONDS), "La consulta debe comenzar.");
                if (!startAfterUpdate) {
                    service.updateValue(PlanParameters.WAITING_DAYS, "45");
                }
                completeTransaction(TransactionSynchronization.STATUS_COMMITTED);
            } finally {
                finishQuery.countDown();
            }
            assertEquals("62", pendingRead.get(5, TimeUnit.SECONDS));
        }

        assertEquals(45L, service.getLong(PlanParameters.WAITING_DAYS));
        verify(parameters, times(3)).findByParamName(PlanParameters.WAITING_DAYS);
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
        SystemParameterService proxy = transactionalProxy(transactions);

        assertThrows(IllegalStateException.class, () -> proxy.updateValue(PlanParameters.WAITING_DAYS, "45"));

        verify(transactions).rollback(status);
        verify(transactions, never()).commit(any());
    }

    private SystemParameterService transactionalProxy(PlatformTransactionManager transactions) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(advice);
        return (SystemParameterService) factory.getProxy();
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private static void completeTransaction(int completion) {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(completion));
        TransactionSynchronizationManager.clear();
    }

    private static SystemParameter parameter(String name, String value) {
        SystemParameter parameter = new SystemParameter();
        parameter.setParamName(name);
        parameter.setParamValue(value);
        return parameter;
    }
}
