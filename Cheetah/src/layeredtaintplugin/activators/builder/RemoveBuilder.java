package layeredtaintplugin.activators.builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.runtime.CoreException;

import layeredtaintplugin.Config;

public class RemoveBuilder {

	public static void removeBuilder(IProject project) {
		try {
			// Remove builder
			final IProjectDescription description = project.getDescription();
			final List<ICommand> commands = new ArrayList<ICommand>();
			commands.addAll(Arrays.asList(description.getBuildSpec()));

			for (final ICommand buildSpec : description.getBuildSpec()) {
				if (Config.BUILDER_ID.equals(buildSpec.getBuilderName())) {
					commands.remove(buildSpec);
				}
			}
			description.setBuildSpec(commands.toArray(new ICommand[commands.size()]));
			project.setDescription(description, null);
		} catch (final CoreException e) {
		}
	}
}
