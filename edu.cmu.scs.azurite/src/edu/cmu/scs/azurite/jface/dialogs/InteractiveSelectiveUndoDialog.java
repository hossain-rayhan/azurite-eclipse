package edu.cmu.scs.azurite.jface.dialogs;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.CompareViewerSwitchingPane;
import org.eclipse.compare.contentmergeviewer.TextMergeViewer;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.UIJob;

import edu.cmu.scs.azurite.commands.runtime.RuntimeDC;
import edu.cmu.scs.azurite.compare.AzuriteCompareInput;
import edu.cmu.scs.azurite.compare.AzuriteCompareLabelProvider;
import edu.cmu.scs.azurite.compare.DocumentRangeCompareItem;
import edu.cmu.scs.azurite.jface.widgets.AlternativeButton;
import edu.cmu.scs.azurite.model.FileKey;
import edu.cmu.scs.azurite.model.OperationId;
import edu.cmu.scs.azurite.model.RuntimeHistoryManager;
import edu.cmu.scs.azurite.model.undo.Chunk;
import edu.cmu.scs.azurite.model.undo.SelectiveUndoEngine;
import edu.cmu.scs.azurite.model.undo.SelectiveUndoParams;
import edu.cmu.scs.azurite.model.undo.UndoAlternative;
import edu.cmu.scs.azurite.plugin.Activator;
import edu.cmu.scs.azurite.preferences.Initializer;
import edu.cmu.scs.azurite.views.RectSelectionListener;
import edu.cmu.scs.azurite.views.TimelineViewPart;

public class InteractiveSelectiveUndoDialog extends TitleAreaDialog implements RectSelectionListener {
	
	private static final int DEFAULT_WIDTH = 800;
	private static final int DEFAULT_HEIGHT = 600;
	
	private static final int MARGIN_WIDTH = 10;
	private static final int MARGIN_HEIGHT = 10;
	
	private static final int SPACING = 10;
	
	private static final int SURROUNDING_CONTEXT_SIZE = 3;
	
	private static final String TEXT = "Azurite - Interactive Selective Undo";
	private static final String TITLE = "Interactive Selective Undo";
	private static final String DEFAULT_MESSAGE = "The preview will be updated as you select/deselect rectangles from the timeline.";
	
	private static final String CHUNKS_TITLE = "Changes to be performed";
	private static final String ALTERNATIVES_TITLE = "Choose one of the alternatives below to resolve the conflict.";
	
	protected static final String NEXT_CHANGE_ID = "edu.cmu.scs.azurite.nextChange";
	protected static final String NEXT_CHANGE_TOOLTOP = "Select Next Chunk";
	
	protected static final String PREVIOUS_CHANGE_ID = "edu.cmu.scs.azurite.previousChange";
	protected static final String PREVIOUS_CHANGE_TOOLTIP = "Select Previous Chunk";
	
	private static final String INFORMATION_SELECT_RECTS = "You must select some rectangles to undo.";
	private static final String INFORMATION_SELECT_CHUNK = "Select a chunk from the list on the top to see the preview.";
	
	private static final String GENERATING_PREVIEW_ERROR_MSG = "Error occurred while generating the preview.";
	
	private static InteractiveSelectiveUndoDialog instance = null;
	public static InteractiveSelectiveUndoDialog getInstance() {
		return instance;
	}
	
	public static void launch() {
		if (getInstance() != null) {
			getInstance().getShell().forceActive();
		} else {
			final Shell parentShell = Display.getDefault().getActiveShell();
			InteractiveSelectiveUndoDialog isuDialog = new InteractiveSelectiveUndoDialog(parentShell);
			isuDialog.create();
			isuDialog.open();
		}
	}
	
	private final class TreeViewerMenuListener implements IMenuListener {
		@Override
		public void menuAboutToShow(IMenuManager manager) {
			IStructuredSelection treeSel = (IStructuredSelection) mChunksTreeViewer.getSelection();
			if (treeSel != null && treeSel.size() > 0) {
				final Object selElem = treeSel.getFirstElement();
				if (selElem instanceof TopLevelElement) {
					manager.add(new Action("Remove this file from the selection (Keep it unchanged)") {
						@Override
						public void run() {
							TopLevelElement topElem = (TopLevelElement) selElem;
							List<RuntimeDC> runtimeDCs = new ArrayList<RuntimeDC>();
							
							for (Chunk chunk : topElem.getChunks()) {
								runtimeDCs.addAll(chunk.getInvolvedChanges());
							}
							
							// Get the operation ids.
							List<OperationId> ids = OperationId.getOperationIdsFromRuntimeDCs(runtimeDCs);
							
							// Tell the timeline to REMOVE these from the selection.
							TimelineViewPart timeline = TimelineViewPart.getInstance();
							if (timeline != null) {
								timeline.removeSelection(ids);
							}
						}
					});
				} else if (selElem instanceof ChunkLevelElement) {
					manager.add(new Action("Remove this chunk from the selection (Keep it unchanged)") {
						@Override
						public void run() {
							ChunkLevelElement chunkElem = (ChunkLevelElement) selElem;
							List<RuntimeDC> runtimeDCs = chunkElem.getChunk().getInvolvedChanges();
							
							// Get the operation ids.
							List<OperationId> ids = OperationId.getOperationIdsFromRuntimeDCs(runtimeDCs);
							
							// Tell the timeline to REMOVE these from the selection.
							TimelineViewPart timeline = TimelineViewPart.getInstance();
							if (timeline != null) {
								timeline.removeSelection(ids);
							}
						}
					});
				}
			}
		}
	}

	private class KeepSelectedCodeUnchanged extends Action {
		private final ITextSelection sel;

		private KeepSelectedCodeUnchanged(String text, ITextSelection sel) {
			super(text);
			this.sel = sel;
		}

