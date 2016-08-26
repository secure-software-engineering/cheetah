package layeredtaintplugin.ui.viewers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Activator;
import layeredtaintplugin.icfg.JitIcfg;
import layeredtaintplugin.internal.FlowAbstraction;
import soot.SootClass;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class Warning {

	private final static Logger LOGGER = LoggerFactory.getLogger(Warning.class);

	private int warningId;
	private final int runId;
	private final FlowAbstraction sinkAbs;
	private final UnitInfo source;
	private final UnitInfo sink;
	private List<UnitInfo> path;
	private int additionalId = 0;

	public Warning(int runId, FlowAbstraction sinkAbs, JitIcfg icfg, IJavaProject project) {
		this.warningId = Activator.getDefault().getNewWarningId();
		this.runId = runId;
		this.sinkAbs = sinkAbs;
		this.path = new ArrayList<UnitInfo>();

		Unit source = sinkAbs.getSource();
		Unit sink = sinkAbs.getUnit();
		String sourceFileName = getSourceFileName(source, icfg);
		String sinkFileName = getSourceFileName(sink, icfg);
		String sourceMethod = icfg.getMethodOf(source).getSignature();
		String sinkMethod = icfg.getMethodOf(sink).getSignature();
		IFile sourceFile = null;
		IFile sinkFile = null;
		String sourceJavaUnit = "";
		String sinkJavaUnit = "";
		if (sourceFileName != null) {
			try {
				sourceFile = getSourceFile(sourceFileName, project);
				sourceJavaUnit = getJavaUnit(sourceFileName, source, project);
			} catch (CoreException e) {
				LOGGER.error("Error in retrieving java code");
			}
		}
		if (sinkFileName != null) {
			try {
				sinkFile = getSourceFile(sinkFileName, project);
				sinkJavaUnit = getJavaUnit(sinkFileName, sink, project);
			} catch (CoreException e) {
				LOGGER.error("Error in retrieving java code");
			}
		}
		int sourceLine = source.getJavaSourceStartLineNumber();
		int sinkLine = sink.getJavaSourceStartLineNumber();

		this.source = new UnitInfo(source, sourceJavaUnit, sourceFileName, sourceLine, sourceFile, sourceMethod);
		this.sink = new UnitInfo(sink, sinkJavaUnit, sinkFileName, sinkLine, sinkFile, sinkMethod);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		if (sinkAbs != null) {
			result = prime * result + sinkAbs.getShortName().hashCode();
			result = prime * result + getCall(sinkAbs.getUnit()).hashCode();
			result = prime * result + getCall(sinkAbs.getSource()).hashCode();
			result = prime * result + source.getMethod().hashCode();
			result = prime * result + sink.getMethod().hashCode();
			result = prime * result + additionalId;
		}
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Warning other = (Warning) obj;
		if (sinkAbs == null) {
			if (other.sinkAbs != null)
				return false;
		} else if (!sameAbs(sinkAbs, other.sinkAbs))
			return false;
		if (source == null) {
			if (other.source != null)
				return false;
		} else if (!source.getMethod().equals(other.source.getMethod()))
			return false;
		if (sink == null) {
			if (other.sink != null)
				return false;
		} else if (!sink.getMethod().equals(other.sink.getMethod()))
			return false;
		return (this.additionalId == other.additionalId);
	}

	private boolean sameAbs(FlowAbstraction fa1, FlowAbstraction fa2) {
		if (!fa1.getShortName().equals(fa2.getShortName()))
			return false;
		if (!getCall(fa1.getUnit()).equals(getCall(fa2.getUnit())))
			return false;
		if (!getCall(fa1.getSource()).equals(getCall(fa2.getSource())))
			return false;
		return true;
	}

	private String getCall(Unit unit) {
		Stmt stmt = (Stmt) unit;
		if (stmt.containsInvokeExpr()) {
			InvokeExpr invE = stmt.getInvokeExpr();
			return invE.getMethod().getSignature();
		} else
			return unit.toString();
	}

	public void setPath(List<FlowAbstraction> prunnedPath, JitIcfg icfg, IJavaProject project) {
		UnitInfo prevUnit = null;
		for (FlowAbstraction fa : prunnedPath) {
			String fileName = getSourceFileName(fa.getUnit(), icfg);
			String faMethod = icfg.getMethodOf(fa.getUnit()).getSignature();
			String javaUnit = "";
			IFile file = null;
			if (fileName != null) {
				try {
					file = getSourceFile(fileName, project);
					javaUnit = getJavaUnit(fileName, fa.getUnit(), project);
				} catch (CoreException e) {
					LOGGER.error("Error in retrieving java code");
				}
			}
			int lineNb = fa.getUnit().getJavaSourceStartLineNumber();

			if (prevUnit == null
					|| !((javaUnit.toString().equals(prevUnit.getJava()) && (lineNb == prevUnit.getLine())))) {
				UnitInfo unitInfo = new UnitInfo(fa.getUnit(), javaUnit, fileName, lineNb, file, faMethod);
				path.add(unitInfo);
				prevUnit = unitInfo;
			}
		}
		if (path.size() > 0) {
			if (!path.get(0).equals(this.source))
				path.add(0, source);
			if (!path.get(path.size() - 1).equals(this.sink))
				path.add(sink);

			path.get(0).setSource(true);
			path.get(path.size() - 1).setSink(true);
		}
	}

	/***** Getters and setters *****/

	public void additionalId(int n) {
		this.additionalId = n;
	}

	public List<UnitInfo> getPath() {
		return path;
	}

	public int getRunId() {
		return runId;
	}

	public int getId() {
		return warningId;
	}

	public UnitInfo getSource() {
		return source;
	}

	public UnitInfo getSink() {
		return sink;
	}

	public void setId(int id) {
		this.warningId = id;
	}

	/***** Utils *****/

	private String getJavaUnit(String fileName, Unit unit, IJavaProject project) throws CoreException {
		if (fileName != null) {
			final IFile file = getSourceFile(fileName, project);
			if (file != null && unit.getJavaSourceStartLineNumber() >= 0)
				return getJavaSource(file, unit.getJavaSourceStartLineNumber(), unit.toString()).trim();
		}
		return unit.toString();
	}

	private String getJavaSource(IFile file, int lineNb, String jimpleUnit) {
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(file.getRawLocation().makeAbsolute().toFile()));
			String line;
			int count = 1;
			while ((line = br.readLine()) != null) {
				if (count == lineNb) {
					br.close();
					return line;
				}
				count++;
			}
		} catch (IOException e) {
		}
		try {
			br.close();
		} catch (IOException e) {
		}
		return jimpleUnit;
	}

	private String getSourceFileName(Unit u, JitIcfg icfg) {
		SootClass sc = icfg.getMethodOf(u).getDeclaringClass();
		String fileName = "UnknownFile";
		if (sc.getTag("SourceFileTag") != null)
			fileName = sc.getTag("SourceFileTag").toString();
		else
			fileName = sc.getJavaStyleName() + ".java";
		return fileName;
	}

	private IFile getSourceFile(String name, IJavaProject project) throws CoreException {
		IContainer container = project.getProject();
		return getSourceFile(container, name);
	}

	private IFile getSourceFile(IContainer container, String name) throws CoreException {
		for (IResource r : container.members()) {
			if (r instanceof IContainer) {
				IFile file = getSourceFile((IContainer) r, name);
				if (file != null) {
					return file;
				}
			} else if (r instanceof IFile && r.getName().equals(name)) {
				return (IFile) r;
			}
		}
		return null;
	}

}
