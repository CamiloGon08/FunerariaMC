package com.example.funerariamc.persistence;

import com.example.funerariamc.model.Client;
import com.example.funerariamc.model.enums.ClientType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    long countByClientType(ClientType clientType);

}
