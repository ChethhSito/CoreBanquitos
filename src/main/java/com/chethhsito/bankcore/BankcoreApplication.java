package com.chethhsito.bankcore;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "BankCore API", version = "0.1.0",
        description = "API educativa de cuentas y transferencias simuladas en PEN"))
public class BankcoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(BankcoreApplication.class, args);
	}

}
