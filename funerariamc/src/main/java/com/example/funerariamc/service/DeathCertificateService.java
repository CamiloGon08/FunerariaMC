package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.DeathCertificate;
import com.example.funerariamc.model.Deceased;
import com.example.funerariamc.model.enums.DeathType;
import com.example.funerariamc.model.enums.DocumentType;
import com.example.funerariamc.persistence.DeathCertificateRepository;
import com.example.funerariamc.persistence.DeceasedRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Documentos legales de un difunto (RF-DIF-01, RF-DIF-03): certificado de defunción, informe
 * forense y orden de entrega del cuerpo. El archivo se guarda con FileStorageService y en la
 * base solo queda su URL (RN-25). Si la transacción no se confirma, el archivo copiado se borra.
 */
@Service
public class DeathCertificateService {

    /** Carpeta de los documentos dentro de app.files.base-dir. */
    public static final String FOLDER = "difuntos";

    private final DeathCertificateRepository certificateRepository;
    private final DeceasedRepository deceasedRepository;
    private final FileStorageService fileStorageService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public DeathCertificateService(DeathCertificateRepository certificateRepository,
                                   DeceasedRepository deceasedRepository, FileStorageService fileStorageService,
                                   AccessLogService accessLogService, SessionContext sessionContext) {
        this(certificateRepository, deceasedRepository, fileStorageService, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    DeathCertificateService(DeathCertificateRepository certificateRepository, DeceasedRepository deceasedRepository,
                            FileStorageService fileStorageService, AccessLogService accessLogService,
                            SessionContext sessionContext, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.deceasedRepository = deceasedRepository;
        this.fileStorageService = fileStorageService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    /** RF-DIF-03: agrega un documento que faltaba (informe forense, orden de entrega...). */
    @Transactional
    public DeathCertificate addDocument(Long deceasedId, DeathDocumentData data) {
        requireAccess();
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        Deceased deceased = deceasedRepository.findById(deceasedId)
                .orElseThrow(() -> new ResourceNotFoundException("Difunto", deceasedId));
        DeathCertificate saved = attach(deceased, data, actor);
        accessLogService.record(actor.getId(), AccessLogService.DEATH_DOCUMENT_ADDED
                + DeceasedService.code(deceased) + " " + data.type().name());
        return saved;
    }

    /**
     * Valida el documento, copia el archivo y lo guarda. Lo usa también DeceasedService.register
     * dentro de su transacción. Cada tipo de documento se registra una sola vez por difunto.
     */
    DeathCertificate attach(Deceased deceased, DeathDocumentData data, AppUser actor) {
        validate(data, deceased.getDeathDate());
        if (deceased.getId() != null
                && certificateRepository.existsByDeceasedIdAndDocumentType(deceased.getId(), data.type())) {
            throw new InvalidDataException("El difunto ya tiene " + data.type().getDisplayName().toLowerCase()
                    + " registrado.");
        }
        String url = fileStorageService.store(data.file(), FOLDER);
        deleteFileOnRollback(url);

        DeathCertificate certificate = new DeathCertificate();
        certificate.setDeceased(deceased);
        certificate.setDocumentType(data.type());
        certificate.setIssuingAuthority(data.issuingAuthority().trim());
        certificate.setDocumentNumber(data.documentNumber().trim());
        certificate.setIssueDate(data.issueDate());
        certificate.setFileUrl(url);
        certificate.setNotes(blankToNull(data.notes()));
        certificate.setRegisteredByEmployee(actor.getEmployee());
        certificate.setCreatedAt(LocalDateTime.now(clock));
        return certificateRepository.save(certificate);
    }

    private void validate(DeathDocumentData data, LocalDate deathDate) {
        if (data == null || data.type() == null) {
            throw new InvalidDataException("Seleccione el tipo de documento.");
        }
        String what = data.type().getDisplayName().toLowerCase();
        required(data.issuingAuthority(), "la entidad que expide el " + what, 100);
        required(data.documentNumber(), "el número del " + what, 20);
        if (data.issueDate() == null) {
            throw new InvalidDataException("Ingrese la fecha de expedición del " + what + ".");
        }
        if (data.issueDate().isAfter(LocalDate.now(clock))) {
            throw new InvalidDataException("La fecha de expedición no puede ser futura.");
        }
        if (deathDate != null && data.issueDate().isBefore(deathDate)) {
            throw new InvalidDataException("La fecha de expedición no puede ser anterior a la defunción.");
        }
        if (data.notes() != null && data.notes().trim().length() > 150) {
            throw new InvalidDataException("Las observaciones admiten máximo 150 caracteres.");
        }
        if (data.file() == null) {
            throw new InvalidDataException("Adjunte el archivo del " + what + " (PDF o imagen).");
        }
    }

    /** Si la base de datos no guarda, el archivo copiado no queda huérfano en la carpeta. */
    private void deleteFileOnRollback(String url) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    fileStorageService.delete(url);
                }
            }
        });
    }

    private static void required(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidDataException("Ingrese " + what + ".");
        }
        if (value.trim().length() > max) {
            throw new InvalidDataException("El campo " + what.replaceFirst("^(el|la) ", "")
                    + " admite máximo " + max + " caracteres.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Mismo permiso que el módulo Difuntos: Secretaría, Administrador de Cementerio y Maestro. */
    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName()
                .map(AppModule.DIFUNTOS::isAllowedFor)
                .orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    /** Documentos obligatorios según el tipo de muerte (RN-02, RN-03). */
    public static List<DocumentType> requiredDocuments(DeathType type) {
        return type == DeathType.NON_NATURAL
                ? List.of(DocumentType.MEDICAL_CERTIFICATE, DocumentType.FORENSIC_REPORT,
                        DocumentType.BODY_RELEASE_ORDER)
                : List.of(DocumentType.MEDICAL_CERTIFICATE);
    }
}
