package layeredtaintplugin.internal;

import java.util.Set;

import soot.SootMethod;

public class ExperimentalConfiguration {

	private final String startPoint;
	private SootMethod sm;
	private final String projectId;
	private final String apkPath;
	private final String sootCP;
	private final Set<String> projectClasses;

	public ExperimentalConfiguration(String startPoint, String projectId, String apkPath, Set<String> projectClasses,
			String sootCP) {
		this.startPoint = startPoint;
		this.projectId = projectId;
		this.apkPath = apkPath;
		this.projectClasses = projectClasses;
		this.sootCP = sootCP;
	}

	@Override
	public String toString() {
		return this.projectId;
	}

	public String getApk() {
		return this.apkPath;
	}

	public String getStartPoint() {
		return this.startPoint;
	}

	public String getName() {
		return this.projectId;
	}

	public String getSootCP() {
		return this.sootCP;
	}

	public SootMethod getStartSootMethod() {
		return this.sm;
	}

	public void setStartSootMethod(SootMethod sm) {
		this.sm = sm;
	}

	public Set<String> getProjectClasses() {
		return projectClasses;
	}
}
