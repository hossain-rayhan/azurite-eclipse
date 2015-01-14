package edu.cmu.scs.azurite.model.grouper;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import edu.cmu.scs.fluorite.commands.document.DocChange;
import edu.cmu.scs.fluorite.commands.document.Range;

public abstract class BaseChangeInformation implements IChangeInformation {
	
	private DocChange mergedChange;
	
	private Range preRange;
	private Range postRange;
	
	public BaseChangeInformation(DocChange mergedChange) {
		this.mergedChange = mergedChange;
		
		this.preRange = null;
		this.postRange = null;
	}
	
	@Override
	public String getChangeTypeString() {
		return "type_" + getChangeType().toString().toLowerCase();
	}
	
	@Override
	public String getChangeSummary() {
		return "";
	}

	@Override
	public DocChange getMergedChange() {
		return this.mergedChange;
	}
	
	@Override
	public ASTNode getPreNode() {
		return null;
	}
	
	@Override
	public ASTNode getPostNode() {
		return null;
	}
	
	@Override
	public Range getPreRange() {
		if (getPreNode() == null) { return null; }
		
		if (this.preRange == null) {
			this.preRange = new Range(getPreNode());
		}
		
		return this.preRange;
	}
	
	@Override
	public Range getPostRange() {
		if (getPostNode() == null) { return null; }
		
		if (this.postRange == null) {
			this.postRange = new Range(getPostNode());
		}
		
		return this.postRange;
	}
	
	protected void setPreRange(Range preRange) {
		this.preRange = preRange;
	}
	
	protected void setPostRange(Range postRange) {
		this.postRange = postRange;
	}
	
	protected String getFieldName(ASTNode fieldDeclarationNode) {
		if (fieldDeclarationNode instanceof FieldDeclaration) {
			FieldDeclaration fd = (FieldDeclaration) fieldDeclarationNode;
			if (fd.fragments().size() > 0) {
				Object fragment = fd.fragments().get(0);
				if (fragment instanceof VariableDeclarationFragment) {
					VariableDeclarationFragment vdf = (VariableDeclarationFragment) fragment;
					if (vdf.getName() != null) {
						return vdf.getName().getIdentifier();
					}
				}
			}
		}
		
		return null;
	}
	
	protected String getMethodName(ASTNode methodDeclarationNode) {
		if (methodDeclarationNode instanceof MethodDeclaration) {
			MethodDeclaration md = (MethodDeclaration) methodDeclarationNode;
			if (md.getName() != null) {
				return md.getName().getIdentifier();
			}
		}
		
		return null;
	}

}
