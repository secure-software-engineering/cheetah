package layeredtaintplugin.activators.builder;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Activator;
import layeredtaintplugin.Config;

public class Builder extends IncrementalProjectBuilder {

	private final static Logger LOGGER = LoggerFactory.getLogger(Builder.class);

	@Override
	protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
				if (window != null) {
					IWorkbenchPage page = window.getActivePage();
					if (page != null) {
						IEditorPart editorPart = page.getActiveEditor();

						if (editorPart != null) {
							IWorkbenchPartSite site = editorPart.getSite();
							if (site != null) {
								ITextSelection textSelection = (ITextSelection) site.getSelectionProvider()
										.getSelection();

								ITypeRoot root = (ITypeRoot) JavaUI
										.getEditorInputJavaElement(editorPart.getEditorInput());
								int offset = textSelection.getOffset();
								if (offset > 0) {
									IJavaElement elt;
									try {
										elt = root.getElementAt(offset);
										if (elt != null) {
											IMethod method = (IMethod) elt.getAncestor(IJavaElement.METHOD);
											IType type = (IType) elt.getAncestor(IJavaElement.TYPE);

											if (type != null && method == null && type.getMethods().length > 0)
												method = type.getMethods()[0];
											if (method != null) {
												PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
														.showView(Config.OVERVIEW_ID);
												IJavaProject project = method.getJavaProject();
												Activator.getDefault().getAnalysis().prepareAnalysis(method, project);
											}
										}
									} catch (JavaModelException | PartInitException e) {
										LOGGER.error(e.getMessage());
									}
								}
							}
						}
					}
				}
			}
		});
		return null;
	}

}
