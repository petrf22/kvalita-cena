package cz.kvalitacena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // pseudonymizace observací po 180 dnech, přepočet agregátů, úklid vypršelých tokenů
public class KvalitaACenaApplication {

  public static void main(String[] args) {
    SpringApplication.run(KvalitaACenaApplication.class, args);
  }
}
