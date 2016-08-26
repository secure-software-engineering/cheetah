package layeredtaintplugin.internal.layer;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.internal.FlowAbstraction;
import layeredtaintplugin.internal.ProjectInformation;
import layeredtaintplugin.internal.Task;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class L2_Class extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L2_Class.class);

	public L2_Class(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> requiredTasks() {
		Set<Task> requiredTasks = new HashSet<Task>();
		// all methods from the class
		for (SootMethod sm : task.getStartMethod().getDeclaringClass().getMethods()) {
			if (!sm.isAbstract() && !sm.isNative()) {
				Task newTask = new Task(Layer.INTRA, sm, null);
				requiredTasks.add(newTask);
			}
		}

		if (DEBUG_TASKS) {
			for (Task t : requiredTasks)
				LOGGER.debug(task.getLayer() + " create task " + t.toLongString());
		}
		return requiredTasks;
	}

	@Override
	public Set<Task> nextTasks() {
		// go to next layer if no further tasks have been created
		Task newTask = new Task(Layer.CLASS_CALLBACKS, task.getStartMethod(), null);
		nextTasks.add(newTask);
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	// From any layer, at a call, when should tasks for L2 be created?
	// If targets are static or special calls from the same class
	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {

		Set<Task> tasksForCall = new HashSet<Task>();

		InvokeExpr callExpr = ((Stmt) call).getInvokeExpr();
		if (callExpr.getMethod().isNative())
			return tasksForCall;

		// SootMethod caller = icfg.getMethodOf(call);
		SootMethod caller = projectInformation.startPoint();

		for (SootMethod potentialTarget : chaTargets) {
			// if same class
			if (caller.getDeclaringClass() == potentialTarget.getDeclaringClass()) {
				Set<SootMethod> targets = new HashSet<SootMethod>();
				targets.add(potentialTarget);
				Task newTask = new Task(Layer.CLASS, caller, call, new HashSet<FlowAbstraction>(), targets);
				tasksForCall.add(newTask);
			}
		}
		return tasksForCall;
	}
}
