package com.github.subsound.utils;

import java.util.function.Supplier;

public class Lazy<T> {
    private Supplier<T> supplier;
    private T value;
    private volatile boolean initialized = false;

    private Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }
    public static <T> Lazy<T> ofValue(T value) {
        return new Lazy<>(() -> value);
    }
    public T get() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    value = supplier.get();
                    supplier = null; // Allow GC
                    initialized = true;
                }
            }
        }
        return value;
    }
}