package layeredtaintplugin.ui.viewers;

import org.eclipse.core.resources.IFile;

import soot.Unit;

public class UnitInfo {
	private final Unit unit;
	private final String java;
	private final String file;
	private final int line;
	private final IFile sourceFile;
	private boolean isSource = false;
	private boolean isSink = false;
	private final String method;

	public UnitInfo(Unit unit, String java, String file, int line, IFile sourceFile, String method) {
		this.unit = unit;
		this.java = java;
		this.file = file;
		this.line = line;
		this.sourceFile = sourceFile;
		this.method = method;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((file == null) ? 0 : file.hashCode());
		result = prime * result + (isSink ? 1231 : 1237);
		result = prime * result + (isSource ? 1231 : 1237);
		result = prime * result + line;
		result = prime * result + ((unit == null) ? 0 : unit.hashCode());
		result = prime * result + ((method == null) ? 0 : method.hashCode());
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
		UnitInfo other = (UnitInfo) obj;
		if (file == null) {
			if (other.file != null)
				return false;
		} else if (!file.equals(other.file))
			return false;
		if (isSink != other.isSink)
			return false;
		if (isSource != other.isSource)
			return false;
		if (line != other.line)
			return false;
		if (unit == null) {
			if (other.unit != null)
				return false;
		} else if (!unit.equals(other.unit))
			return false;
		if (method == null) {
			if (other.method != null)
				return false;
		} else if (!method.equals(other.method))
			return false;
		return true;
	}

	public Unit getUnit() {
		return unit;
	}

	public String getJava() {
		return java;
	}

	public String getFile() {
		return file;
	}

	public String getMethod() {
		return method;
	}

	public IFile getSourceFile() {
		return sourceFile;
	}

	public int getLine() {
		return line;
	}

	public String getInfo() {
		return this.line + ":" + this.file;
	}

	public boolean isSource() {
		return isSource;
	}

	public boolean isSink() {
		return isSink;
	}

	public void setSource(boolean b) {
		isSource = b;
	}

	public void setSink(boolean b) {
		isSink = b;
	}
}
