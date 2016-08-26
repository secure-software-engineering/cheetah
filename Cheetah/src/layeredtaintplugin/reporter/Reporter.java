package layeredtaintplugin.reporter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import heros.solver.IFDSSolver;
import heros.solver.JumpFunctions;
import layeredtaintplugin.Config;
import layeredtaintplugin.icfg.JitIcfg;
import layeredtaintplugin.internal.FlowAbstraction;
import layeredtaintplugin.ui.viewers.OverviewView;
import layeredtaintplugin.ui.viewers.Warning;
import soot.SootMethod;
import soot.Unit;

public class Reporter {

	private final static Logger LOGGER = LoggerFactory.getLogger(Reporter.class);

	private JitIcfg icfg;
	private PathFinder pathFinder;

	private final SootMethod startPoint;

	// Cache
	private Map<FlowAbstraction, Warning> reported;
	private final int runId;
	private IJavaProject project;

	public Reporter(int runId, SootMethod startPoint, IJavaProject project) {
		this.runId = runId;
		this.project = project;
		this.startPoint = startPoint;
		this.reported = new HashMap<FlowAbstraction, Warning>();
	}

	public void setIFDS(JitIcfg icfg, JumpFunctions<Unit, FlowAbstraction, IFDSSolver.BinaryDomain> jumpFunctions) {
		this.icfg = icfg;
		this.pathFinder = new PathFinder(icfg, jumpFunctions);
	}

	/***** Path finding *****/

	class PathFinderTask implements Callable<List<FlowAbstraction>> {

		private final Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks;
		private final FlowAbstraction fa;

		public PathFinderTask(FlowAbstraction fa, Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks) {
			this.summaryLeaks = summaryLeaks;
			this.fa = fa;
		}

		@Override
		public List<FlowAbstraction> call() throws Exception {
			try {
				return pathFinder.findPath(fa, summaryLeaks);
			} catch (Exception e) {
				LOGGER.error("Could not extract path.");
			}
			return null;
		}

	}

	/***** Reporting *****/

	class ReportingTask implements Runnable {

		private final FlowAbstraction fa;
		private final Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks;

		public ReportingTask(FlowAbstraction fa, Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks) {
			this.fa = fa;
			this.summaryLeaks = summaryLeaks;
		}

		@Override
		public void run() {
			Warning warning = new Warning(runId, fa, icfg, project);
			synchronized (reported) {
				if (contains(reported.keySet(), fa)) {
					return;
				}
				warning.additionalId(seen(warning));
				reported.put(fa, warning);
			}
			addWarningToView(warning);

			List<FlowAbstraction> path = new ArrayList<FlowAbstraction>();
			List<FlowAbstraction> prunnedPath = new ArrayList<FlowAbstraction>();

			ExecutorService executor = Executors.newSingleThreadExecutor();
			Future<List<FlowAbstraction>> future = executor.submit(new PathFinderTask(fa, summaryLeaks));
			try {
				path = future.get(10, TimeUnit.SECONDS);
			} catch (TimeoutException | InterruptedException | ExecutionException e) {
				future.cancel(true);
				LOGGER.error("Path lookup terminated");
			}
			executor.shutdownNow();

			if (path == null)
				path = new ArrayList<FlowAbstraction>();
			prunnedPath = prunePath(path);
			prunnedPath = trimPath(prunnedPath, fa);
			prunnedPath = removeWrongLines(prunnedPath);

			addPathToWarning(warning, prunnedPath);
		}

		private boolean contains(Set<FlowAbstraction> reported, FlowAbstraction fa) {
			for (FlowAbstraction flowAbs : reported) {
				if (equalUnit(flowAbs.getUnit(), fa.getUnit()) && equalUnit(flowAbs.getSource(), fa.getSource()))
					return true;
			}
			return false;
		}

		private boolean equalUnit(Unit unit1, Unit unit2) {
			if (!unit1.toString().equals(unit2.toString()))
				return false;
			if (unit1.getJavaSourceStartLineNumber() != unit2.getJavaSourceStartLineNumber())
				return false;
			if (!icfg.getMethodOf(unit1).getSignature().equals(icfg.getMethodOf(unit2).getSignature()))
				return false;
			return true;
		}
	}

	public void report(FlowAbstraction fa, Map<FlowAbstraction, Set<List<FlowAbstraction>>> summaryLeaks) {
		ReportingTask reportingTask = new ReportingTask(fa, summaryLeaks);
		Thread t = new Thread(reportingTask);
		t.start();
	}

