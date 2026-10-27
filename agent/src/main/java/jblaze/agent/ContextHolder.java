package jblaze.agent;

/**
 * The app's Spring context, set by {@code ContextCapture}. Typed as Object so the agent never loads Spring
 * classes in an app that doesn't have Spring.
 */
public final class ContextHolder {

	public static volatile Object context;

	/** True once the context finished refreshing; Spring isn't touched before that. */
	public static volatile boolean ready;

	/**
	 * The class loader of the running app. After an in-place restart (DevTools), classes of the previous loader
	 * are still loaded until collected; only this loader's classes (and its parents') are live.
	 */
	public static volatile ClassLoader appLoader;

	/** Counts contexts captured, so a restart can wait for the next one rather than see the old one. */
	public static volatile int generation;

	private ContextHolder() {
	}

}
