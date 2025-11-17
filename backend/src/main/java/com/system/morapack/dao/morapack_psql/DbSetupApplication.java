package com.system.morapack.dao.morapack_psql;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import jakarta.persistence.EntityManagerFactory;
import com.system.morapack.dao.morapack_psql.repository.UserRepository;
import com.system.morapack.dao.morapack_psql.repository.AccountRepository;
import com.system.morapack.dao.morapack_psql.model.User;
import com.system.morapack.dao.morapack_psql.model.Account;
import com.system.morapack.schemas.TypeUser;
import java.util.HashMap;
import java.util.Map;

/**
 * Escanea únicamente el paquete de entidades ...model (y subpaquetes).
 * Genera/actualiza el esquema JPA con Hibernate y SALE (no queda pegado).
 */
@SpringBootApplication
@EntityScan(basePackages = "com.system.morapack.dao.morapack_psql.model")
public class DbSetupApplication {

  public static void main(String[] args) {
    Map<String, Object> props = new HashMap<>();
    props.put("spring.main.web-application-type", "none");

    props.put("spring.datasource.url",
        getenvOr("DB_URL", "jdbc:postgresql://localhost:5435/postgres"));
    props.put("spring.datasource.username", getenvOr("DB_USER", "postgres"));
    props.put("spring.datasource.password", getenvOr("DB_PASSWORD", "postgres"));

    // create | create-drop | validate | update | none
    props.put("spring.jpa.hibernate.ddl-auto", getenvOr("DDL_AUTO", "update"));
    props.put("spring.jpa.show-sql", "false");
    props.put("spring.jpa.properties.hibernate.default_schema",
        getenvOr("DB_SCHEMA", "public"));

    ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DbSetupApplication.class)
        .web(WebApplicationType.NONE)
        .properties(props)
        .run(args);

    ctx.getBean(EntityManagerFactory.class).createEntityManager().close();

    // Insert a mock account for local development if it doesn't exist
    try {
      UserRepository userRepo = ctx.getBean(UserRepository.class);
      AccountRepository accountRepo = ctx.getBean(AccountRepository.class);

      String mockEmail = "monosupremo@gmail.com";
      String mockPassword = "monosupremo123";

      if (!accountRepo.existsByEmail(mockEmail)) {
        User mockUser = User.builder()
            .name("Mono")
            .lastName("Supremo")
            .userType(TypeUser.ADMIN)
            .build();
        mockUser = userRepo.save(mockUser);

        Account mockAccount = Account.builder()
            .email(mockEmail)
            .password(mockPassword) // Note: stored in plain text in this demo app
            .user(mockUser)
            .build();
        accountRepo.save(mockAccount);

        System.out.println("Inserted mock account: " + mockEmail);
      } else {
        System.out.println("Mock account already exists: " + mockEmail);
      }
    } catch (Exception e) {
      System.out.println("Could not insert mock account: " + e.getMessage());
    }

    ctx.close();
  }

  private static String getenvOr(String k, String def) {
    String v = System.getenv(k);
    return (v == null || v.isBlank()) ? def : v;
  }
}