		@Override
		public void run() {
			// Determine the currently shown file.
			try {
				IStructuredSelection treeSelection = (IStructuredSelection) mChunksTreeViewer.getSelection();
				Object treeSelElem = treeSelection.getFirstElement();
				
				FileKey key = null;
				if (treeSelElem instanceof TopLevelElement) {
					key = ((TopLevelElement) treeSelElem).getFileKey();
				} else if (treeSelElem instanceof ChunkLevelElement) {
					key = ((ChunkLevelElement) treeSelElem).getParent().getFileKey();
				}
				
				// Get the runtime dcs.
				List<RuntimeDC> runtimeDCs = RuntimeHistoryManager.getInstance()
						.filterDocumentChangesByRegion(
								key,
								sel.getOffset(),
								sel.getOffset() + sel.getLength());
				
				// Get the operation ids.
				List<OperationId> ids = OperationId.getOperationIdsFromRuntimeDCs(runtimeDCs);
				
				// Tell the timeline to REMOVE these from the selection.
				TimelineViewPart timeline = TimelineViewPart.getInstance();
				if (timeline != null) {
					timeline.removeSelection(ids);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private class ChunksTreeViewer extends TreeViewer {
	
		public ChunksTreeViewer(Composite parent) {
			super(parent);
		}
	
		public ChunksTreeViewer(Composite parent, int style) {
			super(parent, style);
		}
	
		public ChunksTreeViewer(Tree tree) {
			super(tree);
		}
	
		public void revealNext() {
			revealElement(true);
		}
	
		public void revealPrevious() {
			revealElement(false);
		}
	
		private void revealElement(boolean next) {
			TopLevelElement[] topElements = (TopLevelElement[]) getInput();
			topElements = Arrays.copyOf(topElements, topElements.length);
			Arrays.sort(topElements, new Comparator<TopLevelElement>() {
				@Override
				public int compare(TopLevelElement lhs, TopLevelElement rhs) {
					return getComparator().compare(ChunksTreeViewer.this, lhs, rhs);
				}
			});
			
			Object current = null;
			Object candidate = null;
			
			IStructuredSelection selection = (IStructuredSelection) getSelection();
			if (!selection.isEmpty()) {
				current = selection.iterator().next();
			}
			
			if (current instanceof TopLevelElement) {
				candidate = getCandidateFromTopLevelElement(
						(TopLevelElement) current, next, topElements);
			} else if (current instanceof ChunkLevelElement) {
				candidate = getCandidateFromChunkLevelElement(
						(ChunkLevelElement) current, next, topElements);
			} else {
				// If there are some elements at least..
				if (topElements.length > 0) {
					if (next) {
						// Select the first element
						candidate = topElements[0];
					} else {
						candidate = topElements[topElements.length - 1];
					}
				} else {
					candidate = null;
				}
			}
			
			if (candidate != null) {
				setSelection(new StructuredSelection(candidate), true);
			}
			else {
				getControl().getDisplay().beep();
			}
		}

		private Object getCandidateFromChunkLevelElement(ChunkLevelElement chunkElem,
				boolean next, TopLevelElement[] topElements) {
			Object candidate;
			TopLevelElement parentElem = chunkElem.getParent();
			
			int curChunkIndex = parentElem.getChunkElements().indexOf(chunkElem);
			int curParentIndex = Arrays.asList(topElements).indexOf(parentElem);
			
			if (next) {
				if (curChunkIndex < parentElem.getChunkElements().size() - 1) {
					candidate = parentElem.getChunkElements().get(curChunkIndex + 1);
				} else {
					// Find the next top level element.
					if (curParentIndex < topElements.length - 1) {
						candidate = topElements[curParentIndex + 1];
					} else {
						candidate = null;
					}
				}
			} else {
				if (curChunkIndex > 0) {
					candidate = parentElem.getChunkElements().get(curChunkIndex - 1);
				} else {
					// Select my own parent.
					candidate = parentElem;
				}
			}
			return candidate;
		}

		private Object getCandidateFromTopLevelElement(TopLevelElement topElem,
				boolean next, TopLevelElement[] topElements) {
			Object candidate;
			int curIndex = Arrays.asList(topElements).indexOf(topElem);
			
			if (next) {
				if (mShowChunks) {
					// There must be some children here.
					candidate = topElem.getChunkElements().get(0);
				} else {
					if (curIndex < topElements.length - 1) {
						candidate = topElements[curIndex + 1];
					} else {
						candidate = null;
					}
				}
			} else {
				if (mShowChunks) {
					// Find the last chunk of the previous top level element.
					if (curIndex > 0) {
						TopLevelElement prevTopElem = topElements[curIndex - 1];
						candidate = prevTopElem.getChunkElements().get(prevTopElem.getChunkElements().size() - 1);
					} else {
						candidate = null;
					}
				} else {
					if (curIndex > 0) {
						candidate = topElements[curIndex - 1];
					} else {
						candidate = null;
					}
				}
			}
			
			return candidate;
		}
	}

	private class NextChunk extends Action {
		public NextChunk() {
			setId(NEXT_CHANGE_ID);
			setImageDescriptor(CompareUI.DESC_ETOOL_NEXT);
			setDisabledImageDescriptor(CompareUI.DESC_DTOOL_NEXT);
			setHoverImageDescriptor(CompareUI.DESC_CTOOL_NEXT);
			setToolTipText(NEXT_CHANGE_TOOLTOP);
		}
		public void run() {
			mChunksTreeViewer.revealNext();
		}
	}

	private class PreviousChunk extends Action {
		public PreviousChunk() {
			setId(PREVIOUS_CHANGE_ID);
			setImageDescriptor(CompareUI.DESC_ETOOL_PREV);
			setDisabledImageDescriptor(CompareUI.DESC_DTOOL_PREV);
			setHoverImageDescriptor(CompareUI.DESC_CTOOL_PREV);
			setToolTipText(PREVIOUS_CHANGE_TOOLTIP);
		}
		public void run() {
			mChunksTreeViewer.revealPrevious();
		}
	}
	
	private class TopLevelElement {
		private FileKey mFileKey;
		private List<ChunkLevelElement> mChunkElements;
		
		public TopLevelElement(FileKey fileKey, List<Chunk> chunks) {
			if (fileKey == null || chunks == null) {
				throw new IllegalArgumentException();
			}
			
			mFileKey = fileKey;
			
			mChunkElements = new ArrayList<ChunkLevelElement>();
			for (Chunk chunk : chunks) {
				mChunkElements.add(new ChunkLevelElement(chunk, this));
			}
		}
		
		public FileKey getFileKey() {
			return mFileKey;
		}
		
		public List<ChunkLevelElement> getChunkElements() {
			return Collections.unmodifiableList(mChunkElements);
		}
		
		public List<Chunk> getChunks() {
			List<Chunk> result = new ArrayList<Chunk>();
			for (ChunkLevelElement chunkElem : getChunkElements()) {
				result.add(chunkElem.getChunk());
			}
			
			return result;
		}

		public Map<Chunk, UndoAlternative> getAlternativeChoiceMap() {
			List<ChunkLevelElement> chunkElems = getChunkElements();
			Map<Chunk, UndoAlternative> chosenAlternatives =
					new HashMap<Chunk, UndoAlternative>();
			for (ChunkLevelElement chunkElem : chunkElems) {
				Chunk chunk = chunkElem.getChunk();
				
				if (chunk.hasConflictOutsideThisChunk()) {
					chosenAlternatives.put(chunk, chunkElem.getChosenAlternative());
				}
			}
			return chosenAlternatives;
		}
		
		public SelectiveUndoParams getSelectiveUndoParams() {
			return new SelectiveUndoParams(getChunks(), edu.cmu.scs.azurite.util.Utilities.findDocumentForKey(getFileKey()), getAlternativeChoiceMap());
		}
		
		public boolean hasUnresolvedConflict() {
			for (ChunkLevelElement chunkElem : mChunkElements) {
				if (chunkElem.hasUnresolvedConflict()) {
					return true;
				}
			}
			
			return false;
		}
	}
	
	private class ChunkLevelElement {
		private Chunk mChunk;
		
		private List<UndoAlternative> mAlternatives;
		private UndoAlternative mChosenAlternative;
		
		private TopLevelElement mParent;
		
		public ChunkLevelElement(Chunk chunk, TopLevelElement parent) {
			if (chunk == null || parent == null) {
				throw new IllegalArgumentException();
			}
			
			mChunk = chunk;
			
			mParent = parent;
			
			calculateUndoAlternatives();
		}
		
		private void calculateUndoAlternatives() {
			if (getChunk().hasConflictOutsideThisChunk()) {
				try {
					IDocument doc = findDocumentForChunk(getChunk());
					Chunk expandedChunk = getChunk().getExpandedChunkWithDepth(SelectiveUndoEngine.MAX_EXPANSION_DEPTH);
					int initialOffset = expandedChunk.getStartOffset();
					String initialContent = doc.get(initialOffset, expandedChunk.getChunkLength());
					
					mAlternatives = SelectiveUndoEngine.getInstance().doSelectiveUndoChunkWithConflicts(getChunk(), initialContent);
					if (mAlternatives.size() == 1) {
						mChosenAlternative = mAlternatives.get(0);
					} else {
						mChosenAlternative = null;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		public Chunk getChunk() {
			return mChunk;
		}
		
		public TopLevelElement getParent() {
			return mParent;
		}
		
		public boolean hasUnresolvedConflict() {
			return mChunk.hasConflictOutsideThisChunk() && mChosenAlternative == null;
		}
		
		public List<UndoAlternative> getUndoAlternatives() {
			return mAlternatives;
		}
		
		public UndoAlternative getChosenAlternative() {
			return mChosenAlternative;
		}
		
		public void setChosenAlternative(UndoAlternative chosenAlternative) {
			mChosenAlternative = chosenAlternative;
		}
	}
	
	private class ChunksContentProvider implements ITreeContentProvider {

		@Override
		public void dispose() {
			// Do nothing.
		}

		@Override
		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
			// Do nothing.
		}

		// Assume that inputElement is typed as Map<FileKey, List<Chunk>>
		@Override
		public Object[] getElements(Object inputElement) {
			if (inputElement instanceof Object[]) {
				return (Object[]) inputElement; 
			} else {
				return null;
			}
		}

		@Override
		public Object[] getChildren(Object parentElement) {
			if (mShowChunks) {
				if (parentElement instanceof TopLevelElement) {
					TopLevelElement topElem = (TopLevelElement) parentElement;
					return topElem.getChunkElements().toArray();
				}
			}
			
			return null;
		}

		@Override
		public Object getParent(Object element) {
			if (element instanceof ChunkLevelElement) {
				ChunkLevelElement chunkElem = (ChunkLevelElement) element;
				return chunkElem.getParent();
			}
			
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			if (element instanceof TopLevelElement) {
				TopLevelElement topElem = (TopLevelElement) element;
				return !topElem.getChunkElements().isEmpty();
			}
			
			return false;
		}
		
	}
	
	private class ChunksLabelProvider extends LabelProvider {
		
		private Map<ImageDescriptor, Image> mImages;
		
		public ChunksLabelProvider() {
			mImages = new HashMap<ImageDescriptor, Image>();
		}

		@Override
		public Image getImage(Object element) {
			if (element instanceof TopLevelElement) {
				TopLevelElement topElem = (TopLevelElement) element;
				FileKey fileKey = topElem.getFileKey();
				
				ImageDescriptor baseImageDesc = PlatformUI.getWorkbench().getEditorRegistry().getImageDescriptor(fileKey.getFileNameOnly());
				Image baseImage = getImage(baseImageDesc);

				if (topElem.hasUnresolvedConflict()) {
					ImageDescriptor decError = PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_DEC_FIELD_ERROR);
					DecorationOverlayIcon decorated = new DecorationOverlayIcon(baseImage, decError, IDecoration.BOTTOM_RIGHT);
					
					return getImage(decorated);
				} else {
					return baseImage;
				}
			} else if (element instanceof ChunkLevelElement) {
				ChunkLevelElement chunkElem = (ChunkLevelElement) element;
				
				ImageDescriptor errorImage = PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_OBJS_ERROR_TSK);
				ImageDescriptor resolvedImage = Activator.getImageDescriptor("icons/tick.png");
				return chunkElem.hasUnresolvedConflict() ? getImage(errorImage)
						: chunkElem.getChunk().hasConflictOutsideThisChunk() && chunkElem.getUndoAlternatives().size() > 1 ? getImage(resolvedImage)
						: null;
			} else {
				return null;
			}
		}

		@Override
		public String getText(Object element) {
			if (element instanceof TopLevelElement) {
				TopLevelElement topElem = (TopLevelElement) element;
				return topElem.getFileKey().getFileNameOnly();
			} else if (element instanceof ChunkLevelElement) {
				ChunkLevelElement chunkElem = (ChunkLevelElement) element;
				Chunk chunk = chunkElem.getChunk();
				IDocument doc = findDocumentForChunk(chunk);
				
				String label = getLabelForChunk(chunk, doc);
				
				if (chunkElem.getChunk().hasConflictOutsideThisChunk() && chunkElem.getUndoAlternatives().size() == 1) {
					label += " [no effect]";
				}
				
				return label;
			} else {
				return super.getText(element);
			}
		}
		
		@Override
		public void dispose() {
			super.dispose();
			
			for (Image image : mImages.values()) {
				image.dispose();
			}
		}
		
		private Image getImage(ImageDescriptor imgDesc) {
			if (!mImages.containsKey(imgDesc)) {
				Image image = imgDesc.createImage();
				mImages.put(imgDesc, image);
			}
			
			return mImages.get(imgDesc);
		}
		
	}
	
	// Indicates whether to show the individual chunk-level elements or not.
	// Retrieve this preference setting when the dialog is first launched,
	// and then use that until this dialog is closed.
	// (i.e., in order to change the setting, the user has to relaunch the ISUD.)
	private boolean mShowChunks;
	
	// Menu
	private MenuManager mLeftSourceViewerMenuMgr;
	// ----------------------------------------
	
	// Sash Forms
	private SashForm mBottomSash;
	// ----------------------------------------
	
	// For the top part
	private ChunksTreeViewer mChunksTreeViewer;
	// ----------------------------------------
	
	private Composite mBottomArea;
	private StackLayout mBottomStackLayout;	// the StackLayout object used for panel switching in the bottom area.
	
	// For Preview Panel ---------------
	private Composite mConflictResolutionArea;
	
	private CompareViewerSwitchingPane mPreviewPane;
	private CompareConfiguration mCompareConfiguration;
	private String mCompareTitle;
	
	private SourceViewer mLeftSourceViewer;
	// ----------------------------------------
	
	// For Conflict Resolution Panel ----------
	@SuppressWarnings("restriction")
	private org.eclipse.jdt.internal.ui.util.ViewerPane mConflictResolutionPane;
	// ----------------------------------------
	
	// For Information Panel ------------------
	private Label mInformationLabel;
	// ----------------------------------------

	public InteractiveSelectiveUndoDialog(Shell parent) {
		super(parent);
		
		// Make the dialog modeless.
		setShellStyle(SWT.CLOSE | SWT.MODELESS | SWT.BORDER | SWT.TITLE | SWT.RESIZE);
		setBlockOnOpen(false);
		
		setHelpAvailable(false);
		
		// Create the default compare configuration.
		mCompareConfiguration = createCompareConfiguration();
		
		mShowChunks = Activator.getDefault().getPreferenceStore()
				.getBoolean(Initializer.Pref_InteractiveSelectiveUndoShowChunks);
	}

	private CompareConfiguration createCompareConfiguration() {
		CompareConfiguration configuration = new CompareConfiguration();
		configuration.setDefaultLabelProvider(new AzuriteCompareLabelProvider());
		return configuration;
	}

	@Override
	public void create() {
		super.create();
		
		instance = this;
		
		getShell().setText(TEXT);
		
		setTitle(TITLE);
		setMessage(DEFAULT_MESSAGE);
		
		getShell().setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		
		// Register myself to the timeline.
		TimelineViewPart.getInstance().addRectSelectionListener(this);
	}

	@Override
	protected void handleShellCloseEvent() {
		cleanup();
		
		super.handleShellCloseEvent();
	}

	@Override
	protected void cancelPressed() {
		cleanup();
		
		super.cancelPressed();
	}

	@Override
	protected void okPressed() {
		// Cleanup the listeners
		cleanup();
		
		// Actually perform the selective undo
		TopLevelElement[] topElems = (TopLevelElement[]) mChunksTreeViewer.getInput();
		Map<FileKey, SelectiveUndoParams> paramsMap = new HashMap<FileKey, SelectiveUndoParams>();
		for (TopLevelElement topElem : topElems) {
			paramsMap.put(topElem.getFileKey(), topElem.getSelectiveUndoParams());
		}
		
		SelectiveUndoEngine.getInstance().doSelectiveUndoOnMultipleFilesWithChoices(paramsMap);
		
		// Call the original method so that the dialog is properly disposed.
		super.okPressed();
	}

	private void cleanup() {
		// Deregister myself from the timeline.
		TimelineViewPart.getInstance().removeRectSelectionListener(this);
		
		instance = null;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite composite = createMainArea(parent);
		
		// Create the top sash.
		SashForm topSash = new SashForm(composite, SWT.VERTICAL);
		
		// Chunks tree on the top.
		createChunksTreeViewer(topSash);
		
		// Bottom Area. Use StackLayout to switch between panels.
		createBottomArea(topSash);
		
		topSash.setSashWidth(SPACING);
		topSash.setWeights(new int[] { 1, 3 });
		
		// Setup the menu
		createMenuBar();
		
		// Set the initial input
		setChunksTreeViewerInput();
		
		return composite;
	}

	@Override
	protected Control createContents(Composite parent) {
		Control contents = super.createContents(parent);
		
		updateOKButtonEnabled();
		
		return contents;
	}

	private void createMenuBar() {
		Menu menuBar = new Menu(getShell(), SWT.BAR);
		
		final MenuItem testMenu = new MenuItem(menuBar, SWT.CASCADE);
		testMenu.setText("Test");
		
		// Uncomment the following line to restore the menu bar.
//		getShell().setMenuBar(mMenuBar);
		
		// Setup the menu manager for the left source viewer.
		mLeftSourceViewerMenuMgr = new MenuManager();
		mLeftSourceViewerMenuMgr.setRemoveAllWhenShown(true);
		mLeftSourceViewerMenuMgr.addMenuListener(new IMenuListener() {
			@Override
			public void menuAboutToShow(IMenuManager manager) {
				if (mLeftSourceViewer != null) {
					final ITextSelection sel = (ITextSelection) mLeftSourceViewer.getSelection();
					if (sel.getLength() > 0) {
						manager.add(new KeepSelectedCodeUnchanged("Keep this code unchanged", sel));
					}
				}
			}
		});
		
		// Setup the menu manager for the chunks tree viewer.
		MenuManager treeViewerMenuMgr = new MenuManager();
		treeViewerMenuMgr.setRemoveAllWhenShown(true);
		treeViewerMenuMgr.addMenuListener(new TreeViewerMenuListener());
		
		Control treeControl = mChunksTreeViewer.getControl();
		treeControl.setMenu(treeViewerMenuMgr.createContextMenu(treeControl));
	}

	private Composite createMainArea(Composite parent) {
		Composite composite = new Composite(parent, SWT.NONE);
		
		// Use SashForm.
		FillLayout fillLayout = new FillLayout();
		fillLayout.marginWidth = MARGIN_WIDTH;
		fillLayout.marginHeight = MARGIN_HEIGHT;
		
		composite.setLayout(fillLayout);
		composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		
		return composite;
	}

	@SuppressWarnings("restriction")
	private void createChunksTreeViewer(Composite parent) {
		org.eclipse.jdt.internal.ui.util.ViewerPane chunksPane =
				new org.eclipse.jdt.internal.ui.util.ViewerPane(parent, SWT.BORDER | SWT.FLAT);
		mChunksTreeViewer = new ChunksTreeViewer(chunksPane, SWT.NONE);
		
		// Set the label text.
		chunksPane.setText(CHUNKS_TITLE);
		
		// Setup the toolbar.
		ToolBarManager tbm = chunksPane.getToolBarManager();
		tbm.add(new NextChunk());
		tbm.add(new PreviousChunk());
		
		tbm.update(true);
		
		// Setup the model for the tree viewer.
		mChunksTreeViewer.setContentProvider(createChunksTreeContentProvider());
		mChunksTreeViewer.setLabelProvider(createChunksTreeLabelProvider());
		mChunksTreeViewer.setComparator(createChunksTreeComparator());
		mChunksTreeViewer.addSelectionChangedListener(createSelectionChangedListener());
		
		// This means that the top-level elements will automatically be expanded.
		mChunksTreeViewer.setAutoExpandLevel(2);
		
		// Set the content of the ViewerPane to the tree-control.
		chunksPane.setContent(mChunksTreeViewer.getControl());
	}

	private ITreeContentProvider createChunksTreeContentProvider() {
		return new ChunksContentProvider();
	}

	private ILabelProvider createChunksTreeLabelProvider() {
		return new ChunksLabelProvider();
	}

	private ViewerComparator createChunksTreeComparator() {
		return new ViewerComparator() {
			@Override
			public int compare(Viewer viewer, Object e1, Object e2) {
				if (e1 instanceof TopLevelElement && e2 instanceof TopLevelElement) {
					TopLevelElement lhs = (TopLevelElement) e1;
					TopLevelElement rhs = (TopLevelElement) e2;
					
					String lhsFileName = lhs.getFileKey().getFileNameOnly();
					String rhsFileName = rhs.getFileKey().getFileNameOnly();
					
					int result = lhsFileName.compareTo(rhsFileName);
					if (result != 0) {
						return result;
					} else {
						// Compare the full paths.
						lhsFileName = lhs.getFileKey().getFilePath();
						rhsFileName = rhs.getFileKey().getFilePath();
						
						return lhsFileName.compareTo(rhsFileName);
					}
				} else if (e1 instanceof ChunkLevelElement && e2 instanceof ChunkLevelElement) {
					ChunkLevelElement lhs = (ChunkLevelElement) e1;
					ChunkLevelElement rhs = (ChunkLevelElement) e2;
					
					return Chunk.getLocationComparator().compare(lhs.getChunk(), rhs.getChunk());
				}
				
				return 0;
			}
		};
	}

	private ISelectionChangedListener createSelectionChangedListener() {
		return new ISelectionChangedListener() {
			@Override
			public void selectionChanged(SelectionChangedEvent event) {
				final IStructuredSelection sel = (IStructuredSelection) event.getSelection();
				UIJob job = new UIJob("Interactive Selective Undo Dialog Update") {
					
					@Override
					public IStatus runInUIThread(IProgressMonitor monitor) {
						updateBottomPanel(sel);
						return Status.OK_STATUS;
					}
				};
				
				job.setSystem(true);
				job.setUser(false);
				job.schedule();
			}
		};
	}
	
	private void setChunksTreeViewerInput() {
		// If the viewer is not initialized, do nothing.
		if (mChunksTreeViewer == null) {
			return;
		}
		
		TimelineViewPart timeline = TimelineViewPart.getInstance();
		// If the timeline is not open, do nothing.
		if (timeline == null) {
			return;
		}
		
		List<OperationId> ids = timeline.getRectSelection();
		
		Map<FileKey, List<RuntimeDC>> fileDCMap = RuntimeHistoryManager
				.getInstance().extractFileDCMapFromOperationIds(ids);
		
		List<TopLevelElement> topList = new ArrayList<TopLevelElement>();
		
		for (FileKey fileKey : fileDCMap.keySet()) {
			List<Chunk> chunksForThisFile = SelectiveUndoEngine.getInstance()
					.determineChunksWithRuntimeDCs(fileDCMap.get(fileKey));
			
			TopLevelElement topElem = new TopLevelElement(fileKey, chunksForThisFile);
			topList.add(topElem);
		}
		
		// Remember the old input / selection before setting a new one.
		TopLevelElement[] oldInput = (TopLevelElement[]) mChunksTreeViewer.getInput();
		Object oldSelection = ((IStructuredSelection) mChunksTreeViewer.getSelection()).getFirstElement();

		TopLevelElement[] newInput = topList.toArray(new TopLevelElement[topList.size()]);
		
		// Restore the chosen alternatives.
		if (oldInput != null) {
			restoreChosenAlternatives(oldInput, newInput);
		}
		
		mChunksTreeViewer.setInput(newInput);
		
		restoreSelections(oldSelection, newInput);
		
		updateOKButtonEnabled();
	}

	private void restoreSelections(Object oldSelection, TopLevelElement[] newInput) {
		if (oldSelection instanceof TopLevelElement) {
			TopLevelElement oldTopElem = (TopLevelElement) oldSelection;
			
			for (TopLevelElement newTopElem : newInput) {
				if (oldTopElem.getFileKey().equals(newTopElem.getFileKey())) {
					mChunksTreeViewer.setSelection(new StructuredSelection(newTopElem), true);
					break;
				}
			}
		} else if (oldSelection instanceof ChunkLevelElement) {
			ChunkLevelElement oldChunkElem = (ChunkLevelElement) oldSelection;
			TopLevelElement oldTopElem = oldChunkElem.getParent();
			
			for (TopLevelElement newTopElem : newInput) {
				if (restoreSelectionsForTopElement(oldChunkElem, oldTopElem, newTopElem)) {
					break;
				}
			}
		} else if (oldSelection == null) {
			// Select the first item, if there's any.
			if (newInput != null && newInput.length > 0) {
				mChunksTreeViewer.setSelection(new StructuredSelection(newInput[0]), true);
			}
		}
	}

	private boolean restoreSelectionsForTopElement(ChunkLevelElement oldChunkElem,
			TopLevelElement oldTopElem, TopLevelElement newTopElem) {
		if (oldTopElem.getFileKey().equals(newTopElem.getFileKey())) {
			for (ChunkLevelElement newChunkElem : newTopElem.getChunkElements()) {
				if (areSameChunks(oldChunkElem.getChunk(), newChunkElem.getChunk())) {
					mChunksTreeViewer.setSelection(new StructuredSelection(newChunkElem), true);
					break;
				}
			}
			
			return true;
		}
		
		return false;
	}

	private void restoreChosenAlternatives(TopLevelElement[] oldInput,
			TopLevelElement[] newInput) {
		for (TopLevelElement oldTopElem : oldInput) {
			TopLevelElement matchingTopElem = null;
			for (TopLevelElement newTopElem : newInput) {
				if (oldTopElem.getFileKey().equals(newTopElem.getFileKey())) {
					matchingTopElem = newTopElem;
					break;
				}
			}
			
			if (matchingTopElem == null) {
				continue;
			}
			
			restoreChosenAlternatives(oldTopElem, matchingTopElem);
		}
	}

	private void restoreChosenAlternatives(TopLevelElement oldTopElem,
			TopLevelElement matchingTopElem) {
		for (ChunkLevelElement oldChunkElem : oldTopElem.getChunkElements()) {
			if (oldChunkElem.getChosenAlternative() == null) {
				continue;
			}
			
			boolean found = false;
			
			for (ChunkLevelElement newChunkElem : matchingTopElem.getChunkElements()) {
				if (areSameChunks(oldChunkElem.getChunk(), newChunkElem.getChunk()) &&
						oldChunkElem.getUndoAlternatives().size() == newChunkElem.getUndoAlternatives().size()) {
					int oldAlternativeIndex = oldChunkElem.getUndoAlternatives().indexOf(oldChunkElem.getChosenAlternative());
					newChunkElem.setChosenAlternative(newChunkElem.getUndoAlternatives().get(oldAlternativeIndex));
					
					found = true;
					break;
				}
			}
			
			if (found) {
				continue;
			}
		}
	}

	@Override
	public void rectSelectionChanged() {
		setChunksTreeViewerInput();
		updateBottomPanel();
	}
	
	private void createBottomArea(Composite parent) {
		// Create the bottom sash form.
		mBottomSash = new SashForm(parent, SWT.VERTICAL);
		mBottomSash.setSashWidth(SPACING);
		
		// Create the conflict resolution area.
		createConflictResolutionArea(mBottomSash);
		
		// Create the bottom area
		mBottomArea = new Composite(mBottomSash, SWT.NONE);
		mBottomSash.setWeights(new int[] { 1, 3 });
		
		// Use StackLayout
		mBottomStackLayout = new StackLayout();
		mBottomArea.setLayout(mBottomStackLayout);
		
		// Create the side-by-side preview panel.
		createPreviewPanel(mBottomArea);
		
		// Information panel, telling some useful information
		createInformationPanel(mBottomArea);
		
		// Update the bottom panel.
		updateBottomPanel();
	}
	
	@SuppressWarnings("restriction")
	private void createConflictResolutionArea(Composite parent) {
		mConflictResolutionPane = new org.eclipse.jdt.internal.ui.util.ViewerPane(
				mBottomSash, SWT.BORDER | SWT.FLAT);
		
		// Set the label text.
		mConflictResolutionPane.setText(ALTERNATIVES_TITLE);
	}
	
	private void createPreviewPanel(Composite parent) {
		mPreviewPane = new CompareViewerSwitchingPane(parent, SWT.BORDER | SWT.FLAT) {
			@SuppressWarnings("restriction")
			@Override
			protected Viewer getViewer(Viewer oldViewer, Object input) {
				Viewer v = CompareUI.findContentViewer(oldViewer, input, this, mCompareConfiguration);
				v.getControl().setData(CompareUI.COMPARE_VIEWER_TITLE, mCompareTitle);
				
				ToolBarManager tbm = CompareViewerSwitchingPane.getToolBarManager(this);
				
				// Delete unwanted toolbar buttons.
				List<IContributionItem> toBeDeleted = new ArrayList<IContributionItem>();
				String[] unwanted = new String[] {
						"Next Difference",
						"Previous Difference",
						"Copy Current Change to Right",
						"Copy Current Change to Left",
						"Copy Left to Right",
						"Copy Right to Left" };
				List<String> unwantedList = Arrays.asList(unwanted);
				
				for (IContributionItem item : tbm.getItems()) {
					if (!(item instanceof ActionContributionItem)) {
						continue;
					}
					
					ActionContributionItem aci = (ActionContributionItem) item;
					if (aci.getAction() != null && unwantedList.contains(aci.getAction().getText())) {
						toBeDeleted.add(item);
					}
				}
				
				for (IContributionItem item : toBeDeleted) {
					tbm.remove(item);
				}
				
				mLeftSourceViewer = null;
				SourceViewer rightSourceViewer = null;
				
				// HACK HACK access the private field directly from TextMergeViewer, in order to get the SourceViewer instances.
				try {
					Field leftField = getField(TextMergeViewer.class, "fLeft");
					Field rightField = getField(TextMergeViewer.class, "fRight");
					
					leftField.setAccessible(true);
					rightField.setAccessible(true);
					
					mLeftSourceViewer = ((org.eclipse.compare.internal.MergeSourceViewer) leftField.get(v)).getSourceViewer();
					rightSourceViewer = ((org.eclipse.compare.internal.MergeSourceViewer) rightField.get(v)).getSourceViewer();
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				if (mLeftSourceViewer != null) {
					StyledText te = mLeftSourceViewer.getTextWidget();
					te.setMenu(mLeftSourceViewerMenuMgr.createContextMenu(te));
				}
				
				if (rightSourceViewer != null) {
					StyledText te = rightSourceViewer.getTextWidget();
					te.setMenu(null);
				}
				
				return v;
			}
		};
	}
	
	public static Field getField(Class<?> clazz, String fieldName) {
	    Class<?> tmpClass = clazz;
	    do {
	        try {
	            Field f = tmpClass.getDeclaredField(fieldName);
	            return f;
	        } catch (NoSuchFieldException e) {
	            tmpClass = tmpClass.getSuperclass();
	        }
	    } while (tmpClass != null);

	    throw new RuntimeException("Field '" + fieldName
	            + "' not found on class " + clazz);
	}
	
	private void showConflictResolution() {
		mBottomSash.setMaximizedControl(null);
		mBottomSash.setWeights(new int[] { 1, 3 });
	}
	
	private void hideConflictResolution() {
		mBottomSash.setMaximizedControl(mBottomArea);
	}
	
	private void showBottomPanel(boolean showConflictResolution, String informationMessage) {
		if (showConflictResolution) {
			showConflictResolution();
		} else {
			hideConflictResolution();
		}
		
		if (informationMessage != null) {
			// Show the information panel.
			mInformationLabel.setText(informationMessage);
			mBottomStackLayout.topControl = mInformationLabel;
		} else {
			// Show the preview panel.
			mBottomStackLayout.topControl = mPreviewPane;
		}
		
		mBottomArea.layout();
	}
	
	private void createInformationPanel(Composite parent) {
		mInformationLabel = new Label(parent, SWT.BORDER | SWT.FLAT);
	}
	
	private void showPreviewPanel(Chunk chunk) {
		if (chunk == null) {
			throw new IllegalArgumentException();
		}
		
		try {
			IDocument doc = findDocumentForChunk(chunk);
			
			// Original source
			String originalContents = doc.get(chunk.getStartOffset(), chunk.getChunkLength());
			
			// Calculate the preview using selective undo engine
			String undoResult = SelectiveUndoEngine.getInstance()
					.doSelectiveUndoChunkWithoutConflicts(chunk, originalContents);
			
			mCompareTitle = getLabelForChunk(chunk, doc);
			
			setPreviewInput(doc, undoResult, chunk.getStartOffset(), chunk.getChunkLength());
			
			// Bring the preview panel to top.
			showBottomPanel(false, null);
		} catch (Exception e) {
			e.printStackTrace();
			
			// Display an error message on the screen.
			String msg = GENERATING_PREVIEW_ERROR_MSG;
			showBottomPanel(false, msg);
		}
	}
	
	private void setPreviewInput(IDocument doc, String undoResult, int start, int length) throws BadLocationException {
		Document originalDoc = new Document(doc.get());
		
		Document resultDoc = new Document(originalDoc.get());
		resultDoc.replace(start, length, undoResult);
		
		// Setup the documents.
		setupDocumentForJava(originalDoc);
		setupDocumentForJava(resultDoc);
		
		// Calculate the startline / endline from the originalContents.
		int startLine = originalDoc.getLineOfOffset(start);
		int endLine = originalDoc.getLineOfOffset(start + length);
		
		// Add surrounding context before/after the code
		int contextStartLine = Math.max(startLine - SURROUNDING_CONTEXT_SIZE, 0);
		int contextEndLine = Math.min(endLine + SURROUNDING_CONTEXT_SIZE, originalDoc.getNumberOfLines() - 1);
		
		int contextStartOffset = originalDoc.getLineOffset(contextStartLine);
		int contextEndOffset = originalDoc.getLineOffset(contextEndLine) + originalDoc.getLineLength(contextEndLine);
		
		int beforeContextLength = start - contextStartOffset;
		int afterContextLength = contextEndOffset - (start + length);
		
		DocumentRangeCompareItem leftItem = new DocumentRangeCompareItem(
				"Current Source",
				originalDoc,
				contextStartOffset,
				beforeContextLength + length + afterContextLength,
				false);
		
		DocumentRangeCompareItem rightItem = new DocumentRangeCompareItem(
				"Preview of Selective Undo Result",
				resultDoc,
				contextStartOffset,
				beforeContextLength + undoResult.length() + afterContextLength,
				false);
		
		// Build the compareInput and feed it into the compare viewer switching pane.
		AzuriteCompareInput compareInput = new AzuriteCompareInput(
				leftItem,	// Original Source
				rightItem);	// Preview Source
		
		mPreviewPane.setInput(compareInput);
	}

	private void setupDocumentForJava(Document resultDoc) {
		@SuppressWarnings("restriction")
		IDocumentPartitioner partitioner = org.eclipse.jdt.internal.ui.JavaPlugin.getDefault().getJavaTextTools().createDocumentPartitioner();
		resultDoc.setDocumentPartitioner(IJavaPartitions.JAVA_PARTITIONING, partitioner);
		partitioner.connect(resultDoc);
	}
	
	private void showPreviewPanel(TopLevelElement topElem) {
		if (topElem == null) {
			throw new IllegalArgumentException();
		}
		
		try {
			FileKey fileKey = topElem.getFileKey();
			IDocument doc = edu.cmu.scs.azurite.util.Utilities.findDocumentForKey(fileKey);
			
			// Original source
			String originalContents = doc.get();
			
			// Copy of the source.
			IDocument docResult = new Document(originalContents);
			SelectiveUndoEngine.getInstance()
					.doSelectiveUndoWithChunks(topElem.getChunks(), docResult, topElem.getAlternativeChoiceMap());
			String undoResult = docResult.get();
			
			mCompareTitle = fileKey.getFileNameOnly();
			
			setPreviewInput(doc, undoResult, 0, doc.get().length());
			
			// Bring the preview panel to top.
			showBottomPanel(false, null);
		} catch (Exception e) {
			e.printStackTrace();
			
			// Display an error message on the screen.
			String msg = GENERATING_PREVIEW_ERROR_MSG;
			showBottomPanel(false, msg);
		}
	}
	
	public String getLabelForChunk(Chunk chunk, IDocument doc) {
		try {
			int startLine = doc.getLineOfOffset(chunk.getStartOffset());
			int endLine = doc.getLineOfOffset(chunk.getEndOffset());
			if (startLine == endLine) {
				return chunk.getBelongsTo().getFileNameOnly() + ": line " + startLine;
			} else {
				return chunk.getBelongsTo().getFileNameOnly() + ": lines " + startLine + "-" + endLine;
			}
		} catch (Exception e) {
			return chunk.getBelongsTo().getFileNameOnly();
		}
	}

	public IDocument findDocumentForChunk(Chunk chunk) {
		return edu.cmu.scs.azurite.util.Utilities.findDocumentForKey(chunk.getBelongsTo());
	}
	
	private void showConflictResolutionPanel(final ChunkLevelElement chunkElem) {
		// Delete the conflict resolution area if there was previously.
		clearConflictResolutionArea();
		
		mConflictResolutionArea = new Composite(mConflictResolutionPane, SWT.NONE);
		mConflictResolutionArea.setLayout(new FillLayout());
		
		mConflictResolutionPane.setContent(mConflictResolutionArea);
		
		// Add the alternatives
		for (final UndoAlternative alternative : chunkElem.getUndoAlternatives()) {
			AlternativeButton buttonAlternative = new AlternativeButton(mConflictResolutionArea, SWT.RADIO);
			buttonAlternative.setAlternativeCode(alternative.getResultingCode());
			buttonAlternative.setToolTipText(alternative.getDescription());
			
			if (chunkElem.getChosenAlternative() == alternative) {
				buttonAlternative.setSelected(true);
				showPreviewForConflictResolution(chunkElem);
			}
			
			buttonAlternative.addSelectionListener(new SelectionAdapter() {
				@Override
				public void widgetSelected(SelectionEvent e) {
					chunkElem.setChosenAlternative(alternative);
					mChunksTreeViewer.update(chunkElem, null);
					mChunksTreeViewer.update(chunkElem.getParent(), null);
					showPreviewForConflictResolution(chunkElem);
					
					updateOKButtonEnabled();
				}
			});
		}
		
		mConflictResolutionArea.layout(true);

		// update the layout so that the conflict resolution panel is shown.
		if (chunkElem.getChosenAlternative() == null) {
			String msg = "Please select one of the alternatives to resolve this conflict.";
			showBottomPanel(true, msg);
		} else {
			showBottomPanel(true, null);
		}
	}
	
	private void updateOKButtonEnabled() {
		Button okButton = null;
		
		try {
			okButton = getButton(OK);
			
			boolean enabled = true;
			for (TopLevelElement topElem : (TopLevelElement[]) mChunksTreeViewer.getInput()) {
				if (topElem.hasUnresolvedConflict()) {
					enabled = false;
					break;
				}
			}
			
			okButton.setEnabled(enabled);
		} catch (Exception e) {
			if (okButton != null) {
				okButton.setEnabled(false);
			}
		}
	}
	
	private void showPreviewForConflictResolution(ChunkLevelElement chunkElem) {
		try {
			Chunk chunk = chunkElem.getChunk();
			IDocument doc = findDocumentForChunk(chunk);
			
			Chunk expandedChunk = chunk.getExpandedChunkWithDepth(SelectiveUndoEngine.MAX_EXPANSION_DEPTH);
			
			// Calculate the preview using selective undo engine
			String undoResult = chunkElem.getChosenAlternative().getResultingCode();
			
			mCompareTitle = getLabelForChunk(expandedChunk, doc);
			
			setPreviewInput(doc, undoResult, expandedChunk.getStartOffset(), expandedChunk.getChunkLength());
			
			showBottomPanel(true, null);
		} catch (Exception e) {
			e.printStackTrace();
			
			// Display an error message on the screen.
			String msg = GENERATING_PREVIEW_ERROR_MSG;
			showBottomPanel(true, msg);
		}
	}

	public void clearConflictResolutionArea() {
		if (mConflictResolutionArea != null) {
			mConflictResolutionArea.dispose();
			mConflictResolutionArea = null;
			mConflictResolutionPane.layout(true);
		}
	}
	
	public void updateBottomPanel() {
		updateBottomPanel((IStructuredSelection)mChunksTreeViewer.getSelection());
	}

	public void updateBottomPanel(IStructuredSelection sel) {
		if (sel.size() == 1) {
			Object firstElement = sel.getFirstElement();
			
			if (firstElement instanceof ChunkLevelElement) {
				ChunkLevelElement chunkElem = (ChunkLevelElement) firstElement;
				Chunk chunk = chunkElem.getChunk();
				
				if (chunk.hasConflictOutsideThisChunk() && chunkElem.getUndoAlternatives().size() > 1) {
					// Show the conflict resolution panel.
					showConflictResolutionPanel(chunkElem);
				} else {
					// Show a normal preview..
					showPreviewPanel(chunk);
				}
			} else if (firstElement instanceof TopLevelElement) {
				TopLevelElement topElem = (TopLevelElement) firstElement;
				
				if (topElem.hasUnresolvedConflict()) {
					String msg = "You must resolve all the conflicts under this file to be able to see the entire preview.";
					showBottomPanel(false, msg);
				} else {
					showPreviewPanel(topElem);
				}
			}
		} else {
			TopLevelElement[] input = (TopLevelElement[]) mChunksTreeViewer.getInput();
			boolean chunksEmpty = input == null || input.length == 0;
			
			String msg = chunksEmpty ? INFORMATION_SELECT_RECTS
					: INFORMATION_SELECT_CHUNK;
			showBottomPanel(false, msg);
		}
	}
	
	// TODO maybe move to Chunk class?
	private boolean areSameChunks(Chunk lhs, Chunk rhs) {
		if (lhs == null || rhs == null) {
			return false;
		}
		
		if (!lhs.getBelongsTo().equals(rhs.getBelongsTo())) {
			return false;
		}
		
		if (lhs.size() != rhs.size()) {
			return false;
		}
		
		for (int i = 0; i < lhs.size(); ++i) {
			if (lhs.get(i) != rhs.get(i)) {
				return false;
			}
		}
		
		return true;
	}
	
}
