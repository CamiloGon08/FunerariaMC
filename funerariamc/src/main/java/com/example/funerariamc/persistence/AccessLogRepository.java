package com.example.funerariamc.persistence;

import com.example.funerariamc.model.AccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    /** Últimos registros de la bitácora con el usuario y su empleado ya cargados. */
    @Query("""
            select l from AccessLog l
            join fetch l.appUser u
            join fetch u.employee
            order by l.timestamp desc, l.id desc
            """)
    List<AccessLog> findRecent(Pageable pageable);

    /**
     * Una página de la bitácora, del más reciente al más antiguo, con el total de registros.
     * El countQuery va aparte porque un "join fetch" no se puede usar para contar.
     */
    @Query(value = """
            select l from AccessLog l
            join fetch l.appUser u
            join fetch u.employee
            order by l.timestamp desc, l.id desc
            """,
            countQuery = "select count(l) from AccessLog l")
    Page<AccessLog> findPage(Pageable pageable);
}
