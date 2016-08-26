package layeredtaintplugin;

import java.util.HashSet;
import java.util.Set;

import soot.BooleanType;
import soot.ByteType;
import soot.CharType;
import soot.DoubleType;
import soot.FloatType;
import soot.IntType;
import soot.LongType;
import soot.RefType;
import soot.ShortType;
import soot.jimple.toolkits.typing.fast.Integer127Type;
import soot.jimple.toolkits.typing.fast.Integer1Type;
import soot.jimple.toolkits.typing.fast.Integer32767Type;

public class Config {

	public static final String OVERVIEW_ID = "layeredanalysis.views.overview";
	public static final String DETAIL_ID = "layeredanalysis.views.detailview";
	public static final String markerSinkId = "layeredplugin.markers.leftbar.sink";
	public static final String markerSourceId = "layeredplugin.markers.leftbar.source";
	public static final String markerSinkIdGray = "layeredplugin.markers.leftbar.sink-gray";
	public static final String markerSourceIdGray = "layeredplugin.markers.leftbar.source-gray";
	public static final String markerHighlightId = "org.eclipse.viatra2.slicemarker";
	public static final String BUILDER_ID = "layeredplugin.builder.Builder";

	public final static String callbacks = "AndroidCallbacks.txt";
	public final static String susi = "SourcesAndSinks.txt";

	public static final int apLength = 5;

	public static final String dummyMainMethodName = "dummyMainMethod";
	public static final String dummyMainClassName = "dummyMainClass";

	public static final Set<RefType> primTypes;
	static {
		primTypes = new HashSet<RefType>();
		primTypes.add(ByteType.v().boxedType());
		primTypes.add(BooleanType.v().boxedType());
		primTypes.add(CharType.v().boxedType());
		primTypes.add(DoubleType.v().boxedType());
		primTypes.add(FloatType.v().boxedType());
		primTypes.add(Integer127Type.v().boxedType());
		primTypes.add(Integer1Type.v().boxedType());
		primTypes.add(Integer32767Type.v().boxedType());
		primTypes.add(IntType.v().boxedType());
		primTypes.add(LongType.v().boxedType());
		primTypes.add(ShortType.v().boxedType());
	}

	public static final Set<String> primTypesNames;
	static {
		primTypesNames = new HashSet<String>();
		primTypesNames.add("void");
		primTypesNames.add("byte");
		primTypesNames.add("int");
		primTypesNames.add("boolean");
		primTypesNames.add("long");
		primTypesNames.add("short");
		primTypesNames.add("float");
		primTypesNames.add("double");
	}

}
