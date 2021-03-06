package edu.cmu.scs.azurite.model.grouper;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import edu.cmu.scs.azurite.commands.runtime.RuntimeDC;
import edu.cmu.scs.azurite.model.FileKey;
import edu.cmu.scs.azurite.model.RuntimeDCListener;
import edu.cmu.scs.fluorite.commands.ICommand;
import edu.cmu.scs.fluorite.commands.document.DocChange;
import edu.cmu.scs.fluorite.commands.document.Range;
import edu.cmu.scs.fluorite.model.Events;

public class OperationGrouper implements RuntimeDCListener {
	
	private static final int MERGE_TIME_THRESHOLD = 2000;	// in milliseconds
	
	private static final boolean MERGE_WHITESPACES = true;
	
	public static final int LEVEL_PARSABLE = 0;
	public static final int LEVEL_METHOD = 1;
	public static final int LEVEL_TYPE = 2;
	public static final int NUM_LEVELS = 3;
	
	private Map<FileKey, Document>[] knownSnapshots;
	private FileKey currentFile;
	
	private List<RuntimeDC>[] pendingChangesList;
	private DocChange[] mergedPendingChanges;
	private IChangeInformation[] pendingChangeInformation;
	
	private ListenerList listeners;
	
	public OperationGrouper() {
		clearData();
		
		this.listeners = new ListenerList();
	}

	@SuppressWarnings("unchecked")
	public void clearData() {
		this.knownSnapshots = new Map[NUM_LEVELS];
		this.pendingChangesList = new ArrayList[NUM_LEVELS];
		for (int i = 0; i < NUM_LEVELS; ++i) {
			this.knownSnapshots[i] = new HashMap<FileKey, Document>();
			this.pendingChangesList[i] = new ArrayList<RuntimeDC>();
		}
		
		this.currentFile = null;
		
		this.mergedPendingChanges = new DocChange[NUM_LEVELS];
		Arrays.fill(this.mergedPendingChanges, null);
		
		this.pendingChangeInformation = new IChangeInformation[NUM_LEVELS];
		Arrays.fill(this.pendingChangeInformation, null);
	}

	@Override
	public void activeFileChanged(FileKey fileKey, String snapshot) {
		// Flush all the pending changes.
		flushAllPendingChanges();
		
		this.currentFile = fileKey;
		
		// The "initial" snapshot should be kept here, but not the subsequent ones.
		// Because, if a later snapshot is different from the previous one,
		// DiffDCs will be added by the RuntimeHistoryManager.
		if (snapshot != null) {
			for (int i = 0; i < NUM_LEVELS; ++i) {
				if (!this.knownSnapshots[i].containsKey(fileKey)) {
					this.knownSnapshots[i].put(fileKey, new Document(snapshot));
				}
			}
		}
	}

	public void flushAllPendingChanges() {
		for (int level = 0; level < NUM_LEVELS; ++level) {
			flushPendingChanges(level);
		}
	}

	@Override
	public void runtimeDCAdded(RuntimeDC dc) {
		Document currentSnapshot = getCurrentSnapshot(0);
		if (currentSnapshot == null) { return; }
		
		processRuntimeDCs(0, Collections.singletonList(dc));
	}

	@Override
	public void documentChangeAdded(DocChange docChange) {
	}

	@Override
	public void documentChangeUpdated(DocChange docChange) {
	}

	@Override
	public void documentChangeAmended(DocChange oldDocChange, DocChange newDocChange) {
		// TODO Do something with this as well.
	}

	@Override
	public void pastLogsRead(List<Events> listEvents) {
		// TODO Do something with this as well.
	}
	
	@Override
	public void codingEventOccurred(ICommand command) {
		flushAllPendingChanges();
	}
	
	private Document getCurrentSnapshot(int level) {
		return this.knownSnapshots[level].containsKey(currentFile)
				? this.knownSnapshots[level].get(this.currentFile)
				: null;
	}
	
	private void processRuntimeDCs(int level, List<RuntimeDC> dcs) {
		Document docBefore = getCurrentSnapshot(level);
		if (this.mergedPendingChanges[level] != null) {
			docBefore = new Document(docBefore.get());
			this.mergedPendingChanges[level].apply(docBefore);
		}
		DocChange mergedChange = RuntimeDC.mergeChanges(dcs, docBefore);
		
		if (this.pendingChangesList[level].isEmpty() || shouldBeMerged(level, dcs, mergedChange)) {
			addPendingChanges(level, dcs, mergedChange);
		} else {
			flushPendingChanges(level);
			addPendingChanges(level, dcs, mergedChange);
		}
	}
	
