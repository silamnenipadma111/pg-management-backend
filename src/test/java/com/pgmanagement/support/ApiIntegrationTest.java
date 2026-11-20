package com.pgmanagement.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application (H2 with the demo data, real security filter chain) driven through MockMvc.
 * Each distinct property set gets its own database, so cached contexts never share tables.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:it-${random.uuid};MODE=Oracle;DB_CLOSE_DELAY=-1",
        "app.auth.bcrypt-strength=4",
        "app.rate-limit.enabled=false",
        "app.geocoding.enabled=false"
})
@AutoConfigureMockMvc
@Import(SynchronousAsyncConfig.class)
public @interface ApiIntegrationTest {
}
