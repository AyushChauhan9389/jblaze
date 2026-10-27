package jblaze.agent;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A class's members and their runtime annotations, so jblaze can tell what a redefinition changed beyond
 * method bodies. Keys look like {@code "class"}, {@code "field name type"}, {@code "method name(params)"},
 * {@code "private method name(params)"} and {@code "ctor(params)"}; parameter annotations are prefixed
 * {@code "p<index>:"}.
 */
public final class Snapshot {

	/** One member that differs. {@code annotations} holds the annotation types that were added, removed or changed. */
	public record Change(String member, Set<String> annotations, boolean structural) {
	}

	private Snapshot() {
	}

	public static Map<String, Set<String>> of(Class<?> type) {
		Map<String, Set<String>> members = new TreeMap<>();
		members.put("class", annotations(type.getDeclaredAnnotations()));
		for (Field f : type.getDeclaredFields()) {
			if (!f.isSynthetic()) {
				members.put("field " + f.getName() + " " + f.getType().getTypeName(),
						annotations(f.getDeclaredAnnotations()));
			}
		}
		for (Method m : type.getDeclaredMethods()) {
			if (!m.isSynthetic()) {
				String kind = Modifier.isPrivate(m.getModifiers()) ? "private method " : "method ";
				members.put(kind + m.getName() + params(m), withParams(m));
			}
		}
		for (Constructor<?> c : type.getDeclaredConstructors()) {
			if (!c.isSynthetic()) {
				members.put("ctor" + params(c), withParams(c));
			}
		}
		return members;
	}

	public static List<Change> diff(Map<String, Set<String>> before, Map<String, Set<String>> after) {
		List<Change> changes = new ArrayList<>();
		Set<String> keys = new TreeSet<>(before.keySet());
		keys.addAll(after.keySet());
		for (String key : keys) {
			Set<String> b = before.get(key);
			Set<String> a = after.get(key);
			if (b == null || a == null) {
				changes.add(new Change(key, types(a != null ? a : b), true));
			}
			else if (!b.equals(a)) {
				Set<String> differing = new TreeSet<>(b);
				differing.addAll(a);
				differing.removeIf(s -> b.contains(s) && a.contains(s));
				changes.add(new Change(key, types(differing), false));
			}
		}
		return changes;
	}

	/** {@code "p0:@a.b.C(x=1)"} → {@code "a.b.C"}. */
	static Set<String> types(Collection<String> annotations) {
		return annotations.stream().map(s -> {
			String t = s.replaceFirst("^p\\d+:", "");
			int paren = t.indexOf('(');
			return t.substring(1, paren >= 0 ? paren : t.length());
		}).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> annotations(Annotation[] annotations) {
		return Arrays.stream(annotations).map(Annotation::toString).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> withParams(Executable e) {
		Set<String> all = annotations(e.getDeclaredAnnotations());
		Annotation[][] params = e.getParameterAnnotations();
		for (int i = 0; i < params.length; i++) {
			for (Annotation a : params[i]) {
				all.add("p" + i + ":" + a);
			}
		}
		return all;
	}

	private static String params(Executable e) {
		return Arrays.stream(e.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(",", "(", ")"));
	}

}