	private void addPendingChanges(int level, List<RuntimeDC> dcs, DocChange mergedChange) {
		Document docBefore = getCurrentSnapshot(level);
		this.pendingChangesList[level].addAll(dcs);
		this.mergedPendingChanges[level] = DocChange.mergeChanges(this.mergedPendingChanges[level], mergedChange, docBefore);
		
		RuntimeDC firstDC = this.pendingChangesList[level].get(0);
		int firstId = firstDC.getOriginal().getCommandIndex();
		
		updateCollapseIDs(dcs, level, firstDC, firstId);
	}
	
	private void updateCollapseIDs(List<RuntimeDC> dcs, int level, RuntimeDC collapseDC, int collapseID) {
		DocChange mergedChange = this.mergedPendingChanges[level];
		IChangeInformation ci = mergedChange != null
				? determineChangeKind(level, getCurrentSnapshot(level).get(), mergedChange)
				: null;
		
		this.pendingChangeInformation[level] = ci;
		
		for (int i = level; i < NUM_LEVELS; ++i) {
			collapseDC.setChangeInformation(i, ci);
			
			for (RuntimeDC dc : dcs) {
				if (dc == collapseDC) { continue; }
				dc.setCollapseID(i, collapseID);
				dc.setChangeInformation(i, null);
			}
			
			fireCollapseIDsUpdatedEvent(dcs, i, collapseID, ci);
		}
	}
	
	private boolean shouldBeMerged(int level, List<RuntimeDC> dcs, DocChange mergedChange) {
		switch (level) {
		case 0:
			return shouldBeMergedLevel0(dcs, mergedChange);
			
		case 1:
			return shouldBeMergedLevel1(dcs, mergedChange);
			
		case 2:
			return shouldBeMergedLevel2(dcs, mergedChange);
			
		default:
			throw new UnsupportedOperationException();
		}
	}
	
	/**
	 * Decide whether to really merge these two changes or not.
	 * 
	 * 1. If the two edits were performed in different lines, return false;
	 * 2. If the previous state is NOT parsable, return true.
	 * 3. If the time differs within MERGE_TIME_THRESHOLD, return true.
	 * 4. Return false, otherwise.
	 * 
	 * @return true if the two edits should be merged; false otherwise.
	 */
	@SuppressWarnings("unused")
	private boolean shouldBeMergedLevel0(List<RuntimeDC> dcs, DocChange mergedChange) {
		if (dcs == null || dcs.size() != 1 || dcs.get(0) == null || dcs.get(0).getOriginal() == null) {
			throw new IllegalArgumentException();
		}
		
		// If the previous pending changes were all cancelled out themselves, just merge them.
		if (this.mergedPendingChanges[0] == null) { return true; }
		
		Document docBefore = getCurrentSnapshot(0);
		DocChange oldEvent = this.mergedPendingChanges[0];
		DocChange newEvent = dcs.get(0).getOriginal();
		
		// Do NOT merge whitespace only changes with non-whitespace changes.
		if (!MERGE_WHITESPACES && oldEvent.isWhitespaceOnly() ^ newEvent.isWhitespaceOnly()) { return false; }
		
		Document docIntermediate = new Document(docBefore.get());
		oldEvent.apply(docIntermediate);
		
		try {
			Document doc = new Document(docBefore.get());
			oldEvent.apply(doc);
			if (!DocChange.overlap(oldEvent, newEvent) &&
				docIntermediate.getLineOfOffset(oldEvent.getInsertionRange().getEndOffset()) !=
				docIntermediate.getLineOfOffset(newEvent.getDeletionRange().getOffset()) &&
				docIntermediate.getLineOfOffset(oldEvent.getInsertionRange().getOffset()) !=
				docIntermediate.getLineOfOffset(newEvent.getDeletionRange().getEndOffset())) {
				return false;
			}
		} catch (BadLocationException e) {
			e.printStackTrace();
			if (!DocChange.overlap(oldEvent, newEvent)) {
				return false;
			}
		}
		
		if (!isLocallyParsable(docIntermediate.get(), oldEvent)) {
			return true;
		}
		
		if (newEvent.getTimestamp() - oldEvent.getTimestamp2() < MERGE_TIME_THRESHOLD) {
			return true;
		}
		
		return false;
	}

	private boolean shouldBeMergedLevel1(List<RuntimeDC> dcs, DocChange mergedChange) {
		int level = 1;
		
		// The mergedChange can be null when the new set of document changes cancel themselves out.
		if (this.mergedPendingChanges[level] == null || mergedChange == null) { return true; }
		
		if (this.pendingChangeInformation[level] == null) {
			this.pendingChangeInformation[level] = determineChangeKind(level, getCurrentSnapshot(level).get(), this.mergedPendingChanges[level]);
		}
		
		// The level 0 snapshot should contain the presnapshot.
		IChangeInformation prevChange = this.pendingChangeInformation[level];
		IChangeInformation nextChange = determineChangeKind(level, getCurrentSnapshot(level - 1).get(), mergedChange);
		
		if (prevChange != null && nextChange != null) {
			return prevChange.shouldBeMerged(level, nextChange);
		}
		
		return false;
	}
	
