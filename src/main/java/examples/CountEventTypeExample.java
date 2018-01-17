package examples;

import java.io.File;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Maps;

import cc.kave.commons.model.events.IIDEEvent;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.ReadingArchive;

public class CountEventTypeExample {

	private String dir;

	public CountEventTypeExample(String dir) {
		this.dir = dir;
	}

	public void run() {
		Set<String> zips = IoHelper.findAllZips(dir);

		int zipTotal = zips.size();
		int zipCount = 0;
		for (String zip : zips) {
			double perc = 100 * zipCount / (double) zipTotal;
			zipCount++;

			System.out.printf("## %s, processing %s... (%d/%d, %.1f%% done)\n", new Date(), zip, zipCount, zipTotal,
					perc);
			File zipFile = Paths.get(dir, zip).toFile();

			int numEvents = 0;
			Map<String, Integer> counts = Maps.newHashMap();

			int printCounter = 0;
			try (IReadingArchive ra = new ReadingArchive(zipFile)) {
				while (ra.hasNext()) {
					if (printCounter++ % 100 == 0) {
						System.out.printf(".");
					}
					numEvents++;
					IIDEEvent e = ra.getNext(IIDEEvent.class);

					String key = e.getClass().getSimpleName();
					Integer count = counts.get(key);
					if (count == null) {
						counts.put(key, 1);
					} else {
						counts.put(key, count + 1);
					}
				}
			}
			counts.put("<total>", numEvents);

			System.out.printf("\nFound the following events:\n");
			for (String key : counts.keySet()) {
				int count = counts.get(key);
				System.out.printf("%s: %d\n", key, count);
			}
			System.out.printf("\n");
		}

		System.out.printf("Done (%s)\n", new Date());
	}
}