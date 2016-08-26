package layeredtaintplugin.internal;

import java.util.Set;

import soot.SootMethod;

public class ProjectInformation {

	private final Set<String> projectClasses;

	private final SootMethod startPoint;

	public ProjectInformation(Set<String> projectClasses, SootMethod startPoint) {
		this.projectClasses = projectClasses;
		this.startPoint = startPoint;
	}

	public SootMethod startPoint() {
		return this.startPoint;
	}

	public Set<String> projectClasses() {
		return this.projectClasses;
	}

}
