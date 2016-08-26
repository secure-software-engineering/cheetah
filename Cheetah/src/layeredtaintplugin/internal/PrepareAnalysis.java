package layeredtaintplugin.internal;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import heros.solver.Pair;
import layeredtaintplugin.Activator;
import layeredtaintplugin.Config;
import layeredtaintplugin.android.SetupApplicationJIT;
import layeredtaintplugin.reporter.Reporter;
import layeredtaintplugin.ui.viewers.OverviewView;
import soot.G;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;

public class PrepareAnalysis {

	private final static Logger LOGGER = LoggerFactory.getLogger(PrepareAnalysis.class);

	private LinkedList<Pair<IMethod, IJavaProject>> startPoints = new LinkedList<Pair<IMethod, IJavaProject>>();
	private boolean currentlyComputing = false;

	public void prepareAnalysis(final IMethod method, final IJavaProject project) {
		synchronized (this) {
			startPoints.clear();
			startPoints.add(new Pair<IMethod, IJavaProject>(method, project));
			if (!currentlyComputing)
				analyse();
			else
				G.reset();
		}
	}

	private void analyse() {
		currentlyComputing = true;

		Job job = new Job("Run analysis") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {

				while (!startPoints.isEmpty()) {
					Pair<IMethod, IJavaProject> startPoint = startPoints.poll();
					if (startPoint != null) {
						ExecutorService executor = Executors.newSingleThreadExecutor();
						Future<?> future = executor
								.submit(new FullAnalysisTask(startPoint.getO1(), startPoint.getO2()));
						try {
							future.get();
						} catch (Exception e) {
							future.cancel(true);
							LOGGER.error("Aborted future : " + e.getMessage());
							e.printStackTrace();
						}
						executor.shutdownNow();
					}
				}
				currentlyComputing = false;
				return Status.OK_STATUS;
			}
		};
		job.schedule();
	}

	class FullAnalysisTask implements Runnable {

		private final IMethod method;
		private final IJavaProject project;

		public FullAnalysisTask(IMethod method, IJavaProject project) {
			this.method = method;
			this.project = project;
		}

		@Override
		public void run() {
			final int runId = Activator.getDefault().getNewId();
			invalidateWarnings();

			LOGGER.info("Layered analysis triggered for run " + runId);

			try {
				String sootMethodSignature = getSootMethodSignature(method);
				String sootCP = getSootCP(project);
				String apkPath = getApkFile(project);
				Set<String> projectClasses = getProjectClasses(project);

				ExperimentalConfiguration config = new ExperimentalConfiguration(sootMethodSignature,
						project.getElementName(), apkPath, projectClasses, sootCP);

				SetupApplicationJIT app = new SetupApplicationJIT(config.getApk(), config.getSootCP(),
						Activator.getDefault().getSusiParser());
				app.initializeSoot();
				LOGGER.info("Retrieveing starting point " + config.getStartPoint());

				String startClass = config.getStartPoint().substring(1, config.getStartPoint().indexOf(":"));
				SootClass sc = Scene.v().loadClassAndSupport(startClass);
				if (!sc.isPhantom()) {
					SootMethod sm = Scene.v().getMethod(config.getStartPoint());
					if (!sm.isAbstract() && !sm.isNative()) {
						LOGGER.info("Starting point found: " + sm);
						config.setStartSootMethod(sm);
						Reporter reporter = new Reporter(runId, config.getStartSootMethod(), project);
						LayeredAnalysis la = new LayeredAnalysis(reporter, app, config.getProjectClasses());
						la.startAnalysis();
					}
				}
			} catch (IOException e) {
				LOGGER.error("Error in caluclating entry points: " + e.getMessage());
			} catch (RuntimeException | JavaModelException e) {
				LOGGER.error("Aborted run " + runId + " : " + e.getMessage());
			}
			LOGGER.info("Run " + runId + " finished");
			removeWarnings(runId);
		}
	}

	/***** View *****/

	public void removeWarnings(int runId) {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				synchronized (this) {
					OverviewView view = (OverviewView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().findView(Config.OVERVIEW_ID);
					view.removeWarnings(runId);
				}
			}
		});
	}

	private void invalidateWarnings() {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				synchronized (this) {
					OverviewView view = (OverviewView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
							.getActivePage().findView(Config.OVERVIEW_ID);
					view.invalidateWarnings();
				}
			}
		});
	}

	/***** Soot CP *****/

	private String getSootCP(IJavaProject javaProject) {
		String sootCP = "";
		try {
			sootCP = getClassFilesLocation(javaProject);
			for (String resource : getJarFilesLocation(javaProject))
				sootCP = sootCP + File.pathSeparator + resource;
		} catch (JavaModelException e) {
			LOGGER.error("Could not retrieve Soot classpath for project " + javaProject.getElementName());
		}
		sootCP = sootCP + File.pathSeparator;
		return sootCP;
	}

	/***** Find project files *****/

	private Set<String> getProjectClasses(IJavaProject project) throws JavaModelException {
		Set<String> projectClasses = new HashSet<String>();
		for (ICompilationUnit cu : getProjectCompilationUnits(project))
			if (cu != null && cu.findPrimaryType() != null)
				projectClasses.add(cu.findPrimaryType().getFullyQualifiedName());
		return projectClasses;
	}

	public Set<ICompilationUnit> getProjectCompilationUnits(IJavaProject project) throws JavaModelException {
		Set<ICompilationUnit> compilationUnits = new HashSet<ICompilationUnit>();
		for (IPackageFragment packageFragment : project.getPackageFragments()) {
			if (packageFragment.getKind() == IPackageFragmentRoot.K_SOURCE)
				Collections.addAll(compilationUnits, packageFragment.getCompilationUnits());
		}
		return compilationUnits;
	}

	private String getApkFile(IJavaProject javaProject) throws FileNotFoundException, JavaModelException {
		String path = javaProject.getOutputLocation().toString();
		IResource binFolder = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
		if (binFolder != null) {
			String classFiles = binFolder.getLocation().toString();
			File classFilesDirectory = new File(classFiles);
			File parentDirectory = classFilesDirectory.getParentFile();
			for (File child : parentDirectory.listFiles()) {
				if (child.getName().endsWith(".apk"))
					return child.getAbsolutePath();
			}
		}
		return null;
	}

	protected Set<String> getJarFilesLocation(IJavaProject javaProject) throws JavaModelException {
		Set<String> jars = new HashSet<String>();
		IClasspathEntry[] resolvedClasspath = javaProject.getResolvedClasspath(true);
		for (IClasspathEntry classpathEntry : resolvedClasspath) {
			String path = classpathEntry.getPath().toOSString();
			if (path.endsWith(".jar")) {
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(classpathEntry.getPath());
				if (file != null && file.getRawLocation() != null)
					path = file.getRawLocation().toOSString();
				jars.add(path);
			}
		}
		return jars;
	}

	protected String getClassFilesLocation(IJavaProject javaProject) throws JavaModelException {
		String path = javaProject.getOutputLocation().toString();
		IResource binFolder = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
		if (binFolder != null)
			return binFolder.getLocation().toString();
		throw new RuntimeException("Could not retrieve Soot classpath for project " + javaProject.getElementName());
	}

	/***** SootMethod signatures *****/

	public static String getSootMethodSignature(IMethod iMethod) {
		try {
			StringBuilder name = new StringBuilder();
			name.append("<");
			name.append(iMethod.getDeclaringType().getFullyQualifiedName());
			name.append(": ");
			String retTypeName = resolveName(iMethod, iMethod.getReturnType());
			if (retTypeName == null)
				return null;
			name.append(retTypeName);
			name.append(" ");
			if (iMethod.isConstructor())
				name.append("<init>");
			else
				name.append(iMethod.getElementName());
			name.append("(");

			String comma = "";
			String[] parameterTypes = iMethod.getParameterTypes();
			for (int i = 0; i < iMethod.getParameterTypes().length; ++i) {
				name.append(comma);
				String readableName = resolveName(iMethod, parameterTypes[i]);
				if (readableName == null)
					return null;
				name.append(readableName);
				comma = ",";
			}

			name.append(")");
			name.append(">");

			return name.toString();
		} catch (JavaModelException e) {
			LOGGER.error("Error building Soot method signature", e);
			return null;
		}
	}

	private static String resolveName(IMethod iMethod, String simpleName) throws JavaModelException {
		String readableName = Signature.toString(simpleName);
		String arraySuffix = "";
		if (readableName.contains("[]")) {
			int arraySuffixStart = readableName.indexOf("[]");
			arraySuffix = readableName.substring(arraySuffixStart);
			readableName = readableName.substring(0, arraySuffixStart);
		}
		if (!Config.primTypesNames.contains(readableName)) {
			String[][] fqTypes = iMethod.getDeclaringType().resolveType(readableName);
			if (fqTypes == null || fqTypes.length == 0) {
				LOGGER.debug("Failed to resolve type " + readableName + " in "
						+ iMethod.getDeclaringType().getFullyQualifiedName());
				return null;
			} else if (fqTypes.length > 1) {
				LOGGER.debug("Type " + readableName + " is ambiguous "
						+ iMethod.getDeclaringType().getFullyQualifiedName() + ":");
				for (int i = 0; i < fqTypes.length; i++) {
					LOGGER.debug("    " + fqTypes[i][0] + "." + fqTypes[i][1]);
				}
				return null;
			}
			String pkg = fqTypes[0][0];
			String className = fqTypes[0][1];
			readableName = pkg + "." + className;
		}
		return readableName + arraySuffix;
	}
}
