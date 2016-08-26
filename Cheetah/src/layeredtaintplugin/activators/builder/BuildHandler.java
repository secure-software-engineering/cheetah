package layeredtaintplugin.activators.builder;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import layeredtaintplugin.Activator;
import soot.G;

public class BuildHandler extends ContributionItem {

	@Override
	public void fill(Menu menu, int index) {
		final IProject project = getSelectedProject();
		final boolean exists = AddBuilder.hasBuilder(project);
		MenuItem menuItem = new MenuItem(menu, SWT.CHECK, index);
		menuItem.setText((exists ? "Remove" : "Add") + " Layered Builder");
		menuItem.addSelectionListener(new SelectionAdapter() {
			@Override
			public void widgetSelected(SelectionEvent e) {
				G.reset();
				if (exists) {
					RemoveBuilder.removeBuilder(project);
				} else {
					AddBuilder.addBuilder(project);
				}
				Activator.getDefault().getAnalysis().removeWarnings(1000);
			}
		});
	}

	IProject getSelectedProject() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null) {
			IStructuredSelection selection = (IStructuredSelection) window.getSelectionService().getSelection();
			Object firstElement = selection.getFirstElement();
			if (firstElement instanceof IAdaptable) {
				IProject project = ((IAdaptable) firstElement).getAdapter(IProject.class);
				return project;
			}
		}
		return null;
	}
}
