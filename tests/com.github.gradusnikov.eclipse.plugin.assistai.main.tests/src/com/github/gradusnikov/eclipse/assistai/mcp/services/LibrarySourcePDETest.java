package com.github.gradusnikov.eclipse.assistai.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.mcp.results.ClassOutlineResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.MethodSourceResponse;
import com.github.gradusnikov.eclipse.assistai.mcp.results.TypeResolutionResponse;
import com.github.gradusnikov.eclipse.assistai.tools.Javadocs;
import com.github.gradusnikov.eclipse.assistai.resources.ResourceReadResult;
import com.github.gradusnikov.eclipse.assistai.resources.SourceOrigin;
import com.github.gradusnikov.eclipse.assistai.services.AiIgnoreService;

/**
 * A type that lives in a JAR inside a workspace project, with a source attachment.
 * <p>
 * JDT reports such a type's resource as the JAR file. {@code getSource} used to take any
 * file it got as the source to read, so it returned the JAR's bytes as text - megabytes
 * of them - labelled as editable workspace source, with the JAR as the file to edit. The
 * attached source, which Eclipse itself shows on open, is what it must return.
 * <p>
 * The reading tools reached the attachment through {@code IType.getCompilationUnit()},
 * which is null for every type out of a JAR whether or not source is attached, so an
 * outline or a method body of a library class came back as "no source" while
 * {@code getSource} was returning that same source in full. They resolve it through
 * {@code IType.getTypeRoot()} now, and the range they report is read back with
 * {@code getSource(typeName, startLine, endLine)} - the attachment has no project and no
 * file, so {@code readProjectResource} can never be pointed at it.
 */
public class LibrarySourcePDETest
{
    private static final String BUILD_PROJECT = "LibrarySourceBuildProject";
    private static final String CONSUMER_PROJECT = "LibrarySourceConsumerProject";
    /** Created before, and named before, the consumer: getProjects() is documented in no particular
     *  order, so the fixture has to beat both plausible ones. Its point is to be the unreadable copy
     *  that a first-match lookup settles on. */
    private static final String BARE_PROJECT = "LibrarySourceBareJarProject";
    private static final String SOURCE = """
            package lib;

            import java.util.List;

            /** A thing in a JAR. */
            public class Thing
            {
                /** How many. */
                public int count;

                public int size()
                {
                    return 42;
                }

                public List<String> names()
                {
                    return List.of();
                }

                public String describe( String... labels )
                {
                    return String.join( ",", labels );
                }
            }
            """;

    /** Compiled into the same JAR, but deliberately absent from the sources JAR. */
    private static final String GADGET_SOURCE = """
            package lib;

            public class Gadget
            {
                /** Not a compile-time constant, so the class file gets a static initialiser. */
                static final long BASE = System.currentTimeMillis();

                public long compute( int factor )
                {
                    return factor * 2L;
                }

                public String describe( String... labels )
                {
                    return String.join( ",", labels );
                }
            }
            """;


    /** Also only in the JAR. An enum's class file spells out what its source never wrote. */
    private static final String MODE_SOURCE = """
            package lib;

            public enum Mode
            {
                FAST,
                SLOW
            }
            """;


    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private IProject buildProject;
    private IProject consumer;
    private IProject bare;
    private JavaDocService service;
    private CodeAnalysisService codeAnalysisService;
    private OutlineService outlineService;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        Map<String, byte[]> classes = compileFixture( root );

        bare = createJavaProject( root, BARE_PROJECT );
        consumer = createJavaProject( root, CONSUMER_PROJECT );
        IFolder lib = consumer.getFolder( "lib" );
        lib.create( IResource.NONE, true, monitor );
        IFile jar = lib.getFile( "thing.jar" );
        jar.create( new ByteArrayInputStream( jar( classes ) ), true, monitor );
        // Only Thing.java goes in: a partial sources JAR is what makes lib.Gadget the no-source case.
        IFile sources = lib.getFile( "thing-sources.jar" );
        sources.create( new ByteArrayInputStream(
                jar( Map.of( "lib/Thing.java", SOURCE.getBytes( StandardCharsets.UTF_8 ) ) ) ), true, monitor );

