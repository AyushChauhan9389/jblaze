package jblaze.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

import jblaze.agent.Snapshot.Change;

import org.junit.jupiter.api.Test;

class SnapshotTest {

	@Retention(RetentionPolicy.RUNTIME)
	@interface Mark {

		String value() default "";

	}

	// "before" and "after" versions of one class: same shape where it matters, different annotations

	@Mark("a")
	static class Before {

		int same;

		@Mark
		int annotatedField;

		@Mark("x")
		void path() {
		}

		void param(String s) {
		}

		void removed() {
		}

		private void helper() {
		}

	}

	@Mark("b")
	static class After {

		int same;

		int annotatedField;

		@Mark("y")
		void path() {
		}

		void param(@Mark String s) {
		}

		void added() {
		}

		private void helper() {
		}

	}

	@Test
	void reportsAnnotationAndStructuralChangesPerMember() {
		List<Change> diff = Snapshot.diff(Snapshot.of(Before.class), Snapshot.of(After.class));
		String mark = Mark.class.getCanonicalName();

		// constructors differ only by declaring class, which isn't part of the key
		assertEquals(List.of(new Change("class", Set.of(mark), false),
				new Change("field annotatedField int", Set.of(mark), false), new Change("method added()", Set.of(), true),
				new Change("method param(java.lang.String)", Set.of(mark), false),
				new Change("method path()", Set.of(mark), false), new Change("method removed()", Set.of(), true)), diff);
	}

	@Test
	void identicalClassesHaveNoDiff() {
		assertEquals(List.of(), Snapshot.diff(Snapshot.of(Before.class), Snapshot.of(Before.class)));
	}

	@Test
	void extractsAnnotationTypes() {
		assertEquals(Set.of("a.B", "c.D"), Snapshot.types(List.of("@a.B(x=1)", "p0:@c.D")));
	}

}
