package com.example.funerariamc.persistence;

import com.example.funerariamc.model.DeathCertificate;
import com.example.funerariamc.model.enums.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Documentos legales de los difuntos: certificado de defunción, informe forense y orden de entrega. */
public interface DeathCertificateRepository extends JpaRepository<DeathCertificate, Long> {

    /** Documentos de un difunto con el empleado que los registró, del más antiguo al más reciente. */
    @Query("""
            select dc from DeathCertificate dc
            left join fetch dc.registeredByEmployee
            where dc.deceased.id = :deceasedId
            order by dc.createdAt, dc.id
            """)
    List<DeathCertificate> findByDeceased(@Param("deceasedId") Long deceasedId);

    boolean existsByDeceasedIdAndDocumentType(Long deceasedId, DocumentType documentType);
}
