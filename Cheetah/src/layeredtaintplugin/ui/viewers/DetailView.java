package layeredtaintplugin.ui.viewers;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Activator;

public class DetailView extends ViewPart {

	private final static Logger LOGGER = LoggerFactory.getLogger(DetailView.class);

	private TableViewer viewer;
	protected Map<String, Image> images = null;

	public DetailView() {
		super();
		this.images = new HashMap<String, Image>();
		URL url = FileLocator.find(Activator.getDefault().getBundle(), new Path("icons/sink2.png"), null);
		this.images.put("sink", ImageDescriptor.createFromURL(url).createImage());
		url = FileLocator.find(Activator.getDefault().getBundle(), new Path("icons/source2.png"), null);
		this.images.put("source", ImageDescriptor.createFromURL(url).createImage());
	}

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(2, false);
		parent.setLayout(layout);
		createViewer(parent);
		viewer.addDoubleClickListener(new DetailDoubleClickListener());
	}

	private void createViewer(Composite parent) {
		viewer = new TableViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.FULL_SELECTION | SWT.BORDER);
		createColumns(parent, viewer);
		final Table table = viewer.getTable();
		table.setHeaderVisible(true);
		table.setLinesVisible(true);

		viewer.setContentProvider(new ArrayContentProvider());
		getSite().setSelectionProvider(viewer);

		// define layout for the viewer
		GridData gridData = new GridData();
		gridData.verticalAlignment = GridData.FILL;
		gridData.horizontalSpan = 2;
		gridData.grabExcessHorizontalSpace = true;
		gridData.grabExcessVerticalSpace = true;
		gridData.horizontalAlignment = GridData.FILL;
		viewer.getControl().setLayoutData(gridData);
	}

	private void createColumns(final Composite parent, final TableViewer viewer) {
		String[] titles = { "Statement", "Location" }; // "Line"
		int[] bounds = { 180, 100 }; // , 50

		TableViewerColumn col = createTableViewerColumn(titles[0], bounds[0], 0);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				UnitInfo unitInfo = (UnitInfo) element;
				return unitInfo.getJava();
			}

			@Override
			public Image getImage(Object element) {
				UnitInfo unitInfo = (UnitInfo) element;
				if (unitInfo.isSource()) {
					return images.get("source");
				} else if (unitInfo.isSink()) {
					return images.get("sink");
				} else {
					return null;
				}
			}
		});

		col = createTableViewerColumn(titles[1], bounds[1], 1);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				UnitInfo unitInfo = (UnitInfo) element;
				return unitInfo.getLine() + ":" + unitInfo.getFile();
			}
		});

		// col = createTableViewerColumn(titles[2], bounds[2], 2);
		// col.setLabelProvider(new ColumnLabelProvider() {
		// @Override
		// public String getText(Object element) {
		// UnitInfo unitInfo = (UnitInfo) element;
		// return unitInfo.getLine() + "";
		// }
		// });
	}

	private TableViewerColumn createTableViewerColumn(String title, int bound, final int colNumber) {
		final TableViewerColumn viewerColumn = new TableViewerColumn(viewer, SWT.NONE);
		final TableColumn column = viewerColumn.getColumn();
		column.setText(title);
		column.setWidth(bound);
		column.setResizable(true);
		column.setMoveable(true);
		return viewerColumn;
	}

	@Override
	public void setFocus() {
		// viewer.getControl().setFocus();
	}

	/***** Actions *****/

	public void updatePath(Warning warning) {
		viewer.setInput(warning.getPath());
	}

	public void showWarning(Warning warning) {
		viewer.setInput(warning.getPath());
		viewer.getControl().setFocus();
	}

	public void clearView() {
		viewer.setInput(new ArrayList<UnitInfo>());
	}

	/***** Doubleclick listener *****/

	class DetailDoubleClickListener implements IDoubleClickListener {
		@Override
		public void doubleClick(DoubleClickEvent e) {
			IStructuredSelection selection = (IStructuredSelection) e.getSelection();
			Object selectedNode = selection.getFirstElement();
			if (selectedNode instanceof UnitInfo) {
				UnitInfo unitInfo = (UnitInfo) selectedNode;
				openFile(unitInfo.getSourceFile(), unitInfo.getLine());
				viewer.getControl().setFocus();
			}
		}
	}

	public static void openFile(final IFile file, final int lineNumber) {
		try {
			Display.getDefault().asyncExec(new Runnable() {
				@Override
				public void run() {
					IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
					if (window != null) {
						IWorkbenchPage page = window.getActivePage();
						if (page != null) {
							try {
								IMarker marker = file.createMarker(IMarker.TEXT);
								marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
								IDE.openEditor(page, marker);
								marker.delete();
							} catch (Exception e) {
								LOGGER.error(
										"Error in opening the IFile: " + file.getName() + " at line: " + lineNumber);
							}
						}
					}
				}
			});
		} catch (Exception e1) {
			LOGGER.error("Error in opening the IFile: " + file.getName() + " at line: " + lineNumber);
		}
	}

}
