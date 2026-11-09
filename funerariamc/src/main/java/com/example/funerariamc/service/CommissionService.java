package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FinancialTransaction;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.enums.ContractStatus;
import com.example.funerariamc.model.enums.EmployeeStatus;
import com.example.funerariamc.model.enums.SaleStatus;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import com.example.funerariamc.persistence.FinancialTransactionRepository;
import com.example.funerariamc.persistence.SaleRepository;
import com.example.funerariamc.persistence.ServiceContractRepository;
import com.example.funerariamc.persistence.StaffOptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Comisiones del mes calendario:
 * vendedor = total de sus ventas vigentes del mes por su porcentaje (aunque el cliente aún deba abonos);
 * tanatólogo = preparaciones del cuerpo que registró en el mes (inhumación o cremación) por su tarifa por caso.
 * Calcular la comisión la guarda como un egreso a nombre de la persona. La misma persona y el mismo
 * mes no se calculan dos veces mientras ese egreso esté vigente; si se anula, se puede calcular de nuevo.
 */
@Service
public class CommissionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Kind {
        SALESPERSON("Vendedor funerario"),
        THANATOPRACTOR("Tanatólogo");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Una persona en el mes. base = total vendido (vendedor) o null (tanatólogo); rate = porcentaje o
     * tarifa por caso; amount = comisión que da; liquidatedAt != null si ya se calculó.
     */
    public record CommissionRow(Long employeeId, String name, Kind kind, boolean active, long cases,
                                BigDecimal base, BigDecimal rate, BigDecimal amount,
                                Long transactionId, LocalDateTime liquidatedAt) {

        public boolean liquidated() {
            return transactionId != null;
        }
    }

    /** monthOpen: el mes todavía no termina, así que pueden entrar más ventas o preparaciones. */
    public record Overview(YearMonth month, List<CommissionRow> rows, BigDecimal liquidated, BigDecimal pending,
                           boolean monthOpen) {
    }

    private final StaffOptionRepository staffRepository;
    private final SaleRepository saleRepository;
    private final ServiceContractRepository contractRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final FinancialTransactionService transactionService;
    private final AccessLogService accessLogService;
    private final Clock clock;

