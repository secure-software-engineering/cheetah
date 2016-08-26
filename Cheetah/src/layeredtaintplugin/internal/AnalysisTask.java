package layeredtaintplugin.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Table;

import heros.EdgeFunction;
import heros.FlowFunction;
import heros.FlowFunctions;
import heros.solver.IFDSSolver;
import heros.solver.IFDSSolver.BinaryDomain;
import heros.solver.JumpFunctions;
import layeredtaintplugin.Config;
import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.icfg.JitIcfg;
import layeredtaintplugin.internal.layer.Layer;
import layeredtaintplugin.reporter.Reporter;
import soot.Body;
import soot.Local;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.CastExpr;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.DefaultJimpleIFDSTabulationProblem;
import soot.tagkit.Tag;

public abstract class AnalysisTask {

	private final static Logger LOGGER = LoggerFactory.getLogger(AnalysisTask.class);

	protected final static boolean DEBUG_REPORT = false;
	protected final static boolean DEBUG_IFDS_RESULTS = false;
	protected final static boolean DEBUG_IFDS = false;
	protected final static boolean DEBUG_TASKS = false;
	protected final static boolean DEBUG_TASKS2 = false;
	protected final static boolean DEBUG_NEIGHBOURS = false;
	protected final static boolean DEBUG_SUSI = false;
	protected final static boolean DEBUG_ALIAS = false;
	protected final static boolean DEBUG_API = false;
	protected final static boolean DEBUG_SANITIZE = false;
	protected final static boolean DEBUG_DUMMY_MAIN = false;

	private Reporter reporter;

	protected final Task task;
	protected Set<Task> nextTasks = new HashSet<Task>();

	protected JumpFunctions<Unit, FlowAbstraction, BinaryDomain> jumpFunctions;
	protected Table<Unit, FlowAbstraction, Table<Unit, FlowAbstraction, EdgeFunction<BinaryDomain>>> endSum;
	protected Table<Unit, FlowAbstraction, Map<Unit, Set<FlowAbstraction>>> inc;
	protected JitIcfg icfg;
	protected SetupApplicationJIT app;
	protected ProjectInformation projectInformation;

	public AnalysisTask(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		this.task = task;
		this.app = app;
		this.projectInformation = projectInformation;
	}

	public void setAnalysisInfo(JumpFunctions<Unit, FlowAbstraction, BinaryDomain> jumpFunctions,
			Table<Unit, FlowAbstraction, Table<Unit, FlowAbstraction, EdgeFunction<BinaryDomain>>> endSum,
			Table<Unit, FlowAbstraction, Map<Unit, Set<FlowAbstraction>>> inc, JitIcfg icfg) {
		this.jumpFunctions = jumpFunctions;
		this.endSum = endSum;
		this.inc = inc;
		this.icfg = icfg;
	}

	public void setReporter(Reporter reporter) {
		this.reporter = reporter;
	}

	/***** Tasks *****/

	// What is needed for the given starting class and statement?
	public abstract Set<Task> requiredTasks();

	// What should be scanned in the next level?
	public abstract Set<Task> nextTasks();

