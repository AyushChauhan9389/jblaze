package sample.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import sample.core.Greeter;
import sample.core.Person;
import sample.core.PersonMapper;
import sample.core.Source;

@RestController
class GreetController {

	private final Greeter greeter = new Greeter();

	@GetMapping("/greet")
	String greet() {
		return greeter.greet("you");
	}

	@GetMapping("/person")
	String person() {
		return new Person("Ada").describe() + " " + PersonMapper.INSTANCE.toDto(new Source("Ada"));
	}

}
