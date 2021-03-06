package edu.cmu.scs.azurite.util;

import java.io.File;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.progress.UIJob;

import edu.cmu.scs.azurite.commands.runtime.RuntimeDC;
import edu.cmu.scs.azurite.model.FileKey;

public class Utilities {

	public static IDocument findDocumentFromOpenEditors(FileKey fileKey) {
		try {
			IEditorReference[] editorRefs = PlatformUI.getWorkbench()
					.getActiveWorkbenchWindow().getActivePage()
					.getEditorReferences();
			
			for (IEditorReference editorRef : editorRefs) {
				IEditorInput input = editorRef.getEditorInput();
				if (input instanceof IFileEditorInput) {
					IFileEditorInput fileInput = (IFileEditorInput) input;
					IFile file = fileInput.getFile();
					
					FileKey key = new FileKey(
							file.getProject().getName(),
							file.getLocation().toOSString());
					
					// This is the same file!
					// Get the IDocument object from this editor.
					if (fileKey.equals(key)) {
						return edu.cmu.scs.fluorite.util.Utilities.getDocument(editorRef.getEditor(false));
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		return null;
	}

	public static IDocument findDocumentForKey(FileKey fileKey) {
		try {
			// Retrieve the IDocument, using the file information.
			IDocument doc = findDocumentFromOpenEditors(fileKey);
			// If this file is not open, then just connect it with the relative path.
			if (doc == null) {
				IWorkspace workspace = ResourcesPlugin.getWorkspace();
				IWorkspaceRoot root = workspace.getRoot();
				
				IPath absPath = new Path(fileKey.getFilePath());
				IFile file = root.getFileForLocation(absPath);
				if (file == null) {
					return null;
				}
				
				IPath relPath = file.getFullPath();
				
				ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
				manager.connect(relPath, LocationKind.IFILE, null);
				ITextFileBuffer buffer = manager.getTextFileBuffer(relPath, LocationKind.IFILE);
				
				doc = buffer.getDocument();
			}
			return doc;
		} catch (CoreException e) {
			// This means that manager.connect() has failed because the file could not be found.
			// Just return null.
			return null;
		}
	}

	public static IEditorPart openEditorWithKey(FileKey key) {
		File fileToOpen = new File(key.getFilePath());
		
		IEditorPart editor = null;
		if (fileToOpen.exists() && fileToOpen.isFile()) {
		    IFileStore fileStore = EFS.getLocalFileSystem().getStore(fileToOpen.toURI());
		    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		 
		    try {
		        editor = IDE.openEditorOnFileStore( page, fileStore );
		    } catch ( PartInitException e ) {
		        //Put your exception handler here if you wish to
		    }
		} else {
		    //Do something if the file does not exist
		}
		
		return editor;
	}

	public static void moveCursorToChangeLocation(IEditorPart editor, RuntimeDC runtimeDC) {
		if (editor != null) {
			final ITextViewerExtension5 textViewerExt5 = edu.cmu.scs.fluorite.util.Utilities.getTextViewerExtension5(editor);
			
			final int offset = runtimeDC.getAllSegments().get(0).getOffset();
			final StyledText styledText = edu.cmu.scs.fluorite.util.Utilities.getStyledText(editor);
			UIJob job = new UIJob("Jump to the Code") {
				
				@Override
				public IStatus runInUIThread(IProgressMonitor monitor) {
					styledText.setSelection(textViewerExt5.modelOffset2WidgetOffset(offset));
					styledText.setFocus();
					styledText.showSelection();
					return Status.OK_STATUS;
				}
			};
			
			job.schedule();
		}
	}

}
