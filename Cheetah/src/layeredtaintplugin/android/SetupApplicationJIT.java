package layeredtaintplugin.android;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParserException;

import layeredtaintplugin.Activator;
import soot.G;
import soot.PackManager;
import soot.Scene;
import soot.SootMethod;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.resources.ARSCFileParser;
import soot.jimple.infoflow.android.resources.LayoutFileParser;
import soot.jimple.infoflow.android.source.AccessPathBasedSourceSinkManager;
import soot.jimple.infoflow.android.source.AndroidSourceSinkManager.LayoutMatchingMode;
import soot.jimple.infoflow.data.SootMethodAndClass;
import soot.jimple.infoflow.source.data.ISourceSinkDefinitionProvider;
import soot.options.Options;

public class SetupApplicationJIT {

	private final Logger LOGGER = LoggerFactory.getLogger(getClass());

	private final String sootCP;
	private final String apkFileLocation;

	private Set<String> entrypoints = null;
	private List<ARSCFileParser.ResPackage> resourcePackages = null;
	private String appPackageName = "";

	private final Map<String, Set<SootMethodAndClass>> callbackMethods = new HashMap<String, Set<SootMethodAndClass>>(
			10000);
	private Set<String> androidCallbacks;

	private AccessPathBasedSourceSinkManager sourceSinkManager = null;
	private AndroidEntryPointCreatorJIT entryPointCreator = null;

	public SetupApplicationJIT(String apkFileLocation, String sootCP,
			ISourceSinkDefinitionProvider sourceSinkProvider) {
		this.apkFileLocation = apkFileLocation;
		this.sootCP = sootCP;
		try {
			// Load Android callbacks
			this.androidCallbacks = Activator.getDefault().getAndroidCallbacks();

			// Process manifest
			ProcessManifest processMan = new ProcessManifest(apkFileLocation);
			this.appPackageName = processMan.getPackageName();
			this.entrypoints = processMan.getEntryPointClasses();

			// Parse the resource file
			ARSCFileParser resParser = new ARSCFileParser();
			resParser.parse(apkFileLocation);
			this.resourcePackages = resParser.getPackages();

			// LayoutFileParser
			LayoutFileParser lfp = new LayoutFileParser(this.appPackageName, resParser);
			lfp.parseLayoutFile(apkFileLocation, entrypoints);

			// Create the SourceSinkManager
			Set<SootMethodAndClass> callbacks = new HashSet<>();
			for (Set<SootMethodAndClass> methods : this.callbackMethods.values())
				callbacks.addAll(methods);
			sourceSinkManager = new AccessPathBasedSourceSinkManager(sourceSinkProvider.getSources(),
					sourceSinkProvider.getSinks(), callbacks, LayoutMatchingMode.MatchSensitiveOnly,
					lfp == null ? null : lfp.getUserControlsByID());
			sourceSinkManager.setAppPackageName(this.appPackageName);
			sourceSinkManager.setResourcePackages(this.resourcePackages);
			sourceSinkManager.setEnableCallbackSources(true);
		} catch (IOException | XmlPullParserException e) {
			LOGGER.error("Error initializing " + apkFileLocation);
		}
	}

	/***** Dummy main *****/

	public SootMethod createFullDummyMain() {
		entryPointCreator = createEntryPointCreator();
		return entryPointCreator.createFullDummyMain();
	}

	public SootMethod createDummyMainForClass(String className) {
		collectClassEntryPoints(className);
		entryPointCreator = createEntryPointCreator();
		return entryPointCreator.createDummyMainForClass(className);
	}

	private void collectClassEntryPoints(String className) {
		AnalyzeJimpleClassJIT jimpleClass = new AnalyzeJimpleClassJIT(androidCallbacks);
		jimpleClass.collectCallbackMethodsForClass(className);

		// Run the soot-based operations
		PackManager.v().getPack("wjpp").apply();
		PackManager.v().getPack("cg").apply();
		PackManager.v().getPack("wjtp").apply();

		// Collect the results of the soot-based phases
		for (Entry<String, Set<SootMethodAndClass>> entry : jimpleClass.getCallbackMethods().entrySet()) {
			if (this.callbackMethods.containsKey(entry.getKey())) {
				this.callbackMethods.get(entry.getKey()).addAll(entry.getValue());
			} else {
				this.callbackMethods.put(entry.getKey(), new HashSet<>(entry.getValue()));
			}
		}

		entrypoints.addAll(jimpleClass.getDynamicManifestComponents());

		Set<SootMethodAndClass> callbacks = new HashSet<>();
		for (Set<SootMethodAndClass> methods : this.callbackMethods.values())
			callbacks.addAll(methods);
		sourceSinkManager.setCallbacks(callbacks);
	}

	/***** Entry points calculation *****/

	private AndroidEntryPointCreatorJIT createEntryPointCreator() {
		AndroidEntryPointCreatorJIT entryPointCreator = new AndroidEntryPointCreatorJIT(
				new ArrayList<String>(this.entrypoints));
		Map<String, List<String>> callbackMethodSigs = new HashMap<String, List<String>>();
		for (String className : this.callbackMethods.keySet()) {
			List<String> methodSigs = new ArrayList<String>();
			callbackMethodSigs.put(className, methodSigs);
			for (SootMethodAndClass am : this.callbackMethods.get(className))
				methodSigs.add(am.getSignature());
		}
		entryPointCreator.setCallbackFunctions(callbackMethodSigs);
		return entryPointCreator;
	}

	/***** Getters *****/

	public Set<String> getEntryPoints() {
		return this.entrypoints;
	}

	public AccessPathBasedSourceSinkManager getSourceSinkManager() {
		return sourceSinkManager;
	}

	/***** Init soot *****/

	public void initializeSoot() {
		G.reset();

		Options.v().set_no_bodies_for_excluded(true);
		Options.v().set_allow_phantom_refs(true);
		Options.v().set_output_format(Options.output_format_none);
		Options.v().set_whole_program(true);
		Options.v().set_process_dir(Collections.singletonList(apkFileLocation));
		Options.v().set_soot_classpath(sootCP);
		Options.v().set_src_prec(Options.src_prec_apk); // src_prec_apk_class_jimple
		Options.v().setPhaseOption("jb", "use-original-names:true");
		Options.v().set_keep_line_number(true);
		Options.v().set_on_the_fly(true);
		Options.v().setPhaseOption("cg.cha", "on");

		Scene.v().loadBasicClasses();
	}
}