        IJavaProject javaProject = JavaCore.create( consumer );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaRuntime.getDefaultJREContainerEntry(),
                JavaCore.newLibraryEntry( jar.getFullPath(), sources.getFullPath(), null ) },
                consumer.getFullPath().append( "bin" ), monitor );

        // Its own copy of the same JAR, with no source attachment at all, so lib.Thing resolves in two
        // projects to two copies: this one, which cannot be read, and the consumer's, which can. A copy
        // rather than the consumer's file, so nothing about the fixture depends on whether JDT keys a
        // source attachment by classpath entry or by the file it points at.
        IFolder bareLib = bare.getFolder( "lib" );
        bareLib.create( IResource.NONE, true, monitor );
        IFile bareJar = bareLib.getFile( "thing.jar" );
        bareJar.create( new ByteArrayInputStream( jar( classes ) ), true, monitor );
        JavaCore.create( bare ).setRawClasspath( new IClasspathEntry[] {
                JavaRuntime.getDefaultJREContainerEntry(),
                JavaCore.newLibraryEntry( bareJar.getFullPath(), null, null ) },
                bare.getFullPath().append( "bin" ), monitor );

        // The build project also resolves lib.Thing, as workspace source; it must not be the one that answers.
        buildProject.delete( true, true, monitor );

        IEclipseContext context = EclipseContextFactory.create();
        context.set( ILog.class, Activator.getDefault().getLog() );
        context.set( AiIgnoreService.class, ContextInjectionFactory.make( AiIgnoreService.class, context ) );
        service = ContextInjectionFactory.make( JavaDocService.class, context );
        codeAnalysisService = ContextInjectionFactory.make( CodeAnalysisService.class, context );
        outlineService = ContextInjectionFactory.make( OutlineService.class, context );
    }

    @AfterEach
    public void afterEach() throws CoreException
    {
        for ( IProject project : new IProject[] { buildProject, bare, consumer } )
        {
            if ( project != null && project.exists() )
            {
                project.delete( true, true, monitor );
            }
        }
    }

    @Test
    public void getSourceReturnsTheAttachedSourceNotTheJar()
    {
        ResourceReadResult result = service.getSourceWithResource( "lib.Thing" );

        assertEquals( ResourceReadResult.ReadStatus.OK, result.status(), result.toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, result.origin() );
        assertTrue( result.content().contains( "public class Thing" ), result.content() );
        assertFalse( result.content().startsWith( "PK" ), "the JAR's own bytes are not source" );
        assertTrue( result.readOnly(), "attached source cannot be written back" );
        assertNull( result.filePath(), "a JAR is not a file the editing tools may be pointed at" );
    }

    @Test
    public void typeResolutionNamesTheJarButOffersNoFileToEdit()
    {
        TypeResolutionResponse response = service.explainTypeResolution( CONSUMER_PROJECT, "lib.Thing" );

        assertEquals( TypeResolutionResponse.Status.OK, response.status() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.sourceOrigin() );
        assertEquals( "/" + CONSUMER_PROJECT + "/lib/thing.jar", response.classpathEntryPath() );
        assertNull( response.filePath(), "the JAR is reported as the classpath entry, not as a file to edit" );
        assertNull( response.projectName() );
    }

    @Test
    public void classOutlineReadsAttachedSource()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Thing", true, Javadocs.Detail.SUMMARY );

        assertEquals( ClassOutlineResponse.Status.OK, response.status(), response.summaryText() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.origin() );
        assertNull( response.projectName(), "attached source has no project to read it back from" );
        assertNull( response.filePath() );
        assertEquals( "A thing in a JAR.", response.declaration().javadoc() );
        assertEquals( List.of( "count" ), response.fields().stream().map( ClassOutlineResponse.Member::name ).toList() );

        ClassOutlineResponse.Member size = member( response.methods(), "size" );
        assertTrue( size.startLine() > 0 && size.endLine() >= size.startLine(), size.toString() );
        assertTrue( member( response.methods(), "names" ).startLine() > 0 );
    }

    @Test
    public void classOutlineListsAGeneratedConstructorWithNoRange()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Thing", true, Javadocs.Detail.NONE );

        // The class declares no constructor, so the class file carries one the attached source does not.
        ClassOutlineResponse.Member generated = member( response.methods(), "Thing" );
        assertEquals( 0, generated.startLine(), "there is no line in the attachment to point at" );
        assertEquals( 0, generated.lineCount(), "and no lines to budget for reading it" );
    }

    @Test
    public void methodSourceReportsAGeneratedConstructorAsNotFound()
    {
        MethodSourceResponse response = outlineService.getMethodSource( "lib.Thing", "Thing", null, true );

        assertEquals( List.of( "Thing" ), response.notFound(),
                "a member with no source is missing from the read, not an empty success" );
        assertTrue( response.methods().isEmpty() );
    }

    @Test
    public void methodSourceReadsAttachedSource()
    {
        MethodSourceResponse response = outlineService.getMethodSource( "lib.Thing", "size", null, true );

        assertEquals( MethodSourceResponse.Status.OK, response.status(), response.diagnostics().toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.origin() );
        assertNull( response.projectName() );
        assertTrue( response.methods().get( 0 ).source().contains( "return 42;" ),
                response.methods().get( 0 ).source() );
    }

    @Test
    public void filteredSourceCollapsesAttachedSourceToo()
    {
        ResourceReadResult result = outlineService.getFilteredSource( "lib.Thing", true, "size" );

        assertEquals( ResourceReadResult.ReadStatus.PARTIAL, result.status(), result.toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, result.origin() );
        assertTrue( result.readOnly() );
        assertTrue( result.content().contains( "return 42;" ), result.content() );
        assertFalse( result.content().contains( "return List.of();" ),
                "the body of a method that was not asked for is what this tool omits" );
        assertFalse( result.content().contains( "import java.util.List;" ),
                "JDT models no import container for a class file, so the block is found in the text" );
    }

    @Test
    public void getSourceReturnsOnlyTheLinesAnOutlineNamed()
    {
        ClassOutlineResponse outline =
                codeAnalysisService.getClassOutline( "lib.Thing", true, Javadocs.Detail.NONE );
        ClassOutlineResponse.Member size = member( outline.methods(), "size" );

        ResourceReadResult result =
                service.getSourceWithResource( "lib.Thing", size.startLine(), size.endLine() );

        assertEquals( ResourceReadResult.ReadStatus.PARTIAL, result.status(), result.toString() );
        assertEquals( SourceOrigin.ATTACHED_SOURCE, result.origin() );
        assertEquals( size.startLine(), result.returnedRange().startLine() );
        assertEquals( size.endLine(), result.returnedRange().endLine() );
        assertTrue( result.content().contains( "return 42;" ), result.content() );
        assertFalse( result.content().contains( "public class Thing" ),
                "an outline's range is what makes reading one member of a library class cheap" );
        assertFalse( result.truncated(), "a range that was honoured in full is not a truncation" );
    }

    @Test
    public void classOutlineOfABinaryTypeWithNoSourceListsItsMembers()
    {
        // lib.Gadget is in the JAR but not in the sources JAR: the class file is all there is.
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Gadget", true, Javadocs.Detail.SUMMARY );

        assertEquals( ClassOutlineResponse.Status.OK, response.status(), response.summaryText() );
        assertEquals( SourceOrigin.DECOMPILED_CLASS, response.origin() );
        assertEquals( "public class Gadget", response.declaration().label() );

        ClassOutlineResponse.Member compute = member( response.methods(), "compute" );
        assertTrue( compute.label().contains( "compute" ), compute.label() );
        assertEquals( 0, compute.startLine(), "a class file carries no line to point at" );
        assertEquals( 0, compute.lineCount() );
        assertNull( compute.javadoc(), "and no documentation either" );
    }

    @Test
    public void varargsReadAsVarargsRatherThanTransient()
    {
        // ACC_VARARGS is the bit that means "transient" on a field, so a method carrying it used to
        // print as "transient ... (String[] labels)" - wrong in both halves of the signature.
        for ( String typeName : new String[] { "lib.Thing", "lib.Gadget" } )
        {
            ClassOutlineResponse response =
                    codeAnalysisService.getClassOutline( typeName, false, Javadocs.Detail.NONE );
            String label = member( response.methods(), "describe" ).label();

            assertTrue( label.contains( "String... labels" ), typeName + ": " + label );
            assertFalse( label.contains( "transient" ), typeName + ": " + label );
        }
    }

    @Test
    public void compilerOnlyMethodsAreLeftOutOfTheOutline()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Gadget", true, Javadocs.Detail.NONE );

        // lib.Gadget has a static initialiser, which JDT lists among the class file's methods.
        assertTrue( response.methods().stream().noneMatch( m -> "<clinit>".equals( m.name() ) ),
                "the static initialiser cannot be called from source and is not part of the API: "
                        + response.methods().stream().map( ClassOutlineResponse.Member::name ).toList() );
        assertNotNull( member( response.methods(), "compute" ), "and the real methods are still there" );
    }

    @Test
    public void anEnumOutlineReadsAsItWasWritten()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Mode", true, Javadocs.Detail.NONE );

        assertEquals( ClassOutlineResponse.Status.OK, response.status(), response.summaryText() );
        // The class file says "final ... extends java.lang.Enum"; the source said none of it.
        assertEquals( "public enum Mode", response.declaration().label() );

        assertEquals( List.of( "FAST", "SLOW" ),
                response.fields().stream().map( ClassOutlineResponse.Member::name ).toList(),
                "the synthetic $VALUES array is the compiler's, not the type's" );

        List<String> methods = response.methods().stream().map( ClassOutlineResponse.Member::name ).toList();
        assertTrue( methods.containsAll( List.of( "values", "valueOf" ) ), methods.toString() );
        assertFalse( methods.contains( "$values" ), methods.toString() );
        assertFalse( methods.contains( "<clinit>" ), methods.toString() );
    }

    @Test
    public void anOutlineWithNoRangesSaysSoInsteadOfClaimingLineZero()
    {
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Gadget", true, Javadocs.Detail.NONE );

        assertTrue( response.summaryText().contains( "signatures only" ), response.summaryText() );
        assertFalse( response.summaryText().contains( "lines 0-0" ), response.summaryText() );
    }

    @Test
    public void readingPrefersTheCopyThatHasSource() throws Exception
    {
        IType bareCopy = JavaCore.create( bare ).findType( "lib.Thing" );
        assertNotNull( bareCopy, "the fixture is pointless unless the bare project offers a second copy" );
        assertNull( bareCopy.getClassFile().getSource(),
                "and pointless unless that second copy really is the unreadable one" );

        // Both projects resolve lib.Thing; only the consumer's copy has an attachment, and the
        // outline must land on that one however the projects happen to be ordered.
        ClassOutlineResponse response =
                codeAnalysisService.getClassOutline( "lib.Thing", true, Javadocs.Detail.NONE );

        assertEquals( SourceOrigin.ATTACHED_SOURCE, response.origin(), response.summaryText() );
        assertTrue( member( response.methods(), "size" ).startLine() > 0 );
    }

    private static ClassOutlineResponse.Member member( List<ClassOutlineResponse.Member> members, String name )
    {
        return members.stream().filter( m -> name.equals( m.name() ) ).findFirst()
                .orElseThrow( () -> new AssertionError( "no member '" + name + "' in "
                        + members.stream().map( ClassOutlineResponse.Member::name ).toList() ) );
    }

    // ---- fixture ---------------------------------------------------------

    /** Compiles the fixture classes in a throwaway project and returns their class files by JAR entry name. */
    private Map<String, byte[]> compileFixture( IWorkspaceRoot root ) throws Exception
    {
        buildProject = createJavaProject( root, BUILD_PROJECT );
        IJavaProject javaProject = JavaCore.create( buildProject );
        createFolder( buildProject, "src" );
        createFolder( buildProject, "src/lib" );
        buildProject.getFile( "src/lib/Thing.java" )
                .create( new ByteArrayInputStream( SOURCE.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        buildProject.getFile( "src/lib/Gadget.java" )
                .create( new ByteArrayInputStream( GADGET_SOURCE.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        buildProject.getFile( "src/lib/Mode.java" )
                .create( new ByteArrayInputStream( MODE_SOURCE.getBytes( StandardCharsets.UTF_8 ) ), true, monitor );
        javaProject.setRawClasspath( new IClasspathEntry[] {
                JavaCore.newSourceEntry( buildProject.getFullPath().append( "src" ) ),
                JavaRuntime.getDefaultJREContainerEntry() },
                buildProject.getFullPath().append( "bin" ), monitor );

        buildProject.build( IncrementalProjectBuilder.FULL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor );
        Job.getJobManager().join( ResourcesPlugin.FAMILY_AUTO_BUILD, monitor );
        buildProject.refreshLocal( IResource.DEPTH_INFINITE, monitor );

        Map<String, byte[]> classes = new LinkedHashMap<>();
        for ( String name : new String[] { "Thing", "Gadget", "Mode" } )
        {
            IFile classFile = buildProject.getFile( "bin/lib/" + name + ".class" );
            assertTrue( classFile.exists(),
                    "the fixture did not compile; the JRE container may be unresolved in this workspace" );
            try ( var in = classFile.getContents() )
            {
                classes.put( "lib/" + name + ".class", in.readAllBytes() );
            }
        }
        return classes;
    }

    private IProject createJavaProject( IWorkspaceRoot root, String name ) throws CoreException
    {
        IProject project = root.getProject( name );
        if ( project.exists() )
        {
            project.delete( true, true, monitor );
        }
        IProjectDescription description = root.getWorkspace().newProjectDescription( name );
        description.setNatureIds( new String[] { JavaCore.NATURE_ID } );
        ICommand javaBuilder = description.newCommand();
        javaBuilder.setBuilderName( JavaCore.BUILDER_ID );
        description.setBuildSpec( new ICommand[] { javaBuilder } );
        project.create( description, monitor );
        project.open( monitor );
        createFolder( project, "bin" );
        return project;
    }

    private void createFolder( IProject project, String path ) throws CoreException
    {
        IFolder folder = project.getFolder( path );
        if ( !folder.exists() )
        {
            folder.create( IResource.NONE, true, monitor );
        }
    }

    private static byte[] jar( Map<String, byte[]> entries ) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try ( JarOutputStream out = new JarOutputStream( bytes ) )
        {
            for ( Map.Entry<String, byte[]> entry : entries.entrySet() )
            {
                out.putNextEntry( new JarEntry( entry.getKey() ) );
                out.write( entry.getValue() );
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
