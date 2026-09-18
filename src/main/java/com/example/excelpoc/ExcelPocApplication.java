
package com.example.excelpoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class ExcelPocApplication {


    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        System.out.println("Java timezone = " + java.util.TimeZone.getDefault().getID());

        System.out.println("user.timezone = " + System.getProperty("user.timezone"));
        SpringApplication.run(ExcelPocApplication.class, args);
    }
}