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

public class L7_ProjectPolymorphic extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L7_ProjectPolymorphic.class);

	public L7_ProjectPolymorphic(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> nextTasks() {
		Task newTask = new Task(Layer.ANDROID_LIFECYCLE, null, null);
		nextTasks.add(newTask);
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	// From any layer, at a call, when should tasks for L5 be created?
	// Virtual calls from different files
	// Polymorphic: more than one suitable chaTarget
	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {

		Set<Task> tasksForCall = new HashSet<Task>();
		Set<SootMethod> targets = new HashSet<SootMethod>();
		// SootMethod caller = icfg.getMethodOf(call);
		SootMethod caller = projectInformation.startPoint();

		InvokeExpr callExpr = ((Stmt) call).getInvokeExpr();
		if (callExpr.getMethod().isNative())
			return tasksForCall;

		for (SootMethod potentialTarget : chaTargets) {
			// If not in same package
			if (!inSamePackage(potentialTarget.getDeclaringClass(), caller.getDeclaringClass())
					&& inProject(potentialTarget.getDeclaringClass().getName()))
				targets.add(potentialTarget);
		}

		// check for polymorphism
		if (!targets.isEmpty() && targets.size() > 1) {
			Task newTask = new Task(Layer.PROJECT_POLYMORPHIC, caller, call, new HashSet<FlowAbstraction>(), targets);
			tasksForCall.add(newTask);
		}
		return tasksForCall;
	}
}
