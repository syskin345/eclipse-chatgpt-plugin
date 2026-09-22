package com.github.gradusnikov.eclipse.assistai.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportContainer;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;

import com.github.gradusnikov.eclipse.assistai.mcp.results.Diagnostic;
import com.github.gradusnikov.eclipse.assistai.mcp.results.DiagnosticCode;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSourceResponse;
import com.github.gradusnikov.eclipse.assistai.resources.ContentRange;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceDescriptor;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceVersion;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;
import com.github.gradusnikov.eclipse.assistai.tools.TypeSource;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Reads the source of a single Java type: whole methods, or the whole file with its
 * imports and the method bodies the caller did not ask for collapsed.
 * <p>
 * Both results are exact source text. Where the previous renderings prefixed every
 * line with its number and replaced a collapsed body with a
 * {@code // ... (lines 40-91)} comment, the line numbers are now ranges in the result
 * - {@code MethodSource.range} and {@code ResourceReadResult.omittedRanges} - so a
 * caller reads them as data rather than recovering them from the code it was given.
 * <p>
 * The outline tool used to live here as well. It now returns a record from
 * {@link CodeAnalysisService#getClassOutline}, and the declaration, field and method
 * formatters went with it rather than being kept in two places. What is still needed
 * here - rendering a method's parameter list, so a caller's {@code methodSignature}
 * filter can be matched against it - is borrowed from there.
 */
@Creatable
@Singleton
public class OutlineService
{
    @Inject
    ILog logger;

    @Inject
    AiIgnoreService aiIgnoreService;

    /**
     * Returns the source of the named methods of one type.
     * <p>
     * Each method is returned as exact text with its own line range, rather than
     * concatenated under a banner comment: several methods of one file are several
     * disjoint regions, and a single blob of content would say nothing about which
     * lines belong to which.
     *
     * @param methodNames comma-separated; a name that matches nothing is reported in
     *            {@code notFound} rather than as a trailing comment in the source
     * @param methodSignature optional parameter-type hint, matched against the same
     *            rendering {@link CodeAnalysisService#formatMethodParameters} produces
     *            for the outline, so the two agree on what an overload looks like
     * @param includeJavadoc whether each method's range starts at its Javadoc
     */
    public MethodSourceResponse getMethodSource( String fullyQualifiedClassName, String methodNames,
                                                 String methodSignature, boolean includeJavadoc )
    {
        Set<String> requestedMethods = methodNames == null ? Set.of()
                : Arrays.stream( methodNames.split( "," ) )
                        .map( String::trim )
                        .filter( s -> !s.isEmpty() )
                        .collect( Collectors.toCollection( LinkedHashSet::new ) );

        if ( requestedMethods.isEmpty() )
        {
            // Unclassified rather than a dedicated code: DiagnosticCode describes what
            // the workspace could not do, and this is a request that asked for nothing.
            return MethodSourceResponse.failed( fullyQualifiedClassName, Diagnostic.fatal(
                    DiagnosticCode.INTERNAL_ERROR,
                    "No method names specified. Pass one or more comma-separated method names." ) );
        }

        for ( IJavaProject javaProject : getAvailableJavaProjects() )
        {
            try
            {
                IType type = javaProject.findType( fullyQualifiedClassName );
                if ( type == null )
                {
                    continue;
                }

                TypeSource typeSource = TypeSource.of( type );
                if ( typeSource == null )
                {
                    // This project resolves the type to bytecode with no source attached; another may attach some.
                    continue;
                }

                IFile file = typeSource.file();
                if ( file != null && aiIgnoreService.isExcluded( file ) )
                {
                    return MethodSourceResponse.failed( fullyQualifiedClassName, Diagnostic.fatal(
                            DiagnosticCode.RESOURCE_NOT_ACCESSIBLE,
                            "'" + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore." ) );
                }

                String source = typeSource.contents();
                Document doc = new Document( source );

                List<MethodSourceResponse.MethodSource> found = new ArrayList<>();
                List<String> notFound = new ArrayList<>( requestedMethods );

                for ( IMethod method : type.getMethods() )
                {
                    if ( !requestedMethods.contains( method.getElementName() ) )
                    {
                        continue;
                    }

                    String parameters = CodeAnalysisService.formatMethodParameters( method );
                    if ( methodSignature != null && !methodSignature.isEmpty()
                            && !parameters.contains( methodSignature ) )
                    {
                        continue;
                    }

                    ISourceRange range = method.getSourceRange();
                    if ( !SourceRange.isAvailable( range ) )
                    {
                        // A binary member the attachment does not contain - a generated constructor, a bridge
                        // method. There is no source to return, so it stays in notFound rather than coming back empty.
                        continue;
                    }

                    notFound.remove( method.getElementName() );

                    int startOffset = range.getOffset();
                    int endOffset = startOffset + range.getLength();

                    // JDT's source range for a method already begins at its Javadoc, so
                    // includeJavadoc is a request to drop it, not to add it. The
                    // previous code did the opposite - min() with the Javadoc offset,
                    // which is inside the range it was compared against - so the flag
                    // had never had any effect at all.
                    ISourceRange javadocRange = method.getJavadocRange();
                    if ( !includeJavadoc && javadocRange != null )
                    {
                        // Start at the line after the Javadoc rather than at the
                        // character after it: annotations sit between the two and are
                        // part of the declaration.
                        int javadocEndLine =
                                doc.getLineOfOffset( javadocRange.getOffset() + javadocRange.getLength() - 1 );
                        if ( javadocEndLine + 1 < doc.getNumberOfLines() )
                        {
                            startOffset = Math.min( doc.getLineOffset( javadocEndLine + 1 ), endOffset );
                        }
                    }

                    // Lines come from the document's own line tracker, so a CRLF file
                    // reports the same lines as an LF one.
                    int startLine = doc.getLineOfOffset( startOffset ) + 1;
                    int endLine = doc.getLineOfOffset( endOffset - 1 ) + 1;

                    // Whole lines, indentation included. The range reports column 1,
                    // and JDT's own offsets start at the '/' of the Javadoc or the
                    // first modifier - so text taken from them verbatim would begin
                    // mid-line and contradict the range that describes it.
                    int from = doc.getLineOffset( startLine - 1 );
                    int to = Math.min( source.length(),
                            doc.getLineOffset( endLine - 1 ) + doc.getLineLength( endLine - 1 ) );

                    found.add( new MethodSourceResponse.MethodSource(
                            method.getElementName(),
                            parameters,
                            ContentRange.ofLines( doc, startLine, endLine ),
                            source.substring( from, to ) ) );
                }

                return MethodSourceResponse.of(
                        fullyQualifiedClassName,
                        file == null ? null : file.getProject().getName(),
                        file == null ? null : file.getProjectRelativePath().toString(),
                        typeSource.origin(),
                        ResourceVersion.of( file ),
                        found,
                        notFound );
            }
            catch ( Exception e )
            {
                logger.error( e.getMessage(), e );
            }
        }

        return MethodSourceResponse.failed( fullyQualifiedClassName, Diagnostic.fatal(
                DiagnosticCode.RESOURCE_NOT_FOUND,
                "No open Java project resolves the type '" + fullyQualifiedClassName + "' to source,"
                        + " in the workspace or attached to a library." ) );
    }

    /**
     * Returns one file's source with the import block and the method bodies the caller
     * did not ask for left out.
     * <p>
     * This is a single contiguous resource with holes in it, which is exactly what
     * {@link ResourceReadResult} describes: the content is exact, and every omission
     * is a range in {@code omittedRanges} rather than a {@code // ... (lines 40-91)}
     * comment spliced into the code. A caller that wants an omitted region reads it
     * with {@code readProjectResource(projectName, filePath, startLine, endLine)}, or -
     * for a type out of a JAR, which has neither - with
     * {@code getSource(fullyQualifiedClassName, startLine, endLine)}.
     *
     * @param methodNames comma-separated method names to expand; null or empty expands
     *            all of them, in which case only the imports can be omitted
     */
    public ResourceReadResult getFilteredSource( String fullyQualifiedClassName, boolean excludeImports,
                                                 String methodNames )
    {
        final String toolName = "getFilteredSource";

        Set<String> expandMethods = ( methodNames != null && !methodNames.isBlank() )
                ? Arrays.stream( methodNames.split( "," ) )
                        .map( String::trim )
                        .filter( s -> !s.isEmpty() )
                        .collect( Collectors.toSet() )
                : Collections.emptySet();
        boolean expandAll = expandMethods.isEmpty();

        for ( IJavaProject javaProject : getAvailableJavaProjects() )
        {
            try
            {
                IType type = javaProject.findType( fullyQualifiedClassName );
                if ( type == null )
                {
                    continue;
                }

                TypeSource typeSource = TypeSource.of( type );
                if ( typeSource == null )
                {
                    // This project resolves the type to bytecode with no source attached; another may attach some.
                    continue;
                }

                IFile file = typeSource.file();
                if ( file != null && aiIgnoreService.isExcluded( file ) )
                {
                    return ResourceReadResult.failed( projectNameOf( file ), pathOf( file ),
                            Diagnostic.fatal( DiagnosticCode.RESOURCE_NOT_ACCESSIBLE, "'"
                                    + fullyQualifiedClassName + "' is excluded from AI processing by .aiignore." ) );
                }

                String source = typeSource.contents();
                String[] lines = source.split( "\n", -1 );
                Document doc = new Document( source );

                // Omitted ranges: startLine -> endLine, 1-based and inclusive.
                TreeMap<Integer, Integer> omit = new TreeMap<>();

                if ( excludeImports )
                {
                    LineSpan imports = importLines( typeSource, type, lines, doc );
                    if ( imports != null )
                    {
                        omit.put( imports.start(), imports.end() );
                    }
                }

                if ( !expandAll )
                {
                    for ( IMethod method : type.getMethods() )
                    {
                        if ( expandMethods.contains( method.getElementName() ) )
                        {
                            continue;
                        }

                        ISourceRange range = method.getSourceRange();
                        String methodSource = method.getSource();
                        if ( methodSource == null )
                        {
                            continue;
                        }

                        int braceIndex = findOpeningBrace( methodSource );
                        if ( braceIndex < 0 )
                        {
                            continue;
                        }

                        int methodStartOffset = range.getOffset();
                        int braceLine = doc.getLineOfOffset( methodStartOffset + braceIndex ) + 1;
                        int methodEndLine = doc.getLineOfOffset( methodStartOffset + range.getLength() - 1 ) + 1;

                        // The signature and both braces stay; only the body goes.
                        int bodyStart = braceLine + 1;
                        int bodyEnd = methodEndLine - 1;

                        if ( bodyStart <= bodyEnd )
                        {
                            omit.put( bodyStart, bodyEnd );
                        }
                    }
                }

                StringBuilder content = new StringBuilder();
                List<ContentRange> omittedRanges = new ArrayList<>();
                int i = 0;
                while ( i < lines.length )
                {
                    int lineNumber = i + 1;
                    var entry = omit.floorEntry( lineNumber );
                    if ( entry != null && lineNumber >= entry.getKey() && lineNumber <= entry.getValue() )
                    {
                        if ( lineNumber == entry.getKey() )
                        {
                            omittedRanges.add( ContentRange.ofLines( doc, entry.getKey(), entry.getValue() ) );
                        }
                        i = entry.getValue();
                        continue;
                    }
                    content.append( lines[i] ).append( "\n" );
                    i++;
                }

                int totalLines = lines.length;

                return new ResourceReadResult(
                        omittedRanges.isEmpty() ? ResourceReadResult.ReadStatus.OK
                                                : ResourceReadResult.ReadStatus.PARTIAL,
                        ResourceDescriptor.fromJavaType( type, toolName ).uri().toString(),
                        projectNameOf( file ),
                        pathOf( file ),
                        "java",
                        ResourceVersion.of( file ),
                        new ContentRange( 1, 1, Math.max( 1, totalLines ), 1 ),
                        totalLines,
                        content.toString(),
                        typeSource.origin(),
                        !typeSource.origin().isEditable(),
                        // Nothing was cut off the end: the content runs to the last
                        // line, with holes. The holes are omittedRanges.
                        false,
                        omittedRanges,
                        Diagnostic.none() );
            }
            catch ( Exception e )
            {
                logger.error( e.getMessage(), e );
            }
        }

        return ResourceReadResult.failed( null, null, Diagnostic.fatal(
                DiagnosticCode.RESOURCE_NOT_FOUND,
                "No open Java project resolves the type '" + fullyQualifiedClassName + "' to source,"
                        + " in the workspace or attached to a library." ) );
    }

    /** A run of whole lines, 1-based and inclusive. */
    private record LineSpan( int start, int end )
    {
    }

    /**
     * The import block's first and last line, or null when the type has no imports.
     * <p>
     * JDT models an import container for a compilation unit only, so attached source is
     * read as text instead. The scan stops at the type declaration, which is what keeps
     * a line inside a method body from being taken for an import.
     */
    private static LineSpan importLines( TypeSource typeSource, IType type, String[] lines, Document doc )
            throws JavaModelException, BadLocationException
    {
        ICompilationUnit cu = typeSource.compilationUnit();
        if ( cu != null )
        {
            IImportContainer importContainer = cu.getImportContainer();
            if ( importContainer == null || !importContainer.exists() )
            {
                return null;
            }
            ISourceRange importRange = importContainer.getSourceRange();
            return new LineSpan( doc.getLineOfOffset( importRange.getOffset() ) + 1,
                    doc.getLineOfOffset( importRange.getOffset() + importRange.getLength() - 1 ) + 1 );
        }

        ISourceRange typeRange = type.getSourceRange();
        int declarationLine = typeRange == null || typeRange.getOffset() < 0
                ? lines.length
                : doc.getLineOfOffset( typeRange.getOffset() );
        int first = -1;
        int last = -1;
        for ( int i = 0; i < Math.min( declarationLine, lines.length ); i++ )
        {
            if ( lines[i].strip().startsWith( "import " ) )
            {
                first = first < 0 ? i : first;
                last = i;
            }
        }
        return first < 0 ? null : new LineSpan( first + 1, last + 1 );
    }

    private static String projectNameOf( IResource resource )
    {
        return resource == null ? null : resource.getProject().getName();
    }

    private static String pathOf( IResource resource )
    {
        return resource == null ? null : resource.getProjectRelativePath().toString();
    }

    private int findOpeningBrace(String methodSource)
    {
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < methodSource.length(); i++)
        {
            char c = methodSource.charAt(i);
            char next = (i + 1 < methodSource.length()) ? methodSource.charAt(i + 1) : 0;

            if (inLineComment)
            {
                if (c == '\n')
                    inLineComment = false;
                continue;
            }
            if (inBlockComment)
            {
                if (c == '*' && next == '/')
                {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString)
            {
                if (c == '\\') { i++; continue; }
                if (c == '"')
                    inString = false;
                continue;
            }
            if (inChar)
            {
                if (c == '\\') { i++; continue; }
                if (c == '\'')
                    inChar = false;
                continue;
            }

            if (c == '/' && next == '/')
            {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*')
            {
                inBlockComment = true;
                i++;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '{')
                return i;
        }

        return -1;
    }

    private List<IJavaProject> getAvailableJavaProjects()
    {
        List<IJavaProject> javaProjects = new ArrayList<>();
        try
        {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects)
            {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID))
                {
                    javaProjects.add(JavaCore.create(project));
                }
            }
        }
        catch (CoreException e)
        {
            throw new RuntimeException(e);
        }
        return javaProjects;
    }
}
