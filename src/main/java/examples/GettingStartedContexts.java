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
package examples;

import java.io.File;
import java.util.Set;

import cc.kave.commons.model.events.completionevents.Context;
import cc.kave.commons.model.naming.Names;
import cc.kave.commons.model.naming.codeelements.IMethodName;
import cc.kave.commons.model.naming.codeelements.IParameterName;
import cc.kave.commons.model.naming.types.ITypeName;
import cc.kave.commons.model.ssts.ISST;
import cc.kave.commons.model.ssts.IStatement;
import cc.kave.commons.model.ssts.declarations.IMethodDeclaration;
import cc.kave.commons.model.ssts.impl.visitor.AbstractTraversingNodeVisitor;
import cc.kave.commons.model.typeshapes.IMemberHierarchy;
import cc.kave.commons.model.typeshapes.ITypeHierarchy;
import cc.kave.commons.model.typeshapes.ITypeShape;
import cc.kave.commons.utils.io.IReadingArchive;
import cc.kave.commons.utils.io.ReadingArchive;

@SuppressWarnings("unused")
public class GettingStartedContexts {

	private String ctxsDir;

	public GettingStartedContexts(String ctxsDir) {
		this.ctxsDir = ctxsDir;
	}

	public void run() {

		System.out.printf("looking (recursively) for solution zips in folder %s\n",
				new File(ctxsDir).getAbsolutePath());

		/*
		 * Each .zip that is contained in the ctxsDir represents all contexts that we
		 * have extracted for a specific C# solution found on GitHub. The folder
		 * represents the structure of the repository. The first level is the GitHub
		 * user, the second the repository name. After that, the folder structure
		 * represents the file organization within the respective repository. If you
		 * manually open the corresponding GitHub repository, you will find a "<x>.sln"
		 * file for a "<x>.sln.zip" that is contained in our dataset.
		 */
		Set<String> slnZips = IoHelper.findAllZips(ctxsDir);

		for (String slnZip : slnZips) {
			System.out.printf("\n#### processing solution zip: %s #####\n", slnZip);
			processSlnZip(slnZip);
		}
	}

	private void processSlnZip(String slnZip) {
		int numProcessedContexts = 0;

		// open the .zip file ...
		try (IReadingArchive ra = new ReadingArchive(new File(ctxsDir, slnZip))) {
			// ... and iterate over content.

			// the iteration will stop after 10 contexts to speed things up in the example.
			while (ra.hasNext() && (numProcessedContexts++ < 10)) {
				/*
				 * within the slnZip, each stored context is contained as a single file that
				 * contains the Json representation of a {@see Context}.
				 */
				Context ctx = ra.getNext(Context.class);

				// the events can then be processed individually
				processContext(ctx);
			}
		}
	}

	private void processContext(Context ctx) {
		// a context is an abstract view on a single type declaration that contains of
		// two things:

		// 1) a simplified syntax tree of the type declaration
		process(ctx.getSST());

		// 2) a "type shape" that provides information about the hierarchy of the
		// declared type
		process(ctx.getTypeShape());
	}

	private void process(ISST sst) {
		// SSTs represent a simplified meta model for source code. You can use the
		// various accessors to browse the contained information

		// which type was edited?
		ITypeName declType = sst.getEnclosingType();

		// which methods are defined?
		for (IMethodDeclaration md : sst.getMethods()) {
			IMethodName m = md.getName();

			for (IStatement stmt : md.getBody()) {
				// process the body, most likely by traversing statements with an {@see
				// ISSTNodeVisitor}
				stmt.accept(new ExampleVisitor(), null);
			}
		}

		// all references to types or type elements are fully qualified and preserve
		// many information about the resolved type
		declType.getNamespace();
		declType.isInterfaceType();
		declType.getAssembly();

		// you can distinguish reused types from types defined in a local project
		boolean isLocal = declType.getAssembly().isLocalProject();

		// the same is possible for all other <see>IName</see> subclasses, e.g.,
		// <see>IMethodName</see>
		IMethodName m = Names.getUnknownMethod();
		m.getDeclaringType();
		m.getReturnType();
		// or inspect the signature
		for (IParameterName p : m.getParameters()) {
			String pid = p.getName();
			ITypeName ptype = p.getValueType();
		}
	}

	private void process(ITypeShape ts) {
		// a type shape contains hierarchy info for the declared type
		ITypeHierarchy th = ts.getTypeHierarchy();
		// the type that is being declared in the SST
		ITypeName tElem = th.getElement();
		// the type might extend another one (that again has a hierarchy)
		if (th.getExtends() != null) {
			ITypeName tExt = th.getExtends().getElement();
		}
		// or implement interfaces...
		for (ITypeHierarchy tImpl : th.getImplements()) {
			ITypeName tInterf = tImpl.getElement();
		}

		// a type shape contains hierarchy info for all methods declared in the SST
		Set<IMemberHierarchy<IMethodName>> mhs = ts.getMethodHierarchies();
		for (IMemberHierarchy<IMethodName> mh : mhs) {
			// the declared element (you will find the same name in the SST)
			IMethodName elem = mh.getElement();

			// potentially, the method overrides another one higher in the hierarchy
			// (may be null)
			IMethodName sup = mh.getSuper();

			// in deep hierarchies, the method signature might have been introduced earlier
			// (may be null)
			IMethodName first = mh.getFirst();
		}

		// you can also access hierarchy information about other members...
		ts.getDelegates();
		ts.getEventHierarchies();
		ts.getFields();
		ts.getPropertyHierarchies();
		// ... and nested types
		ts.getNestedTypes();
	}

	private class ExampleVisitor extends AbstractTraversingNodeVisitor<Object, Object> {
		// empty implementation for the example, in reality, you will either reuse
		// existing {@see ISSTNodeVisitor} or build your own subclass.
	}
}