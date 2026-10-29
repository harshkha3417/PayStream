package com.paygate.refund.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RefundNotFoundException.class)
    public ProblemDetail handleRefundNotFound(RefundNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://paystream.dev/errors/refund-not-found"));
        problem.setTitle("Refund Not Found");
        return problem;
    }

    @ExceptionHandler(PaymentNotRefundableException.class)
    public ProblemDetail handlePaymentNotRefundable(PaymentNotRefundableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setType(URI.create("https://paystream.dev/errors/payment-not-refundable"));
        problem.setTitle("Payment Not Refundable");
        return problem;
    }

    @ExceptionHandler(InvalidRefundAmountException.class)
    public ProblemDetail handleInvalidRefundAmount(InvalidRefundAmountException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://paystream.dev/errors/invalid-refund-amount"));
        problem.setTitle("Invalid Refund Amount");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("https://paystream.dev/errors/validation-failed"));
        problem.setTitle("Validation Failed");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problem.setType(URI.create("https://paystream.dev/errors/internal-error"));
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