	protected abstract Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets);

	protected Set<Task> createTasks(Unit call) {

		Set<Task> newTasks = new HashSet<Task>();

		Set<SootMethod> chaTargets = icfg.unitToCallees.getUnchecked(call);

		for (Iterator<SootMethod> iterator = chaTargets.iterator(); iterator.hasNext();) {
			SootMethod potentialTarget = iterator.next();
			// If names match and target is not abstract
			if (potentialTarget.isAbstract()
					|| !potentialTarget.getName().equals(((Stmt) call).getInvokeExpr().getMethod().getName())) {
				iterator.remove();
			}
		}

		for (Layer l : Layer.values()) {
			AnalysisTask al = Layer.getAnalysisLayer(l, task, app, projectInformation);
			al.setAnalysisInfo(jumpFunctions, endSum, inc, icfg);
			Set<Task> tasks = al.createTasksForCall(call, chaTargets);
			newTasks.addAll(tasks);
		}
		return newTasks;
	}

	/***** Class loading *****/

	protected SootClass loadClass(String className) {

		if (!Scene.v().containsType(className)
				|| Scene.v().getSootClass(className).resolvingLevel() < SootClass.BODIES) {
			try {
				Scene.v().loadClassAndSupport(className);
			} catch (Exception e) {
				LOGGER.error("Soot class not found " + className);
				e.printStackTrace();
			}
		}
		return Scene.v().getSootClass(className);
	}

	protected Body loadActiveBody(SootMethod sm) {
		String className = sm.getDeclaringClass().getName();
		loadClass(className);
		if (!sm.hasActiveBody()) {
			Body b = sm.retrieveActiveBody();
			Scene.v().getOrMakeFastHierarchy();
			return b;
		}
		return sm.getActiveBody();
	}

	/***** Project information *****/

	protected boolean inSameFile(SootClass class1, SootClass class2) {
		if (!class1.getPackageName().equals(class2.getPackageName()))
			return false;
		Tag class1Tag = class1.getTag("SourceFileTag");
		Tag class2Tag = class2.getTag("SourceFileTag");
		if (class1Tag != null && class2Tag != null
				&& new String(class1Tag.getValue()).equals(new String(class2Tag.getValue()))) {
			return true;
		}
		return false;
	}

	protected boolean inSamePackage(SootClass class1, SootClass class2) {
		return class1.getJavaPackageName().equals(class2.getJavaPackageName());
	}

	protected boolean inProject(String scName) {
		return projectInformation.projectClasses().contains(scName);
	}

	protected Set<SootClass> classesInSameFile(SootClass declaringClass) {
		Set<SootClass> classesInSameFile = new HashSet<SootClass>();
		// classes in the same file have necessarily been loaded before
		for (SootClass sc : Scene.v().getClasses()) {
			if (inSameFile(sc, declaringClass))
				classesInSameFile.add(sc);
		}
		return classesInSameFile;
	}

	protected Set<SootClass> classesInSamePackage(SootClass bsc) {
		Set<SootClass> res = new HashSet<SootClass>();
		// need to explicitly load all classes in package
		for (String className : projectInformation.projectClasses())
			if (className.startsWith(bsc.getJavaPackageName()))
				res.add(loadClass(className));
		return res;
	}

	public Set<SootClass> getProjectClasses() {
		Set<SootClass> res = new HashSet<SootClass>();
		// need to explicitly load all classes in project
		for (String className : projectInformation.projectClasses())
			res.add(loadClass(className));
		return res;
	}

	/***** CG *****/

	protected Set<SootMethod> calleesOfCallAt(Unit u) {
		if (task.getStartUnit() == u)
			return task.getTargets();
		return new HashSet<SootMethod>();
	}

	public void analyze() {
		icfg.initForMethod(task.getStartMethod());
		InterproceduralAnalysisProblem problem = new InterproceduralAnalysisProblem(icfg);
		IFDSSolver<Unit, FlowAbstraction, SootMethod, JitIcfg> solver = new IFDSSolver<Unit, FlowAbstraction, SootMethod, JitIcfg>(
				problem, jumpFunctions, endSum, inc) {

			// FlowTwist -> PropagateAndMerge
			@Override
			protected void propagate(FlowAbstraction sourceVal, Unit target, FlowAbstraction targetVal,
					EdgeFunction<IFDSSolver.BinaryDomain> f, Unit relatedCallSite, boolean isUnbalancedReturn,
					boolean force) {

				// Report leaks from summaries
				Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks = jumpFn.summaryPathsLookup(sourceVal,
						target);
				if (!summaryLeaks.isEmpty()) {
					if (DEBUG_REPORT)
						LOGGER.debug("Report (sum) " + sourceVal + " --- " + target.getJavaSourceStartLineNumber() + ":"
								+ target + " --- " + summaryLeaks);
					reporter.report(sourceVal, summaryLeaks);
				}

				// Set neighbours
				FlowAbstraction neighbour = null;
				Set<FlowAbstraction> similarTargets = jumpFn.forwardLookup(sourceVal, target).keySet();
				for (FlowAbstraction fa : similarTargets) {
					// If the values are similar, but not exact
					if (!targetVal.equals(FlowAbstraction.zeroAbstraction()) && targetVal.getUnit() != target
							&& !fa.exactEquals(targetVal) && fa.getShortName().equals(targetVal.getShortName())) {
						fa.addNeighbour(targetVal);
						neighbour = fa;
						if (DEBUG_NEIGHBOURS)
							LOGGER.debug("Initiate Neighbour " + targetVal + ":" + targetVal.getUnit() + " TO " + fa
									+ ":" + fa.getUnit() + " AT " + target + " METHOD " + icfg.getMethodOf(target));
						break;
					}
				}
				if (neighbour == null)
					propagateAfterMerge(sourceVal, target, targetVal, f, relatedCallSite, isUnbalancedReturn, force);
				else
					propagateAfterMerge(sourceVal, target, neighbour, f, relatedCallSite, isUnbalancedReturn, force);

			}
		};
		solver.solve();
	}

	// IFDS Problem
	protected class InterproceduralAnalysisProblem
			extends DefaultJimpleIFDSTabulationProblem<FlowAbstraction, JitIcfg> {

		public InterproceduralAnalysisProblem(JitIcfg icfg) {
			super(icfg);
		}

		@Override
		public FlowFunctions<Unit, FlowAbstraction, SootMethod> createFlowFunctionsFactory() {
			return new FlowFunctions<Unit, FlowAbstraction, SootMethod>() {

				@Override
				public FlowFunction<FlowAbstraction> getNormalFlowFunction(Unit src, Unit dest) {
					if (DEBUG_IFDS)
						LOGGER.info("norm " + src + " --- " + interproceduralCFG().getMethodOf(src));
					return doNormalFlowFunction(src, dest);
				}

				@Override
				public FlowFunction<FlowAbstraction> getCallFlowFunction(Unit src, final SootMethod dest) {
					if (DEBUG_IFDS)
						LOGGER.info("call " + src + " --- " + interproceduralCFG().getMethodOf(src));
					return doCallFlowFunction(src, dest);
				}

				@Override
				public FlowFunction<FlowAbstraction> getReturnFlowFunction(Unit callSite, final SootMethod callee,
						Unit exitStmt, Unit retSite) {
					if (DEBUG_IFDS)
						LOGGER.info("ret " + callSite + " --- " + interproceduralCFG().getMethodOf(callSite));
					return doReturnFlowFunction(callSite, callee, exitStmt, retSite);
				}

				@Override
				public FlowFunction<FlowAbstraction> getCallToReturnFlowFunction(final Unit call, Unit returnSite) {
					if (DEBUG_IFDS)
						LOGGER.info("cot " + call + " --- " + interproceduralCFG().getMethodOf(call));
					return doCallToReturnFlowFunction(call, returnSite);
				}
			};
		}

		@Override
		public FlowAbstraction createZeroValue() {
			return FlowAbstraction.zeroAbstraction();
		}

		@Override
		public Map<Unit, Set<FlowAbstraction>> initialSeeds() {
			Map<Unit, Set<FlowAbstraction>> res = new HashMap<Unit, Set<FlowAbstraction>>();
			Unit unit = task.getStartUnit();
			if (unit == null) {
				SootMethod method = task.getStartMethod();
				unit = method.getActiveBody().getUnits().getFirst();
			}
			Set<FlowAbstraction> abs = task.getInFacts();
			abs.add(zeroValue());
			res.put(unit, abs);
			return res;
		}
	}

	// Default rules for the taint analysis. Called by the layer if necessary
	protected FlowFunction<FlowAbstraction> doNormalFlowFunction(final Unit src, Unit dest) {

		final Stmt stmt = (Stmt) src;
		if (stmt instanceof AssignStmt) {

			final AssignStmt assignStmt = (AssignStmt) stmt;
			final Value left = assignStmt.getLeftOp();
			final Value right = assignStmt.getRightOp();
			return new FlowFunction<FlowAbstraction>() {

				@Override
				public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {

					Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();

					// If the right side is tainted, we need to taint the left
					// side as well
					for (FlowAbstraction fa : getTaints(right, left, source, src)) {
						outSet.add(fa);
						outSet.addAll(taintAliases(fa));
					}

					// We only propagate the incoming taint forward if the
					// respective variable is not overwritten
					boolean leftSideMatches = false;
					if (left instanceof Local && source.getLocal() == left)
						leftSideMatches = true;
					else if (left instanceof InstanceFieldRef) {
						InstanceFieldRef ifr = (InstanceFieldRef) left;
						if (source.hasPrefix(ifr))
							leftSideMatches = true;
					} else if (left instanceof StaticFieldRef) {
						StaticFieldRef sfr = (StaticFieldRef) left;
						if (source.hasPrefix(sfr))
							leftSideMatches = true;
					}
					if (!leftSideMatches)
						outSet.add(source.deriveWithNewStmt(src, icfg.getMethodOf(src)));

					removeUnusedTaints(src, outSet);

					if (DEBUG_IFDS_RESULTS)
						printDebugIFDSInfo("norm", src, source, outSet);

					return outSet;
				}
			};
		}

		return new FlowFunction<FlowAbstraction>() {

			@Override
			public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {
				Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();
				FlowAbstraction newAbs = source.deriveWithNewStmt(src, icfg.getMethodOf(src));
				outSet.add(newAbs);
				removeUnusedTaints(src, outSet);
				if (DEBUG_IFDS_RESULTS)
					printDebugIFDSInfo("norm (id)", src, source, outSet);

				return outSet;
			}

		};
	}

	protected FlowFunction<FlowAbstraction> doCallFlowFunction(final Unit src, final SootMethod dest) {

		final Stmt stmt = (Stmt) src;
		final InvokeExpr ie = stmt.getInvokeExpr();
		// Get the formal parameter locals in the callee
		final List<Local> paramLocals = new ArrayList<Local>();
		if (!dest.getName().equals("<clinit>"))
			for (int i = 0; i < dest.getParameterCount(); i++)
				paramLocals.add(dest.getActiveBody().getParameterLocal(i));

		return new FlowFunction<FlowAbstraction>() {

			@Override
			public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {

				Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();

				// Static fields
				if (source.getLocal() == null) {
					outSet.add(source.deriveWithNewStmt(src, icfg.getMethodOf(src)));
					// Map the "this" value
				} else if (ie instanceof InstanceInvokeExpr
						&& ((InstanceInvokeExpr) ie).getBase() == source.getLocal()) {
					outSet.add(source.deriveWithNewLocal(dest.getActiveBody().getThisLocal(), src,
							icfg.getMethodOf(src), source));
					// Map the parameters
				} else if (ie.getArgs().contains(source.getLocal())) {
					int argIndex = ie.getArgs().indexOf(source.getLocal());
					FlowAbstraction fa = source.deriveWithNewLocal(paramLocals.get(argIndex), src,
							icfg.getMethodOf(src), source);
					if (DEBUG_IFDS_RESULTS)
						printDebugIFDSInfo("call (args)", src, source, Collections.singleton(fa));
					return Collections.singleton(fa);
				}

				if (DEBUG_IFDS_RESULTS)
					printDebugIFDSInfo("call", src, source, outSet);
				return outSet;
			}
		};
	}

	public FlowFunction<FlowAbstraction> doReturnFlowFunction(final Unit callSite, final SootMethod callee,
			Unit exitStmt, Unit retSite) {

		final Value retOp = (exitStmt instanceof ReturnStmt) ? ((ReturnStmt) exitStmt).getOp() : null;
		final Value tgtOp = (callSite instanceof DefinitionStmt) ? ((DefinitionStmt) callSite).getLeftOp() : null;
		final InvokeExpr invExpr = ((Stmt) callSite).getInvokeExpr();

		// Get the formal parameter locals in the callee
		final List<Local> paramLocals = new ArrayList<Local>();
		if (!callee.getName().equals("<clinit>"))
			for (int i = 0; i < callee.getParameterCount(); i++)
				paramLocals.add(callee.getActiveBody().getParameterLocal(i));

		return new FlowFunction<FlowAbstraction>() {

			@Override
			public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {
				Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();
				// Map the return value
				if (retOp != null && source.getLocal() == retOp && tgtOp != null) {
					outSet.add(source.deriveWithNewLocal((Local) tgtOp, callSite, icfg.getMethodOf(callSite), source));
				} else if (invExpr instanceof InstanceInvokeExpr
						&& source.getLocal() == callee.getActiveBody().getThisLocal()) {
					// Map the the "this" local
					Local baseLocal = (Local) ((InstanceInvokeExpr) invExpr).getBase();
					FlowAbstraction fa = source.deriveWithNewLocal(baseLocal, callSite, icfg.getMethodOf(callSite),
							source);
					outSet.add(fa);
					outSet.addAll(taintAliases(fa));
				}
				// Map the parameters
				else if (source.getFields() != null && paramLocals.contains(source.getLocal())) {
					int paramIdx = paramLocals.indexOf(source.getLocal());
					if (!(invExpr.getArg(paramIdx) instanceof Constant)) {
						FlowAbstraction fa = source.deriveWithNewLocal((Local) invExpr.getArg(paramIdx), callSite,
								icfg.getMethodOf(callSite), source);
						outSet.add(fa);
						outSet.addAll(taintAliases(fa));
					}
				}
				// Static variables
				if (source.getLocal() == null) {
					FlowAbstraction newAbs = source.deriveWithNewStmt(callSite, icfg.getMethodOf(callSite));
					outSet.add(newAbs);
				}

				removeUnusedTaints(callSite, outSet);

				if (DEBUG_IFDS_RESULTS)
					printDebugIFDSInfo("ret", callSite, source, outSet);
				return outSet;
			}
		};
	}

	public FlowFunction<FlowAbstraction> doCallToReturnFlowFunction(final Unit call, final Unit returnSite) {

		final Stmt stmt = (Stmt) call;

		/***** SOURCE OR SINK *****/

		if (stmt.containsInvokeExpr()) {
			final boolean isSink = app.getSourceSinkManager().isSink((Stmt) call, icfg, null);
			final boolean isSource = (app.getSourceSinkManager().getSourceInfo((Stmt) call, icfg) != null);

			if (isSource || isSink) {
				return new FlowFunction<FlowAbstraction>() {
					@Override
					public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {

						Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();

						if (isSource && (stmt instanceof DefinitionStmt)) {

							if (DEBUG_SUSI)
								LOGGER.info(task.getLayer() + " found a source: " + call);

							outSet.add(source.deriveWithNewStmt(call, icfg.getMethodOf(call)));
							if (source.equals(FlowAbstraction.zeroAbstraction())) {
								DefinitionStmt defStmt = (DefinitionStmt) stmt;
								Local leftLocal = (Local) defStmt.getLeftOp();
								FlowAbstraction fa = new FlowAbstraction(defStmt, leftLocal, call,
										icfg.getMethodOf(call), FlowAbstraction.zeroAbstraction());
								outSet.add(fa);
								outSet.addAll(taintAliases(fa));
							}
						}
						if (isSink) {
							if (DEBUG_SUSI)
								LOGGER.info(task.getLayer() + " found a sink: " + call);

							FlowAbstraction fa = source.deriveWithNewStmt(call, icfg.getMethodOf(call));
							boolean sourceInArgs = false;
							for (Value arg : stmt.getInvokeExpr().getArgs()) {
								if (source.hasPrefix(arg)) {
									sourceInArgs = true;
									break;
								}
							}

							boolean baseIsSource = false;
							if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
								InstanceInvokeExpr iie = (InstanceInvokeExpr) stmt.getInvokeExpr();
								baseIsSource = source.hasPrefix(iie.getBase());
							}

							if (sourceInArgs || baseIsSource) {
								if (DEBUG_REPORT)
									LOGGER.debug("REPORT " + task.getLayer() + " callToReturnFF " + call);
								reporter.report(fa, new HashMap<FlowAbstraction, Set<List<FlowAbstraction>>>());
							}

							outSet.add(fa);
						}

						// Sources and sinks are API calls
						for (FlowAbstraction fa : taintApi(call, source, outSet)) {
							outSet.add(fa);
							outSet.addAll(taintAliases(fa));
						}

						FlowAbstraction fa = source.deriveWithNewStmt(call, icfg.getMethodOf(call)); // source
						outSet.add(fa);

						if (DEBUG_IFDS_RESULTS)
							printDebugIFDSInfo("susiCOT", call, source, outSet);
						return outSet;
					}
				};
			}

			/***** TASK CREATION *****/

			if (!stmt.equals(task.getStartUnit()) && !isSource && !isSink) {

				final Set<Task> newTasks = createTasks(call);

				if (!newTasks.isEmpty()) { // Has new tasks
					nextTasks.addAll(newTasks);
					if (DEBUG_TASKS) {
						for (Task t : newTasks)
							LOGGER.debug(task.getLayer() + " create task " + t.toLongString() + " --- " + call);
					}

					// kill taints of arguments and add them to the task
					return new FlowFunction<FlowAbstraction>() {
						@Override
						public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {

							Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();
							FlowAbstraction fa = source.deriveWithNewStmt(call, icfg.getMethodOf(call)); // source

							Value sourceInArgs = null;
							for (Value arg : stmt.getInvokeExpr().getArgs()) {
								if (source.hasPrefix(arg)) {
									sourceInArgs = arg;
									break;
								}
							}
							boolean sourceIsCallerObj = false;
							if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
								InstanceInvokeExpr iie = (InstanceInvokeExpr) stmt.getInvokeExpr();
								if (source.hasPrefix(iie.getBase()))
									sourceIsCallerObj = true;
							}

							boolean staticField = false;
							if (fa.getLocal() == null)
								staticField = true;

							if (sourceInArgs != null || sourceIsCallerObj || staticField) {
								for (Task t : newTasks) {
									t.addInFact(fa);
								}

								if (sourceInArgs != null && source.same(sourceInArgs) || staticField) {
									outSet.add(fa);
								}
							} else
								outSet.add(fa);

							if (source.isZeroAbstraction())
								outSet.add(fa);

							if (DEBUG_IFDS_RESULTS)
								printDebugIFDSInfo("taskCOT", call, source, outSet);
							return outSet;
						}
					};
				} else {

					return new FlowFunction<FlowAbstraction>() {

						@Override
						public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {
							Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();

							if (!applySanitizer(call, source)) {
								outSet.add(source.deriveWithNewStmt(stmt, icfg.getMethodOf(stmt)));
								for (FlowAbstraction fa : taintApi(call, source, outSet)) {
									outSet.add(fa);
									outSet.addAll(taintAliases(fa));
								}
							}

							if (DEBUG_IFDS_RESULTS)
								printDebugIFDSInfo("cot (api)", call, source, outSet);
							return outSet;
						}
					};
				}
			}
		}

		return new FlowFunction<FlowAbstraction>() {

			@Override
			public Set<FlowAbstraction> computeTargets(FlowAbstraction source) {
				Set<FlowAbstraction> outSet = new HashSet<FlowAbstraction>();
				FlowAbstraction newAbs = source.deriveWithNewStmt(call, icfg.getMethodOf(call));

				// Args or caller
				Value sourceInArgs = null;
				for (Value arg : stmt.getInvokeExpr().getArgs()) {
					if (source.hasPrefix(arg)) {
						sourceInArgs = arg;
						break;
					}
				}
				boolean sourceIsCallerObj = false;
				if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
					InstanceInvokeExpr iie = (InstanceInvokeExpr) stmt.getInvokeExpr();
					if (source.hasPrefix(iie.getBase()))
						sourceIsCallerObj = true;
				}
				boolean staticField = false;
				if (source.getLocal() == null)
					staticField = true;

				// ID
				boolean overwritten = false;
				if (call instanceof AssignStmt) {
					AssignStmt assignStmt = (AssignStmt) call;
					final Local leftLocal = (Local) assignStmt.getLeftOp();
					FlowAbstraction fa = new FlowAbstraction(assignStmt, leftLocal, call, icfg.getMethodOf(call),
							FlowAbstraction.zeroAbstraction());
					if (newAbs.equals(fa))
						overwritten = true;
				}

				if (!overwritten && sourceInArgs == null && !sourceIsCallerObj && !staticField)
					outSet.add(newAbs);

				if (DEBUG_IFDS_RESULTS)
					printDebugIFDSInfo("cot ID", call, source, outSet);
				return outSet;
			}

		};
	}

	/***** Aliases *****/

	private Set<FlowAbstraction> taintAliases(FlowAbstraction fa) {

		// System.out.println(icfg.getMethodOf(fa.getUnit()).getActiveBody());

		Set<FlowAbstraction> ret = new HashSet<FlowAbstraction>();

		// Should not consider other cases...
		if (fa.getLocal() != null) {
			Set<Value> mayAliases = icfg.mayAlias(fa.getLocal(), fa.getUnit());
			mayAliases.remove(fa.getLocal());

			for (Value alias : mayAliases) {
				if (alias instanceof Local || alias instanceof ArrayRef || alias instanceof StaticFieldRef
						|| alias instanceof InstanceFieldRef) {
					FlowAbstraction faT = getTaint(fa.getLocal(), alias, fa, fa.getUnit());
					if (faT != null)
						ret.add(faT);
				}
			}

			if (DEBUG_ALIAS) {
				if (!ret.isEmpty()) {
					LOGGER.debug("At " + fa.getUnit());
					LOGGER.debug("\tAliases of " + fa.getLocal() + " are: " + mayAliases);
					LOGGER.debug("\tAlias tainting " + ret);
				}
			}
		}
		return ret;
	}

	/***** Taints *****/

	private FlowAbstraction getTaint(Value right, Value left, FlowAbstraction source, Unit src) {
		FlowAbstraction fa = null;

		if (right instanceof CastExpr)
			right = ((CastExpr) right).getOp();

		if (right instanceof Local && source.getLocal() == right) {
			fa = FlowAbstraction.v(source.getSource(), left, src, icfg.getMethodOf(src), source);
			fa = fa.append(source.getFields());
		} else if (right instanceof InstanceFieldRef) {
			InstanceFieldRef ifr = (InstanceFieldRef) right;
			if (source.hasPrefix(ifr)) {
				fa = FlowAbstraction.v(source.getSource(), left, src, icfg.getMethodOf(src), source);
				fa = fa.append(source.getPostfix(ifr));
			}
		} else if (right instanceof StaticFieldRef) {
			StaticFieldRef sfr = (StaticFieldRef) right;
			if (source.hasPrefix(sfr)) {
				fa = FlowAbstraction.v(source.getSource(), left, src, icfg.getMethodOf(src), source);
				fa = fa.append(source.getPostfix(sfr));
			}
		} else if (right instanceof ArrayRef) {
			ArrayRef ar = (ArrayRef) right;
			if (ar.getBase() == source.getLocal())
				fa = FlowAbstraction.v(source.getSource(), left, src, icfg.getMethodOf(src), source);
		}
		return fa;
	}

	private Set<FlowAbstraction> getTaints(Value right, Value left, FlowAbstraction source, Unit src) {
		Set<FlowAbstraction> ret = new HashSet<FlowAbstraction>();
		FlowAbstraction fa = getTaint(right, left, source, src);
		if (fa != null)
			ret.add(fa);

		// f0 = o.x {o} -> taint f0 and o.x if o is API object
		if (right instanceof InstanceFieldRef && source.getLocal() != null && source.getFields().length == 0) {
			String type = ((InstanceFieldRef) right).getBase().getType().toString();
			// if o is an API object (ex: Point)
			if (!inProject(type)) {
				fa = FlowAbstraction.v(source.getSource(), right, src, icfg.getMethodOf(src), source);
				if (fa.hasPrefix(source.getLocal())) {
					ret.add(fa);
					fa = FlowAbstraction.v(source.getSource(), left, src, icfg.getMethodOf(src), source);
					ret.add(fa);
				}
			}

		}
		return ret;
	}

	private void removeUnusedTaints(Unit src, Set<FlowAbstraction> outSet) {
		// Set<FlowAbstraction> toRemove = new HashSet<FlowAbstraction>();
		// for (FlowAbstraction fa : outSet) {
		// if (killUnusedTaint(icfg, fa))
		// toRemove.add(fa);
		// }
		// outSet.removeAll(toRemove);
	}

	// private boolean killUnusedTaint(JitIcfg icfg, FlowAbstraction fa) {
	// if (fa.getLocal() != null && fa.getLocal().toString().startsWith("$")) {
	// Unit u = icfg.lastOccurenceOf(fa.getLocal(), fa.getUnit());
	// boolean ret = false;
	// if (u != null)
	// ret = u.equals(fa.getUnit());
	// if (DEBUG_ALIAS) {
	// if (ret)
	// LOGGER.debug("Killing taint " + fa + " at " + fa.getUnit());
	// }
	// return ret;
	// }
	// return false;
	// }

	/***** API calls *****/

	// Sanitizer for user study. Kill the parameter and its aliases
	private boolean applySanitizer(Unit call, FlowAbstraction source) {

		// Call itself
		Stmt stmt = (Stmt) call;
		final String target = stmt.getInvokeExpr().getMethod().getSignature();
		final List<Value> args = stmt.getInvokeExpr().getArgs();

		if (!target.equals("<sanitizers.Sanitizer: void sanitize(java.lang.Object)>"))
			return false;

		if (killforSanit(call, args.get(0), source))
			return true;

		// Case of valueOf for primitive types
		// a = Integer.valueOf(b); and sanit(a) -> must also treat aliases of b
		List<Unit> predecessors = icfg.getPredsOf(call);
		for (Unit predecessor : predecessors) {
			if (predecessor instanceof DefinitionStmt) {
				DefinitionStmt def = (DefinitionStmt) predecessor;
				if (def.getLeftOp().equals(args.get(0)) && def.getRightOp() instanceof StaticInvokeExpr) {
					InvokeExpr expr = (StaticInvokeExpr) def.getRightOp();
					final SootMethod method = expr.getMethod();
					if (method.getName().equals("valueOf") && expr.getArgCount() == 1
							&& method.getDeclaringClass().getType().equals(method.getReturnType())
							&& isPrimitiveType(method.getReturnType())) {
						if (killforSanit(predecessor, expr.getArg(0), source))
							return true;
					}
				}
			}
		}
		return false;
	}

	private boolean isPrimitiveType(Type returnType) {
		return Config.primTypes.contains(returnType);
	}

	private boolean killforSanit(Unit call, Value value, FlowAbstraction source) {
		Set<Value> mayAliases = icfg.mayAlias(value, call);
		for (Value alias : mayAliases) {
			FlowAbstraction fa = FlowAbstraction.v(source.getSource(), alias, source.getUnit(), source.getMethod(),
					source.predecessor());
			if (source.equals(fa)) {
				if (DEBUG_SANITIZE)
					LOGGER.info("Sanitization killing: " + source);
				return true;
			}
		}
		return false;
	}

	private Set<FlowAbstraction> taintApi(Unit call, FlowAbstraction source, Set<FlowAbstraction> outSet) {
		Stmt stmt = (Stmt) call;
		final String target = stmt.getInvokeExpr().getMethod().getSignature();
		final List<Value> args = stmt.getInvokeExpr().getArgs();
		final Local baseLocal = stmt.getInvokeExpr() instanceof InstanceInvokeExpr
				? (Local) ((InstanceInvokeExpr) stmt.getInvokeExpr()).getBase() : null;
		Local receiver = null;
		if (stmt instanceof AssignStmt)
			receiver = (Local) ((AssignStmt) stmt).getLeftOp();

		Set<FlowAbstraction> ret = new HashSet<FlowAbstraction>();

		// Summaries for API calls
		if (target.contains("java.lang.String toString()")) {
			// if base is tainted, taint receiver
			if (baseLocal != null && source.getLocal() == baseLocal && receiver != null)
				ret.add(FlowAbstraction.v(source.getSource(), receiver, stmt, icfg.getMethodOf(stmt), source));
			return ret;
		}

		switch (target) {
		case "<java.lang.String: void getChars(int,int,char[],int)>":
			// if base is tainted, taint third parameter
			if (baseLocal != null && source.getLocal() == baseLocal)
				ret.add(FlowAbstraction.v(source.getSource(), args.get(2), stmt, icfg.getMethodOf(stmt), source));
			break;

		case "<java.lang.System: void arraycopy(java.lang.Object,int,java.lang.Object,int,int)>":
			// if first parameter is tainted, taint third parameter
			if (source.getLocal() == args.get(0))
				ret.add(FlowAbstraction.v(source.getSource(), args.get(2), stmt, icfg.getMethodOf(stmt), source));
			break;

		case "<android.content.ContextWrapper: android.content.Context getApplicationContext()>":
			// taint nothing
			break;

		case "<android.content.ContextWrapper: void sendBroadcast(android.content.Intent)>":
			// taint nothing
			break;

		case "<android.telephony.SmsManager: void sendTextMessage(java.lang.String,java.lang.String,java.lang.String,android.app.PendingIntent,android.app.PendingIntent)>":
			// taint nothing
			break;

		case "<java.lang.StringBuilder: java.lang.StringBuilder append(java.lang.String)>":
			// if first parameter or base is tainted, taint receiver
			if (receiver != null
					&& (source.getLocal() == args.get(0) || baseLocal != null && source.getLocal() == baseLocal)) {
				ret.add(FlowAbstraction.v(source.getSource(), receiver, stmt, icfg.getMethodOf(stmt), source));
			}
			break;

		case "<android.app.Activity: java.lang.Object getSystemService(java.lang.String)>":
			// taint nothing
			break;

		default:
			ret.addAll(taintApiDefault(call, source, stmt, outSet));
			break;
		}

		return ret;
	}

	private Set<FlowAbstraction> taintApiDefault(Unit call, FlowAbstraction source, Stmt stmt,
			Set<FlowAbstraction> outSet) {
		final List<Value> args = stmt.getInvokeExpr().getArgs();
		final Local baseLocal = stmt.getInvokeExpr() instanceof InstanceInvokeExpr
				? (Local) ((InstanceInvokeExpr) stmt.getInvokeExpr()).getBase() : null;
		Local receiver = null;
		if (stmt instanceof AssignStmt)
			receiver = (Local) ((AssignStmt) stmt).getLeftOp();

		Set<FlowAbstraction> ret = new HashSet<FlowAbstraction>();

		// If a parameter is tainted, we taint the base local and the receiver
		if (source.getLocal() != null && args.contains(source.getLocal())) {
			if (baseLocal != null && !baseLocal.toString().equals("this"))
				ret.add(FlowAbstraction.v(source.getSource(), baseLocal, call, icfg.getMethodOf(call), source));
			if (receiver != null)
				ret.add(FlowAbstraction.v(source.getSource(), receiver, call, icfg.getMethodOf(call), source));
		}

		// If the base local is tainted, we taint the receiver
		if (baseLocal != null && source.getLocal() == baseLocal && receiver != null)
			ret.add(FlowAbstraction.v(source.getSource(), receiver, call, icfg.getMethodOf(call), source));

		return ret;
	}

	/***** Print *****/

	private void printDebugIFDSInfo(String flowFctType, Unit src, FlowAbstraction source, Set<FlowAbstraction> outSet) {
		boolean zeroPropagation = (source != null && source.isZeroAbstraction() && outSet != null && outSet.size() == 1
				&& outSet.iterator().next().isZeroAbstraction());
		if (!zeroPropagation) {
			LOGGER.info(task.getLayer() + " " + flowFctType + " " + src + " --- " + source + " --- " + outSet + " --- "
					+ icfg.getMethodOf(src));
		}
	}

}
