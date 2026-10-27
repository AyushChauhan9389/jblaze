package jblaze.classfile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Just enough of the class-file format (JVMS §4) for jblaze: which classes a class refers to, its ABI (what
 * other classes compile against), its compile-time constants, and renaming a class. JDK only, so both the
 * agent and the CLI can use it. ponytail: hand-rolled because java.lang.classfile is final only from JDK 24.
 */
public final class ClassFile {

	public record Member(int access, String name, String descriptor, String signature, String constant,
			List<String> exceptions) {
	}

	private static final int ACC_PRIVATE = 0x0002;

	private static final int ACC_SYNCHRONIZED = 0x0020;

	/** A class name inside a descriptor or signature: {@code Lpkg/Name;} or {@code Lpkg/Name<...>;}. */
	private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z0-9_$/]+)[;<]");

	public final String name;

	public final String superName;

	public final List<String> interfaces = new ArrayList<>();

	public final int access;

	public final String sourceFile;

	public final String signature;

	public final List<Member> fields = new ArrayList<>();

	public final List<Member> methods = new ArrayList<>();

	/** Internal names of every class this one mentions (constant pool classes and descriptors). */
	public final Set<String> references = new TreeSet<>();

	private final Object[] cp;

	private ClassFile(byte[] bytes) throws IOException {
		var in = new DataInputStream(new ByteArrayInputStream(bytes));
		if (in.readInt() != 0xCAFEBABE) {
			throw new IOException("not a class file");
		}
		in.readUnsignedShort();
		in.readUnsignedShort();
		cp = readConstantPool(in);
		access = in.readUnsignedShort();
		name = className(in.readUnsignedShort());
		int superIndex = in.readUnsignedShort();
		superName = superIndex == 0 ? null : className(superIndex);
		for (int n = in.readUnsignedShort(); n > 0; n--) {
			interfaces.add(className(in.readUnsignedShort()));
		}
		readMembers(in, fields);
		readMembers(in, methods);
		String source = null;
		String sig = null;
		for (int n = in.readUnsignedShort(); n > 0; n--) {
			String attr = utf8(in.readUnsignedShort());
			int length = in.readInt();
			switch (attr) {
				case "SourceFile" -> source = utf8(in.readUnsignedShort());
				case "Signature" -> sig = utf8(in.readUnsignedShort());
				default -> in.skipNBytes(length);
			}
		}
		sourceFile = source;
		signature = sig;
		collectReferences();
	}

	public static ClassFile read(byte[] bytes) {
		try {
			return new ClassFile(bytes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The parts of this class that other classes compile against: everything non-private, with signatures,
	 * thrown exceptions and constant values. Two versions with equal ABIs need no dependent recompiles.
	 */
	public String abi() {
		Set<String> lines = new TreeSet<>();
		lines.add("class " + access + " " + name + " extends " + superName + " implements " + interfaces + " "
				+ signature);
		for (Member f : fields) {
			if ((f.access() & ACC_PRIVATE) == 0) {
				lines.add("field " + f.access() + " " + f.name() + " " + f.descriptor() + " " + f.signature() + " = "
						+ f.constant());
			}
		}
		for (Member m : methods) {
			if ((m.access() & ACC_PRIVATE) == 0) {
				lines.add("method " + (m.access() & ~ACC_SYNCHRONIZED) + " " + m.name() + m.descriptor() + " "
						+ m.signature() + " throws " + m.exceptions());
			}
		}
		return String.join("\n", lines);
	}

	/** Compile-time constants (static final primitives and strings): javac copies these into the classes using them. */
	public Map<String, String> constants() {
		Map<String, String> constants = new TreeMap<>();
		for (Member f : fields) {
			if (f.constant() != null) {
				constants.put(f.name(), f.constant());
			}
		}
		return constants;
	}

	/**
	 * Renames class {@code from} (and its nested classes {@code from$...}) to {@code to} throughout a class file.
	 * Names live only in UTF-8 constants, so rewriting the constant pool is enough; the rest is copied as is.
	 * A string literal spelled exactly like the internal name is renamed too, which is harmless in practice.
	 */
	public static byte[] rename(byte[] bytes, String from, String to) {
		Pattern name = Pattern.compile("(?<=^|L|\\[)" + Pattern.quote(from) + "(?=$|[;$<.])");
		return rewriteUtf8(bytes, s -> name.matcher(s).replaceAll(Matcher.quoteReplacement(to)));
	}

	/** A whole internal class name, as it appears in a class constant or a descriptor/signature. */
	private static final Pattern CLASS_NAME_TOKEN = Pattern.compile("(?<=^|L|\\[)[A-Za-z0-9_$/]+(?=$|[;<.])");

	/**
	 * Renames several classes at once ({@code old internal name → new}, nested classes listed separately).
	 * Unlisted names are left alone.
	 */
	public static byte[] renameAll(byte[] bytes, Map<String, String> names) {
		return rewriteUtf8(bytes, s -> CLASS_NAME_TOKEN.matcher(s)
			.replaceAll(m -> Matcher.quoteReplacement(names.getOrDefault(m.group(), m.group()))));
	}

	private static byte[] rewriteUtf8(byte[] bytes, java.util.function.UnaryOperator<String> rewrite) {
		try {
			var in = new DataInputStream(new ByteArrayInputStream(bytes));
			var buffer = new ByteArrayOutputStream(bytes.length + 64);
			var out = new DataOutputStream(buffer);
			out.writeInt(in.readInt());
			out.writeShort(in.readUnsignedShort());
			out.writeShort(in.readUnsignedShort());
			int count = in.readUnsignedShort();
			out.writeShort(count);
			for (int i = 1; i < count; i++) {
				int tag = in.readUnsignedByte();
				out.writeByte(tag);
				if (tag == 1) {
					out.writeUTF(rewrite.apply(in.readUTF()));
				}
				else {
					out.write(in.readNBytes(entrySize(tag)));
					if (tag == 5 || tag == 6) {
						i++; // long and double take two slots
					}
				}
			}
			in.transferTo(out);
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// --- parsing ---

	private static Object[] readConstantPool(DataInputStream in) throws IOException {
		Object[] cp = new Object[in.readUnsignedShort()];
		for (int i = 1; i < cp.length; i++) {
			int tag = in.readUnsignedByte();
			cp[i] = switch (tag) {
				case 1 -> in.readUTF();
				case 3 -> new Num("I:" + in.readInt());
				case 4 -> new Num("F:" + in.readFloat());
				case 5 -> new Num("J:" + in.readLong());
				case 6 -> new Num("D:" + in.readDouble());
				case 7 -> new Ref(7, in.readUnsignedShort());
				case 8 -> new Ref(8, in.readUnsignedShort());
				default -> {
					in.skipNBytes(entrySize(tag));
					yield null;
				}
			};
			if (tag == 5 || tag == 6) {
				i++;
			}
		}
		return cp;
	}

	/** A Class (7) or String (8) constant pointing at a UTF-8 entry. */
	private record Ref(int tag, int utf8) {
	}

	/** A numeric constant, rendered like "I:42". */
	private record Num(String value) {
	}

	private static int entrySize(int tag) throws IOException {
		return switch (tag) {
			case 3, 4, 9, 10, 11, 12, 17, 18 -> 4;
			case 5, 6 -> 8;
			case 7, 8, 16, 19, 20 -> 2;
			case 15 -> 3;
			default -> throw new IOException("bad constant pool tag " + tag);
		};
	}

	private void readMembers(DataInputStream in, List<Member> into) throws IOException {
		for (int n = in.readUnsignedShort(); n > 0; n--) {
			int acc = in.readUnsignedShort();
			String memberName = utf8(in.readUnsignedShort());
			String descriptor = utf8(in.readUnsignedShort());
			String sig = null;
			String constant = null;
			List<String> exceptions = new ArrayList<>();
			for (int a = in.readUnsignedShort(); a > 0; a--) {
				String attr = utf8(in.readUnsignedShort());
				int length = in.readInt();
				switch (attr) {
					case "Signature" -> sig = utf8(in.readUnsignedShort());
					case "ConstantValue" -> constant = constant(in.readUnsignedShort());
					case "Exceptions" -> {
						for (int e = in.readUnsignedShort(); e > 0; e--) {
							exceptions.add(className(in.readUnsignedShort()));
						}
					}
					default -> in.skipNBytes(length);
				}
			}
			into.add(new Member(acc, memberName, descriptor, sig, constant, exceptions));
		}
	}

	private void collectReferences() {
		for (Object entry : cp) {
			if (entry instanceof Ref ref && ref.tag() == 7) {
				String className = utf8(ref.utf8());
				if (className.startsWith("[")) {
					addDescriptorClasses(className);
				}
				else {
					references.add(className);
				}
			}
			else if (entry instanceof String s) {
				addDescriptorClasses(s);
			}
		}
		references.remove(name);
	}

	private void addDescriptorClasses(String text) {
		Matcher m = DESCRIPTOR_CLASS.matcher(text);
		while (m.find()) {
			references.add(m.group(1));
		}
	}

	private String constant(int index) {
		Object entry = cp[index];
		return entry instanceof Ref ref ? "S:" + utf8(ref.utf8()) : ((Num) entry).value();
	}

	private String className(int index) {
		return utf8(((Ref) cp[index]).utf8());
	}

	private String utf8(int index) {
		return (String) cp[index];
	}

}
