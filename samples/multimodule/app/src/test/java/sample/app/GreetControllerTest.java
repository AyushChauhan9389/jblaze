package sample.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Same package as the package-private controller, as tests usually are. */
@SpringBootTest
class GreetControllerTest {

	@Autowired
	GreetController controller;

	@Test
	void greets() {
		assertEquals("Hello you", controller.greet());
	}

	@Test
	void describesAPerson() {
		assertTrue(controller.person().startsWith("person Ada"), controller.person());
	}

}