    @Autowired
    public CommissionService(StaffOptionRepository staffRepository, SaleRepository saleRepository,
                             ServiceContractRepository contractRepository,
                             FinancialTransactionRepository transactionRepository,
                             FinancialTransactionService transactionService, AccessLogService accessLogService) {
        this(staffRepository, saleRepository, contractRepository, transactionRepository, transactionService,
                accessLogService, Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha de hoy con un Clock. */
    CommissionService(StaffOptionRepository staffRepository, SaleRepository saleRepository,
                      ServiceContractRepository contractRepository,
                      FinancialTransactionRepository transactionRepository,
                      FinancialTransactionService transactionService, AccessLogService accessLogService, Clock clock) {
        this.staffRepository = staffRepository;
        this.saleRepository = saleRepository;
        this.contractRepository = contractRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.accessLogService = accessLogService;
        this.clock = clock;
    }

    /** Vendedores y tanatólogos del mes con su comisión; los inactivos solo si tuvieron casos. */
    @Transactional(readOnly = true)
    public Overview overview(YearMonth month) {
        transactionService.requireFinance();
        FinancePeriod period = FinancePeriod.month(month);
        Map<Long, SaleRepository.SellerTotal> sales = new HashMap<>();
        for (SaleRepository.SellerTotal total : saleRepository.sumBySeller(period.dbFrom(), period.dbTo(), SaleStatus.ACTIVE)) {
            sales.put(total.getEmployeeId(), total);
        }
        Map<Long, Long> preparations = new HashMap<>();
        // La hora de la preparación la guarda la app en hora de Colombia.
        for (ServiceContractRepository.PreparationCount count : contractRepository.countPreparations(
                period.appFrom(), period.appTo(), ContractStatus.CANCELLED)) {
            preparations.put(count.getThanatopractorId(), count.getCases());
        }
        Map<Long, FinancialTransaction> done = new HashMap<>();
        for (FinancialTransaction t : transactionRepository.findCommissions(prefix(month) + " ·%",
                TransactionType.EXPENSE, TransactionStatus.ACTIVE)) {
            done.put(t.getEmployee().getId(), t);
        }

        List<CommissionRow> rows = new ArrayList<>();
        for (FuneralSalesperson seller : staffRepository.findAllSalespeople()) {
            SaleRepository.SellerTotal total = sales.get(seller.getId());
            long cases = total == null ? 0 : total.getSales();
            BigDecimal base = total == null ? BigDecimal.ZERO : orZero(total.getTotal());
            FinancialTransaction liquidated = done.get(seller.getId());
            if (cases == 0 && liquidated == null && seller.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }
            rows.add(new CommissionRow(seller.getId(), name(seller), Kind.SALESPERSON,
                    seller.getStatus() == EmployeeStatus.ACTIVE, cases, base, seller.getCommissionRate(),
                    liquidated != null ? liquidated.getAmount() : sellerAmount(base, seller.getCommissionRate()),
                    liquidated == null ? null : liquidated.getId(),
                    liquidated == null ? null : AccessLogService.toAppTime(liquidated.getTransactionDate())));
        }
        for (Thanatopractor thanatopractor : staffRepository.findAllThanatopractors()) {
            long cases = preparations.getOrDefault(thanatopractor.getId(), 0L);
            FinancialTransaction liquidated = done.get(thanatopractor.getId());
            if (cases == 0 && liquidated == null && thanatopractor.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }
            rows.add(new CommissionRow(thanatopractor.getId(), name(thanatopractor), Kind.THANATOPRACTOR,
                    thanatopractor.getStatus() == EmployeeStatus.ACTIVE, cases, null,
                    thanatopractor.getCommissionPerCase(),
                    liquidated != null ? liquidated.getAmount() : caseAmount(cases, thanatopractor.getCommissionPerCase()),
                    liquidated == null ? null : liquidated.getId(),
                    liquidated == null ? null : AccessLogService.toAppTime(liquidated.getTransactionDate())));
        }
        BigDecimal paid = rows.stream().filter(CommissionRow::liquidated).map(CommissionRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = rows.stream().filter(r -> !r.liquidated()).map(CommissionRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean open = !month.isBefore(YearMonth.now(clock));
        return new Overview(month, rows, paid, pending, open);
    }

    /** Calcula y guarda la comisión del mes como un egreso a nombre de la persona. */
    @Transactional
    public BigDecimal liquidate(Long employeeId, YearMonth month) {
        AppUser actor = transactionService.requireFinance();
        if (employeeId == null || month == null) {
            throw new InvalidDataException("Elija la persona y el mes.");
        }
        if (month.isAfter(YearMonth.now(clock))) {
            throw new InvalidDataException("No se puede calcular la comisión de un mes que todavía no empieza.");
        }
        Employee person = staffRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", employeeId));
        String monthText = FinancePeriod.monthName(month) + " " + month.getYear();
        if (transactionRepository.existsByEmployeeIdAndTransactionTypeAndStatusAndDescriptionStartingWith(
                employeeId, TransactionType.EXPENSE, TransactionStatus.ACTIVE, prefix(month) + " ·")) {
            throw new InvalidDataException("La comisión de " + name(person) + " de " + monthText
                    + " ya se calculó. Si hubo un error, anule ese egreso en Movimientos y vuelva a calcularla.");
        }
        FinancePeriod period = FinancePeriod.month(month);
        BigDecimal amount;
        String detail;
        if (person instanceof FuneralSalesperson seller) {
            if (seller.getCommissionRate() == null) {
                throw new InvalidDataException(name(person) + " no tiene porcentaje de comisión registrado en Personal.");
            }
            SaleRepository.SellerTotal total = saleRepository.sumBySeller(period.dbFrom(), period.dbTo(), SaleStatus.ACTIVE)
                    .stream().filter(t -> employeeId.equals(t.getEmployeeId())).findFirst().orElse(null);
            if (total == null || total.getSales() == 0) {
                throw new InvalidDataException(name(person) + " no tiene ventas en " + monthText + ": no se genera comisión.");
            }
            amount = sellerAmount(orZero(total.getTotal()), seller.getCommissionRate());
            detail = total.getSales() + (total.getSales() == 1 ? " venta" : " ventas") + " al "
                    + seller.getCommissionRate().stripTrailingZeros().toPlainString() + " %";
        } else if (person instanceof Thanatopractor thanatopractor) {
            if (thanatopractor.getCommissionPerCase() == null) {
                throw new InvalidDataException(name(person) + " no tiene tarifa por caso registrada en Personal.");
            }
            long cases = contractRepository.countPreparations(period.appFrom(), period.appTo(), ContractStatus.CANCELLED)
                    .stream().filter(c -> employeeId.equals(c.getThanatopractorId()))
                    .mapToLong(ServiceContractRepository.PreparationCount::getCases).sum();
            if (cases == 0) {
                throw new InvalidDataException(name(person) + " no tiene preparaciones en " + monthText
                        + ": no se genera comisión.");
            }
            amount = caseAmount(cases, thanatopractor.getCommissionPerCase());
            detail = cases + (cases == 1 ? " preparación" : " preparaciones");
        } else {
            throw new InvalidDataException("Solo los vendedores funerarios y los tanatólogos tienen comisión.");
        }
        if (amount.signum() <= 0) {
            throw new InvalidDataException("La comisión de " + name(person) + " da $ 0: no se registra.");
        }
        FinancialTransaction expense = new FinancialTransaction();
        expense.setTransactionType(TransactionType.EXPENSE);
        expense.setAmount(amount);
        expense.setEmployee(person);
        expense.setStatus(TransactionStatus.ACTIVE);
        String description = prefix(month) + " · " + name(person) + " · " + detail;
        expense.setDescription(description.length() > 150 ? description.substring(0, 150) : description);
        transactionRepository.save(expense);
        accessLogService.record(actor.getId(), AccessLogService.COMMISSION_REGISTERED + month + " "
                + person.getDocumentNumber() + " " + amount.toPlainString());
        return amount;
    }

    /** "Comisión 2026-10": así empieza la descripción del egreso de ese mes. */
    static String prefix(YearMonth month) {
        return FinancialTransactionService.COMMISSION_PREFIX + month;
    }

    static BigDecimal sellerAmount(BigDecimal sold, BigDecimal ratePercent) {
        if (ratePercent == null) {
            return BigDecimal.ZERO;
        }
        return orZero(sold).multiply(ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal caseAmount(long cases, BigDecimal perCase) {
        if (perCase == null) {
            return BigDecimal.ZERO;
        }
        return perCase.multiply(BigDecimal.valueOf(cases)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String name(Employee e) {
        return e.getName() + " " + e.getLastname();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
