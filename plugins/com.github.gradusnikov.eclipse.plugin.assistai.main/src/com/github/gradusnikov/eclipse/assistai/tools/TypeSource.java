package com.github.gradusnikov.eclipse.assistai.tools;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaModelException;

import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;

/**
 * The source text behind a Java type, whether it is a workspace file or the source a
 * classpath entry attaches to a JAR.
 * <p>
 * {@link IType#getCompilationUnit()} is null for every type that came out of a JAR,
 * attachment or not, so a tool that took it as "the source of this type" treated an
 * attached library as having none. {@link IType#getTypeRoot()} is the member that
 * spans both cases, and it is the one to resolve a type's source through.
 * <p>
 * The source ranges JDT reports for a binary type's members are offsets into this
 * same text, so an outline or a method body reads out of a library exactly as it does
 * out of the workspace. What does not carry over is the location: a library type has
 * no {@code file}, because the pair the editing tools take is a project and a
 * project-relative path, and a JAR is neither editable nor readable as text.
 *
 * @param root the compilation unit or class file the text came from
 * @param contents the whole of that unit's source, never blank
 * @param file the workspace file to read and edit, or null for attached source
 * @param origin WORKSPACE_SOURCE or ATTACHED_SOURCE, which is what says whether an
 *            edit built from a range of this text could be written back
 */
public record TypeSource( ITypeRoot root, String contents, IFile file, SourceOrigin origin )
{
    /**
     * Resolves the source of a type.
     *
     * @return null when there is none to read - a class file whose classpath entry
     *         has no source attachment, which is the case a caller answers by
     *         decompiling instead
     */
    public static TypeSource of( IType type ) throws JavaModelException
    {
        ITypeRoot root = type == null ? null : type.getTypeRoot();
        if ( root == null )
        {
            return null;
        }
        if ( root instanceof ICompilationUnit unit )
        {
            // The buffer rather than the file, so an editor's unsaved changes are what the caller is told about.
            IBuffer buffer = unit.getBuffer();
            String contents = buffer == null ? null : buffer.getContents();
            if ( contents == null || contents.isBlank() )
            {
                return null;
            }
            return new TypeSource( root, contents, unit.getResource() instanceof IFile f ? f : null,
                    SourceOrigin.WORKSPACE_SOURCE );
        }

        // A class file's source is whatever its source attachment supplies; blank means there is no attachment.
        String attached = root.getSource();
        if ( attached == null || attached.isBlank() )
        {
            return null;
        }
        return new TypeSource( root, attached, null, SourceOrigin.ATTACHED_SOURCE );
    }

    /** The compilation unit, or null for attached source - the imports JDT models only for one. */
    public ICompilationUnit compilationUnit()
    {
        return root instanceof ICompilationUnit unit ? unit : null;
    }
}
