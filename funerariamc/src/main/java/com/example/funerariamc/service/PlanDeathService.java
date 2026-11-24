package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.model.Deceased;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Registrar difunto del plan": la persona del plan que falleció se registra en Difuntos con su
 * certificado y, en la misma transacción, se crea su contrato con el plan. Con el contrato el servicio
 * queda en Preparación. Si algo falla, no queda ni el difunto ni el contrato.
 */
@Service
public class PlanDeathService {

    private final DeceasedService deceasedService;
    private final ContractService contractService;

    public PlanDeathService(DeceasedService deceasedService, ContractService contractService) {
        this.deceasedService = deceasedService;
        this.contractService = contractService;
    }

    /**
     * person y certificate van solo si la persona aún no está en Difuntos (data.deceasedId() es null);
     * si ya está, se registra solo el contrato. Devuelve el número de la venta.
     */
    @Transactional
    public Long register(ContractData data, DeceasedData person, DeathDocumentData certificate) {
        if (data == null || data.kind() != ContractService.SaleKind.PLAN) {
            throw new InvalidDataException("Elija «Registrar difunto del plan».");
        }
        if (data.deceasedId() != null) {
            return contractService.register(data);
        }
        if (person == null) {
            throw new InvalidDataException("Elija la persona del plan que falleció.");
        }
        Deceased saved = deceasedService.register(new DeceasedData(false, person.name(), person.lastname(),
                person.documentNumber(), person.birthDate(), person.deathDate(), person.deathType(),
                person.externalCause(), data.clientId(), data.planId()), certificate);
        return contractService.register(data.withDeceased(saved.getId()));
    }
}
