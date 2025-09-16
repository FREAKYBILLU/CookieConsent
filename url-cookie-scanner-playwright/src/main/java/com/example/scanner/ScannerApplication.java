
package com.example.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableRetry
@ComponentScan(basePackages = "com.example.scanner")
public class ScannerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ScannerApplication.class, args);
  }
}
