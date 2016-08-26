package layeredtaintplugin.activators;

import java.io.IOException;

import org.eclipse.ui.IStartup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Activator;
import layeredtaintplugin.Config;
import layeredtaintplugin.android.readers.PermissionMethodParserJIT;

public class EarlyStartup implements IStartup {

	private final static Logger LOGGER = LoggerFactory.getLogger(EarlyStartup.class);

	@Override
	public void earlyStartup() {
		try {
			Activator.getDefault().setSusiParser(PermissionMethodParserJIT.fromFile(Config.susi));
			Activator.getDefault().loadAndroidCallbacks();
		} catch (IOException e) {
			LOGGER.error("Couldn't parse susi file " + Config.susi);
			e.printStackTrace();
		}
	}
}
