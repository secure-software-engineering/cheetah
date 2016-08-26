package layeredtaintplugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import layeredtaintplugin.android.readers.PermissionMethodParserJIT;
import layeredtaintplugin.internal.PrepareAnalysis;

/**
 * The activator class controls the plug-in life cycle
 */
public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "LayeredTaintPlugin"; //$NON-NLS-1$

	private static Activator plugin;

	private PrepareAnalysis analysis;
	private int runId = 0;
	private int warningId = 0;
	private PermissionMethodParserJIT susiParser;
	private Set<String> androidCallbacks;

	public Activator() {
	}

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}

	public PrepareAnalysis getAnalysis() {
		if (analysis == null) {
			analysis = new PrepareAnalysis();
		}
		return analysis;
	}

	public synchronized int getNewId() {
		runId++;
		return runId;
	}

	public synchronized int getNewWarningId() {
		warningId++;
		return warningId;
	}

	public void setSusiParser(PermissionMethodParserJIT susiParser) {
		this.susiParser = susiParser;
	}

	public PermissionMethodParserJIT getSusiParser() {
		return this.susiParser;
	}

	public void loadAndroidCallbacks() throws IOException {
		this.androidCallbacks = new HashSet<String>();
		String line;
		InputStream is = null;
		BufferedReader br = null;

		try {
			is = FileLocator.openStream(Activator.getDefault().getBundle(), new Path(Config.callbacks), false);
			br = new BufferedReader(new InputStreamReader(is));
			while ((line = br.readLine()) != null)
				androidCallbacks.add(line);
		} finally {
			if (br != null)
				br.close();
			if (is != null)
				is.close();
		}
	}

	public Set<String> getAndroidCallbacks() {
		return this.androidCallbacks;
	}

}
