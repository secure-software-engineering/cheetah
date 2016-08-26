package layeredtaintplugin.internal.layer;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Config;
import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.internal.ProjectInformation;
import layeredtaintplugin.internal.Task;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;

public class L3_Class_Callbacks extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L3_Class_Callbacks.class);

	public L3_Class_Callbacks(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> requiredTasks() {
		Set<Task> requiredTasks = new HashSet<Task>();

		Task newTask = new Task(Layer.CLASS, task.getStartMethod(), task.getStartUnit());
		requiredTasks.add(newTask);

		if (app.getEntryPoints().contains(task.getStartMethod().getDeclaringClass().getName())) {
			SootMethod sm = createDummyMain(task.getStartMethod().getDeclaringClass().getName());
			newTask = new Task(Layer.INTRA, sm, null);
			requiredTasks.add(newTask);
		}

		if (DEBUG_TASKS) {
			for (Task t : requiredTasks)
				LOGGER.debug(task.getLayer() + " create task " + t.toLongString());
		}
		return requiredTasks;
	}

	private SootMethod createDummyMain(String className) {
		if (Scene.v().containsClass(className)) {
			SootClass sc = Scene.v().getSootClass(className);
			if (!sc.declaresMethodByName(Config.dummyMainMethodName)) {
				SootMethod dm = app.createDummyMainForClass(className);
				if (DEBUG_DUMMY_MAIN) {
					LOGGER.info("Creating dummyMain for " + className);
					LOGGER.info(dm.getActiveBody() + "");
				}
				return dm;
			}
			return sc.getMethodByName(Config.dummyMainMethodName);
		} else
			throw new RuntimeException("SootClass does not exist " + className);
	}

	@Override
	public Set<Task> nextTasks() {
		// go to next layer if no further tasks have been created
		Task newTask = new Task(Layer.FILE, task.getStartMethod(), null);
		nextTasks.add(newTask);
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	// Never create. Will never explicitely be called
	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {
		// No tasks created for a call
		return new HashSet<Task>();
	}
}
