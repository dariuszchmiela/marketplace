package pl.dch.orderactivity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderActivityApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderActivityApplication.class, args);
    }
}
