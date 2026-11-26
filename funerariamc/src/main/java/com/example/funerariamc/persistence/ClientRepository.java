package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.utils.enums.ClientType;
import com.example.funerariamc.utils.enums.TransactionStatus;
import com.example.funerariamc.utils.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {
    /** Si el documento pertenece a un cliente, lo muestra */
    Optional<Client> findByDocumentNumber(String documentNumber);

    /** pagina de la lista de clientes */
    @Query(value = """
            select c from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
            order by case when cast(function('translate', lower(concat(' ', c.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, c.registrationDate desc, c.id desc
            """,
            countQuery = """
            select count(c) from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
            """)
            Page<Client> search(@Param("pattern") String pattern,
                                @Param("types") Collection<ClientType> types,
                                Pageable pageable);

    /**
     * Buscador de titular (Planes exequiales): nombres o apellidos que EMPIEZAN con lo escrito,
     * sin importar tildes, o documento que empieza con esos números. wordStart es "% texto%" y
     * start es "texto%", ya en minúsculas y sin tildes.
     */
    @Query("""
            select c from Client c
            where cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))),
                                'áéíóúüñ', 'aeiouun') as String) like :wordStart
               or lower(coalesce(c.documentNumber, '')) like :start
            order by case when cast(function('translate', lower(concat(' ', c.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:wordStart, 2) then 0 else 1 end, c.name, c.lastname, c.id
            """)
    List<Client> searchByWordStart(@Param("wordStart") String wordStart, @Param("start") String start,
                                   Pageable pageable);

    /**
     * filtro Primeros clientes que coinciden con el texto
     */
    @Query("""
            select c from Client c
            where cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
               or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern
            order by case when cast(function('translate', lower(concat(' ', c.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, c.name, c.lastname, c.id
            """)
    List<Client> searchOptions(@Param("pattern") String pattern, Pageable pageable);

    /**Página de las tarjetas de Clientes con el filtro de saldo.*/
    @Query(value = """
            select c from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active))))
            order by case when cast(function('translate', lower(concat(' ', c.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, c.registrationDate desc, c.id desc
            """,
            countQuery = """
            select count(c) from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active))))
            """)
    Page<Client> searchByBalance(@Param("pattern") String pattern,
                                 @Param("types") Collection<ClientType> types,
                                 @Param("balance") String balance,
                                 @Param("income") TransactionType income,
                                 @Param("active") TransactionStatus active,
                                 Pageable pageable);

    /**
     * Página de las tarjetas de Clientes con los filtros de saldo y de plan exequial: plan en
     * 'WITH' deja a los que tienen un plan no cancelado y en 'WITHOUT' a los que no tienen ninguno.
     */
    @Query(value = """
            select c from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
              and (:plan = 'ALL'
                   or (:plan = 'WITH' and exists (
                        select p.id from FuneralPlan p
                        where p.client = c
                          and p.status <> com.example.funerariamc.utils.enums.PlanStatus.CANCELLED))
                   or (:plan = 'WITHOUT' and not exists (
                        select p.id from FuneralPlan p
                        where p.client = c
                          and p.status <> com.example.funerariamc.utils.enums.PlanStatus.CANCELLED)))
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active))))
            order by case when cast(function('translate', lower(concat(' ', c.name)), 'áéíóúüñ', 'aeiouun') as String) like substring(:pattern, 2) then 0 else 1 end, c.registrationDate desc, c.id desc
            """,
            countQuery = """
            select count(c) from Client c
            where c.clientType in :types
              and (cast(function('translate', lower(concat(' ', c.name, ' ', coalesce(c.lastname, ''))), 'áéíóúüñ', 'aeiouun') as String) like :pattern
                   or concat(' ', lower(coalesce(c.documentNumber, ''))) like :pattern)
              and (:plan = 'ALL'
                   or (:plan = 'WITH' and exists (
                        select p.id from FuneralPlan p
                        where p.client = c
                          and p.status <> com.example.funerariamc.utils.enums.PlanStatus.CANCELLED))
                   or (:plan = 'WITHOUT' and not exists (
                        select p.id from FuneralPlan p
                        where p.client = c
                          and p.status <> com.example.funerariamc.utils.enums.PlanStatus.CANCELLED)))
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
                          and s.status = com.example.funerariamc.utils.enums.SaleStatus.ACTIVE
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active))))
            """)
    Page<Client> searchByPlanAndBalance(@Param("pattern") String pattern,
                                 @Param("types") Collection<ClientType> types,
                                 @Param("plan") String plan,
                                 @Param("balance") String balance,
                                 @Param("income") TransactionType income,
                                 @Param("active") TransactionStatus active,
                                 Pageable pageable);

    long countByClientType(ClientType clientType);

    /** Indicador "Nuevos este mes" */
    long countByRegistrationDateGreaterThanEqual(LocalDate date);

}