package layeredtaintplugin.reporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import heros.solver.IFDSSolver;
import heros.solver.IFDSSolver.BinaryDomain;
import heros.solver.JumpFunctions;
import layeredtaintplugin.icfg.JitIcfg;
import layeredtaintplugin.internal.FlowAbstraction;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;

public class PathFinder {

	private JitIcfg icfg;
	private Map<Unit, List<FlowAbstraction>> paths;
	private JumpFunctions<Unit, FlowAbstraction, IFDSSolver.BinaryDomain> jumpFunctions;

	public PathFinder(JitIcfg icfg, JumpFunctions<Unit, FlowAbstraction, BinaryDomain> jumpFunctions) {
		this.icfg = icfg;
		this.jumpFunctions = jumpFunctions;
		this.paths = new HashMap<Unit, List<FlowAbstraction>>();
	}

	public List<FlowAbstraction> findPath(FlowAbstraction fa,
			Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks) {
		if (summaryLeaks.isEmpty()) { // fa is the sink
			return findPath(fa, new ArrayList<FlowAbstraction>(), fa);
		} else { // sinks are in summaryLeaks
			for (FlowAbstraction sink : summaryLeaks.keySet()) {
				for (List<FlowAbstraction> path : summaryLeaks.get(sink)) {
					ArrayList<FlowAbstraction> pPrime = (ArrayList<FlowAbstraction>) ((ArrayList<FlowAbstraction>) path)
							.clone();
					return findPath(fa, pPrime, sink);
				}
			}
		}
		return new ArrayList<FlowAbstraction>();
	}

	private List<FlowAbstraction> findPath(FlowAbstraction currentAbs, ArrayList<FlowAbstraction> pathStub,
			FlowAbstraction sinkAbs) {
		if (this.paths.keySet().contains(sinkAbs.getUnit()))
			return this.paths.get(sinkAbs.getUnit());

		List<FlowAbstraction> path = extractPath(currentAbs, pathStub, sinkAbs);
		Collections.reverse(path);
		this.paths.put(sinkAbs.getUnit(), path);
		return path;
	}

	private List<FlowAbstraction> extractPath(FlowAbstraction currentAbs, ArrayList<FlowAbstraction> pathStub,
			FlowAbstraction sinkAbs) {
		List<List<FlowAbstraction>> extractedPaths = extractPaths(currentAbs, pathStub, sinkAbs);
		for (List<FlowAbstraction> path : extractedPaths)
			if (!path.isEmpty() && path.get(path.size() - 1).getUnit().equals(sinkAbs.getSource())) {
				return path;
			}
		return new ArrayList<FlowAbstraction>();
	}

	/***** Path reconstruction *****/

	public List<List<FlowAbstraction>> extractPaths(FlowAbstraction fa, ArrayList<FlowAbstraction> path,
			FlowAbstraction faOrig) {

		List<List<FlowAbstraction>> paths = new ArrayList<List<FlowAbstraction>>();

		while (fa.getUnit() != null) {

			if (Thread.currentThread().isInterrupted())
				return paths;

			path.add(fa);

			if (hasLoops(path, fa))
				return paths;

			// Create paths for neighbours
			for (FlowAbstraction neighbour : fa.neighbours()) {

				if (consistantCallStack(neighbour, path)) {
					ArrayList<FlowAbstraction> path2 = (ArrayList<FlowAbstraction>) path.clone();
					List<List<FlowAbstraction>> childPaths = extractPaths(neighbour, path2, faOrig);
					if (childPaths != null && !childPaths.isEmpty()) {
						return childPaths;
					} else {
						for (List<FlowAbstraction> childPath : childPaths)
							if (childPath.get(childPath.size() - 1).getUnit().equals(faOrig.getSource()))
								paths.add(childPath);
					}
				}
			}

			// See if can continue this path
			if (!fa.neighbours().isEmpty() && !consistantCallStack(fa, path)) {
				if (fa.predecessor().getUnit() == null && fa.getUnit().equals(faOrig.getSource())) {
					// if source, return path
					path.add(fa);
					paths.add(path);
					return paths;
				}
				// if not source, abort
				return paths;
			}

			// Update jump functions
			Unit firstUnitInTargetMethod = icfg.getMethodOf(fa.getUnit()).getActiveBody().getUnits().getFirst();
			assert firstUnitInTargetMethod != null;
			List<Unit> endPoints = getBeforeEndPoints(
					(List<Unit>) icfg.getEndPointsOf(icfg.getMethodOf(firstUnitInTargetMethod)));

			if (fa.getUnit() == firstUnitInTargetMethod) {
				Set<List<FlowAbstraction>> jmpPaths = new HashSet<List<FlowAbstraction>>();
				for (List<FlowAbstraction> p : paths) {
					ArrayList<FlowAbstraction> subP = new ArrayList<FlowAbstraction>(p.subList(0, p.indexOf(fa)));
					if (!subP.isEmpty() && !tailInPath(endPoints, subP)) {
						ArrayList<FlowAbstraction> pPrime = (ArrayList<FlowAbstraction>) subP.clone();
						jmpPaths.add(pPrime);
					}
				}
				if (!path.isEmpty() && !tailInPath(endPoints, path))
					jmpPaths.add((List<FlowAbstraction>) path.clone());

				if (!jmpPaths.isEmpty())
					this.jumpFunctions.addPaths(fa, fa.getUnit(), faOrig, jmpPaths);
			}

			fa = fa.predecessor();
		}
		if (path.get(path.size() - 1).getUnit().equals(faOrig.getSource()))
			paths.add(path);
		return paths;
	}

