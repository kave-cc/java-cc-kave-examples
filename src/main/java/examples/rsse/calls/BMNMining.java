/**
 * Copyright 2018 University of Zurich
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
package examples.rsse.calls;

import static cc.kave.commons.utils.io.Logger.append;
import static cc.kave.commons.utils.io.Logger.log;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import cc.kave.commons.model.events.completionevents.Context;
import cc.kave.commons.model.naming.types.ITypeName;
import cc.kave.commons.utils.io.Directory;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.Logger;
import cc.kave.commons.utils.io.ReadingArchive;
import cc.kave.commons.utils.naming.TypeErasure;
import cc.kave.rsse.calls.UsageExtractor;
import cc.kave.rsse.calls.UsageMining;
import cc.kave.rsse.calls.UsageSorter;
import cc.kave.rsse.calls.mining.Options;
import cc.kave.rsse.calls.model.usages.IUsage;
import cc.kave.rsse.calls.recs.bmn.BMNModel;
import cc.kave.rsse.calls.recs.bmn.BMNModelStore;

public class BMNMining {

	private final String dirContexts;
	private final Options opts;

	private final UsageSorter usageSorter;
	private final BMNModelStore bmnModelStore;

	public BMNMining(Options opts, String dirContexts, String dirSortedUsages, String dirBmnModels) {
		this.opts = opts;
		this.dirContexts = dirContexts;
		usageSorter = new UsageSorter(dirSortedUsages, opts);
		bmnModelStore = new BMNModelStore(dirBmnModels, opts);
	}

	public void run() {
		clearAndSortUsages();
		clearAndMineModels();

		append("\n\n");
		log("done");
	}

	private void clearAndSortUsages() {
		usageSorter.clear();

		log("Searching for zips in %s... ", dirContexts);
		Set<String> zips = findContextZips();
		int total = zips.size();
		append("found %d zips", total);

		int cur = 0;
		for (String zip : zips) {
			double perc = 100 * ++cur / (double) total;
			log("###");
			log("### (%d/%d, %.1f%% started) --  opening %s ... ", cur, total, perc, zip);

			List<Context> ctxs = readCtxs(zip);
			append("(%d contexts)", ctxs.size());
			log("###\n");

			try {
				usageSorter.openLRUCache();
				for (Context ctx : ctxs) {
					ctx = TypeErasure.of(ctx); // remove bindings of generic types

					UsageExtractor ue = new UsageExtractor(ctx);
					List<IUsage> usages = ue.getUsages();
					List<IUsage> filtered = filter(usages);

					append("%d:%d, ", usages.size(), filtered.size());
					usageSorter.store(usages);
				}
				log("");
			} finally {
				usageSorter.close();
			}
		}
	}

	private Set<String> findContextZips() {
		Set<String> relZips = new Directory(dirContexts).findFiles(s -> s.endsWith(".zip"));
		return relZips.stream().map(n -> dirContexts + n).collect(Collectors.toSet());
	}

	private static List<Context> readCtxs(String zip) {
		File f = new File(zip);
		List<Context> ctxs = new LinkedList<>();
		try (IReadingArchive ra = new ReadingArchive(f)) {
			for (Context ctx : ra.getAll(Context.class)) {
				if (shouldProcess(ctx)) {
					ctxs.add(ctx);
				}
			}
		}
		return ctxs;
	}

	private static boolean shouldProcess(Context ctx) {
		ITypeName type = ctx.getSST().getEnclosingType();
		boolean hasMethods = ctx.getSST().getMethods().size() > 0;
		boolean shouldProcess = type.isClassType() && hasMethods;
		return shouldProcess;
	}

	private List<IUsage> filter(List<IUsage> usages) {
		return usages.stream().filter(BMNMining::isInteresting).collect(Collectors.toList());
	}

	private static boolean isInteresting(IUsage u) {
		ITypeName t = u.getType();
		if (t.isUnknown()) {
			return false;
		}
		if (t.isArray() || t.isTypeParameter() || t.isVoidType()) {
			return false;
		}
		if (t.getAssembly().isLocalProject()) {
			return false;
		}
		if (u.getMemberAccesses().size() == 0) {
			return false;
		}

		return true;
	}

	private void clearAndMineModels() {
		bmnModelStore.clear();

		log("Finding types ... ");
		Set<ITypeName> types = usageSorter.registeredTypes();
		int total = types.size();
		log("found %d types", total);

		int cur = 0;
		for (ITypeName t : types) {
			double perc = 100 * ++cur / (double) total;
			log("## (%d/%d, %.1f%% started) -- mining  %s", cur, total, perc, t);

			List<IUsage> usages = usageSorter.read(t);
			append(" (%d usages)", usages.size());

			if (usages.size() == 0) {
				log("Ignoring type.");
				continue;
			}

			// actually mine the models from all usages of a given type
			BMNModel bmnModel = UsageMining.mineBMN(usages, opts);

			if (bmnModel.table.getBMNTable().length == 0) {
				Logger.debug("Ignoring empty model.");
			} else {
				logModelSize(bmnModel);
				bmnModelStore.store(t, bmnModel);
			}
		}
	}

	private static void logModelSize(BMNModel m) {
		double mb = 1024 * 1024;
		append(" --> model is %.1f MB", m.table.getSize() / mb);
	}
}