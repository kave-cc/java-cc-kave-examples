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

import java.io.Closeable;
import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;

import cc.kave.commons.assertions.Asserts;
import cc.kave.commons.model.events.IDEEvent;
import cc.kave.commons.model.events.completionevents.CompletionEvent;
import cc.kave.commons.model.events.completionevents.Context;
import cc.kave.commons.model.events.completionevents.IProposal;
import cc.kave.commons.model.events.completionevents.TerminationState;
import cc.kave.commons.model.naming.IName;
import cc.kave.commons.model.naming.codeelements.IMemberName;
import cc.kave.commons.model.naming.codeelements.IMethodName;
import cc.kave.commons.model.naming.types.ITypeName;
import cc.kave.commons.model.ssts.impl.expressions.assignable.CompletionExpression;
import cc.kave.commons.model.typeshapes.ITypeHierarchy;
import cc.kave.commons.utils.io.Directory;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.Logger;
import cc.kave.commons.utils.io.ReadingArchive;
import cc.kave.commons.utils.naming.TypeErasure;
import cc.kave.commons.utils.ssts.completioninfo.CompletionInfo;
import cc.kave.rsse.calls.UsageMining;
import cc.kave.rsse.calls.mining.Options;
import cc.kave.rsse.calls.recs.bmn.BMNModelStore;
import cc.kave.rsse.calls.recs.bmn.BMNRecommender;

public class BMNEvaluation {

	private final Options opts;
	private final BMNModelStore bmnModelStore;
	private final String dirEvents;

	public BMNEvaluation(Options opts, String dirBmnModels, String dirEvents) {
		this.opts = opts;
		this.dirEvents = dirEvents;
		bmnModelStore = new BMNModelStore(dirBmnModels, opts);
	}

	public void run() {

		Set<String> eventZips = findEventZips();
		// Set<String> eventZips = new
		// HashSet<>(Arrays.asList("/Volumes/Data/Events-170301-2/earlier/data/1200-1299/1274.zip"));
		// Set<String> eventZips = new
		// HashSet<>(Arrays.asList("/Volumes/Data/Events-170301-2/2016-05-09/1.zip"));

		log("found %d event zips...", eventZips.size());
		int total = eventZips.size();
		int cur = 1;
		for (String zip : eventZips) {
			double perc = 100 * cur / (double) total;
			log("###");
			log("### %d/%d (%.1f%%): %s ...", cur++, total, perc, zip);
			double zipSize = FileUtils.sizeOf(new File(zip)) / (1024d * 1024d);
			append(" (%.2f MB)", zipSize);
			log("###");

			try (ReadingArchiveIterator it = findAppliedCompletionEvents(zip)) {

				try {
					while (it.hasNext()) {
						evaluate(it.next());
					}
				} catch (Exception e) {
					Logger.debug("Caught exception...");
					e.printStackTrace();
				}

				printResults();
			}
		}

		append("\n\n");
		log("done");
	}

	private int[] topK = new int[11]; // with "0" being total

	private void evaluate(CompletionEvent ce) {
		List<IName> vsProposals = getVisualStudioProposals(ce);

		Context ctx = ce.getContext();
		ctx = TypeErasure.of(ctx); // remove bindings of generic types

		// skip event, if no selection exists
		IProposal selection = ce.getLastSelectedProposal();
		if (selection == null) {
			append("x, ");
			return;
		}
		IName expectation = selection.getName();

		Optional<CompletionInfo> info = extractCompletionInfoFrom(ctx);

		if (!info.isPresent()) {
			// no completion info
			return;
		}

		CompletionExpression complE = (CompletionExpression) info.get().getCompletionExpr();
		if (complE.getTypeReference() != null) {
			// completion of type name
			return;
		}

		if (!(expectation instanceof IMemberName)) {
			// no member completion
			return;
		}
		IMemberName expectedMember = (IMemberName) expectation;

		if (expectedMember.isStatic()) {
			// completion of static member
			return;
		}

		ITypeName callDeclType = expectedMember.getDeclaringType();
		if (ctx.getSST().getEnclosingType().equals(callDeclType)) {
			// completion on "this"
			return;
		}
		if (isInHierarchy(callDeclType, ctx.getTypeShape().getTypeHierarchy())) {
			// completion on Base
			return;
		}

		ITypeName t = info.get().getTriggeredType();
		if (t == null) {
			// triggered type cannot be determined
			return;
		}

		if (expectedMember.isUnknown() || t.isUnknown()) {
			// invalid type
			return;
		}
		if (!bmnModelStore.hasModel(t)) {
			// no model
			return;
		}

		// make sure to remove generics
		expectedMember = TypeErasure.of(expectedMember);

		// instantiate recommender...
		BMNRecommender bmnRec = UsageMining.getBMNRecommender(bmnModelStore, opts);
		// .. and request proposals
		Set<Pair<IMemberName, Double>> bmnRes = bmnRec.query(ctx, vsProposals);
		evaluate(t, expectedMember, bmnRes);
	}

