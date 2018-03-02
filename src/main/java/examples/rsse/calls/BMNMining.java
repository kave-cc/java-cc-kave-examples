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
import static cc.kave.commons.utils.ssts.completioninfo.CompletionInfo.extractCompletionInfoFrom;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import cc.kave.commons.model.events.IDEEvent;
import cc.kave.commons.model.events.completionevents.CompletionEvent;
import cc.kave.commons.model.events.completionevents.Context;
import cc.kave.commons.model.events.completionevents.IProposal;
import cc.kave.commons.model.events.completionevents.TerminationState;
import cc.kave.commons.model.naming.IName;
import cc.kave.commons.model.naming.codeelements.IMethodName;
import cc.kave.commons.model.naming.types.ITypeName;
import cc.kave.commons.utils.io.Directory;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.Logger;
import cc.kave.commons.utils.io.ReadingArchive;
import cc.kave.commons.utils.naming.TypeErasure;
import cc.kave.commons.utils.ssts.completioninfo.CompletionInfo;
import cc.kave.rsse.calls.KaVEMining;
import cc.kave.rsse.calls.UsageExtractor;
import cc.kave.rsse.calls.UsageSorter;
import cc.kave.rsse.calls.bmn.BMNModel;
import cc.kave.rsse.calls.bmn.BMNModelStore;
import cc.kave.rsse.calls.bmn.BMNRecommender;
import cc.kave.rsse.calls.datastructures.Tuple;
import cc.kave.rsse.calls.usages.Usage;
import cc.kave.rsse.calls.utils.RsseCallsJsonUtils;

public class BMNMining {

	private static final String dirRoot = "/path/to/folder/";
	private static final String dirEvents = dirRoot + "someevents/";
	private static final String dirContexts = dirRoot + "Contexts-170503/";
	private static final String dirSortedUsages = dirRoot + "sortedusages/";
	private static final String dirModels = dirRoot + "bmn-models/";

	private final UsageSorter us = new UsageSorter(dirSortedUsages, "all-in-one-no-rare");
	private BMNModelStore bmnModels = new BMNModelStore(dirModels, "default");

	public void run() {
		init();

		// the following two statements are required to build models from scratch. Once
		// these have passed, you will find all models in the "dirModels" folder (of if
		// you have downloaded models into this folder) and you can disable both steps
		// to significantly speed-up the execution.
		clearAndSortUsages();
		clearAndMineModels();

		// requesting
		Set<String> eventZips = findEventZips();
		log("found %d event zips...", eventZips.size());
		for (String zip : eventZips) {
			log("### %s ... ", zip);
			List<CompletionEvent> appliedCompletions = findAppliedCompletionEvents(zip);
			append("(%d applied completions)\n", appliedCompletions.size());

			evaluate(appliedCompletions);
		}

		append("\n\n");
		log("done");
	}

	private void evaluate(List<CompletionEvent> appliedCompletions) {

		for (CompletionEvent ce : appliedCompletions) {
			List<IName> vsProposals = getVisualStudioProposals(ce);

			Context ctx = ce.getContext();
			ctx = TypeErasure.of(ctx); // remove bindings of generic types

			// skip event, if no selection exists
			IProposal selection = ce.getLastSelectedProposal();
			if (selection == null) {
				append("x, ");
				continue;
			}
			IName expectation = selection.getName();

			ITypeName t = findTypeOfTarget(ctx);
			if (t != null && !t.isUnknown()) {

				if (!bmnModels.hasModel(t)) {
					log("no model for %s, skipping... :/", t);
					continue;
				}

				BMNModel model = bmnModels.getModel(t);
				BMNRecommender rec = KaVEMining.getBMNRecommender(model);

				// query with context...
				Set<Tuple<IMethodName, Double>> res = rec.query(ctx, vsProposals);

				evaluate(expectation, res);
			}
		}
	}

