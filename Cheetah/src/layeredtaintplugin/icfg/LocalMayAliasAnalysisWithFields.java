package layeredtaintplugin.icfg;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import layeredtaintplugin.icfg.LocalMayAliasAnalysisWithFields.EquivValue;
import soot.Body;
import soot.Local;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.ConcreteRef;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceFieldRef;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.ForwardFlowAnalysis;

/**
 * Conducts a method-local, equality-based may-alias analysis.
 */
public class LocalMayAliasAnalysisWithFields extends ForwardFlowAnalysis<Unit, Set<Set<EquivValue>>> {

	private final Body body;
	private final Map<EquivValue, Unit> lastOccurence = new HashMap<EquivValue, Unit>();

	public LocalMayAliasAnalysisWithFields(UnitGraph graph) {
		super(graph);
		body = graph.getBody();
		doAnalysis();
	}

	class EquivValue {
		public Value value;

		public EquivValue(Value value) {
			this.value = value;
		}

		@Override
		public int hashCode() {
			return value.equivHashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof EquivValue)
				return value.equivTo(((EquivValue) o).value);
			return false;
		}

		@Override
		public String toString() {
			return value.toString() + "(" + value.hashCode() + ")";
		}
	}

	@Override
	protected void flowThrough(Set<Set<EquivValue>> source, Unit unit, Set<Set<EquivValue>> target) {

		// Last occurence
		for (ValueBox vb : unit.getUseAndDefBoxes()) {
			EquivValue ev = new EquivValue(vb.getValue());
			Unit lastSeenUnit = lastOccurence.get(ev);
			if (comesAfter(unit, lastSeenUnit))
				lastOccurence.put(ev, unit);
		}

		// Track aliases
		target.addAll(source);
		if (unit instanceof DefinitionStmt) {
			DefinitionStmt def = (DefinitionStmt) unit;

			boolean leftIsLocalOrConcrete = (def.getLeftOp() instanceof Local
					|| def.getLeftOp() instanceof ConcreteRef);
			boolean rightIsLocalOrConcrete = (def.getRightOp() instanceof Local
					|| def.getRightOp() instanceof ConcreteRef);

			if (leftIsLocalOrConcrete) {
				EquivValue left = new EquivValue(def.getLeftOp());
				EquivValue right = new EquivValue(def.getRightOp());

				if (rightIsLocalOrConcrete && !(right.value instanceof Constant)) {
					// remove left from its sets
					Set<Set<EquivValue>> leftSets = getSets(target, left);
					removeFromSets(target, leftSets, left);
					// add left into right's sets
					Set<Set<EquivValue>> rightSets = getSets(target, right);
					addAndMerge(target, rightSets, left);

					// Not totally precise here
					if (left.value instanceof Local) {
						// find the sets containing left's children
						Map<Set<EquivValue>, Set<EquivValue>> leftChildrenSets = getChildrenSets(target,
								(Local) left.value);
						// remove left children from their sets
						removeChildrenFromSets(target, leftChildrenSets);
						// add left children on their own
						for (Set<EquivValue> children : leftChildrenSets.keySet()) {
							for (EquivValue ev : children) {
								target.add(Collections.singleton(ev));
							}
						}
					}
				} else {

					// remove left from its sets
					Set<Set<EquivValue>> leftSets = getSets(target, left);
					removeFromSets(target, leftSets, left);
					// add left on its own
					target.add(Collections.singleton(left));

					if (left.value instanceof Local) {
						// find the sets containing left's children
						Map<Set<EquivValue>, Set<EquivValue>> leftChildrenSets = getChildrenSets(target,
								(Local) left.value);
						// remove left children from their sets
						removeChildrenFromSets(target, leftChildrenSets);
						// add left children on their own
						for (Set<EquivValue> children : leftChildrenSets.keySet()) {
							for (EquivValue ev : children) {
								target.add(Collections.singleton(ev));
							}
						}
					}
				}
			}
		}
	}

	private boolean comesAfter(Unit unit, Unit ref) {
		if (ref == null)
			return true;
		String b = body.toString();
		int i = b.lastIndexOf(unit.toString());
		int j = b.lastIndexOf(ref.toString());
		return i > j;
	}

	/***** Set manipulation *****/

	private Map<Set<EquivValue>, Set<EquivValue>> getChildrenSets(Set<Set<EquivValue>> sets, Local local) {
		Map<Set<EquivValue>, Set<EquivValue>> ret = new HashMap<Set<EquivValue>, Set<EquivValue>>();
		for (Set<EquivValue> set : sets) {
			Set<EquivValue> ev = getChildren(set, local);
			if (!ev.isEmpty())
				ret.put(ev, set);
		}
		return ret;
	}

	private void removeChildrenFromSets(Set<Set<EquivValue>> target,
			Map<Set<EquivValue>, Set<EquivValue>> leftChildrenSets) {
		for (Set<EquivValue> children : leftChildrenSets.keySet()) {
			for (EquivValue ev : children)
				removeFromSet(target, leftChildrenSets.get(children), ev);
		}
	}

	private Set<EquivValue> getChildren(Set<EquivValue> set, Local local) {
		Set<EquivValue> children = new HashSet<EquivValue>();
		for (EquivValue ev : set) {
			if (ev.value instanceof InstanceFieldRef) {
				if (local.equivTo(((InstanceFieldRef) ev.value).getBase()))
					children.add(ev);
			}
		}
		return children;
	}

	private void mergeSets(Set<Set<EquivValue>> target, Set<Set<EquivValue>> right) {
		for (Set<EquivValue> set : right) {
			Set<EquivValue> tmpSet = new HashSet<EquivValue>();
			for (EquivValue ev : set) {
				Set<Set<EquivValue>> targetSets = getSets(target, ev);
				for (Set<EquivValue> targetSet : targetSets) {
					tmpSet.addAll(targetSet);
					target.remove(targetSet);
				}
			}
			tmpSet.addAll(set);
			target.add(tmpSet);
		}
	}

	private void addAndMerge(Set<Set<EquivValue>> target, Set<Set<EquivValue>> rightSets, EquivValue left) {
		Set<EquivValue> tmp = new HashSet<EquivValue>();
		for (Set<EquivValue> s : rightSets) {
			tmp.addAll(s);
			target.remove(s);
		}
		tmp.add(left);
		target.add(tmp);
	}

	private void removeFromSets(Set<Set<EquivValue>> target, Set<Set<EquivValue>> sets, EquivValue value) {
		for (Set<EquivValue> set : sets)
			removeFromSet(target, set, value);
	}

	private void removeFromSet(Set<Set<EquivValue>> target, Set<EquivValue> set, EquivValue value) {
		target.remove(set);
		HashSet<EquivValue> setWithoutVal = new HashSet<EquivValue>(set);
		setWithoutVal.remove(value);
		if (!setWithoutVal.isEmpty())
			target.add(setWithoutVal);
	}

	private Set<Set<EquivValue>> getSets(Set<Set<EquivValue>> sets, EquivValue value) {
		Set<Set<EquivValue>> ret = new HashSet<Set<EquivValue>>();
		for (Set<EquivValue> set : sets) {
			if (set.contains(value))
				ret.add(set);
		}
		return ret;
	}

	private void addAll(Set<EquivValue> set, Set<Value> res) {
		for (EquivValue ev : set)
			res.add(ev.value);
	}

	/***** Analysis *****/

	@Override
	protected void copy(Set<Set<EquivValue>> source, Set<Set<EquivValue>> target) {
		target.clear();
		target.addAll(source);
	}

	@Override
	protected Set<Set<EquivValue>> entryInitialFlow() {
		// initially all values only alias themselves
		Set<Set<EquivValue>> res = new HashSet<Set<EquivValue>>();
		for (ValueBox vb : body.getUseAndDefBoxes()) {
			if (vb.getValue() instanceof Local || vb.getValue() instanceof ConcreteRef) {
				res.add(Collections.singleton(new EquivValue(vb.getValue())));
			}
		}
		return res;
	}

	@Override
	protected void merge(Set<Set<EquivValue>> source1, Set<Set<EquivValue>> source2, Set<Set<EquivValue>> target) {
		// we could instead also merge all sets that are non-disjoint
		target.clear();
		target.addAll(source1);
		mergeSets(target, source2);
	}

	@Override
	protected Set<Set<EquivValue>> newInitialFlow() {
		return new HashSet<Set<EquivValue>>();
	}

	/***** Queries *****/

	public boolean mayAlias(Value v1, Value v2, Unit u) {
		EquivValue ev1 = new EquivValue(v1);
		EquivValue ev2 = new EquivValue(v2);
		Set<Set<EquivValue>> res = getFlowAfter(u);
		for (Set<EquivValue> set : res) {
			if (set.contains(ev1) && set.contains(ev2))
				return true;
		}
		return false;
	}

	public Set<Value> mayAliases(Value v, Unit u) {
		EquivValue ev = new EquivValue(v);
		Set<Value> res = new HashSet<Value>();
		Set<Set<EquivValue>> flow = getFlowAfter(u);

		for (Set<EquivValue> set : flow) {
			if (set.contains(ev))
				addAll(set, res);
		}
		return res;
	}

	public Set<Value> mayAliasesAtExit(Value v) {
		EquivValue ev = new EquivValue(v);
		Set<Value> res = new HashSet<Value>();
		for (Unit u : graph.getTails()) {
			Set<Set<EquivValue>> flow = getFlowAfter(u);
			for (Set<EquivValue> set : flow) {
				if (set.contains(ev))
					addAll(set, res);
			}
		}
		return res;
	}

	public Unit lastOccurenceOf(Local l) {
		return lastOccurence.get(new EquivValue(l));
	}

}
