package layeredtaintplugin.activators.builder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;

import layeredtaintplugin.Config;

public class AddBuilder {

	public static void addBuilder(final IProject project) {

		try {
			// verify already registered builders
			if (hasBuilder(project))
				// already enabled
				return;

			// Remove all incremental builders from all other projects
			IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
			for (IProject p : workspaceRoot.getProjects())
				RemoveBuilder.removeBuilder(p);

			// add builder to project properties
			IProjectDescription description = project.getDescription();
			final ICommand buildCommand = description.newCommand();
			buildCommand.setBuilderName(Config.BUILDER_ID);

			final List<ICommand> commands = new ArrayList<ICommand>();
			commands.addAll(Arrays.asList(description.getBuildSpec()));
			commands.add(buildCommand);

			description.setBuildSpec(commands.toArray(new ICommand[commands.size()]));
			project.setDescription(description, null);

		} catch (final CoreException e) {
			// Could not read/write project description
			e.printStackTrace();
		}
	}

	public static IProject getProject(final ExecutionEvent event) {
		final ISelection selection = HandlerUtil.getCurrentSelection(event);
		if (selection instanceof IStructuredSelection) {
			final Object element = ((IStructuredSelection) selection).getFirstElement();

			return Platform.getAdapterManager().getAdapter(element, IProject.class);
		}

		return null;
	}

	public static final boolean hasBuilder(final IProject project) {
		try {
			for (final ICommand buildSpec : project.getDescription().getBuildSpec()) {
				if (Config.BUILDER_ID.equals(buildSpec.getBuilderName()))
					return true;
			}
		} catch (final CoreException e) {
		}

		return false;
	}

}
