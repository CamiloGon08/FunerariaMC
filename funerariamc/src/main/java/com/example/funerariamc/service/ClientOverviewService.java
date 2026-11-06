package com.example.funerariamc.service;

import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.Beneficiary;
import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.PlanStatus;
import com.example.funerariamc.model.enums.Relationship;
import com.example.funerariamc.model.enums.SaleType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.ClientOverviewRepository;
import com.example.funerariamc.persistence.ClientOverviewRepository.ClientCounts;
import com.example.funerariamc.persistence.ClientOverviewRepository.ContractCount;
import com.example.funerariamc.persistence.ClientOverviewRepository.PlanRow;
import com.example.funerariamc.persistence.ClientOverviewRepository.SaleRow;
import com.example.funerariamc.persistence.ClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resumen de cada titular para la pantalla de Clientes (propuesta P-11 / P-11.1): plan exequial,
 * contratos, saldo pendiente, beneficiarios y difuntos atendidos. Solo lectura: no registra
 * ventas ni abonos (eso es del módulo Contratos y ventas).
 * Saldo de una venta = total − abonos activos; un abono anulado (VOIDED) no cuenta.
 */
@Service
public class ClientOverviewService {

    private final ClientOverviewRepository overviewRepository;
    private final ClientRepository clientRepository;
    private final SessionContext sessionContext;
    private final PlanParameters parameters;

    public ClientOverviewService(ClientOverviewRepository overviewRepository, ClientRepository clientRepository,
                                 SessionContext sessionContext, PlanParameters parameters) {
        this.overviewRepository = overviewRepository;
        this.clientRepository = clientRepository;
        this.sessionContext = sessionContext;
        this.parameters = parameters;
    }

    /** Plan exequial del titular. code es "PL-0412"; waitingEnds es el día en que termina la carencia. */
    public record PlanInfo(Long id, String code, String name, PlanStatus status, LocalDate startDate,
                           LocalDate waitingEnds, long beneficiaries) {

        public boolean isActive() {
            return status == PlanStatus.ACTIVE;
        }

        /** Carencia cumplida a la fecha (RN-08). Sin fecha de inicio no se puede asegurar. */
        public boolean isCovered(LocalDate today) {
            return waitingEnds != null && !today.isBefore(waitingEnds);
        }
    }

    /** Lo que muestra la tarjeta: plan vigente (o null), contratos y saldo. */
    public record ClientSummary(PlanInfo plan, long contracts, long sales, BigDecimal balance) {

        public boolean hasBalance() {
            return balance.signum() > 0;
        }
    }

    /** Venta o contrato del panel: code es el número de contrato o "VT-0211". */
    public record SaleInfo(Long id, String code, SaleType type, LocalDateTime date, BigDecimal total,
                           BigDecimal paid, BigDecimal balance) {
    }

    public record BeneficiaryInfo(String fullName, String relationship, String documentNumber) {
    }

    public record DeceasedInfo(String fullName, LocalDate deathDate, String contractNumber,
                               String wakeRoom, LocalDateTime wakeStartAt, LocalDate contractDate) {
    }

    /** Panel lateral del cliente (P-11.1). */
    public record ClientDetail(Client client, ClientSummary summary, List<BeneficiaryInfo> beneficiaries,
                               List<SaleInfo> sales, List<DeceasedInfo> deceased) {
    }

    /** Indicadores de arriba de la pantalla. */
    public record ClientKpis(long total, long natural, long corporate, long withActivePlan,
                             BigDecimal receivable, long salesWithBalance, long newThisMonth) {

        /** Porcentaje de titulares con plan activo, redondeado. */
        public long planPercent() {
            return total == 0 ? 0 : Math.round(withActivePlan * 100.0 / total);
        }
    }