	private boolean shouldBeMergedLevel2(List<RuntimeDC> dcs, DocChange mergedChange) {
		int level = 2;
		
		// The mergedChange can be null when the new set of document changes cancel themselves out.
		if (this.mergedPendingChanges[level] == null || mergedChange == null) { return true; }
		
		if (this.pendingChangeInformation[level] == null) {
			this.pendingChangeInformation[level] = determineChangeKind(level, getCurrentSnapshot(level).get(), this.mergedPendingChanges[level]);
		}
		
		// The level 1 snapshot should contain the presnapshot.
		IChangeInformation prevChange = this.pendingChangeInformation[level];
		IChangeInformation nextChange = determineChangeKind(level, getCurrentSnapshot(level - 1).get(), mergedChange);
		
		if (prevChange != null && nextChange != null) {
			return prevChange.shouldBeMerged(level, nextChange);
		}
		
		return false;
	}
	
	private static IChangeInformation determineChangeKind(int level, String preSnapshot, DocChange docChange) {
		// Pre-AST
		Range preRange = docChange.getDeletionRange();
		ASTNode preRoot = parseSnapshot(preSnapshot);
		NodeFinder preFinder = new NodeFinder(preRoot, preRange.getOffset(), preRange.getLength());
		
		ASTNode preCovered = preFinder.getCoveredNode();
//		ASTNode preCovering = preFinder.getCoveringNode();
		
		// Post-AST
		Range postRange = docChange.getInsertionRange();
		String postSnapshot = docChange.apply(preSnapshot);
		ASTNode postRoot = parseSnapshot(postSnapshot);
		NodeFinder postFinder = new NodeFinder(postRoot, postRange.getOffset(), postRange.getLength());
		
		ASTNode postCovered = postFinder.getCoveredNode();
		ASTNode postCovering = postFinder.getCoveringNode();
		
		// Add Import Statement
		if (preCovered == null && getNodeType(postCovered) == ASTNode.IMPORT_DECLARATION) {
			return new AddImportStatementInformation(docChange);
		}
		
		// Delete Import Statement
		if (getNodeType(preCovered) == ASTNode.IMPORT_DECLARATION && postCovered == null) {
			return new DeleteImportStatementInformation(docChange);
		}
		
		// Add Field
		if (preCovered == null && getNodeType(postCovered) == ASTNode.FIELD_DECLARATION) {
			return new AddFieldInformation(docChange, (FieldDeclaration) postCovered);
		}
		
		// Delete Field
		if (getNodeType(preCovered) == ASTNode.FIELD_DECLARATION && postCovered == null) {
			return new DeleteFieldInformation(docChange, (FieldDeclaration) preCovered);
		}
		
		// Change Field
		IChangeInformation ci = OperationGrouper.<ChangeFieldInformation, FieldDeclaration>determineChangeKindChange(
				preRoot, postCovering, docChange, ChangeFieldInformation.class, FieldDeclaration.class);
		if (ci != null) {
			return ci;
		}
		
		// Add Method
		if (preCovered == null && postCovered != null && postCovered.getNodeType() == ASTNode.METHOD_DECLARATION) {
			return new AddMethodInformation(docChange, (MethodDeclaration) postCovered);
		}
		
		// Delete Method
		if (preCovered != null && preCovered.getNodeType() == ASTNode.METHOD_DECLARATION && postCovered == null) {
			return new DeleteMethodInformation(docChange, (MethodDeclaration) preCovered);
		}
		
		// Determine Method Change
		ci = OperationGrouper.<ChangeMethodInformation, MethodDeclaration>determineChangeKindChange(
				preRoot, postCovering, docChange, ChangeMethodInformation.class, MethodDeclaration.class);
		if (ci != null) {
			return ci;
		}
		
		// Add Type
		if (preCovered == null && postCovered != null && postCovered instanceof AbstractTypeDeclaration) {
			return new AddTypeInformation(docChange, (AbstractTypeDeclaration) postCovered);
		}
		
		// Delete Type
		if (preCovered != null && preCovered instanceof AbstractTypeDeclaration && postCovered == null) {
			return new DeleteTypeInformation(docChange, (AbstractTypeDeclaration) preCovered);
		}
		
		// Change Type
		ci = OperationGrouper.<ChangeTypeInformation, AbstractTypeDeclaration>determineChangeKindChange(
				preRoot, postCovering, docChange, ChangeTypeInformation.class, AbstractTypeDeclaration.class);
		if (ci != null) {
			return ci;
		}
		
		// The code stayed the same. Only formatting or comments were changed.
		if (preRoot.toString().equals(postRoot.toString())) {
			return new NonCodeChangeInformation(docChange);
		}
		
		return new UnknownInformation(docChange);
	}
	
