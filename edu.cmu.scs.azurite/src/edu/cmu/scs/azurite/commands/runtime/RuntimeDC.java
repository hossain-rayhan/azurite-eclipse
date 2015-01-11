package edu.cmu.scs.azurite.commands.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.eclipse.jface.text.IDocument;

import edu.cmu.scs.azurite.model.FileKey;
import edu.cmu.scs.azurite.model.OperationId;
import edu.cmu.scs.azurite.model.grouper.IChangeInformation;
import edu.cmu.scs.azurite.model.grouper.OperationGrouper;
import edu.cmu.scs.fluorite.commands.document.Delete;
import edu.cmu.scs.fluorite.commands.document.DocChange;
import edu.cmu.scs.fluorite.commands.document.Insert;
import edu.cmu.scs.fluorite.commands.document.Replace;

/**
 * @author YoungSeok Yoon
 *
 */
public abstract class RuntimeDC {

	private DocChange mOriginal;
	
	private List<RuntimeDC> mConflicts;
	
	private FileKey mBelongsTo;
	
	private int[] mCollapseTo;
	
	private IChangeInformation[] mChangeInformation;
	
	public static RuntimeDC createRuntimeDocumentChange(DocChange original) {
		if (original instanceof Insert) {
			return new RuntimeInsert((Insert) original);
		} else if (original instanceof Delete) {
			return new RuntimeDelete((Delete) original);
		} else if (original instanceof Replace) {
			return new RuntimeReplace((Replace) original);
		} else {
			throw new IllegalArgumentException("argument should be one of Insert / Delete / Replace");
		}
	}
	
	protected RuntimeDC(DocChange original) {
		mOriginal = original;
		
		mConflicts = new ArrayList<RuntimeDC>();
		
		mCollapseTo = new int[OperationGrouper.NUM_LEVELS];
		Arrays.fill(mCollapseTo, -1);
		
		mChangeInformation = new IChangeInformation[OperationGrouper.NUM_LEVELS];
		Arrays.fill(mChangeInformation, null);
	}
	
	public DocChange getOriginal() {
		return mOriginal;
	}
	
	public abstract void applyInsert(RuntimeInsert insert);
	
	public abstract void applyDelete(RuntimeDelete delete);
	
	public abstract void applyReplace(RuntimeReplace replace);
	
	public abstract void applyTo(RuntimeDC docChange);
	
	public List<RuntimeDC> getConflicts() {
		return mConflicts;
	}
	
	protected void addConflict(RuntimeDC docChange) {
		mConflicts.add(docChange);
	}
	
	public void setBelongsTo(FileKey belongsTo) {
		mBelongsTo = belongsTo;
	}
	
	public FileKey getBelongsTo() {
		return mBelongsTo;
	}
	
	public abstract List<Segment> getAllSegments();
	
	/**
	 * This type index is used inside the timeline view.
	 * The timeline.js code defines:
	 * 
	 * // Constants
	 * var INSERTION = 0;
	 * var DELETION = 1;
	 * var REPLACEMENT = 2;
	 * 
	 * So that it can color those things differently.
	 * 
	 * @return 0 if insertion, 1 if deletion, and 2 if replacement.
	 */
	public abstract int getTypeIndex();
	
	private static Comparator<RuntimeDC> commandIDComparator;
	
	/**
	 * Returns the singleton comparator objects which compares the runtime
	 * document changes based on the command IDs of their original events.
	 * @return comparator object.
	 */
	public static Comparator<RuntimeDC> getCommandIDComparator() {
		if (commandIDComparator == null) {
			commandIDComparator = new Comparator<RuntimeDC>() {

				@Override
				public int compare(RuntimeDC lhs,
						RuntimeDC rhs) {
					if (lhs.getOriginal().getSessionId() < rhs.getOriginal().getSessionId()) {
						return -1;
					}
					
					if (lhs.getOriginal().getSessionId() > rhs.getOriginal().getSessionId()) {
						return 1;
					}
					
					int lindex = lhs.getOriginal().getCommandIndex();
					int rindex = rhs.getOriginal().getCommandIndex();
					return new Integer(lindex).compareTo(rindex);
				}
				
			};
		}
		
		return commandIDComparator;
	}
	
	public abstract String getHtmlInfo();
	
	protected String transformToHtmlString(String originalCode) {
		return originalCode.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
	}
	
	public abstract String getTypeString();
	
	public abstract String getMarkerMessage();
	
	private OperationId mOperationId;
	public OperationId getOperationId() {
		if (mOperationId == null) {
			mOperationId = new OperationId(getOriginal().getSessionId(), getOriginal().getCommandIndex());
		}
		
		return mOperationId;
	}
	
	public int getCollapseID(int level) {
		return mCollapseTo[level];
	}
	
	public void setCollapseID(int level, int id) {
		mCollapseTo[level] = id;
	}
	
	public IChangeInformation getChangeInformation(int level) {
		return mChangeInformation[level];
	}
	
	public void setChangeInformation(int level, IChangeInformation changeInformation) {
		mChangeInformation[level] = changeInformation;
	}
	
	public static DocChange mergeChanges(RuntimeDC oldDC, RuntimeDC newDC) {
		return mergeChanges(oldDC, newDC, null);
	}
	
	public static DocChange mergeChanges(RuntimeDC oldDC, RuntimeDC newDC, IDocument docBefore) {
		return DocChange.mergeChanges(oldDC.getOriginal(), newDC.getOriginal(), docBefore);
	}
	
	public static DocChange mergeChanges(RuntimeDC oldDC, List<RuntimeDC> newDCs) {
		return mergeChanges(oldDC, newDCs, null);
	}
	
	public static DocChange mergeChanges(RuntimeDC oldDC, List<RuntimeDC> newDCs, IDocument docBefore) {
		return mergeChanges(oldDC.getOriginal(), newDCs, docBefore);
	}
	
	public static DocChange mergeChanges(DocChange oldChange, List<RuntimeDC> newDCs) {
		return mergeChanges(oldChange, newDCs, null);
	}
	
	public static DocChange mergeChanges(DocChange oldChange, List<RuntimeDC> newDCs, IDocument docBefore) {
		DocChange result = oldChange;
		for (RuntimeDC newDC : newDCs) {
			result = DocChange.mergeChanges(result, newDC.getOriginal(), docBefore);
		}
		
		return result;
	}
	
	public static DocChange mergeChanges(List<RuntimeDC> dcs) {
		return mergeChanges(dcs, null);
	}
	
	public static DocChange mergeChanges(List<RuntimeDC> dcs, IDocument docBefore) {
		return mergeChanges(dcs.get(0), dcs.subList(1, dcs.size()), docBefore);
	}
	
}
