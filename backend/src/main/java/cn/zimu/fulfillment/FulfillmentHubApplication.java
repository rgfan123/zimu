package cn.zimu.fulfillment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FulfillmentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(FulfillmentHubApplication.class, args);
    }
}
