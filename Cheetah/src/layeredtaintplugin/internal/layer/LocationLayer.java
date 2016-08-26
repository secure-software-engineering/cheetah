package layeredtaintplugin.internal.layer;

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.internal.AnalysisTask;
import layeredtaintplugin.internal.ProjectInformation;
import layeredtaintplugin.internal.Task;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;

public abstract class LocationLayer extends AnalysisTask {

	private final static Logger LOGGER = LoggerFactory.getLogger(LocationLayer.class);

	public LocationLayer(Task task, SetupApplicationJIT app, ProjectInformation projectInformation) {
		super(task, app, projectInformation);
	}

	@Override
	public Set<Task> requiredTasks() {

		Set<Task> requiredTasks = new HashSet<Task>();

		// if (task.getStartUnit() == null) {
		// full file, do all classes in file
		SootClass bsc = task.getStartMethod().getDeclaringClass();
		Set<SootClass> classes = new HashSet<SootClass>();
		if (task.getLayer() == Layer.FILE)
			classes = classesInSameFile(bsc);
		else if (task.getLayer() == Layer.PACKAGE)
			classes = classesInSamePackage(bsc);
		else if (task.getLayer() == Layer.PROJECT_MONOMORPHIC || task.getLayer() == Layer.PROJECT_POLYMORPHIC)
			classes = getProjectClasses();

		for (SootClass sc : classes) {
			if (sc == bsc) {
				// Task initialised with the current method
				if (!task.getStartMethod().isAbstract() && !task.getStartMethod().isNative()) {
					Task newTask = new Task(previousLayer(), task.getStartMethod(), null);
					requiredTasks.add(newTask);
				}
			} else {
				// Task initialised with the first method of the class
				for (SootMethod sm : sc.getMethods()) {
					if (!sm.isAbstract() && !sm.isNative()) {
						Task newTask = new Task(previousLayer(), sm, null);
						requiredTasks.add(newTask);
						break;
					}
				}
			}
		}
		if (DEBUG_TASKS) {
			for (Task t : requiredTasks)
				LOGGER.debug(task.getLayer() + " create task " + t.toLongString());
		}

		return requiredTasks;
	}

	private Layer previousLayer() {
		switch (task.getLayer()) {
		case CLASS:
			return Layer.INTRA;
		case CLASS_CALLBACKS:
			return Layer.CLASS;
		case FILE:
			return Layer.CLASS_CALLBACKS;
		case PACKAGE:
			return Layer.FILE;
		case PROJECT_MONOMORPHIC:
			return Layer.PACKAGE;
		case PROJECT_POLYMORPHIC:
			return Layer.PROJECT_MONOMORPHIC;
		case ANDROID_LIFECYCLE:
			return Layer.PROJECT_POLYMORPHIC;
		default:
			throw new RuntimeException("Layer not registered " + task.getLayer());
		}
	}

	@Override
	public abstract Set<Task> nextTasks();

	@Override
	protected abstract Set<Task> createTasksForCall(Unit call, Set<SootMethod> chaTargets);
}
