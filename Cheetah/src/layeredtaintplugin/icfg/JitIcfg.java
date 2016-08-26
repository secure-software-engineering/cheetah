package layeredtaintplugin.icfg;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import heros.SynchronizedBy;
import heros.solver.IDESolver;
import soot.ArrayType;
import soot.Body;
import soot.FastHierarchy;
import soot.Local;
import soot.NullType;
import soot.PackManager;
import soot.RefType;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.SourceLocator;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.toolkits.ide.icfg.AbstractJimpleBasedICFG;
import soot.jimple.toolkits.pointer.LocalMustNotAliasAnalysis;
import soot.options.Options;
import soot.toolkits.graph.UnitGraph;

public class JitIcfg extends AbstractJimpleBasedICFG {

	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<Body, LocalMustNotAliasAnalysis> bodyToLMNAA = IDESolver.DEFAULT_CACHE_BUILDER
			.build(new CacheLoader<Body, LocalMustNotAliasAnalysis>() {
				@Override
				public LocalMustNotAliasAnalysis load(Body body) throws Exception {
					return new LocalMustNotAliasAnalysis(getOrCreateUnitGraph(body), body);
				}
			});

	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<Body, LocalMayAliasAnalysisWithFields> bodyToLMAAWF = IDESolver.DEFAULT_CACHE_BUILDER
			.build(new CacheLoader<Body, LocalMayAliasAnalysisWithFields>() {
				@Override
				public LocalMayAliasAnalysisWithFields load(Body body) throws Exception {
					return new LocalMayAliasAnalysisWithFields((UnitGraph) getOrCreateUnitGraph(body));
				}
			});

	public Set<Value> mayAlias(Value v, Unit u) {
		return bodyToLMAAWF.getUnchecked(unitToOwner.get(u)).mayAliases(v, u);
	}

	public Unit lastOccurenceOf(Local l, Unit u) {
		return bodyToLMAAWF.getUnchecked(unitToOwner.get(u)).lastOccurenceOf(l);
	}

	@SynchronizedBy("by use of synchronized LoadingCache class")
	public final LoadingCache<Unit, Set<SootMethod>> unitToCallees = IDESolver.DEFAULT_CACHE_BUILDER
			.build(new CacheLoader<Unit, Set<SootMethod>>() {
				@Override
				public Set<SootMethod> load(Unit u) throws Exception {
					Stmt stmt = (Stmt) u;
					InvokeExpr ie = stmt.getInvokeExpr();
					FastHierarchy fastHierarchy = Scene.v().getFastHierarchy();
					// FIXME Handle Thread.start etc.
					if (ie instanceof InstanceInvokeExpr) {
						if (ie instanceof SpecialInvokeExpr) {
							// special
							return Collections.singleton(ie.getMethod());
						} else {
							// virtual and interface
							InstanceInvokeExpr iie = (InstanceInvokeExpr) ie;
							Local base = (Local) iie.getBase();
							RefType concreteType = bodyToLMNAA.getUnchecked(unitToOwner.get(u)).concreteType(base,
									stmt);
							if (concreteType != null) {
								// the base variable definitely points to a
								// single concrete type
								SootMethod singleTargetMethod = fastHierarchy
										.resolveConcreteDispatch(concreteType.getSootClass(), iie.getMethod());
								return Collections.singleton(singleTargetMethod);
							} else {
								SootClass baseTypeClass;
								if (base.getType() instanceof RefType) {
									RefType refType = (RefType) base.getType();
									baseTypeClass = refType.getSootClass();
								} else if (base.getType() instanceof ArrayType) {
									baseTypeClass = Scene.v().getSootClass("java.lang.Object");
								} else if (base.getType() instanceof NullType) {
									// if the base is definitely null then there
									// is no call target
									return Collections.emptySet();
								} else {
									throw new InternalError("Unexpected base type:" + base.getType());
								}
								return fastHierarchy.resolveAbstractDispatch(baseTypeClass, iie.getMethod());
							}
						}
					} else {
						// static
						return Collections.singleton(ie.getMethod());
					}
				}
			});

	@SynchronizedBy("explicit lock on data structure")
	protected Map<SootMethod, Set<Unit>> methodToCallers = new HashMap<SootMethod, Set<Unit>>();

	public JitIcfg(SootMethod... entryPoints) {
		this(Arrays.asList(entryPoints));
	}