	public int seen(Warning warning) {
		int i = 0;
		for (Warning w : reported.values()) {
			if (w.equals(warning) && (w.getRunId() == warning.getRunId()))
				i++;
		}
		return i;
	}

	/***** Viewers *****/

	private void addWarningToView(final Warning warning) {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				synchronized (this) {
					OverviewView view = (OverviewView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().findView(Config.OVERVIEW_ID);
					if (view != null)
						view.addWarning(warning);
				}
			}
		});
	}

	private void addPathToWarning(Warning warning, List<FlowAbstraction> prunnedPath) {
		warning.setPath(prunnedPath, icfg, project);
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				synchronized (this) {
					OverviewView view = (OverviewView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().findView(Config.OVERVIEW_ID);
					if (view != null)
						view.updatePath(warning);
				}
			}
		});
	}

	/***** Path manipulation *****/

	private List<FlowAbstraction> trimPath(List<FlowAbstraction> path, final FlowAbstraction sinkAbs) {
		List<FlowAbstraction> trimmedPath = new ArrayList<FlowAbstraction>();
		FlowAbstraction firstAbs = null;
		FlowAbstraction lastAbs = null;

		for (FlowAbstraction fa : path) {
			if (fa.getUnit() != null) {

				SootMethod method = icfg.getMethodOf(fa.getUnit());
				if (method != null) {

					if (lastAbs != null) {
						if (fa.getShortName().equals(lastAbs.getShortName()) && !fa.equals(sinkAbs))
							continue;
					}

					// Trim path
					trimmedPath.add(fa);
					lastAbs = fa;
					if (firstAbs == null)
						firstAbs = fa;
				}
			}
		}

		if (trimmedPath.size() > 1 && !trimmedPath.get(trimmedPath.size() - 1).equals(sinkAbs)) {
			trimmedPath.add(sinkAbs);
		}
		return trimmedPath;
	}

	private List<FlowAbstraction> removeWrongLines(List<FlowAbstraction> path) {
		List<FlowAbstraction> trimmedPath = new ArrayList<FlowAbstraction>();
		for (FlowAbstraction fa : path) {
			if (fa.getUnit().getJavaSourceStartLineNumber() > 2)
				trimmedPath.add(fa);
		}
		return trimmedPath;
	}

	private List<FlowAbstraction> prunePath(List<FlowAbstraction> path) {
		List<FlowAbstraction> pred = new ArrayList<FlowAbstraction>();
		List<FlowAbstraction> cur = path;
		while (!pred.equals(cur)) {
			pred = cur;
			cur = pruneIndirect(cur);
		}
		cur = pruneDirect(cur);
		return cur;
	}

	private List<FlowAbstraction> pruneDirect(List<FlowAbstraction> path) {
		List<FlowAbstraction> ret = new ArrayList<FlowAbstraction>();
		for (int i = 0; i < path.size(); i++) {
			FlowAbstraction fa = path.get(i);
			FlowAbstraction containedUnit = containsUnit(ret, fa);
			if (containedUnit != null)
				ret = ret.subList(0, ret.indexOf(containedUnit));
			ret.add(fa);
		}
		return ret;
	}

	private FlowAbstraction containsUnit(List<FlowAbstraction> list, FlowAbstraction fa) {
		for (FlowAbstraction f : list)
			if (f.getUnit().equals(fa.getUnit()))
				return f;
		return null;
	}

	private List<FlowAbstraction> pruneIndirect(List<FlowAbstraction> path) {
		int firstIndex = -1;
		int lastIndex = -1;

		outerloop: for (int i = path.size() - 1; i >= 0; i--) {
			FlowAbstraction backward = path.get(i);

			Set<FlowAbstraction> predecessors = new HashSet<FlowAbstraction>();
			if (backward.predecessor() == null)
				continue;
			predecessors.add(backward.predecessor());
			predecessors.addAll(backward.predecessor().neighbours());

			for (FlowAbstraction predecessor : predecessors) {
				for (int j = 0; j < i - 1; j++) {
					FlowAbstraction forward = path.get(j);
					if (forward.equals(predecessor)) {
						firstIndex = j;
						lastIndex = i;
						break outerloop;
					}
				}
			}
		}

		if (firstIndex > 0 && lastIndex > firstIndex) {
			List<FlowAbstraction> p = new ArrayList<FlowAbstraction>();
			p.addAll(path.subList(0, firstIndex));
			p.addAll(path.subList(lastIndex + 1, path.size()));
			return p;
		}
		return path;
	}

	/***** Info *****/

	public SootMethod getStartPoint() {
		return this.startPoint;
	}

}
