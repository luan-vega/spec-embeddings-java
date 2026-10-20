package br.codesearch.adapter.in.web;

import br.codesearch.domain.CodeSearchException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Rota inexistente e método errado respondem no mesmo formato de erro das demais falhas. */
@RestControllerAdvice
class HttpErrors {

    private static String requestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<byte[]> notFound() {
        return HttpApi.error(new CodeSearchException("not_found", "route not found"), requestId());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<byte[]> methodNotAllowed() {
        return HttpApi.error(new CodeSearchException("method_not_allowed", "method not allowed"), requestId());
    }
}
