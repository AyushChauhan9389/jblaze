package jblaze.agent.test;

import java.util.concurrent.CountDownLatch;

/** Main class of the warm test JVM: it only stays alive. The agent's socket thread runs the tests. */
public final class TestMain {

	public static void main(String[] args) throws InterruptedException {
		new CountDownLatch(1).await();
	}

}