	public JitIcfg(Collection<SootMethod> entryPoints) {
		for (SootMethod m : entryPoints) {
			initForMethod(m);
		}
	}

	public Body initForMethod(SootMethod m) {
		assert Scene.v().hasFastHierarchy();
		Body b = null;
		if (m.isConcrete()) {
			SootClass declaringClass = m.getDeclaringClass();
			ensureClassHasBodies(declaringClass);
			synchronized (Scene.v()) {
				b = m.retrieveActiveBody();
			}
			if (b != null) {
				for (Unit u : b.getUnits()) {
					if (unitToOwner.put(u, b) != null) {
						// if the unit was registered already then so were all
						// units;
						// simply skip the rest
						break;
					}
				}
			}
		}
		assert Scene.v().hasFastHierarchy();
		return b;
	}

	private synchronized void ensureClassHasBodies(SootClass cl) {
		assert Scene.v().hasFastHierarchy();
		if (cl.resolvingLevel() < SootClass.BODIES) {
			Scene.v().forceResolve(cl.getName(), SootClass.BODIES);
			Scene.v().getOrMakeFastHierarchy();
		}
		assert Scene.v().hasFastHierarchy();
	}

	@Override
	public Set<SootMethod> getCalleesOfCallAt(Unit u) {
		Set<SootMethod> targets = unitToCallees.getUnchecked(u);
		for (SootMethod m : targets) {
			addCallerForMethod(u, m);
			initForMethod(m);
		}
		return targets;
	}

	public void addCallerForMethod(Unit callSite, SootMethod target) {
		synchronized (methodToCallers) {
			Set<Unit> callers = methodToCallers.get(target);
			if (callers == null) {
				callers = new HashSet<Unit>();
				methodToCallers.put(target, callers);
			}
			callers.add(callSite);
		}
	}

	@Override
	public Set<Unit> getCallersOf(SootMethod m) {
		Set<Unit> callers = methodToCallers.get(m);
		return callers == null ? Collections.<Unit> emptySet() : callers;

		// throw new
		// UnsupportedOperationException("This class is not suited for
		// unbalanced problems");
	}

	public static void loadAllClassesOnClassPathToSignatures() {
		for (String path : SourceLocator.explodeClassPath(Scene.v().getSootClassPath())) {
			for (String cl : SourceLocator.v().getClassesUnder(path)) {
				Scene.v().forceResolve(cl, SootClass.SIGNATURES);
			}
		}
	}

	public static void main(String[] args) {
		PackManager.v().getPack("wjtp").add(new Transform("wjtp.onflyicfg", new SceneTransformer() {

			@Override
			protected void internalTransform(String phaseName, Map<String, String> options) {
				if (Scene.v().hasCallGraph())
					throw new RuntimeException("call graph present!");

				loadAllClassesOnClassPathToSignatures();

				SootMethod mainMethod = Scene.v().getMainMethod();
				JitIcfg icfg = new JitIcfg(mainMethod);
				Set<SootMethod> worklist = new LinkedHashSet<SootMethod>();
				Set<SootMethod> visited = new HashSet<SootMethod>();
				worklist.add(mainMethod);
				int monomorphic = 0, polymorphic = 0;
				while (!worklist.isEmpty()) {
					Iterator<SootMethod> iter = worklist.iterator();
					SootMethod currMethod = iter.next();
					iter.remove();
					visited.add(currMethod);
					System.err.println(currMethod);
					// MUST call this method to initialize ICFG for
					// every method
					Body body = currMethod.getActiveBody();
					if (body == null)
						continue;
					for (Unit u : body.getUnits()) {
						Stmt s = (Stmt) u;
						if (s.containsInvokeExpr()) {
							Set<SootMethod> calleesOfCallAt = icfg.getCalleesOfCallAt(s);
							if (s.getInvokeExpr() instanceof VirtualInvokeExpr
									|| s.getInvokeExpr() instanceof InterfaceInvokeExpr) {
								if (calleesOfCallAt.size() <= 1)
									monomorphic++;
								else
									polymorphic++;
								System.err.println("mono: " + monomorphic + "   poly: " + polymorphic);
							}
							for (SootMethod callee : calleesOfCallAt) {
								if (!visited.contains(callee)) {
									System.err.println(callee);
									// worklist.add(callee);
								}
							}
						}
					}
				}
			}

		}));
		Options.v().set_on_the_fly(true);
		soot.Main.main(args);
	}

}
