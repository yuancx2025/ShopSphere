package ecommerce;

import org.junit.jupiter.api.Test;

import ecommerce.support.MySqlIntegrationTest;

/** The application context starts against a Flyway-migrated MySQL and Hibernate validates every mapping. */
class EcommerceApplicationTests extends MySqlIntegrationTest {

	@Test
	void contextLoads() {
	}

}
