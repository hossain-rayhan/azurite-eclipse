package edu.cmu.scs.azurite.views;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.WorkbenchException;
import org.eclipse.ui.XMLMemento;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.UIJob;
import org.eclipse.ui.texteditor.AbstractTextEditor;

import edu.cmu.scs.azurite.commands.diff.DiffDelete;
import edu.cmu.scs.azurite.commands.diff.DiffInsert;
import edu.cmu.scs.azurite.commands.runtime.RuntimeDC;
import edu.cmu.scs.azurite.jface.action.CommandAction;
import edu.cmu.scs.azurite.model.FileKey;
import edu.cmu.scs.azurite.model.OperationId;
import edu.cmu.scs.azurite.model.RuntimeDCListener;
import edu.cmu.scs.azurite.model.RuntimeHistoryManager;
import edu.cmu.scs.azurite.model.grouper.IChangeInformation;
import edu.cmu.scs.azurite.model.grouper.OperationGrouper;
import edu.cmu.scs.azurite.model.grouper.OperationGrouperListener;
import edu.cmu.scs.azurite.model.undo.SelectiveUndoEngine;
import edu.cmu.scs.azurite.plugin.Activator;
import edu.cmu.scs.azurite.preferences.Initializer;
import edu.cmu.scs.fluorite.commands.ICommand;
import edu.cmu.scs.fluorite.commands.ITimestampOverridable;
import edu.cmu.scs.fluorite.commands.ITypeOverridable;
import edu.cmu.scs.fluorite.commands.document.Delete;
import edu.cmu.scs.fluorite.commands.document.DocChange;
import edu.cmu.scs.fluorite.commands.document.Insert;
import edu.cmu.scs.fluorite.commands.document.Replace;
import edu.cmu.scs.fluorite.model.EventRecorder;
import edu.cmu.scs.fluorite.model.Events;
import edu.cmu.scs.fluorite.util.Utilities;

public class TimelineViewPart extends ViewPart implements RuntimeDCListener, OperationGrouperListener {
	
	private static final String RETURN_CODE_OK = "ok";
	private static final String RETURN_CODE_FAIL = "fail";
	private static final String RETURN_CODE_UNKNOWN = "unknown";
	
	private static final String EXECUTE_JS_CODE_COMMAND_ID = "edu.cmu.scs.azurite.ui.commands.executeJSCode";
	private static final String EXECUTE_JS_CODE_COMMAND_PARAM_ID = "edu.cmu.scs.azurite.ui.commands.executeJSCode.codeToExecute";
	private static String BROWSER_FUNC_PREFIX = "__AZURITE__";
	
	private static final String TIMELINE_VIEW_ID = "edu.cmu.scs.azurite.views.TimelineViewPart";

	private static TimelineViewPart me = null;
	
	private static Map<String, String> timelineEventColorMap;
	
	private static Map<String, String> timelineEventIconMap;
	
	private static Map<String, Boolean> timelineEventDisplayMap;
	
	/**
	 * Not a singleton pattern per se.
	 * This object keeps the reference of itself upon GUI element creation.
	 * Provided just for convenience.
	 * @return The timelineviewpart's object. Could be null, if the view is not shown.
	 */
	public static TimelineViewPart getInstance() {
		return me;
	}
	
