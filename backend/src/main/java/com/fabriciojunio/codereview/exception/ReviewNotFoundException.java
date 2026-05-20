package com.fabriciojunio.codereview.exception;

import java.util.UUID;

public class ReviewNotFoundException extends RuntimeException {
    public ReviewNotFoundException(UUID id) {
        super("Revisão não encontrada: " + id);
    }
}
