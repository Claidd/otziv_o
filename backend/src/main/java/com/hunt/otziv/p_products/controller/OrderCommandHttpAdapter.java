package com.hunt.otziv.p_products.controller;

import com.hunt.otziv.p_products.application.WorkerOrderCommandException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public final class OrderCommandHttpAdapter {
    private OrderCommandHttpAdapter() {}
    public static <T>T invoke(Command<T> command) throws Exception {
        try {return command.run();}
        catch(WorkerOrderCommandException error) {
            if(error.getCause() instanceof ResponseStatusException original)throw original;
            throw new ResponseStatusException(HttpStatusCode.valueOf(error.statusCode()),error.getMessage(),error);
        }
    }
    @FunctionalInterface public interface Command<T> {T run() throws Exception;}
}
