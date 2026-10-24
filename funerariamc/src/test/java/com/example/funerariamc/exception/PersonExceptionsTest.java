package com.example.funerariamc.exception;

import com.example.funerariamc.model.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PersonExceptionsTest {

    @Test
    void duplicateDocumentMessageDoesNotExposePersonalData() {
        BusinessException failure = new DuplicateDocumentException("1065832417");

        assertEquals("Ya existe una persona registrada con ese documento.", failure.getMessage());
        assertFalse(failure.getMessage().contains("1065832417"));
    }

    @Test
    void existingClientIsAvailableForTheFormWithoutIncludingItsDocumentInTheMessage() {
        Client client = new Client();
        client.setDocumentNumber("1065832417");

        ExistingClientException failure = new ExistingClientException(client);

        assertSame(client, failure.getExistingClient());
        assertInstanceOf(BusinessException.class, failure);
        assertFalse(failure.getMessage().contains(client.getDocumentNumber()));
    }

    @Test
    void invalidDataKeepsTheUserFacingBusinessMessage() {
        BusinessException failure = new InvalidDataException("Seleccione el cargo.");

        assertEquals("Seleccione el cargo.", failure.getMessage());
    }
}
