package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.ClientType;
import com.example.funerariamc.model.enums.TransactionStatus;
import com.example.funerariamc.model.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long> {
    /** Si el documento pertenece a un cliente, lo muestra */
    Optional<Client> findByDocumentNumber(String documentNumber);

    /** pagina de la lista de clientes */
    @Query(value = """
            select c from Client c
            where c.clientType in :types
              and (lower(concat(c.name, ' ', c.lastname)) like :pattern
                   or lower(c.documentNumber) like :pattern)
            order by c.registrationDate desc, c.id desc
            """,
            countQuery = """
            select count(c) from Client c
            where c.clientType in :types
              and (lower(concat(c.name, ' ', c.lastname)) like :pattern
                   or lower(c.documentNumber) like :pattern)
            """)
            Page<Client> search(@Param("pattern") String pattern,
                                @Param("types") Collection<ClientType> types,
                                Pageable pageable);
    
    /**Página de las tarjetas de Clientes con el filtro de saldo. */
    @Query(value = """
            select c from Client c
            where c.clientType in :types
              and (lower(concat(c.name, ' ', c.lastname)) like :pattern
                   or lower(c.documentNumber) like :pattern)
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active))))
            order by c.registrationDate desc, c.id desc
            """,
            countQuery = """
            select count(c) from Client c
            where c.clientType in :types
              and (lower(concat(c.name, ' ', c.lastname)) like :pattern
                   or lower(c.documentNumber) like :pattern)
              and (:balance = 'ALL'
                   or (:balance = 'WITH' and exists (
                        select s.id from Sale s
                        where s.client = c
                          and coalesce(s.totalAmount, 0) > (select coalesce(sum(t.amount), 0)
                                                            from FinancialTransaction t
                                                            where t.relatedSale = s
                                                              and t.transactionType = :income
                                                              and t.status = :active)))
                   or (:balance = 'WITHOUT' and not exists (
                        select s.id from Sale s
                        where s.client = c
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

    long countByClientType(ClientType clientType);

    /** Indicador "Nuevos este mes": clientes registrados desde esa fecha. */
    long countByRegistrationDateGreaterThanEqual(LocalDate date);

}