	public static void openTimeline() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null) {
			IWorkbenchPage page = window.getActivePage();
			if (page != null) {
				try {
					page.showView(TIMELINE_VIEW_ID);
				} catch (PartInitException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public static void updateTimelineEventColorMap() {
		timelineEventColorMap = new HashMap<String, String>();
		
		if (Activator.getDefault() != null && Activator.getDefault().getPreferenceStore() != null) {
			IPreferenceStore store = Activator.getDefault().getPreferenceStore();
			String str = store.getString(Initializer.Pref_EventDisplaySettings);
			if (str == null) {
				str = store.getDefaultString(Initializer.Pref_EventDisplaySettings);
			}
			
			if (str != null) {
				try (StringReader reader = new StringReader(str)) {
					IMemento root = XMLMemento.createReadRoot(reader);
					for (IMemento child : root.getChildren()) {
						timelineEventColorMap.put(child.getString("type"), child.getString("color"));
					}
				} catch (WorkbenchException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private static Map<String, String> getTimelineEventColorMap() {
		if (timelineEventColorMap == null) {
			updateTimelineEventColorMap();
		}
		
		return Collections.unmodifiableMap(timelineEventColorMap);
	}
	
	public static void updateTimelineEventIconMap() {
		timelineEventIconMap = new HashMap<String, String>();
		
		if (Activator.getDefault() != null && Activator.getDefault().getPreferenceStore() != null) {
			IPreferenceStore store = Activator.getDefault().getPreferenceStore();
			String str = store.getString(Initializer.Pref_EventDisplaySettings);
			if (str == null) {
				str = store.getDefaultString(Initializer.Pref_EventDisplaySettings);
			}
			
			if (str != null) {
				try (StringReader reader = new StringReader(str)) {
					IMemento root = XMLMemento.createReadRoot(reader);
					for (IMemento child : root.getChildren()) {
						timelineEventIconMap.put(child.getString("type"), child.getString("iconPath"));
					}
				} catch (WorkbenchException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private static Map<String, String> getTimelineEventIconMap() {
		if (timelineEventIconMap == null) {
			updateTimelineEventIconMap();
		}
		
		return Collections.unmodifiableMap(timelineEventIconMap);
	}
	
	public static void updateTimelineEventDisplayMap() {
		timelineEventDisplayMap = new HashMap<String, Boolean>();
		
		if (Activator.getDefault() != null && Activator.getDefault().getPreferenceStore() != null) {
			IPreferenceStore store = Activator.getDefault().getPreferenceStore();
			String str = store.getString(Initializer.Pref_EventDisplaySettings);
			if (str == null) {
				str = store.getDefaultString(Initializer.Pref_EventDisplaySettings);
			}
			
			if (str != null) {
				try (StringReader reader = new StringReader(str)) {
					IMemento root = XMLMemento.createReadRoot(reader);
					for (IMemento child : root.getChildren()) {
						timelineEventDisplayMap.put(child.getString("type"), child.getBoolean("enabled"));
					}
				} catch (WorkbenchException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	private static Map<String, Boolean> getTimelineEventDisplayMap() {
		if (timelineEventDisplayMap == null) {
			updateTimelineEventDisplayMap();
		}
		
		return Collections.unmodifiableMap(timelineEventDisplayMap);
	}

	private Browser browser;
	private ListenerList rectSelectionListenerList;
	
	private RectMarkerManager rectMarkerManager;
	
	public TimelineViewPart() {
		super();
		
		this.rectSelectionListenerList = new ListenerList();
		this.rectMarkerManager = new RectMarkerManager();
		addRectSelectionListener(this.rectMarkerManager);
	}
	
	public void addRectSelectionListener(RectSelectionListener listener) {
		this.rectSelectionListenerList.add(listener);
	}
	
	public void removeRectSelectionListener(RectSelectionListener listener) {
		this.rectSelectionListenerList.remove(listener);
	}
	
	public void fireRectSelectionChanged() {
		for (Object listenerObj : this.rectSelectionListenerList.getListeners()) {
			((RectSelectionListener)listenerObj).rectSelectionChanged();
		}
	}
	
	@Override
	public void createPartControl(Composite parent) {
		
		me = this;
		
		browser = new Browser(parent, SWT.NONE);
		addBrowserFunctions();
		moveToIndexPage();
		
		setupContextMenu();

		
		// Register to the EventRecorder.
		RuntimeHistoryManager manager = RuntimeHistoryManager.getInstance();
		manager.addRuntimeDocumentChangeListener(this);
		manager.getOperationGrouper().addOperationGrouperListener(this);
	}

	private void setupContextMenu() {
		// Create the actions.
		final Map<String, String> paramMap = new HashMap<String, String>();
		
		final Action selectiveUndoAction = new CommandAction(
				"Selective Undo",
				"edu.cmu.scs.azurite.ui.commands.selectiveUndoCommand");
		
		ImageDescriptor isuIcon = Activator.getImageDescriptor("icons/undo_in_region.png");
		final Action interactiveSelectiveUndoAction = new CommandAction(
				"Interactive Selective Undo",
				"edu.cmu.scs.azurite.ui.commands.interactiveSelectiveUndoCommand");
		interactiveSelectiveUndoAction.setImageDescriptor(isuIcon);
		
		final Action jumpToTheAffectedCodeAction = new CommandAction(
				"Jump to the Code",
				"edu.cmu.scs.azurite.ui.commands.jumpToTheAffectedCodeCommand");
		
		paramMap.clear();
		paramMap.put(EXECUTE_JS_CODE_COMMAND_PARAM_ID, "removeAllSelections();");
		final Action deselectAllRectanglesAction = new CommandAction(
				"Deselect All Rectangles",
				EXECUTE_JS_CODE_COMMAND_ID,
				paramMap);
		
		paramMap.clear();
		paramMap.put(EXECUTE_JS_CODE_COMMAND_PARAM_ID, "showSelectedFile();");
		final Action showThisFileOnlyAction = new CommandAction(
				"Show This File Only",
				EXECUTE_JS_CODE_COMMAND_ID,
				paramMap);
		
		paramMap.clear();
		paramMap.put(EXECUTE_JS_CODE_COMMAND_PARAM_ID, "showAllFilesInProject();");
		final Action showAllFilesInTheSameProjectAction = new CommandAction(
				"Show All Files in the Same Project",
				EXECUTE_JS_CODE_COMMAND_ID,
				paramMap);
		
		paramMap.clear();
		paramMap.put(EXECUTE_JS_CODE_COMMAND_PARAM_ID, "showAllFiles();");
		final Action showAllFilesAction = new CommandAction(
				"Show All Files",
				EXECUTE_JS_CODE_COMMAND_ID,
				paramMap);
		
		// Setup the dynamic context menu
		MenuManager mgr = new MenuManager();
		mgr.setRemoveAllWhenShown(true);
		
		mgr.addMenuListener(new IMenuListener() {
			
			@Override
			public void menuAboutToShow(IMenuManager manager) {
				try {
					String menuType = browser.evaluate("return cmenu.typeName;").toString();
					
					switch (menuType) {
						case "main_nothing": {
							manager.add(interactiveSelectiveUndoAction);
							break;
						}
						
						case "main_single": {
							manager.add(selectiveUndoAction);
							manager.add(interactiveSelectiveUndoAction);
							manager.add(jumpToTheAffectedCodeAction);
							
							manager.add(new Separator());
							manager.add(deselectAllRectanglesAction);
							break;
						}
							
						case "main_multi": {
							manager.add(selectiveUndoAction);
							manager.add(interactiveSelectiveUndoAction);
							
							manager.add(new Separator());
							manager.add(deselectAllRectanglesAction);
							break;
						}
							
						case "file_in": {
							manager.add(showThisFileOnlyAction);
							manager.add(showAllFilesInTheSameProjectAction);
							manager.add(showAllFilesAction);
							break;
						}
							
						case "file_out": {
							manager.add(showAllFilesAction);
							break;
						}
						
						case "time_range": {
							Action selectAllInside = new CommandAction(
									"Select All Rectangles Inside",
									"edu.cmu.scs.azurite.ui.commands.selectAllInsideCommand");
							
							Action selectAllOutside = new CommandAction(
									"Select All Rectangles Outside",
									"edu.cmu.scs.azurite.ui.commands.selectAllOutsideCommand");
							
							Action deselectAllInside = new CommandAction(
									"Deselect All Rectangles Inside",
									"edu.cmu.scs.azurite.ui.commands.deselectAllInsideCommand");
							
							Action deselectAllOutside = new CommandAction(
									"Deselect All Rectangles Outside",
									"edu.cmu.scs.azurite.ui.commands.deselectAllOutsideCommand");
							
							Action showAllFilesEditedInRange = new CommandAction(
									"Show All Files Edited In Range",
									"edu.cmu.scs.azurite.ui.commands.showFilesInRangeCommand");
							
							Action openAllFilesEditedInRange = new CommandAction(
									"Open All Files Edited In Range",
									"edu.cmu.scs.azurite.ui.commands.openFilesInRangeCommand");
							
							manager.add(selectAllInside);
							manager.add(selectAllOutside);
							manager.add(deselectAllInside);
							manager.add(deselectAllOutside);
							
							manager.add(new Separator());
							manager.add(showAllFilesEditedInRange);
							manager.add(openAllFilesEditedInRange);
							
							break;
						}
						
						case "marker": {
							long absTimestamp = getMarkerTimestamp();
							
							Action tagThisPointAction = new CommandAction(
									"Tag This Point",
									"edu.cmu.scs.azurite.ui.commands.tagMarkerCommand");
							
							manager.add(tagThisPointAction);
							
							paramMap.clear();
							paramMap.put("edu.cmu.scs.azurite.ui.commands.undoAllFilesToThisPoint.absTimestamp", Long.toString(absTimestamp));
							Action undoAllFilesToThisPointAction = new CommandAction(
									"Undo All Files to This Point",
									"edu.cmu.scs.azurite.ui.commands.undoAllFilesToThisPoint",
									paramMap);
							
							manager.add(undoAllFilesToThisPointAction);
							
							paramMap.clear();
							paramMap.put("edu.cmu.scs.azurite.ui.commands.undoAllFilesToThisPointInteractively.absTimestamp", Long.toString(absTimestamp));
							Action undoAllFilesToThisPointInteractivelyAction = new CommandAction(
									"Undo All Files to This Point Interactively",
									"edu.cmu.scs.azurite.ui.commands.undoAllFilesToThisPointInteractively",
									paramMap);
							
							manager.add(undoAllFilesToThisPointInteractivelyAction);

							// Get the active editor, and display the file name.
							IEditorPart activeEditor = Utilities.getActiveEditor();
							String fileName = null;
							if (activeEditor != null && (activeEditor instanceof AbstractTextEditor)) {
								IEditorInput editorInput = activeEditor.getEditorInput();
								if (editorInput instanceof IFileEditorInput) {
									IFileEditorInput fileEditorInput = (IFileEditorInput) editorInput;
									fileName = fileEditorInput.getFile().getName();
								}
							}
							
							if (fileName != null) {
								paramMap.clear();
								paramMap.put("edu.cmu.scs.azurite.ui.commands.undoCurrentFileToThisPoint.absTimestamp", Long.toString(absTimestamp));
								Action undoCurrentFileToThisPointAction = new CommandAction(
										"Undo \"" + fileName + "\" to This Point",
										"edu.cmu.scs.azurite.ui.commands.undoCurrentFileToThisPoint",
										paramMap);
								
								// Determine whether there are operations to be undone.
								if (!RuntimeHistoryManager.getInstance()
										.hasDocumentChangesLaterThanTimestamp(absTimestamp)) {
									undoCurrentFileToThisPointAction.setEnabled(false);
								}
								
								manager.add(undoCurrentFileToThisPointAction);
							} else {
								Action undoCurrentFileToThisPointAction = new Action(
										"Undo Current File to This Point") {};
								undoCurrentFileToThisPointAction.setEnabled(false);
								
								manager.add(undoCurrentFileToThisPointAction);
							}
							
							manager.add(new Separator());
							
							paramMap.clear();
							paramMap.put("edu.cmu.scs.azurite.ui.commands.absTimestamp", Long.toString(absTimestamp));
							Action selectAllAfter = new CommandAction(
									"Select All Rectangles After This Point",
									"edu.cmu.scs.azurite.ui.commands.selectAllAfterCommand",
									paramMap);
							
							Action selectAllBefore = new CommandAction(
									"Select All Rectangles Before This Point",
									"edu.cmu.scs.azurite.ui.commands.selectAllBeforeCommand",
									paramMap);
							
							Action deselectAllAfter = new CommandAction(
									"Deselect All Rectangles After This Point",
									"edu.cmu.scs.azurite.ui.commands.deselectAllAfterCommand",
									paramMap);
							
							Action deselectAllBefore = new CommandAction(
									"Deselect All Rectangles Before This Point",
									"edu.cmu.scs.azurite.ui.commands.deselectAllBeforeCommand",
									paramMap);
							
							manager.add(selectAllAfter);
							manager.add(selectAllBefore);
							manager.add(deselectAllAfter);
							manager.add(deselectAllBefore);
							
							break;
						}
					}
					
					manager.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
				} catch (Exception e) {
					// Do nothing.
				}
			}
		});
		
		browser.setMenu(mgr.createContextMenu(browser));
	}

	private void moveToIndexPage() {
		// Retrieve the full URL of /html/index.html in our project.
		try {
			URL indexUrl = FileLocator.toFileURL(Platform.getBundle(
					"edu.cmu.scs.azurite").getEntry("/html/index.html"));
			browser.setUrl(indexUrl.toString());
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	private void addBrowserFunctions() {
		new UndoFunction(browser, BROWSER_FUNC_PREFIX + "selectiveUndo");
		new InitializeFunction(browser, BROWSER_FUNC_PREFIX + "initialize");
		new JumpFunction(browser, BROWSER_FUNC_PREFIX + "jump");
		new LogFunction(browser, BROWSER_FUNC_PREFIX + "log");
		new GetInfoFunction(browser, BROWSER_FUNC_PREFIX + "getInfo");
		new MarkerMoveFunction(browser, BROWSER_FUNC_PREFIX + "markerMove");
		new OpenAllFilesEditedInRangeFunction(browser, BROWSER_FUNC_PREFIX + "openAllFilesEditedInRange");
		
		new EclipseCommandFunction(browser, BROWSER_FUNC_PREFIX + "eclipseCommand");
		
		new NotifySelectionChangedFunction(browser, BROWSER_FUNC_PREFIX + "notifySelectionChanged");
		
		new EventColorFunction(browser, BROWSER_FUNC_PREFIX + "eventColorFunc");
		new EventIconFunction(browser, BROWSER_FUNC_PREFIX + "eventIconFunc");
		new EventDisplayFunction(browser, BROWSER_FUNC_PREFIX + "eventDisplayFunc");
	}

	@Override
	public void dispose() {
		RuntimeHistoryManager.getInstance().removeRuntimeDocumentChangeListener(this);
		this.rectMarkerManager.removeAllMarkers();
		removeRectSelectionListener(this.rectMarkerManager);
		
		me = null;
		
		super.dispose();
	}

	@Override
	public void setFocus() {
		browser.setFocus();
	}


	class UndoFunction extends BrowserFunction {

		public UndoFunction(Browser browser, String name) {
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments) {
			if (arguments == null || arguments.length != 1 || arguments[0] == null) {
				return RETURN_CODE_FAIL;
			}
			
			try {
				// Convert everything into operation id.
				List<OperationId> ids = translateSelection(arguments[0]);
				
				Map<FileKey, List<RuntimeDC>> params = RuntimeHistoryManager
						.getInstance().extractFileDCMapFromOperationIds(ids);
				
				SelectiveUndoEngine.getInstance()
						.doSelectiveUndoOnMultipleFiles(params);
				
				return RETURN_CODE_OK;
			} catch (Exception e) {
				e.printStackTrace();
				return RETURN_CODE_FAIL;
			}
		}

	}
	
	class InitializeFunction extends BrowserFunction {
		
		public InitializeFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
            
			final long currentTimestamp = EventRecorder.getInstance().getStartTimestamp();
			
            // Read the existing runtime document changes.
			final RuntimeHistoryManager manager = RuntimeHistoryManager.getInstance();
            manager.scheduleTask(new Runnable() {
            	public void run() {
            		Display.getDefault().asyncExec(new Runnable() {
            			public void run() {
                    		for (FileKey key : manager.getFileKeys()) {
                    			if (key.getProjectName() == null || key.getFilePath() == null) {
                    				continue;
                				}
                    			
                    			addFile(key.getProjectName(), key.getFilePath());
                    			for (RuntimeDC dc : manager.getRuntimeDocumentChanges(key)) {
                    				addOperation(dc, false, dc.getOriginal().getSessionId() == currentTimestamp);
                    			}
                    		}
                    		
                    		// TODO figure out the current file and activate that file in the timeline.
                    		
                    		// Add all the events
                    		for (ICommand eventToBeDisplayed : manager.getEventsToBeDisplayed()) {
                    			addEventToTimeline(eventToBeDisplayed);
                    		}
                    		
                    		scrollToEnd();
            			}
            		});
            	}
            });
            
            performLayout();
            
			return RETURN_CODE_OK;
		}
	}
	
	class LogFunction extends BrowserFunction {
		
		public LogFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			System.out.println( arguments[0] );
			
			return RETURN_CODE_OK;
		}
	}
	
	class JumpFunction extends BrowserFunction {

		public JumpFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments == null || arguments.length != 4
					|| !(arguments[0] instanceof String)
					|| !(arguments[1] instanceof String)
					|| !(arguments[2] instanceof Number)
					|| !(arguments[3] instanceof Number)) {
				return RETURN_CODE_FAIL;
			}
			
			try {
				String projectName = (String)arguments[0];
				String filePath = (String)arguments[1];
				FileKey key = new FileKey(projectName, filePath);
				
				long sid = ((Number)arguments[2]).longValue();
				long id = ((Number)arguments[3]).longValue();
				
				IEditorPart editor = edu.cmu.scs.azurite.util.Utilities.openEditorWithKey(key);

				// Move to the location.
				RuntimeDC runtimeDC = RuntimeHistoryManager.getInstance()
						.filterDocumentChangeById(key, new OperationId(sid, id));

				// Cannot retrieve the associated file.
				if (runtimeDC == null) {
					return RETURN_CODE_FAIL;
				}
				
				edu.cmu.scs.azurite.util.Utilities.moveCursorToChangeLocation(editor, runtimeDC);
				
				return RETURN_CODE_OK;
			} catch (Exception e) {
				return RETURN_CODE_FAIL;
			}
		}
	}
	
	class GetInfoFunction extends BrowserFunction {

		public GetInfoFunction(Browser browser, String name) {
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments) {
			if (arguments == null || arguments.length != 5
					|| !(arguments[0] instanceof String)
					|| !(arguments[1] instanceof String)
					|| !(arguments[2] instanceof Number)
					|| !(arguments[3] instanceof Number)
					|| !(arguments[4] instanceof Number)) {
				return RETURN_CODE_FAIL;
			}
			
			try {
				String projectName = (String)arguments[0];
				String filePath = (String)arguments[1];
				FileKey key = new FileKey(projectName, filePath);
				
				long sid = ((Number)arguments[2]).longValue();
				long id = ((Number)arguments[3]).longValue();
				OperationId oid = new OperationId(sid, id);
				
				int level = ((Number)arguments[4]).intValue();
				
				RuntimeDC runtimeDC = RuntimeHistoryManager.getInstance()
						.filterDocumentChangeByIdWithoutCalculating(key, oid);
				
				if (runtimeDC != null) {
					String result = runtimeDC.getHtmlInfo(level);
					if (result != null) {
						return result;
					}
				}
				
				return RETURN_CODE_UNKNOWN;
			} catch (Exception e) {
				return RETURN_CODE_UNKNOWN;
			}
		}
		
	}
	
	class MarkerMoveFunction extends BrowserFunction {
		
		class CodeHistoryViewUpdateRule implements ISchedulingRule {

			@Override
			public boolean contains(ISchedulingRule rule) {
				return (rule instanceof CodeHistoryViewUpdateRule);
			}

			@Override
			public boolean isConflicting(ISchedulingRule rule) {
				return (rule instanceof CodeHistoryViewUpdateRule);
			}
			
		}
		
		class CodeHistoryViewUpdateJob extends UIJob {
			
			static final String JOB_NAME = "Code History View Update";
			
			public CodeHistoryViewUpdateJob(long absTimestamp) {
				super(JOB_NAME);
				this.absTimestamp = absTimestamp;
			}
			
			private long absTimestamp;

			@Override
			public IStatus runInUIThread(IProgressMonitor monitor) {
				for (CodeHistoryDiffViewPart view : CodeHistoryDiffViewPart.getInstances()) {
					view.selectVersionWithAbsTimestamp(this.absTimestamp);
				}

				return Status.OK_STATUS;
			}

			@Override
			public boolean belongsTo(Object family) {
				return JOB_NAME.equals(family);
			}
		}
		
		private CodeHistoryViewUpdateRule rule = new CodeHistoryViewUpdateRule();

		public MarkerMoveFunction(Browser browser, String name) {
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1 || !(arguments[0] instanceof Number)) {
				return RETURN_CODE_FAIL;
			}
			
			final long absTimestamp = ((Number)arguments[0]).longValue();
			
			UIJob job = new CodeHistoryViewUpdateJob(absTimestamp);
			
			// Discard all the waiting jobs in this family.
			for (Job existingJob : Job.getJobManager().find("Code History View Update")) {
				if (existingJob.getState() == Job.WAITING) {
					existingJob.cancel();
				}
			}
			
			// Schedule this new job.
			job.setSystem(true);
			job.setUser(false);
			job.setRule(this.rule);	// avoid running the same type of job concurrently.
			job.schedule();
			
			return RETURN_CODE_OK;
		}
		
	}
	
	class EclipseCommandFunction extends BrowserFunction {

		public EclipseCommandFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1 || !(arguments[0] instanceof String)) {
				return RETURN_CODE_FAIL;
			}
			
			String eclipseCmdId = (String)arguments[0];
			
			IHandlerService handlerService = (IHandlerService) getSite().getService(IHandlerService.class);
			try {
				handlerService.executeCommand(eclipseCmdId, null);
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return RETURN_CODE_OK;
		}
	}
	
	class NotifySelectionChangedFunction extends BrowserFunction {

		public NotifySelectionChangedFunction(Browser browser, String name) {
			super(browser, name);
		}

		@Override
		public Object function(Object[] arguments) {
			try {
				fireRectSelectionChanged();
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return RETURN_CODE_OK;
		}
		
	}
	
	class OpenAllFilesEditedInRangeFunction extends BrowserFunction {
		
		public OpenAllFilesEditedInRangeFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1) {
				return RETURN_CODE_FAIL;
			}
			
			try {
				Object[] filePaths = (Object[]) arguments[0];
				
				for (Object element : filePaths) {
					String filePath = (String) element;
					File fileToOpen = new File(filePath);
					
					if (fileToOpen.exists() && fileToOpen.isFile()) {
					    IFileStore fileStore = EFS.getLocalFileSystem().getStore(fileToOpen.toURI());
					    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
					 
					    try {
					        IDE.openEditorOnFileStore( page, fileStore );
					    } catch ( PartInitException e ) {
					        //Put your exception handler here if you wish to
					    }
					} else {
					    //Do something if the file does not exist
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return RETURN_CODE_OK;
		}
	}
	
	class EventColorFunction extends BrowserFunction {
		
		public EventColorFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1) {
				return "";
			}
			
			Map<String, String> colorMap = TimelineViewPart.getTimelineEventColorMap();
			if (colorMap == null || !colorMap.containsKey(arguments[0])) {
				return "";
			}
			
			return colorMap.get(arguments[0]);
		}
	}
	
	class EventIconFunction extends BrowserFunction {
		
		private static final String ERROR_ICON = "images/event_icons/error.png";
		
		public EventIconFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1) {
				return ERROR_ICON;
			}
			
			Map<String, String> iconMap = TimelineViewPart.getTimelineEventIconMap();
			if (iconMap == null || !iconMap.containsKey(arguments[0])) {
				return ERROR_ICON;
			}
			
			return iconMap.get(arguments[0]);
		}
	}
	
	class EventDisplayFunction extends BrowserFunction {
		
		public EventDisplayFunction(Browser browser, String name) {
			super(browser, name);
		}
		
		@Override
		public Object function(Object[] arguments) {
			if (arguments.length != 1) {
				return "none";
			}
			
			Map<String, Boolean> displayMap = TimelineViewPart.getTimelineEventDisplayMap();
			if (displayMap == null || !displayMap.containsKey(arguments[0])) {
				return "none";
			}
			
			return Boolean.valueOf(displayMap.get(arguments[0])) ? "" : "none";
		}
	}
	
	@Override
	public void activeFileChanged(FileKey fileKey, String snapshot) {
		String projectName = fileKey.getProjectName();
		String filePath = fileKey.getFilePath();
		
		if (projectName == null || filePath == null) {
			// Some non-text file is opened maybe?
			return;
		}
		
		addFile(projectName, filePath);
	}

	@Override
	public void runtimeDCAdded(RuntimeDC docChange) {
		// Do nothing here. Now the blocks are added at real time.
	}
	
	@Override
	public void documentChangeAdded(DocChange docChange) {
		addOperation(docChange, true, true);
	}
	
	public RectMarkerManager getMarkerManager() {
		return this.rectMarkerManager;
	}

	private void addFile(String projectName, String filePath) {
		String executeStr = getAddFileString(projectName, filePath);
		executeJSCode(executeStr);
	}

	private String getAddFileString(String projectName, String filePath) {
		String executeStr = String.format("addFile('%1$s', '%2$s');",
				projectName,
				filePath == null ? "null" : filePath.replace('\\', '/'));	// avoid escaping..
		return executeStr;
	}
	
	private void addEventToTimeline(ICommand event) {
		final String executeStr = getAddEventString(event);
		Display.getDefault().syncExec(new Runnable() {
			public void run() {
				executeJSCode(executeStr);
			}
		});
	}
	
	private String getAddEventString(ICommand event) {
		long sessionId = event.getSessionId();
		long timestamp = event.getTimestamp();
		long displayTimestamp = event instanceof ITimestampOverridable
				? ((ITimestampOverridable) event).getTimestampForDisplay()
				: sessionId + timestamp;

		String type = null;
		if (event instanceof ITypeOverridable) {
			type = ((ITypeOverridable) event).getTypeForDisplay();
		} else {
			type = event.getCommandType();
		}
		
		String executeStr = String.format("addEvent(%1$d, %2$d, %3$d, %4$d, '%5$s', '%6$s');",
				sessionId,
				event.getCommandIndex(),
				timestamp,
				displayTimestamp,
				type,
				event.getDescription());
		
		return executeStr;
	}
	
	private void addOperation(RuntimeDC dc, boolean scroll, boolean current) {
		String executeStr = getAddOperationString(dc, scroll, true, current);
		executeJSCode(executeStr);
	}

	private void addOperation(DocChange docChange, boolean scroll, boolean current) {
		String executeStr = getAddOperationString(docChange, scroll, true, current);
		executeJSCode(executeStr);
	}
	
	private String getAddOperationString(RuntimeDC dc, boolean scroll, boolean layout, boolean current) {
		StringBuilder builder = new StringBuilder();
		
		DocChange docChange = dc.getOriginal();
		
		builder.append("addOperation(");
		builder.append(docChange.getSessionId());
		builder.append(", ");
		builder.append(docChange.getCommandIndex());
		builder.append(", ");
		builder.append(docChange.getTimestamp());
		builder.append(", ");
		builder.append(docChange.getTimestamp2());
		builder.append(", ");
		builder.append(docChange.getY1());
		builder.append(", ");
		builder.append(docChange.getY2());
		builder.append(", ");
		builder.append(getTypeIndex(docChange));
		builder.append(", ");
		builder.append(scroll);
		builder.append(", ");
		builder.append(layout);
		builder.append(", ");
		builder.append(current);
		
		builder.append(", [");
		builder.append(dc.getCollapseID(0));
		for (int i = 1; i < OperationGrouper.NUM_LEVELS; ++i) {
			builder.append(", ");
			builder.append(dc.getCollapseID(i));
		}
		builder.append("]");
		
		builder.append(", ['");
		builder.append(getCollapseType(dc.getChangeInformation(0)));
		for (int i = 1; i < OperationGrouper.NUM_LEVELS; ++i) {
			builder.append("', '");
			builder.append(getCollapseType(dc.getChangeInformation(i)));
		}
		builder.append("']");
		
		builder.append(");");
		
		return builder.toString();
	}

	private String getAddOperationString(DocChange docChange, boolean scroll, boolean layout, boolean current) {
		String executeStr = String.format("addOperation(%d, %d, %d, %d, %f, %f, %d, %s, %s, %s);",
				docChange.getSessionId(),
				docChange.getCommandIndex(),
				docChange.getTimestamp(),
				docChange.getTimestamp2(),
				docChange.getY1(),
				docChange.getY2(),
				getTypeIndex(docChange),
				Boolean.toString(scroll),
				Boolean.toString(layout),
				Boolean.toString(current));
		
		return executeStr;
	}
	
	private int getTypeIndex(DocChange docChange) {
		if (docChange instanceof DiffInsert) {
			return 10;
		} else if (docChange instanceof DiffDelete) {
			return 11;
		} else if (docChange instanceof Insert) { 
			return 0;
		} else if (docChange instanceof Delete) {
			return 1;
		} else if (docChange instanceof Replace) {
			return 2;
		} else {
			return -1;
		}
	}

	@Override
	public void documentChangeUpdated(DocChange docChange) {
		String executeStr = String.format(
				"updateOperation(%1$d, %2$d, %3$d, %4$f, %5$f, %6$d, true);",
				docChange.getSessionId(),
				docChange.getCommandIndex(),
				docChange.getTimestamp2(),
				docChange.getY1(),
				docChange.getY2(),
				getTypeIndex(docChange));
		executeJSCode(executeStr);
	}
	
	@Override
	public void documentChangeAmended(DocChange oldDocChange, DocChange newDocChange) {
		String executeStr = String.format(
				"updateOperation(%1$d, %2$d, %3$d, %4$f, %5$f, %6$d, true);",
				oldDocChange.getSessionId(),
				oldDocChange.getCommandIndex(),
				newDocChange.getTimestamp2(),
				newDocChange.getY1(),
				newDocChange.getY2(),
				getTypeIndex(newDocChange));
		executeJSCode(executeStr);
	}

	/**
	 * Add selections to the timeline view. Must be called from the SWT EDT.
	 * @param ids list of ids to be selected
	 * @param clearSelection indicates whether the existing selections should be discarded before adding new selections.
	 */
	public void addSelection(List<OperationId> ids, boolean clearSelection) {
		StringBuffer buffer = new StringBuffer();
		buffer.append("addSelectionsByIds(");
		
		getJavaScriptListFromOperationIds(buffer, ids);
		
		buffer.append(", " + Boolean.toString(clearSelection) + ");");
		
		executeJSCode(buffer.toString());
	}
	
	public void removeSelection(List<OperationId> ids) {
		StringBuffer buffer = new StringBuffer();
		buffer.append("removeSelectionsByIds(");
		
		getJavaScriptListFromOperationIds(buffer, ids);
		
		buffer.append(");");
		
		executeJSCode(buffer.toString());
	}

	private void getJavaScriptListFromOperationIds(StringBuffer buffer,
			List<OperationId> ids) {
		buffer.append("[");
		Iterator<OperationId> it;
		
		it = ids.iterator();
		if (it.hasNext()) {
			buffer.append(Long.toString(it.next().sid));
			while (it.hasNext()) {
				buffer.append(", " + it.next().sid);
			}
		}
		
		buffer.append("], [");
		
		it = ids.iterator();
		if (it.hasNext()) {
			buffer.append(Long.toString(it.next().id));
			while (it.hasNext()) {
				buffer.append(", " + it.next().id);
			}
		}
		
		buffer.append("]");
	}
	
	public void activateFirebugLite() {
		executeJSCode("activateFirebugLite();");
	}

	@Override
	public void pastLogsRead(List<Events> listEvents) {
		refresh();
		
		// Update the data.
		performLayout();
	}
	
	@Override
	public void codingEventOccurred(ICommand command) {
		addEventToTimeline(command);
	}
	
	private void scrollToEnd() {
		executeJSCode("scrollToEnd();");
	}
	
	public void executeJSCode(String codeToExecute) {
//		System.out.println(codeToExecute);
		browser.execute(codeToExecute);
	}
	
	public Object evaluateJSCode(String codeToExecute) {
		return browser.evaluate(codeToExecute);
	}
	
	public void refresh() {
		moveToIndexPage();
	}
	
	public void showMarkerAtTimestamp(long absTimestamp) {
		executeJSCode("showMarkerAtTimestamp(" + absTimestamp + ");");
	}
	
	public void hideMarker() {
		executeJSCode("hideMarker();");
	}
	
	public int getSelectedRectsCount() {
		try {
			Object result = evaluateJSCode("return global.selectedRects.length;");
			if (result instanceof Number) {
				return ((Number) result).intValue();
			} else {
				return 0;
			}
		} catch (SWTException e) {
			return 0;
		}
	}
	
	public int getCurrentCollapseLevel() {
		try {
			Object result = evaluateJSCode("return global.collapseLevel;");
			if (result instanceof Number) {
				return ((Number) result).intValue();
			} else {
				return -1;
			}
		} catch (SWTException e) {
			return -1;
		}
	}
	
	public List<OperationId> getRectSelection() {
		Object selected = evaluateJSCode("return getStandardRectSelection();");
		return translateSelection(selected);
	}
	
	public boolean isRangeSelected() {
		try {
			Object result = evaluateJSCode("return global.selectedTimestampRange !== null;");
			if (result instanceof Boolean) {
				return ((Boolean) result).booleanValue();
			} else {
				return false;
			}
		} catch (SWTException e) {
			return false;
		}
	}
	
	public boolean isMarkerVisible() {
		try {
			Object result = evaluateJSCode("return isMarkerVisible();");
			if (result instanceof Boolean) {
				return ((Boolean) result).booleanValue();
			} else {
				return false;
			}
		} catch (SWTException e) {
			return false;
		}
	}

	public static List<OperationId> translateSelection(Object selected) {
		Object[] selectedArray = (Object[]) selected;
		
		List<OperationId> ids = new ArrayList<OperationId>();
		for (Object element : selectedArray) {
			if (!(element instanceof Object[])) {
				continue;
			}
			
			Object[] idComponents = (Object[])element;
			if (idComponents.length == 2 &&
					idComponents[0] instanceof Number &&
					idComponents[1] instanceof Number) {
				Number sid = (Number)idComponents[0];
				Number id = (Number)idComponents[1];
				
				ids.add(new OperationId(sid.longValue(), id.longValue()));
			}
		}
		return ids;
	}
	
	private void performLayout() {
		executeJSCode("layout();");
	}
	
	public long getMarkerTimestamp() {
		return ((Number) browser.evaluate("return global.markerTimestamp;")).longValue();
	}

	public long getTimeRangeStart() {
		return ((Number) browser.evaluate("return global.selectedTimestampRange[0];")).longValue();
	}

	public long getTimeRangeEnd() {
		return ((Number) browser.evaluate("return global.selectedTimestampRange[1];")).longValue();
	}
	
	public void redrawEvents() {
		executeJSCode("updateEvents();");
	}

	@Override
	public void collapseIDsUpdated(List<RuntimeDC> dcs, int level, int collapseID, IChangeInformation changeInformation) {
		if (dcs == null || dcs.isEmpty()) { return; }
		
		long sid = dcs.get(0).getOriginal().getSessionId();
		String path = dcs.get(0).getBelongsTo().getFilePath();
		if (path == null) { path = "null"; }
		else { path = path.replace('\\', '/'); }
		
		String collapseType = getCollapseType(changeInformation);
		
		StringBuilder idList = new StringBuilder();
		idList.append("[");
		idList.append(dcs.get(0).getOriginal().getCommandIndex());
		for (int i = 1; i < dcs.size(); ++i) {
			idList.append(",");
			idList.append(dcs.get(i).getOriginal().getCommandIndex());
		}
		idList.append("]");
		
		String executeStr = String.format("updateCollapseIds(%d, '%s', %d, %d, '%s', %s);",
				sid, path, level, collapseID, collapseType, idList.toString());
		executeJSCode(executeStr);
	}

	private String getCollapseType(IChangeInformation changeInformation) {
		return changeInformation == null ? "type_unknown"
				: "type_" + changeInformation.getChangeKind().toString().toLowerCase();
	}
	
}
