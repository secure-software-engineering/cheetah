package layeredtaintplugin.internal;

import java.util.HashSet;
import java.util.Set;

import layeredtaintplugin.internal.layer.Layer;
import soot.SootMethod;
import soot.Unit;

public class Task implements Comparable<Task> {

	private final long timeStamp;

	private final Layer layer;
	// Where to start propagating
	private SootMethod startMethod; // never null
	private Unit startUnit; // can be null
	// New facts to propagate at start
	private final Set<FlowAbstraction> inFacts;
	// Potential targets of the call in startUnit
	private final Set<SootMethod> targets;

	public Task(Layer layer, SootMethod startMethod, Unit startUnit) {
		this(layer, startMethod, startUnit, new HashSet<FlowAbstraction>(), new HashSet<SootMethod>());
	}

	public Task(Layer layer, SootMethod startMethod, Unit startUnit, Set<FlowAbstraction> inFacts,
			Set<SootMethod> targets) {
		this.timeStamp = System.nanoTime();
		this.layer = layer;
		this.startMethod = startMethod;
		this.startUnit = startUnit;
		this.inFacts = inFacts;
		this.targets = targets;
	}

	public Layer getLayer() {
		return layer;
	}

	public void addInFact(FlowAbstraction source) {
		this.inFacts.add(source);
	}

	public Set<FlowAbstraction> getInFacts() {
		return inFacts;
	}

	public Set<SootMethod> getTargets() {
		return targets;
	}

	public SootMethod getStartMethod() {
		return startMethod;
	}

	public void setStartMethod(SootMethod startMethod) {
		this.startMethod = startMethod;
	}

	public Unit getStartUnit() {
		return startUnit;
	}

	public long getTimeStamp() {
		return this.timeStamp;
	}

	@Override
	public int hashCode() {
		final int prime = 37;
		int result = 1;
		result = prime * result + ((layer == null) ? 0 : layer.hashCode());
		result = prime * result + ((startMethod == null) ? 0 : startMethod.hashCode());
		result = prime * result + ((startUnit == null) ? 0 : startUnit.hashCode());
		result = prime * result + ((inFacts == null) ? 0 : inFacts.hashCode());
		return result;
	}

	@Override
	public String toString() {
		return layer + " : " + startMethod + " --- " + startUnit;
	}

	public String toLongString() {
		return layer + " : " + startMethod + " : " + startUnit + " : " + inFacts;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Task))
			return false;
		return (((Task) obj).hashCode() == hashCode());
	}

	@Override
	public int compareTo(Task otherTask) {
		if (this.getLayer().ordinal() < otherTask.getLayer().ordinal())
			return -1;
		else if (this.getLayer().ordinal() > otherTask.getLayer().ordinal())
			return 1;
		else {
			if (this.getTimeStamp() < otherTask.getTimeStamp())
				return -1;
			return 1;
		}
	}
}
