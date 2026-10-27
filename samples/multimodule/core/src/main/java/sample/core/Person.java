package sample.core;

@lombok.Value
public class Person {

	String name;

	public String describe() {
		return "person " + getName();
	}

}
