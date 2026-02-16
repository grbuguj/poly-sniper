package com.sniper.btc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BtcSniperApplication {

    public static void main(String[] args) {
        SpringApplication.run(BtcSniperApplication.class, args);
    }
}
