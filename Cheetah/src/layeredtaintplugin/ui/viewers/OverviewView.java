package layeredtaintplugin.ui.viewers;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import layeredtaintplugin.Config;
import layeredtaintplugin.ui.markers.MarkerHandler;

public class OverviewView extends ViewPart {

	private final static Logger LOGGER = LoggerFactory.getLogger(OverviewView.class);

	private TableViewer viewer;
	private Warning currentWarning = null;

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(2, false);
		parent.setLayout(layout);
		createViewer(parent);
		viewer.addDoubleClickListener(new OverviewDoubleClickListener());
		// viewer.addSelectionChangedListener(new
		// OverviewSelectionChangeListener());
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
		String[] titles = { "Id", "Source", "Sink", "Source location", "Sink location", "Run" };
		int[] bounds = { 30, 350, 350, 150, 150 }; // 40

		TableViewerColumn col = createTableViewerColumn(titles[0], bounds[0], 0);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Warning warning = (Warning) element;
				return warning.getId() + "";
			}
		});

		col = createTableViewerColumn(titles[1], bounds[1], 1);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Warning warning = (Warning) element;
				return warning.getSource().getJava();
			}
		});

		col = createTableViewerColumn(titles[2], bounds[2], 2);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Warning warning = (Warning) element;
				return warning.getSink().getJava();
			}
		});

		col = createTableViewerColumn(titles[3], bounds[3], 3);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Warning warning = (Warning) element;
				return warning.getSource().getInfo();
			}
		});

		col = createTableViewerColumn(titles[4], bounds[4], 4);
		col.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				Warning warning = (Warning) element;
				return warning.getSink().getInfo();
			}
		});

		// col = createTableViewerColumn(titles[5], bounds[5], 5);
		// col.setLabelProvider(new ColumnLabelProvider() {
		// @Override
		// public String getText(Object element) {
		// Warning warning = (Warning) element;
		// return warning.getRunId() + "";
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

	/***** Warning handling *****/

	public void addWarning(Warning w) {
		synchronized (this) {
			boolean isIn = false;
			TableItem[] items = viewer.getTable().getItems();
			for (int i = 0; i < items.length; i++) {
				TableItem ti = items[i];
				Warning tableWarning = (Warning) ti.getData();
				if (w.equals(tableWarning)) {
					w.setId(tableWarning.getId());
					viewer.replace(w, i);
					Color black = Display.getCurrent().getSystemColor(SWT.COLOR_BLACK);
					ti.setForeground(black);
					MarkerHandler.validateWarning(w);
					isIn = true;
				}
			}
			if (!isIn) {
				viewer.insert(w, viewer.getTable().getItemCount());
				MarkerHandler.addMarkers(w);
			}
		}
	}

	public void invalidateWarnings() {
		for (TableItem ti : viewer.getTable().getItems()) {
			Color gray = Display.getCurrent().getSystemColor(SWT.COLOR_GRAY);
			ti.setForeground(gray);
			Warning warning = (Warning) ti.getData();
			MarkerHandler.invalidateWarning(warning);
		}
	}

	public void removeWarnings(int runId) {
		for (TableItem ti : viewer.getTable().getItems()) {
			Warning warning = (Warning) ti.getData();
			if (warning.getRunId() < runId - 1) {
				viewer.remove(warning);
				MarkerHandler.removeWarning(warning);
				MarkerHandler.removeHighlights(warning);
			}
		}
		if (currentWarning != null && currentWarning.getRunId() != runId) {
			Display.getDefault().asyncExec(new Runnable() {
				@Override
				public void run() {
					synchronized (this) {
						DetailView view = (DetailView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
								.getActivePage().findView(Config.DETAIL_ID);
						if (view != null)
							view.clearView();
					}
				}
			});
		}
	}

	public void updatePath(Warning warning) {
		if (currentWarning != null && currentWarning.equals(warning)) {
			Display.getDefault().asyncExec(new Runnable() {
				@Override
				public void run() {
					synchronized (this) {
						DetailView view = (DetailView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
								.getActivePage().findView(Config.DETAIL_ID);
						if (view != null)
							view.updatePath(warning);
					}
				}
			});
			currentWarning = warning;
		}
	}

	/***** Doubleclick listener *****/

	class OverviewDoubleClickListener implements IDoubleClickListener {
		@Override
		public void doubleClick(DoubleClickEvent e) {
			IStructuredSelection selection = (IStructuredSelection) e.getSelection();
			Object selectedNode = selection.getFirstElement();
			if (selectedNode instanceof Warning) {
				Warning warning = (Warning) selectedNode;
				currentWarning = warning;
				DetailView.openFile(warning.getSource().getSourceFile(), warning.getSource().getLine());
				showWarning(warning);
				highlightPath(warning);
				viewer.getControl().setFocus();
			}
		}
	}

	private void highlightPath(Warning warning) {
		IEditorPart editor = getActiveEditor();
		String[] a = getCurrentFile(editor).split("/");
		String currentOpenedFile = a[a.length - 1];
		Set<UnitInfo> highlightUnits = new HashSet<UnitInfo>();
		for (UnitInfo ui : warning.getPath()) {
			String fileName = ui.getFile();
			int lineNumber = ui.getLine();
			if (fileName.equals(currentOpenedFile) && lineNumber >= 0)
				highlightUnits.add(ui);
		}
		MarkerHandler.highlightLines(warning, highlightUnits);
	}

	private void showWarning(final Warning warning) {
		Display.getDefault().asyncExec(new Runnable() {
			@Override
			public void run() {
				synchronized (this) {
					try {
						DetailView view = (DetailView) PlatformUI.getWorkbench().getActiveWorkbenchWindow()
								.getActivePage().showView(Config.DETAIL_ID);
						view.showWarning(warning);
					} catch (PartInitException e) {
						LOGGER.error(e.getMessage());
					}
				}
			}
		});
	}

	/***** Selectionchange listener *****/

	// class OverviewSelectionChangeListener implements
	// ISelectionChangedListener {
	// @Override
	// public void selectionChanged(SelectionChangedEvent e) {
	// StructuredSelection selection = (StructuredSelection)
	// e.getSelection();
	// Object selectedNode = selection.getFirstElement();
	// IEditorPart editor = getActiveEditor();
	// String[] a = getCurrentFile(editor).split("/");
	// String currentOpenedFile = a[a.length - 1];
	// Set<UnitInfo> highlightUnits = new HashSet<UnitInfo>();
	// if (selectedNode instanceof Warning) {
	// Warning w = (Warning) selectedNode;
	// for (UnitInfo ui : w.getPath()) {
	// String fileName = ui.getFile();
	// int lineNumber = ui.getLine();
	// if (fileName.equals(currentOpenedFile) && lineNumber >= 0)
	// highlightUnits.add(ui);
	// }
	// }
	// MarkerHandler.highlightLines(highlightUnits);
	// }
	// }

	private IEditorPart getActiveEditor() {
		IWorkbench wb = PlatformUI.getWorkbench();
		IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
		IWorkbenchPage page = win.getActivePage();
		return page.getActiveEditor();
	}

	private String getCurrentFile(IEditorPart editor) {
		return editor.getTitleToolTip();
	}
}
