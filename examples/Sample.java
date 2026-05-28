package com.example.demo;

import java.util.List;
import static java.util.Collections.emptyList;

public class Sample {
    private final String name;

    public Sample(String name) {
        this.name = name;
    }

    public List<String> names() {
        return emptyList();
    }

    interface Named {
        String name();
    }
}
