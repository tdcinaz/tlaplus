package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test that runs the real command line, including both LaTeX runs,
 * on every spec in {@code test-model/tla2tex/}. Skipped when {@code pdflatex}
 * is not on {@code PATH}.
 *
 * <p>TLATeX runs LaTeX in {@code \batchmode} and ignores its exit code, so the
 * test inspects the {@code .log} for error lines itself.
 */
public class TypesetWithLaTeXTest {

    private Path workRoot;

    @Before
    public void requirePdflatex() throws IOException {
        Assume.assumeTrue("pdflatex is not on PATH; skipping", TLA2TexTestSupport.isOnPath("pdflatex"));
        TLA2TexTestSupport.resetStatics();
        workRoot = TLA2TexTestSupport.createTempDir("tla2tex-latex-");
    }

    @After
    public void cleanUp() throws IOException {
        if (workRoot != null) {
            TLA2TexTestSupport.deleteRecursively(workRoot);
        }
    }

    @Test
    public void everyTestModelSpecTypesetsToPdfWithoutLatexErrors() throws IOException, InterruptedException {
        for (Path spec : TLA2TexTestSupport.specsInTestModel()) {
            String fileName = spec.getFileName().toString();
            String root = fileName.substring(0, fileName.length() - ".tla".length());
            Path workDir = Files.createDirectory(workRoot.resolve(root));
            Files.copy(spec, workDir.resolve(fileName));

            // Same flags the VS Code extension uses for "Export module to PDF".
            TLA2TexTestSupport.CliResult result = TLA2TexTestSupport.runCli(workDir,
                "-latexCommand", "pdflatex", "-nops", "-shade", "-grayLevel", "0.85", fileName);

            assertEquals("tla2tex exit code for " + fileName + ":\n" + result.output, 0, result.exitCode);
            assertTrue(".tex missing for " + fileName, Files.exists(workDir.resolve(root + ".tex")));
            assertTrue(".pdf missing for " + fileName, Files.exists(workDir.resolve(root + ".pdf")));
            Path log = workDir.resolve(root + ".log");
            assertTrue(".log missing for " + fileName, Files.exists(log));
            assertEquals("LaTeX errors in " + log, 0, TLA2TexTestSupport.countLatexErrors(log));
        }
    }
}
