package consulo.httpClient.cli;

import consulo.application.Application;
import consulo.disposer.AutoDisposable;
import consulo.language.psi.PsiFile;
import consulo.language.psi.PsiManager;
import consulo.project.Project;
import consulo.util.io.FileUtil;
import consulo.test.light.LightProjectBuilder;
import consulo.test.light.impl.LightFileTypeRegistry;
import consulo.virtualFileSystem.StandardFileSystems;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.fileType.FileTypeRegistry;
import org.javamaster.httpclient.HttpFileType;
import org.javamaster.httpclient.parser.HttpFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Headless Consulo application and project, enough to parse .http files with the plugin's lexer and parser
 * and to run requests. Same approach as the BNF generator of maven-consulo-plugin.
 *
 * @author VISTALL
 * @since 2026-11-03
 */
public final class HttpCliLightPlatform implements AutoCloseable {
    private final AutoDisposable myDisposable = AutoDisposable.newAutoDisposable();
    private final Application myApplication;
    private final Project myProject;

    public HttpCliLightPlatform() {
        myApplication = HttpCliApplication.create(myDisposable);

        LightFileTypeRegistry fileTypeRegistry = (LightFileTypeRegistry) myApplication.getInstance(FileTypeRegistry.class);
        fileTypeRegistry.registerFileType(HttpFileType.INSTANCE, "http");
        fileTypeRegistry.registerFileType(HttpFileType.INSTANCE, "rest");

        myProject = LightProjectBuilder.create(myApplication).build();
    }

    public Application getApplication() {
        return myApplication;
    }

    public Project getProject() {
        return myProject;
    }

    public @Nullable HttpFile findHttpFile(Path path) {
        VirtualFile file = StandardFileSystems.local().findFileByPath(toVfsPath(path));
        if (file == null) {
            return null;
        }
        PsiFile psiFile = myApplication.runReadAction((Supplier<PsiFile>) () -> PsiManager.getInstance(myProject).findFile(file));
        return psiFile instanceof HttpFile httpFile ? httpFile : null;
    }

    /**
     * The VFS expects '/' separators and the real path: on Windows a temp path may be an 8.3 short name (RUNNER~1).
     */
    private static String toVfsPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            absolute = absolute.toRealPath();
        }
        catch (IOException e) {
            // keep the absolute path, the caller reports a missing file
        }
        return FileUtil.toSystemIndependentName(absolute.toString());
    }

    @Override
    public void close() {
        myDisposable.close();
    }
}