	private static <CT extends IChangeInformation, AT extends ASTNode> CT determineChangeKindChange(
			ASTNode preRoot,
			ASTNode postCovering,
			DocChange docChange,
			Class<CT> changeInfoClass,
			Class<AT> astClass) {
		ASTNode node = postCovering;
		while (node != null) {
			if (astClass.isInstance(node)) {
				Range postRange = new Range(node);
				Range preRange = null;
				ASTNode preNode = null;
				
				try {
					preRange = docChange.applyInverse(postRange);
					preNode = NodeFinder.perform(preRoot, preRange);
					if (!astClass.isInstance(preNode)) {
						preNode = null;
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				try {
					Constructor<CT> cinfo = changeInfoClass.getConstructor(DocChange.class, astClass, Range.class, astClass);
					return cinfo.newInstance(docChange, preNode, preRange, node);
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				break;
			}
			
			node = node.getParent();
		}
		
		return null;
	}
	
	private static int getNodeType(ASTNode node) {
		if (node == null) { return -1; }
		return node.getNodeType();
	}
	
	private boolean isLocallyParsable(String snapshot, DocChange lastChange) {
		ASTNode rootNode = parseSnapshot(snapshot);
		if (!(rootNode instanceof CompilationUnit)) {
			return false;
		}
		
		// Find the innermost node.
		Range insertionRange = lastChange.getInsertionRange();
		ASTNode node = NodeFinder.perform(rootNode, insertionRange.getEndOffset(), 0);
		
		// Walk all the descendents and see if any of them are malformed.
		MalformedNodeFinder visitor = new MalformedNodeFinder();
		node.accept(visitor);
		if (visitor.isMalformed()) {
			return false;
		}
		
		// Walk up the ancestor nodes and see if any of them are malformed.
		while (node != null) {
			if (isNodeMalformed(node)) {
				return false;
			}
			
			node = node.getParent();
		}
		
		return true;
	}

	private static ASTNode parseSnapshot(String snapshot) {
		// First, parse the file.
		ASTParser parser = ASTParser.newParser(AST.JLS4);
		parser.setSource(snapshot.toCharArray());
		
		@SuppressWarnings("rawtypes")
		Map options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_7, options);
		parser.setCompilerOptions(options);
		
		// Do the parsing.
		ASTNode rootNode = parser.createAST(null);
		return rootNode;
	}
	
	private boolean isNodeMalformed(ASTNode node) {
		return (node.getFlags() & ASTNode.MALFORMED) == ASTNode.MALFORMED; 
	}
	
	private void flushPendingChanges(int level) {
		List<RuntimeDC> dcs = this.pendingChangesList[level];
		if (dcs.isEmpty()) { return; }

		// Go up one level
		if (level + 1 < NUM_LEVELS) {
			processRuntimeDCs(level + 1, dcs);
		}
		
		Document snapshot = getCurrentSnapshot(level);
		if (this.mergedPendingChanges[level] != null) {
			this.mergedPendingChanges[level].apply(snapshot);
		}
		
		// Clear the pending changes.
		this.pendingChangesList[level].clear();
		this.mergedPendingChanges[level] = null;
		this.pendingChangeInformation[level] = null;
	}
	
	public void addOperationGrouperListener(OperationGrouperListener listener) {
		this.listeners.add(listener);
	}
	
	public void removeOperationGrouperListener(OperationGrouperListener listener) {
		this.listeners.remove(listener);
	}
	
	private void fireCollapseIDsUpdatedEvent(List<RuntimeDC> dcs, int level, int collapseID, IChangeInformation changeInformation) {
		for (Object listenerObj : listeners.getListeners()) {
			((OperationGrouperListener) listenerObj).collapseIDsUpdated(dcs, level, collapseID, changeInformation);
		}
	}
	
	private class MalformedNodeFinder extends ASTVisitor {
		
		private boolean malformed = false;
		
		@Override
		public void preVisit(ASTNode node) {
			if (isMalformed()) {
				return;
			}
			
			if (isNodeMalformed(node)) {
				this.malformed = true;
			}
		}
		
		public boolean isMalformed() {
			return this.malformed;
		}
		
	}

}