	private static void evaluate(IName expected, Set<Tuple<IMethodName, Double>> actuals) {
		// TODO: this is just basic debugging output on the terminal, extend to the
		// example and calculate some real metrics.
		if (expected instanceof IMethodName) {
			expected = TypeErasure.of((IMethodName) expected);
		}
		log("-------------------------------");
		log("wanted: %s", expected);
		log("proposals");
		int hit = -1;
		int count = 0;
		for (Tuple<IMethodName, Double> e : actuals) {
			count++;
			IMethodName proposal = e.getFirst();

			log(" - %s  (%.1f%%)", proposal, e.getSecond() * 100);
			if (proposal.equals(expected)) {
				hit = count;
			}
		}
		log("Hit on index: %d", hit);
	}

	private void init() {
		Logger.setDebugging(true);
		Logger.setPrinting(true);

		double gb = 1024 * 1024 * 1024;
		log("Max Memory: %.1f GB", Runtime.getRuntime().maxMemory() / gb);

		RsseCallsJsonUtils.registerJsonAdapters();
	}

	private void clearAndMineModels() {
		log("Clearing %s ...", dirModels);
		bmnModels.clear();

		log("Finding types ... ");
		Set<ITypeName> types = us.registeredTypes();
		int total = types.size();
		log("found %d types", total);

		int cur = 0;
		for (ITypeName t : types) {
			double perc = 100 * ++cur / (double) total;
			log("## (%d/%d, %.1f%% started) -- mining  %s", cur, total, perc, t);

			List<Usage> usages = us.read(t);
			append(" (%d usages)", usages.size());

			// actually mine the models from all usages of a given type
			BMNModel bmnModel = KaVEMining.mineBMN(usages);

			logModelSize(bmnModel);
			bmnModels.store(t, bmnModel);
		}
	}

	private void clearAndSortUsages() {
		us.clear();

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

			for (Context ctx : ctxs) {

				ctx = TypeErasure.of(ctx); // remove bindings of generic types

				// TODO add extract options...
				UsageExtractor ue = new UsageExtractor(ctx);
				List<Usage> usages = ue.getUsages();

				append("%d, ", usages.size());
				us.store(usages);
			}
			log("");
		}
	}

	private static List<IName> getVisualStudioProposals(CompletionEvent ce) {
		return ce.getProposalCollection().stream().map(p -> p.getName()).collect(Collectors.toList());
	}

	private static ITypeName findTypeOfTarget(Context context) {
		Optional<CompletionInfo> info = extractCompletionInfoFrom(context.getSST());
		if (info.isPresent()) {
			return info.get().getTriggeredType();
		}
		return null;
	}

	private static List<CompletionEvent> findAppliedCompletionEvents(String zip) {
		File f = new File(zip);
		List<CompletionEvent> events = new LinkedList<>();
		try (IReadingArchive ra = new ReadingArchive(f)) {
			while (ra.hasNext()) {
				IDEEvent e = ra.getNext(IDEEvent.class);
				if (e instanceof CompletionEvent) {
					CompletionEvent ce = (CompletionEvent) e;
					if (ce.getTerminatedState() == TerminationState.Applied
							&& ce.getLastSelectedProposal().getName() instanceof IMethodName) {
						events.add(ce);
					}
				}
			}
		}
		return events;
	}

	public static Set<String> findEventZips() {
		Set<String> relZips = new Directory(dirEvents).findFiles(s -> s.endsWith(".zip"));
		return relZips.stream().map(n -> dirEvents + n).collect(Collectors.toSet());
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

	private static Set<String> findContextZips() {
		Set<String> relZips = new Directory(dirContexts).findFiles(s -> s.endsWith(".zip"));
		return relZips.stream().map(n -> dirContexts + n).collect(Collectors.toSet());
	}

	private static void logModelSize(BMNModel m) {
		double mb = 1024 * 1024;
		append(" (model is %.1f MB)", m.table.getSize() / mb);
	}
}