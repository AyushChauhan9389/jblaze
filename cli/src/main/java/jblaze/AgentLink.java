package jblaze;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;

/**
 * The socket to the jblaze agent in a JVM jblaze launched. Messages: {@code 'C'} classes, {@code 'R'} resource
 * paths, {@code 'T'} test classes to run; each gets one UTF reply ("ok …", "restart why", "fail why", or a
 * test summary). Connects lazily, since the JVM may still be starting.
 */
final class AgentLink {

	private final int port;

	private Socket socket;

	AgentLink() throws IOException {
		try (var s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = s.getLocalPort();
		}
	}

	/** The {@code -javaagent} option for a JVM this link will talk to. */
	String javaagent(String flags) {
		return "-javaagent:" + System.getProperty("jblaze.agent") + "=" + port + (flags.isEmpty() ? "" : "," + flags);
	}

	synchronized String classes(Map<String, byte[]> classes) throws IOException, InterruptedException {
		DataOutputStream out = out();
		out.writeByte('C');
		out.writeInt(classes.size());
		for (var e : classes.entrySet()) {
			out.writeUTF(e.getKey());
			out.writeInt(e.getValue().length);
			out.write(e.getValue());
		}
		return reply(out);
	}

	synchronized String resources(List<String> paths) throws IOException, InterruptedException {
		return strings('R', paths);
	}

	synchronized String tests(List<String> classNames) throws IOException, InterruptedException {
		return strings('T', classNames);
	}

	/** Restart the app inside its JVM (DevTools' Restarter); waits until it's up. */
	synchronized String restartInPlace() throws IOException, InterruptedException {
		return strings('X', List.of());
	}

	synchronized void close() {
		try {
			if (socket != null) {
				socket.close();
			}
		}
		catch (IOException ignored) {
		}
		socket = null;
	}

	private String strings(char tag, List<String> values) throws IOException, InterruptedException {
		DataOutputStream out = out();
		out.writeByte(tag);
		out.writeInt(values.size());
		for (String v : values) {
			out.writeUTF(v);
		}
		return reply(out);
	}

	private DataOutputStream out() throws IOException, InterruptedException {
		if (socket == null) {
			socket = connect();
		}
		return new DataOutputStream(socket.getOutputStream());
	}

	private String reply(DataOutputStream out) throws IOException {
		out.flush();
		return new DataInputStream(new BufferedInputStream(socket.getInputStream())).readUTF();
	}

	private Socket connect() throws InterruptedException, IOException {
		for (int i = 0; i < 1200; i++) { // the JVM may still be starting
			try {
				return new Socket(InetAddress.getLoopbackAddress(), port);
			}
			catch (IOException e) {
				Thread.sleep(50);
			}
		}
		throw new IOException("agent not reachable on port " + port);
	}

}
