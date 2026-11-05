package com.pad.lab3.dw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DwNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(DwNodeApplication.class, args);
    }
}
