package layeredtaintplugin.internal.layer;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.internal.ProjectInformation;
import layeredtaintplugin.internal.Task;
import soot.SootMethod;
import soot.Unit;

public class L1_Intra extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L1_Intra.class);

	public L1_Intra(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
		SootMethod sm = task.getStartMethod();
		loadActiveBody(sm);
	}

	@Override
	public Set<Task> requiredTasks() {
		return new HashSet<Task>();
	}

	@Override
	public Set<Task> nextTasks() {
		// go to next layer if no further tasks have been created
		Task newTask = new Task(Layer.CLASS, task.getStartMethod(), null);
		nextTasks.add(newTask);
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	// From any layer, at a call, when should tasks for L1 be created?
	// Never
	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {
		// No tasks created for a call
		return new HashSet<Task>();
	}
}