    /** Resumen de varios clientes en 3 consultas (las tarjetas cargan de a 12). */
    @Transactional(readOnly = true)
    public Map<Long, ClientSummary> summaries(Collection<Long> clientIds) {
        requireAccess();
        Map<Long, ClientSummary> result = new HashMap<>();
        if (clientIds == null || clientIds.isEmpty()) {
            return result;
        }
        Map<Long, PlanInfo> plans = new HashMap<>();
        List<PlanRow> planRows = overviewRepository.findPlans(clientIds);
        if (!planRows.isEmpty()) {
            int waitingDays = parameters.waitingDays();
            for (PlanRow row : planRows) {
                // Se queda con el activo; entre planes del mismo estado conserva el más reciente.
                PlanInfo current = plans.get(row.getClientId());
                if (current == null || (!current.isActive() && row.getStatus() == PlanStatus.ACTIVE)) {
                    plans.put(row.getClientId(), plan(row, waitingDays));
                }
            }
        }
        Map<Long, Long> contracts = new HashMap<>();
        for (ContractCount row : overviewRepository.countContracts(clientIds)) {
            contracts.put(row.getClientId(), row.getContracts());
        }
        Map<Long, BigDecimal> balances = new HashMap<>();
        Map<Long, Long> sales = new HashMap<>();
        for (SaleRow row : overviewRepository.findSales(clientIds, TransactionType.INCOME, TransactionStatus.ACTIVE)) {
            balances.merge(row.getClientId(), positive(balance(row)), (total, amount) -> total.add(amount));
            sales.merge(row.getClientId(), 1L, (total, increment) -> total + increment);
        }
        for (Long id : clientIds) {
            result.put(id, new ClientSummary(plans.get(id), contracts.getOrDefault(id, 0L),
                    sales.getOrDefault(id, 0L), balances.getOrDefault(id, BigDecimal.ZERO)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ClientDetail detail(Long clientId) {
        requireAccess();
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Cliente", clientId));
        ClientSummary summary = summaries(List.of(clientId)).get(clientId);

        List<BeneficiaryInfo> beneficiaries = new ArrayList<>();
        if (summary.plan() != null) {
            for (Beneficiary b : overviewRepository.findBeneficiaries(summary.plan().id())) {
                beneficiaries.add(new BeneficiaryInfo(fullName(b.getName(), b.getLastname()),
                        relationshipLabel(b.getRelationship()), b.getDocumentNumber()));
            }
        }
        List<SaleInfo> sales = overviewRepository
                .findSales(List.of(clientId), TransactionType.INCOME, TransactionStatus.ACTIVE).stream()
                .map(row -> new SaleInfo(row.getSaleId(), saleCode(row), row.getSaleType(), row.getSaleDate(),
                        orZero(row.getTotal()), orZero(row.getPaid()), positive(balance(row))))
                .toList();
        List<DeceasedInfo> deceased = overviewRepository.findServedDeceased(clientId).stream()
                .map(row -> new DeceasedInfo(fullName(row.getName(), row.getLastname()), row.getDeathDate(),
                        row.getContractNumber(), row.getWakeRoom(), row.getWakeStartAt(), row.getContractDate()))
                .toList();
        return new ClientDetail(client, summary, beneficiaries, sales, deceased);
    }

    @Transactional(readOnly = true)
    public ClientKpis kpis() {
        requireAccess();
        LocalDate firstOfMonth = LocalDate.now(AccessLogService.APP_ZONE).withDayOfMonth(1);
        ClientCounts counts = overviewRepository.countClients(firstOfMonth);
        long total = counts.getTotal();
        long natural = counts.getNaturalClients();
        return new ClientKpis(total, natural, total - natural, counts.getWithPlan(),
                orZero(counts.getReceivable()), counts.getSalesWithBalance(), counts.getNewThisMonth());
    }

    /** Hoy en Colombia, para comparar con la carencia. */
    public static LocalDate today() {
        return LocalDate.now(AccessLogService.APP_ZONE);
    }

    // ---------- utilidades ----------

    private static PlanInfo plan(PlanRow row, int waitingDays) {
        LocalDate ends = row.getStartDate() == null ? null : row.getStartDate().plusDays(waitingDays);
        String name = row.getPlanName() == null || row.getPlanName().isBlank() ? "Plan exequial" : row.getPlanName();
        return new PlanInfo(row.getPlanId(), "PL-%04d".formatted(row.getPlanId()), name, row.getStatus(),
                row.getStartDate(), ends, row.getBeneficiaries());
    }

    private static String saleCode(SaleRow row) {
        return Optional.ofNullable(row.getContractNumber())
                .filter(number -> !number.isBlank())
                .orElse("VT-%04d".formatted(row.getSaleId()));
    }

    private static BigDecimal balance(SaleRow row) {
        return orZero(row.getTotal()).subtract(orZero(row.getPaid()));
    }

    /** Un abono de más no deja saldo negativo en la tarjeta. */
    private static BigDecimal positive(BigDecimal value) {
        return value.signum() > 0 ? value : BigDecimal.ZERO;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Conserva los textos históricos que todavía no corresponden a un código del catálogo. */
    private static String relationshipLabel(String code) {
        Relationship relationship = Relationship.fromCode(code);
        return relationship == null ? code : relationship.getDisplayName();
    }

    private static String fullName(String name, String lastname) {
        return lastname == null || lastname.isBlank() ? name : name + " " + lastname;
    }

    /** Mismo permiso que ClientService: Vendedor, Secretaría y Administrador Maestro (RN-21, RN-22). */
    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.CLIENTES::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }
}
