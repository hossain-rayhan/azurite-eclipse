package edu.cmu.scs.azurite.commands.runtime;

import java.util.ArrayList;
import java.util.List;

import edu.cmu.scs.fluorite.commands.Replace;

/**
 * @author YoungSeok Yoon
 * 
 */
public class RuntimeReplace extends BaseRuntimeDocumentChange {
	
	private DeleteComponent mDeleteComponent;
	private InsertComponent mInsertComponent;

	public RuntimeReplace(Replace replace) {
		super(replace);
		
		mDeleteComponent = new DeleteComponent();
		mInsertComponent = new InsertComponent();
		
		getDeleteComponent().initialize(
				Segment.createInitialDeleteSegmentFromReplace(getOriginal(), this));
		getInsertComponent().initialize(
				Segment.createInitialInsertSegmentFromReplace(getOriginal(), this));
	}

	@Override
	public Replace getOriginal() {
		return (Replace) (super.getOriginal());
	}

	@Override
	public void applyInsert(RuntimeInsert insert) {
		boolean conflict = false;
		conflict |= getDeleteComponent().applyInsert(insert);
		conflict |= getInsertComponent().applyInsert(insert);
		
		if (conflict) {
			addConflict(insert);
		}
	}

	@Override
	public void applyDelete(RuntimeDelete delete) {
		boolean conflict = false;
		conflict |= getDeleteComponent().applyDelete(delete);
		conflict |= getInsertComponent().applyDelete(delete);
		
		if (conflict) {
			addConflict(delete);
		}
	}

	@Override
	public void applyReplace(RuntimeReplace replace) {
		boolean conflict = false;
		conflict |= getDeleteComponent().applyReplace(replace);
		conflict |= getInsertComponent().applyReplace(replace);
		
		if (conflict) {
			addConflict(replace);
		}
	}

	@Override
	public void applyTo(BaseRuntimeDocumentChange docChange) {
		docChange.applyReplace(this);
	}
	
	public Segment getDeleteSegment() {
		return getDeleteComponent().getDeleteSegment();
	}
	
	public List<Segment> getInsertSegments() {
		return getInsertComponent().getInsertSegments();
	}
	
	private DeleteComponent getDeleteComponent() {
		return mDeleteComponent;
	}
	
	private InsertComponent getInsertComponent() {
		return mInsertComponent;
	}

	@Override
	public List<Segment> getAllSegments() {
		ArrayList<Segment> segments = new ArrayList<Segment>();
		segments.add(getDeleteSegment());
		segments.addAll(getInsertSegments());
		return segments;
	}
	
	@Override
	public int getTypeIndex() {
		return 2;
	}

}
