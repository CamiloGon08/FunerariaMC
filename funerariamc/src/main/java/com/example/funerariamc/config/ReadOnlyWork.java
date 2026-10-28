package com.example.funerariamc.config;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Component
public class ReadOnlyWork {

    @Transactional(readOnly = true)
    public <T> T run(Supplier<T> work){
        return work.get();
    }
}
