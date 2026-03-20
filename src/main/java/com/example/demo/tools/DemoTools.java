package com.example.demo.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DemoTools {

    private final Environment env;

    public DemoTools(Environment env) {
        this.env = env;
    }

    @Tool("Returns the sum of two integers a and b")
    public int sum(@P("first integer") int a, @P("second integer") int b) {
        return a + b;
    }

    @Tool("Returns the current server date and time")
    public String getTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool("Returns a configuration property value for the given key")
    public String getConfig(@P("the property key to look up") String key) {
        return env.getProperty(key, "(not set)");
    }
}
