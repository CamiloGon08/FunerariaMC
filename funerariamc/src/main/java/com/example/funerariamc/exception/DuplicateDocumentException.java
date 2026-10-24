package com.example.funerariamc.exception;

/** person.document_number es UNIQUE: no se registran dos personas con el mismo documento. */
public class DuplicateDocumentException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public DuplicateDocumentException(String documentNumber) {
        super("Ya existe una persona registrada con ese documento.");
    }
}
