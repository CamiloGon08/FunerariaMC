package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.MissingDeathCertificateException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.exception.WaitingPeriodException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.FuneralPlan;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.ServiceContract;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.model.enums.ExternalCause;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.persistence.BeneficiaryRepository;
import com.example.funerariamc.persistence.ClientRepository;
import com.example.funerariamc.persistence.DeathCertificateRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import com.example.funerariamc.persistence.DeceasedRepository.DeceasedRow;
import com.example.funerariamc.persistence.FuneralPlanRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.ServiceTransportRepository;
import com.example.funerariamc.service.DeceasedService.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Criterios de aceptación de RF-DIF-01, RF-DIF-02 y HU-56: registro, NN, certificado y plan. */
class DeceasedServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final LocalDateTime NOW = TODAY.atTime(15, 0);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final DeceasedRepository deceased = mock(DeceasedRepository.class);
    private final DeathCertificateRepository certificates = mock(DeathCertificateRepository.class);
    private final DeathCertificateService certificateService = mock(DeathCertificateService.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final FuneralPlanRepository plans = mock(FuneralPlanRepository.class);
    private final BeneficiaryRepository beneficiaries = mock(BeneficiaryRepository.class);
    private final ServiceContractRepository contracts = mock(ServiceContractRepository.class);
    private final ServiceTransportRepository transports = mock(ServiceTransportRepository.class);
    private final PlanParameters parameters = mock(PlanParameters.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final DeceasedService service = new DeceasedService(deceased, certificates, certificateService, clients,
            plans, beneficiaries, contracts, transports, parameters, accessLog, session,
            Clock.fixed(NOW.atZone(BOGOTA).toInstant(), BOGOTA));

    private final DeathDocumentData certificate = new DeathDocumentData(DocumentType.MEDICAL_CERTIFICATE,
            "Clínica Valledupar", "7720154", TODAY, Path.of("certificado.pdf"), null);

    @BeforeEach
    void setUp() {
        loginAs(RoleNames.SECRETARY);
        when(parameters.waitingDays()).thenReturn(62);
        when(deceased.save(any())).thenAnswer(invocation -> {
            Deceased d = invocation.getArgument(0);
            d.setId(42L);
            return d;
        });
        when(clients.findById(40L)).thenReturn(Optional.of(client("49786554")));
    }

    // ---------- registro (RF-DIF-01, RN-01, RN-02) ----------

    @Test
    void registerSavesTheDeceasedWithItsCertificateAndLogsIt() {
        Deceased saved = service.register(data("12435778", DeathType.NATURAL, null, null), certificate);

        assertEquals("Luis Eduardo", saved.getName());
        assertNull(saved.getExternalCause());
        verify(certificateService).attach(eq(saved), eq(certificate), any(AppUser.class));
        verify(accessLog).record(5L, "DECEASED_REGISTERED: DF-0042 12435778");
    }

    @Test
    void registerWithoutCertificateIsRejected() {
        assertThrows(MissingDeathCertificateException.class,
                () -> service.register(data("12435778", DeathType.NATURAL, null, null), null));
        verify(deceased, never()).save(any());
    }

    @Test
    void nonNaturalDeathRequiresTheExternalCause() {
        assertThrows(InvalidDataException.class,
                () -> service.register(data("12435778", DeathType.NON_NATURAL, null, null), certificate));

        Deceased saved = service.register(data("12435778", DeathType.NON_NATURAL, ExternalCause.ACCIDENT, null),
                certificate);
        assertEquals(ExternalCause.ACCIDENT, saved.getExternalCause());
    }

    @Test
    void documentMustBeUniqueAndNumeric() {
        when(deceased.existsByDocumentNumber("12435778")).thenReturn(true);
        assertThrows(InvalidDataException.class,
                () -> service.register(data("12435778", DeathType.NATURAL, null, null), certificate));
        assertThrows(InvalidDataException.class,
                () -> service.register(data("12A35", DeathType.NATURAL, null, null), certificate));
    }

    @Test
    void deathDateCannotBeInTheFuture() {
        DeceasedData future = new DeceasedData(false, "Luis Eduardo", "Pinto", "12435778", null,
                TODAY.plusDays(1), DeathType.NATURAL, null, 40L, null);
        assertThrows(InvalidDataException.class, () -> service.register(future, certificate));
    }

    // ---------- NN (RF-DIF-02) ----------

    @Test
    void secretaryCannotRegisterAnUnidentifiedBody() {
        assertThrows(UnauthorizedActionException.class, () -> service.register(nn(), certificate));
    }

    @Test
    void cemeteryAdminRegistersAnUnidentifiedBodyWithoutDocumentOrClient() {
        loginAs(RoleNames.CEMETERY_ADMIN);

        Deceased saved = service.register(nn(), certificate);

        assertEquals("NN", saved.getName());
        assertEquals("hombre adulto", saved.getLastname());
        assertNull(saved.getDocumentNumber());
        verify(accessLog).record(5L, "DECEASED_REGISTERED: DF-0042");
    }

    // ---------- plan exequial (HU-56, RN-08) ----------

    @Test
    void planCoversTheHolderAfterTheWaitingPeriod() {
        FuneralPlan plan = plan(412L, "12435778", TODAY.minusDays(100));
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan));

        Deceased saved = service.register(data("12435778", DeathType.NATURAL, null, 412L), certificate);

        assertSame(plan, saved.getFuneralPlan());
    }

    @Test
    void planInWaitingPeriodIsRejectedWithTheDaysLeft() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan(412L, "12435778", TODAY.minusDays(60))));

        WaitingPeriodException error = assertThrows(WaitingPeriodException.class,
                () -> service.register(data("12435778", DeathType.NATURAL, null, 412L), certificate));

        assertEquals(2, error.getDaysRemaining());
        verify(deceased, never()).save(any());
    }

    @Test
    void planMustListTheDeceasedAsHolderOrBeneficiary() {
        when(plans.findDetailById(412L)).thenReturn(Optional.of(plan(412L, "49786112", TODAY.minusYears(2))));
        when(beneficiaries.existsByFuneralPlanIdAndDocumentNumber(412L, "12435778")).thenReturn(false);

        assertThrows(InvalidDataException.class,
                () -> service.register(data("12435778", DeathType.NATURAL, null, 412L), certificate));
    }

    @Test
    void linkPlanIsBlockedOnceTheContractExists() {
        Deceased d = new Deceased();
        d.setId(42L);
        d.setDocumentNumber("12435778");
        when(deceased.findDetailById(42L)).thenReturn(Optional.of(d));
        when(contracts.findByDeceasedId(42L)).thenReturn(Optional.of(new ServiceContract()));

        assertThrows(InvalidDataException.class, () -> service.linkPlan(42L, 412L));
    }

    // ---------- etapa del servicio ----------

    @Test
    void stageFollowsContractWakeTransportAndDestination() {
        assertEquals(Stage.REGISTERED, DeceasedService.stage(row(null, null, null, null, 0, null, null), NOW));
        assertEquals(Stage.PREPARATION, DeceasedService.stage(row("SC-1", null, null, null, 0, null, null), NOW));
        assertEquals(Stage.WAKE, DeceasedService.stage(
                row("SC-1", NOW.minusHours(5), NOW.minusHours(2), NOW.plusHours(6), 0, null, null), NOW));
        assertEquals(Stage.TRANSFER, DeceasedService.stage(
                row("SC-1", NOW.minusHours(20), NOW.minusHours(18), NOW.minusHours(1), 1, null, null), NOW));
        assertEquals(Stage.CREMATION_SCHEDULED, DeceasedService.stage(
                row("SC-1", NOW.minusHours(20), NOW.minusHours(18), NOW.minusHours(1), 0, null, 3L), NOW));
        assertEquals(Stage.FINISHED, DeceasedService.stage(
                row("SC-1", NOW.minusDays(2), NOW.minusDays(2), NOW.minusDays(1), 0, "A-22", null), NOW));
    }

    @Test
    void nonNaturalDeathNeedsForensicReportAndReleaseOrder() {
        assertEquals(List.of(DocumentType.MEDICAL_CERTIFICATE),
                DeathCertificateService.requiredDocuments(DeathType.NATURAL));
        assertEquals(3, DeathCertificateService.requiredDocuments(DeathType.NON_NATURAL).size());
    }

    // ---------- utilidades ----------

    private static DeceasedData data(String document, DeathType type, ExternalCause cause, Long planId) {
        return new DeceasedData(false, "Luis Eduardo", "Pinto", document, LocalDate.of(1948, 2, 14), TODAY,
                type, cause, 40L, planId);
    }

    private static DeceasedData nn() {
        return new DeceasedData(true, "NN", "hombre adulto", null, null, TODAY, DeathType.NON_NATURAL,
                ExternalCause.UNDETERMINED, null, null);
    }

    private static DeceasedRow row(String contract, LocalDateTime prepared, LocalDateTime wakeStart,
                                   LocalDateTime wakeEnd, long inProgress, String vault, Long cremation) {
        DeceasedRow row = mock(DeceasedRow.class);
        when(row.getContractNumber()).thenReturn(contract);
        when(row.getContractStatus()).thenReturn(contract == null ? null : ContractStatus.ACTIVE);
        when(row.getPreparationCompletedAt()).thenReturn(prepared);
        when(row.getWakeStartAt()).thenReturn(wakeStart);
        when(row.getWakeEndAt()).thenReturn(wakeEnd);
        when(row.getTransportsInProgress()).thenReturn(inProgress);
        when(row.getVaultCode()).thenReturn(vault);
        when(row.getCremationId()).thenReturn(cremation);
        return row;
    }

    private static Client client(String document) {
        Client client = new Client();
        client.setId(40L);
        client.setName("Sandra Milena");
        client.setLastname("Pinto");
        client.setDocumentNumber(document);
        return client;
    }

    private static FuneralPlan plan(Long id, String holderDocument, LocalDate start) {
        FuneralPlan plan = new FuneralPlan();
        plan.setId(id);
        plan.setClient(client(holderDocument));
        plan.setStartDate(start);
        plan.setStatus(PlanStatus.ACTIVE);
        return plan;
    }

    private void loginAs(String roleName) {
        Manager employee = new Manager();
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }
}
