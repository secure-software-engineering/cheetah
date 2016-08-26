package layeredtaintplugin.internal.layer;

import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.internal.AnalysisTask;
import layeredtaintplugin.internal.ProjectInformation;
import layeredtaintplugin.internal.Task;

public enum Layer {

	INTRA, CLASS, CLASS_CALLBACKS, FILE, PACKAGE, PROJECT_MONOMORPHIC, PROJECT_POLYMORPHIC, ANDROID_LIFECYCLE;

	public static AnalysisTask createAnalysisTask(Task task, SetupApplicationJIT app,
			ProjectInformation projectInformation) {
		return getAnalysisLayer(task.getLayer(), task, app, projectInformation);
	}

	public static AnalysisTask getAnalysisLayer(Layer layer, Task task, SetupApplicationJIT app,
			ProjectInformation projectInformation) {
		switch (layer) {
		case INTRA:
			return new L1_Intra(task, app, projectInformation);
		case CLASS:
			return new L2_Class(task, app, projectInformation);
		case CLASS_CALLBACKS:
			return new L3_Class_Callbacks(task, app, projectInformation);
		case FILE:
			return new L4_File(task, app, projectInformation);
		case PACKAGE:
			return new L5_Package(task, app, projectInformation);
		case PROJECT_MONOMORPHIC:
			return new L6_ProjectMonomorphic(task, app, projectInformation);
		case PROJECT_POLYMORPHIC:
			return new L7_ProjectPolymorphic(task, app, projectInformation);
		case ANDROID_LIFECYCLE:
			return new L8_AndroidLifecycle(task, app, projectInformation);
		default:
			throw new RuntimeException("Layer not registered " + layer);
		}
	}
}
