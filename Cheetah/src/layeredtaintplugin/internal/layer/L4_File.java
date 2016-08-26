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

public class L4_File extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L4_File.class);

	public L4_File(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> nextTasks() {
		Task newTask = new Task(Layer.PACKAGE, task.getStartMethod(), null);
		nextTasks.add(newTask);
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	// From any layer, at a call, when should tasks for L3 be created?
	// Calls from the same file (but not the same class)
	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {
		Set<Task> tasksForCall = new HashSet<Task>();

		InvokeExpr callExpr = ((Stmt) call).getInvokeExpr();
		if (callExpr.getMethod().isNative())
			return tasksForCall;

		// SootMethod caller = icfg.getMethodOf(call);
		Set<SootMethod> targets = new HashSet<SootMethod>();
		SootMethod caller = projectInformation.startPoint();

		// if in same file but not same class
		for (SootMethod potentialTarget : chaTargets) {
			if (caller.getDeclaringClass() != potentialTarget.getDeclaringClass()
					&& inSameFile(caller.getDeclaringClass(), potentialTarget.getDeclaringClass())) {
				targets.add(potentialTarget);
			}
		}

		if (!targets.isEmpty()) {
			Task newTask = new Task(Layer.FILE, caller, call, new HashSet<FlowAbstraction>(), targets);
			tasksForCall.add(newTask);
		}

		return tasksForCall;
	}
}
