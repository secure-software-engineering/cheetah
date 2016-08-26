package layeredtaintplugin.internal;

import java.util.Arrays;
import java.util.HashSet;

import layeredtaintplugin.Config;
import soot.Local;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.Constant;
import soot.jimple.InstanceFieldRef;
import soot.jimple.StaticFieldRef;

public class FlowAbstraction {

	private final static FlowAbstraction zeroAbstraction = new FlowAbstraction(null, null, null, null, null);

	private final Unit source;
	private final Local local;
	private SootField[] fields = new SootField[] {};
	private Unit unit;
	private SootMethod method;
	private HashSet<FlowAbstraction> neighbours;
	private FlowAbstraction predecessor;

	public FlowAbstraction(Unit source, Local local, Unit stmt, SootMethod method, FlowAbstraction predecessor) {
		this(source, local, new SootField[] {}, stmt, method, predecessor);
	}

	public FlowAbstraction(Unit source, Local local, SootField[] fields, Unit unit, SootMethod method,
			FlowAbstraction predecessor) {
		this.source = source;
		this.local = local;
		this.fields = fields;
		truncateFields();
		this.unit = unit;
		this.method = method;
		this.predecessor = predecessor;
		this.neighbours = new HashSet<FlowAbstraction>();
	}

	public static FlowAbstraction zeroAbstraction() {
		return zeroAbstraction;
	}

	public boolean isZeroAbstraction() {
		if (local == null && source == null && fields.length == 0)
			return true;
		return false;
	}

	/***** Getters and setters *****/

	public Unit getSource() {
		return this.source;
	}

	public SootMethod getMethod() {
		return this.method;
	}

	public Local getLocal() {
		return this.local;
	}

	public SootField[] getFields() {
		return this.fields;
	}

	public Unit getUnit() {
		return this.unit;
	}

	public FlowAbstraction predecessor() {
		return predecessor;
	}

	public HashSet<FlowAbstraction> neighbours() {
		return neighbours;
	}

	public void addNeighbour(FlowAbstraction neighbour) {
		this.neighbours.add(neighbour);
	}

	/***** Utils *****/

	public boolean exactEquals(FlowAbstraction other) {
		if (this.equals(other)) {
			return this.predecessor.equals(other.predecessor);
		}
		return false;
	}

