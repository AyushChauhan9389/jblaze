package jblaze.agent;

import java.util.ArrayList;
import java.util.List;

/** What a change did: the steps applied live, or the first reason it needs a restart. */
public final class Report {

	private final List<String> done = new ArrayList<>();

	private String restart;

	public Report done(String step) {
		done.add(step);
		return this;
	}

	public Report restart(String reason) {
		if (restart == null) {
			restart = reason;
		}
		return this;
	}

	public boolean needsRestart() {
		return restart != null;
	}

	public String restartReason() {
		return restart;
	}

	public List<String> steps() {
		return done;
	}

	@Override
	public String toString() {
		return needsRestart() ? "restart " + restart : "ok " + String.join("; ", done);
	}

}
