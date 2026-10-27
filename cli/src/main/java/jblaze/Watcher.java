package jblaze;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Watches source trees (new subdirectories included) and hands over the files changed in each burst. */
final class Watcher {

	interface Handler {

		void changed(Set<Path> files) throws Exception;

	}

	private final WatchService service = FileSystems.getDefault().newWatchService();

	Watcher(List<Path> dirs) throws IOException {
		for (Path dir : dirs) {
			register(dir);
		}
	}

	/** Blocks forever. Changes landing within 20 ms of each other arrive together (editors save in steps). */
	void run(Handler handler) throws InterruptedException, IOException {
		while (true) {
			WatchKey key = service.take();
			Set<Path> changed = new LinkedHashSet<>();
			do {
				Path dir = (Path) key.watchable();
				for (WatchEvent<?> event : key.pollEvents()) {
					if (event.context() instanceof Path name) {
						Path p = dir.resolve(name);
						if (event.kind() == ENTRY_CREATE && Files.isDirectory(p)) {
							register(p);
							try (Stream<Path> all = Files.walk(p)) {
								all.filter(Files::isRegularFile).forEach(changed::add);
							}
						}
						else {
							changed.add(p);
						}
					}
				}
				key.reset();
			}
			while ((key = service.poll(20, TimeUnit.MILLISECONDS)) != null);
			try {
				handler.changed(changed);
			}
			catch (Exception e) {
				Main.log("error: %s", e);
			}
		}
	}

	private void register(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> dirs = Files.walk(dir)) {
			for (Path d : dirs.filter(Files::isDirectory).toList()) {
				d.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
			}
		}
	}

}