	@Override
	public int hashCode() {
		if (isZeroAbstraction())
			return 0;

		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(fields);
		result = prime * result + ((local == null) ? 0 : local.hashCode());
		result = prime * result + ((unit == null) ? 0 : unit.hashCode());
		if (local == null) {
			SootField firstField = fields[0];
			result = prime * result + firstField.getDeclaringClass().hashCode();
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
		FlowAbstraction other = (FlowAbstraction) obj;
		if (other.isZeroAbstraction() && isZeroAbstraction())
			return true;
		if (!Arrays.equals(fields, other.fields))
			return false;
		if (local == null) {
			if (other.local != null)
				return false;
			else if (!fields[0].getDeclaringClass().equals(other.getFields()[0].getDeclaringClass()))
				return false;
		} else if (!local.equals(other.local))
			return false;
		if (unit == null) {
			if (other.unit != null)
				return false;
		} else if (!unit.equals(other.unit))
			return false;
		return true;
	}

	public String getShortName() {
		if (isZeroAbstraction())
			return "0";

		String res = "";
		if (local != null)
			res += local.getName();
		else
			res += fields[0].getDeclaringClass().getName();
		if (fields != null && fields.length > 0) {
			for (SootField sf : fields) {
				res += "." + sf.getName();
			}
		}
		// res += "(" + this.hashCode() + ")";

		return res;
	}

	@Override
	public String toString() {
		return getShortName();
	}

	public String toLongString() {
		return getShortName() + " --- " + unit + " --- " + predecessor;
	}

	/**** Abstraction operations ****/

	public FlowAbstraction deriveWithNewSource(Unit newSource, Unit unit, SootMethod method,
			FlowAbstraction predecessor) {
		return new FlowAbstraction(newSource, local, fields, unit, method, predecessor);
	}

	public FlowAbstraction deriveWithNewStmt(Unit unit, SootMethod method) {
		// Avoid multiple zeroAbstractions with different units
		if (source == null && local == null && fields == null && this.equals(zeroAbstraction()))
			return zeroAbstraction();
		FlowAbstraction pred = getPredecessor(unit, method, this);
		return new FlowAbstraction(source, local, fields, unit, method, pred);
	}

	public FlowAbstraction deriveWithNewLocal(Local local, Unit unit, SootMethod method, FlowAbstraction predecessor) {
		if (local == null)
			throw new RuntimeException("Target local may not be null");
		predecessor = getPredecessor(unit, method, predecessor);
		return new FlowAbstraction(source, local, fields, unit, method, predecessor);
	}

	public static FlowAbstraction v(Unit source, Value v, Unit unit, SootMethod method, FlowAbstraction predecessor) {
		predecessor = getPredecessor(unit, method, predecessor);
		if (v instanceof Local) {
			return new FlowAbstraction(source, (Local) v, unit, method, predecessor);
		} else if (v instanceof InstanceFieldRef) {
			InstanceFieldRef ifr = (InstanceFieldRef) v;
			return new FlowAbstraction(source, (Local) ifr.getBase(), new SootField[] { ifr.getField() }, unit, method,
					predecessor);
		} else if (v instanceof StaticFieldRef) {
			StaticFieldRef sfr = (StaticFieldRef) v;
			return new FlowAbstraction(source, null, new SootField[] { sfr.getField() }, unit, method, predecessor);
		} else if (v instanceof ArrayRef) {
			ArrayRef ar = (ArrayRef) v;
			return new FlowAbstraction(source, (Local) ar.getBase(), new SootField[] {}, unit, method, predecessor);
		} else
			throw new RuntimeException("Unexpected left side " + v + " (" + v.getClass() + ")");
	}

	private static FlowAbstraction getPredecessor(Unit unit, SootMethod method, FlowAbstraction wantedPredecessor) {
		// If part of any dummyMain, return predecessor of predecessor
		if (method.getName().equals(Config.dummyMainMethodName))
			return wantedPredecessor.predecessor();
		return wantedPredecessor;
	}

	/**** Field operations ****/

	public FlowAbstraction append(SootField[] newFields) {
		SootField[] a = new SootField[fields.length + newFields.length];
		System.arraycopy(fields, 0, a, 0, fields.length);
		System.arraycopy(newFields, 0, a, fields.length, newFields.length);
		this.fields = a;
		this.truncateFields();
		return this;
	}

	public boolean hasPrefix(Value v) { // if this has prefix v
		if (v instanceof Local) {
			if (local == null)
				return false;
			else
				return (local.equals(v));

		} else if (v instanceof InstanceFieldRef) {
			InstanceFieldRef ifr = (InstanceFieldRef) v;
			if (local == null) {
				if (ifr.getBase() != null)
					return false;
			} else if (!local.equals(ifr.getBase()))
				return false;
			if (fields.length > 0 && ifr.getField() == fields[0])
				return true;
			return false;

		} else if (v instanceof StaticFieldRef) {
			StaticFieldRef sfr = (StaticFieldRef) v;
			if (local != null)
				return false;
			if (fields.length > 0 && sfr.getField() == fields[0])
				return true;
			return false;

		} else if (v instanceof ArrayRef) {
			ArrayRef ar = (ArrayRef) v;
			if (local == null)
				return false;
			else
				return (local.equals(ar.getBase()));

		} else if (v instanceof Constant) {
			return false;
		} else
			throw new RuntimeException("Unexpected left side " + v.getClass());
	}

	public SootField[] getPostfix(Value v) { // this is longer than v
		if (v instanceof InstanceFieldRef || v instanceof StaticFieldRef) {
			if (fields.length > 0)
				return Arrays.copyOfRange(fields, 1, fields.length);
			return new SootField[] {};
		} else if (v instanceof ArrayRef) {
			return new SootField[] {};
		} else
			throw new RuntimeException("Unexpected left side " + v.getClass());
	}

	private void truncateFields() {
		if (this.fields.length > Config.apLength) {
			this.fields = Arrays.copyOf(this.fields, Config.apLength);
		}
	}

	public boolean same(Value sourceInArgs) {
		return sourceInArgs.toString().equals(this.getShortName());
	}
}