	private boolean isInHierarchy(ITypeName t, ITypeHierarchy th) {
		if (t.equals(th.getElement())) {
			return true;
		}

		if (th.hasSuperclass() && isInHierarchy(t, th.getExtends())) {
			return true;
		}

		for (ITypeHierarchy thI : th.getImplements()) {
			if (isInHierarchy(t, thI)) {
				return true;
			}
		}
		return false;
	}

	private void evaluate(ITypeName targetType, IMemberName expected, Set<Pair<IMemberName, Double>> actuals) {
		topK[0]++;
		// TODO: this is just basic debugging output on the terminal, extend to the
		// example and calculate some real metrics.
		if (expected instanceof IMethodName) {
			expected = TypeErasure.of((IMethodName) expected);
		}
		log("-------------------------------");
		log("triggered for: %s", targetType);
		log("wanted: %s", expected);
		log("proposals");
		int hit = -1;
		int count = 0;
		for (Pair<IMemberName, Double> e : actuals) {
			count++;
			IMemberName proposal = e.getKey();

			log(" - %s  (%.1f%%)", proposal, e.getValue() * 100);
			if (proposal.equals(expected)) {
				hit = count;
			}
		}
		if (hit != -1) {
			log("Hit on index: %d", hit);
			for (int i = hit; i < topK.length; i++) {
				topK[i]++;
			}
		}
	}

	private void printResults() {
		log("TopK precision for %d completions:", topK[0]);
		for (int i = 1; i < topK.length; i++) {
			double topKPrec = topK[i] / (double) topK[0];
			log("Top%d: %.1f%%", i, topKPrec * 100);
		}
		log("");
	}

	private static List<IName> getVisualStudioProposals(CompletionEvent ce) {
		Stream<IProposal> stream = ce.getProposalCollection().stream();
		Stream<IName> mapped = stream.map(p -> p.getName());
		return mapped.collect(Collectors.toList());
	}

	private static ReadingArchiveIterator findAppliedCompletionEvents(String zip) {
		File f = new File(zip);
		return new ReadingArchiveIterator(new ReadingArchive(f));
	}

	private static class ReadingArchiveIterator implements Iterator<CompletionEvent>, Closeable {

		private IReadingArchive ra;
		CompletionEvent next = null;

		public ReadingArchiveIterator(IReadingArchive ra) {
			this.ra = ra;
		}

		@Override
		public boolean hasNext() {
			if (next != null) {
				return true;
			}
			while (next == null && ra.hasNext()) {
				IDEEvent e = ra.getNext(IDEEvent.class);
				if (e instanceof CompletionEvent) {
					CompletionEvent ce = (CompletionEvent) e;
					if (ce.getTerminatedState() == TerminationState.Applied) {
						next = ce;
					}
				}
			}
			return next != null;
		}

		@Override
		public CompletionEvent next() {
			Asserts.assertTrue(hasNext());
			CompletionEvent res = next;
			next = null;
			return res;
		}

		@Override
		public void close() {
			ra.close();
		}

	}

	public Set<String> findEventZips() {
		Set<String> relZips = new Directory(dirEvents).findFiles(s -> s.endsWith(".zip"));
		return relZips.stream().map(n -> dirEvents + n).collect(Collectors.toSet());
	}
}