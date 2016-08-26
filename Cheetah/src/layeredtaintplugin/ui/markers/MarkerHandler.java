package layeredtaintplugin.ui.markers;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import heros.solver.Pair;
import layeredtaintplugin.Config;
import layeredtaintplugin.ui.viewers.UnitInfo;
import layeredtaintplugin.ui.viewers.Warning;

public class MarkerHandler {

	private final static Logger LOGGER = LoggerFactory.getLogger(MarkerHandler.class);

	/***** Left markers *****/

	private static Map<Warning, Pair<IMarker, IMarker>> iconMarkers = new HashMap<Warning, Pair<IMarker, IMarker>>();

	public static void addMarkers(Warning warning) {
		IMarker sinkMarker = createMarker(warning.getSink(), Config.markerSinkId,
				"Sink from source: " + warning.getSource().getLine() + ":" + warning.getSource().getFile(),
				IMarker.PRIORITY_HIGH, IMarker.SEVERITY_ERROR);
		IMarker sourceMarker = createMarker(warning.getSource(), Config.markerSourceId,
				"Source to sink: " + warning.getSink().getLine() + ":" + warning.getSink().getFile(),
				IMarker.PRIORITY_LOW, IMarker.SEVERITY_INFO);
		iconMarkers.put(warning, new Pair<IMarker, IMarker>(sourceMarker, sinkMarker));
	}

	private static IMarker createMarker(UnitInfo ui, String markerType, String message, int priority, int severity) {
		IFile file = ui.getSourceFile();
		if (file != null) {
			try {
				IMarker marker = file.createMarker(markerType);
				marker.setAttribute(IMarker.LINE_NUMBER, ui.getLine());
				marker.setAttribute(IMarker.MESSAGE, message);
				marker.setAttribute(IMarker.PRIORITY, priority);
				marker.setAttribute(IMarker.SEVERITY, severity);
				return marker;
			} catch (CoreException e) {
				LOGGER.error("Error in creating marker for " + ui);
			}
		}
		return null;
	}

	public static void removeWarning(Warning warning) {
		Pair<IMarker, IMarker> warningMarkers = iconMarkers.get(warning);
		if (warningMarkers != null) {
			IMarker sourceMarker = warningMarkers.getO1();
			IMarker sinkMarker = warningMarkers.getO2();
			try {
				if (sourceMarker != null)
					sourceMarker.delete();
				if (sinkMarker != null)
					sinkMarker.delete();
			} catch (CoreException e) {
				LOGGER.error("Error in deleting markers for " + warning);
			}
		}
		iconMarkers.remove(warning);
	}

	public static void invalidateWarning(Warning warning) {
		replaceMarkersForWarning(warning, Config.markerSinkIdGray, Config.markerSourceIdGray);
	}

	public static void validateWarning(Warning warning) {
		replaceMarkersForWarning(warning, Config.markerSinkId, Config.markerSourceId);
	}

	private static void replaceMarkersForWarning(Warning warning, String markerIdSink, String markerIdSource) {
		Pair<IMarker, IMarker> warningMarkers = iconMarkers.get(warning);
		if (warningMarkers != null) {
			IMarker sourceMarker = warningMarkers.getO1();
			IMarker sinkMarker = warningMarkers.getO2();

			IMarker newSinkMarker = createMarker(warning.getSink(), markerIdSink,
					"Sink from source: " + warning.getSource().getJava(), IMarker.PRIORITY_HIGH,
					IMarker.SEVERITY_ERROR);
			IMarker newSourceMarker = createMarker(warning.getSource(), markerIdSource,
					"Source to sink: " + warning.getSource().getJava(), IMarker.PRIORITY_HIGH, IMarker.SEVERITY_ERROR);
			iconMarkers.put(warning, new Pair<IMarker, IMarker>(newSourceMarker, newSinkMarker));

			try {
				if (sourceMarker != null)
					sourceMarker.delete();
				if (sinkMarker != null)
					sinkMarker.delete();
			} catch (CoreException e) {
				LOGGER.error("Error in deleting markers for " + warning);
			}
		}
	}

	/***** Highlights *****/

	private static Map<UnitInfo, IMarker> highlightMarkers = new HashMap<UnitInfo, IMarker>();
	private static Warning currentlyHighlightedWarning;

	public static void highlightLines(Warning warning, Set<UnitInfo> highlightUnits) {
		currentlyHighlightedWarning = warning;
		try {
			for (UnitInfo ui : highlightMarkers.keySet()) {
				highlightMarkers.get(ui).delete();
			}
			highlightMarkers = new HashMap<UnitInfo, IMarker>();

			if (highlightUnits != null && !highlightUnits.isEmpty()) {
				for (UnitInfo ui : highlightUnits) {
					final int lineNb = ui.getLine();
					IFile file = ui.getSourceFile();
					final IDocument doc = getCurrentDoc();
					final IRegion region = doc.getLineInformation(lineNb - 1);
					final int charstart = region.getOffset();
					final int charend = charstart + region.getLength();
					IMarker marker = file.createMarker(Config.markerHighlightId);
					marker.setAttribute(IMarker.MESSAGE, "");
					marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
					marker.setAttribute(IMarker.LINE_NUMBER, lineNb);
					marker.setAttribute(IMarker.CHAR_START, charstart);
					marker.setAttribute(IMarker.CHAR_END, charend);
					highlightMarkers.put(ui, marker);
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error in highlighting: " + e.getMessage());
		}
	}

	public static void removeHighlights(Warning warning) {
		if (currentlyHighlightedWarning != null && currentlyHighlightedWarning.equals(warning)) {
			try {
				for (UnitInfo ui : highlightMarkers.keySet())
					highlightMarkers.get(ui).delete();
				highlightMarkers = new HashMap<UnitInfo, IMarker>();
			} catch (CoreException e) {
				LOGGER.error("Error in deleting: " + e.getMessage());
			}
		}
	}

	public static IDocument getCurrentDoc() {
		final IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
				.getActiveEditor();
		if (!(editor instanceof ITextEditor))
			return null;
		ITextEditor ite = (ITextEditor) editor;
		return ite.getDocumentProvider().getDocument(ite.getEditorInput());
	}
}
