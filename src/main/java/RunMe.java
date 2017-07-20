/**
 * Copyright 2016 University of Zurich
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */


import java.io.File;
import java.util.Set;

import cc.kave.commons.model.events.CommandEvent;
import cc.kave.commons.model.events.IDEEvent;
import cc.kave.commons.model.events.completionevents.CompletionEvent;
import cc.kave.commons.utils.io.Directory;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.ReadingArchive;

public class RunMe {

	/*
	 * download the interaction data and untip it into the root of this project
	 * (at the level of the pom.xml). Unpack it, you should now have a folder
	 * that includes a bunch of folders that resemble dates and that contain
	 * .zip files. Each .zip represents all events that we have collected for a
	 * specific user, the folder represents the first day when the user uploaded
	 * data.
	 */
	public static String root = "Events-170301";

	public static void main(String[] args) {

		System.out.printf("working in folder %s\n", new File(root).getAbsolutePath());

		int userNum = 0;
		for (String userZip : findUserZips()) {
			System.out.printf("#### (%d) processing user zip: %s #####\n", userNum++, userZip);

			try (IReadingArchive ra = new ReadingArchive(new File(root, userZip))) {
				while (ra.hasNext()) {
					process(ra.getNext(IDEEvent.class));
				}
				System.out.println("...\n");
			}
		}
	}

	private static void process(IDEEvent e) {
		// please bear with me for the casting, I will implement the visitor
		// pattern once I have time... :)

		if (e instanceof CommandEvent) {
			CommandEvent ce = (CommandEvent) e;
			System.out.printf("found a CommandEvent (id: %s)\n", ce.getCommandId());
		} else if (e instanceof CompletionEvent) {
			CompletionEvent compe = (CompletionEvent) e;
			System.out.printf("found a CompletionEvent (was triggered in: %s)\n",
					compe.context.getSST().getEnclosingType().getFullName());
		} else {
			System.out.printf("found an %s (triggered at: %s)\n", e.getClass().getSimpleName(), e.getTriggeredAt());
		}

		// these are just some examples, please explore the API to see what kind
		// of data is available
	}

	public static Set<String> findUserZips() {
		Directory dir = new Directory(root);
		return dir.findFiles(s -> s.endsWith(".zip"));
	}
}