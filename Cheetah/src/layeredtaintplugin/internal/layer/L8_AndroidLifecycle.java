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

public class L8_AndroidLifecycle extends LocationLayer {

	private final static Logger LOGGER = LoggerFactory.getLogger(L8_AndroidLifecycle.class);

	public L8_AndroidLifecycle(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> requiredTasks() {
		Set<Task> requiredTasks = new HashSet<Task>();

		// If we didn't load important classes yet, do it now
		for (String className : app.getEntryPoints()) {

			if (!inProject(className))
				continue;

			SootClass sc = Scene.v().getSootClass(className);
			if (!sc.declaresMethodByName(Config.dummyMainMethodName)) {

				// initialize with the first method
				for (SootMethod sm : sc.getMethods()) {
					if (!sm.isAbstract() && !sm.isNative()) {
						Task newTask = new Task(Layer.FILE, sc.getMethods().get(0), null);
						requiredTasks.add(newTask);
						break;
					}
				}
			}
		}

		// If everything has been loaded, create full dummy main
		if (requiredTasks.isEmpty()) {
			SootMethod dm = getDummyMain();
			task.setStartMethod(dm);
		}

		if (DEBUG_TASKS) {
			for (Task t : requiredTasks)
				LOGGER.debug(task.getLayer() + " create task " + t.toLongString());
		}
		return requiredTasks;
	}

	private SootMethod getDummyMain() {
		if (Scene.v().containsClass(Config.dummyMainClassName)) {
			SootClass sc = Scene.v().getSootClass(Config.dummyMainClassName);
			return sc.getMethodByName(Config.dummyMainMethodName);
		} else {
			if (DEBUG_DUMMY_MAIN)
				LOGGER.info("Creating full dummy main");
			SootMethod dm = app.createFullDummyMain();
			Scene.v().getOrMakeFastHierarchy();
			return dm;
		}
	}

	@Override
	public Set<Task> nextTasks() {
		if (DEBUG_TASKS2) {
			for (Task t : nextTasks)
				LOGGER.debug(task.getLayer() + " next task " + t.toLongString());
		}
		return nextTasks;
	}

	@Override
	protected Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets) {
		// No tasks created for L7 because of a call
		return new HashSet<Task>();
	}

}