	/***** Utils *****/

	private boolean hasLoops(ArrayList<FlowAbstraction> path, FlowAbstraction fa) {
		List<List<FlowAbstraction>> inBetween = new ArrayList<List<FlowAbstraction>>();
		List<FlowAbstraction> bufList = new ArrayList<FlowAbstraction>();
		boolean write = false;

		for (FlowAbstraction flowAbs : path) {
			if (write)
				bufList.add(flowAbs);
			if (fa.exactEquals(flowAbs)) {
				write = true;
				if (!bufList.isEmpty()) {
					if (inBetween.contains(bufList))
						return true;
					inBetween.add(new ArrayList<FlowAbstraction>(bufList));
					bufList = new ArrayList<FlowAbstraction>();
				}
			}
		}
		return false;
	}

	private boolean consistantCallStack(FlowAbstraction flowAbs, ArrayList<FlowAbstraction> path) {
		Stmt stmt = (Stmt) flowAbs.getUnit();

		// Only care about InvokeExpr
		if (!stmt.containsInvokeExpr())
			return true;

		// If callsite and returnsite are the same, we can continue
		SootMethod method = icfg.getMethodOf(stmt);
		assert method != null;
		Unit lastUnitInPathForMethod = getLastUnitInPathForMethod(path, method);
		if (lastUnitInPathForMethod != null) {
			return (lastUnitInPathForMethod.equals(stmt)
					|| lastUnitInPathForMethod.equals(path.get(path.size() - 1).getUnit()));
		}

		// If there is no corresponding returnsite, we are out of the callstack
		return true;
	}

	private Unit getLastUnitInPathForMethod(ArrayList<FlowAbstraction> path, SootMethod method) {
		for (int i = path.size() - 1; i >= 0; i--) {
			Unit u = path.get(i).getUnit();
			SootMethod sootMethod = icfg.getMethodOf(u);
			if (sootMethod != null && method.equals(method))
				return u;
		}
		return null;
	}

	private boolean tailInPath(List<Unit> endPoints, ArrayList<FlowAbstraction> pPrime) {
		boolean tailInPath = false;
		for (Unit tail : endPoints) {
			for (int i = 1; i < pPrime.size(); i++) {
				FlowAbstraction faPrime = pPrime.get(i);
				if (tail.equals(faPrime.getUnit())) {
					tailInPath = true;
					break;
				}
			}
		}
		return tailInPath;
	}

	private List<Unit> getBeforeEndPoints(List<Unit> endPoints) {
		List<Unit> ret = new ArrayList<Unit>();
		for (Unit endPoint : endPoints) {
			ret.addAll(icfg.getPredsOf(endPoint));
		}
		return ret;
	}

}
