package edu.cmu.scs.azurite.ui.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import edu.cmu.scs.azurite.commands.runtime.RuntimeDC;
import edu.cmu.scs.azurite.jface.dialogs.InteractiveSelectiveUndoDialog;
import edu.cmu.scs.azurite.model.FileKey;
import edu.cmu.scs.azurite.model.OperationId;
import edu.cmu.scs.azurite.model.RuntimeHistoryManager;
import edu.cmu.scs.azurite.views.TimelineViewPart;

public class UndoAllFilesToThisPointInteractivelyHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String timestampString = event
				.getParameter("edu.cmu.scs.azurite.ui.commands.undoAllFilesToThisPointInteractively.absTimestamp");
		
		long absTimestamp = Long.parseLong(timestampString);
		
		List<RuntimeDC> dcs = new ArrayList<RuntimeDC>();
		RuntimeHistoryManager manager = RuntimeHistoryManager.getInstance();
		for (FileKey key : manager.getFileKeys()) {
			dcs.addAll(manager.filterDocumentChangesLaterThanOrEqualToTimestamp(key, absTimestamp));
		}
		
		// Extract the ids.
		List<OperationId> ids = OperationId.getOperationIdsFromRuntimeDCs(dcs);
		
		// Send this to the timeline view, if it's available.
		TimelineViewPart timelineViewPart = TimelineViewPart.getInstance();
		if (timelineViewPart != null) {
			timelineViewPart.addSelection(ids, true);
			InteractiveSelectiveUndoDialog.launch();
		} else {
			Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
			MessageDialog.openInformation(
					shell,
					"Azurite - Undo All Files To This Point Interactively",
					"Timeline view is currently not open.\nPlease try again after opening the timeline view.");
		}
		
		return null;
	}

}
