package layeredtaintplugin.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import heros.EdgeFunction;
import heros.solver.IFDSSolver;
import heros.solver.JumpFunctions;
import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.icfg.JitIcfg;
import layeredtaintplugin.internal.layer.Layer;
import layeredtaintplugin.reporter.Reporter;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.toolkits.callgraph.CallGraph;

public class LayeredAnalysis {

	private final static Logger LOGGER = LoggerFactory.getLogger(AnalysisTask.class);

	// private final static Logger LOGGER =
	// LoggerFactory.getLogger(LayeredAnalysis.class);

	// Reporting
	private final Reporter reporter;

	// Waiting list
	private PriorityQueue<Task> taskQueue; // LinkedList
	private List<Task> computedTasks;
	private AnalysisTask currentTask = null;

	// IFDS data carried over from one task to the next
	private JumpFunctions<Unit, FlowAbstraction, IFDSSolver.BinaryDomain> jumpFunctions = null;
	private Table<Unit, FlowAbstraction, Table<Unit, FlowAbstraction, EdgeFunction<IFDSSolver.BinaryDomain>>> endSum = null;
	private Table<Unit, FlowAbstraction, Map<Unit, Set<FlowAbstraction>>> inc = null;
	private JitIcfg icfg = null;

	private SetupApplicationJIT app;
	private ProjectInformation projectInformation;

	public LayeredAnalysis(Reporter reporter, SetupApplicationJIT app, Set<String> projectClasses) {
		this.reporter = reporter;
		this.app = app;
		taskQueue = new PriorityQueue<Task>();
		computedTasks = new ArrayList<Task>();
		this.projectInformation = new ProjectInformation(projectClasses, reporter.getStartPoint());
		initIFDS();
	}

	private void initIFDS() {
		Scene.v().setCallGraph(new CallGraph());
		this.jumpFunctions = new JumpFunctions<Unit, FlowAbstraction, IFDSSolver.BinaryDomain>(IFDSSolver.getAllTop());
		this.endSum = HashBasedTable.create();
		this.inc = HashBasedTable.create();
		this.icfg = new JitIcfg(new ArrayList<SootMethod>()) {
			@Override
			public Set<SootMethod> getCalleesOfCallAt(Unit u) {
				if (currentTask != null)
					return currentTask.calleesOfCallAt(u);
				else // Empty by default (same behaviour as L1)
					return new HashSet<SootMethod>();
			}
		};
		this.reporter.setIFDS(icfg, jumpFunctions);
	}

	public void startAnalysis() {
		Task task = new Task(Layer.INTRA, this.reporter.getStartPoint(), null);
		taskQueue.add(task);
		analyze();
	}

	private void analyze() {
		while (!taskQueue.isEmpty()) {

			Task task = taskQueue.poll(); // pollFirst
			if (computedTasks.contains(task)) {
				// Cancel task
				continue;
			}

			AnalysisTask analysisTask = Layer.createAnalysisTask(task, app, projectInformation);
			analysisTask.setReporter(reporter);
			analysisTask.setAnalysisInfo(jumpFunctions, endSum, inc, icfg);
			Set<Task> requiredTasks = analysisTask.requiredTasks();

			Set<Task> remainingRequiredTasks = notYetExecuted(requiredTasks);

			if (remainingRequiredTasks.isEmpty()) {
				currentTask = analysisTask;
				analysisTask.analyze();
				computedTasks.add(task);
				taskQueue.addAll(analysisTask.nextTasks());

			} else {
				taskQueue.add(task); // push
				for (Task remainingTask : remainingRequiredTasks) {
					taskQueue.remove(remainingTask);
					taskQueue.add(remainingTask); // push
				}
			}
		}
	}

	private Set<Task> notYetExecuted(Set<Task> requiredTasks) {
		Set<Task> remainingTasks = new HashSet<Task>();
		for (Task task : requiredTasks)
			if (!computedTasks.contains(task))
				remainingTasks.add(task);
		return remainingTasks;
	}

}
