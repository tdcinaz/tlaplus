package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test that runs the real command line, including both LaTeX runs,
 * on every spec in {@code test-model/tla2tex/}. Skipped when {@code pdflatex}
 * is not on {@code PATH}.
 *
 * <p>TLATeX runs LaTeX in {@code \batchmode} and exits with an error when LaTeX
 * does; the test also checks the {@code .log} for error lines.
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

    @Test
    public void sourcePaddingAfterAMacroAddsNoSpace() throws IOException, InterruptedException {
        Path workDir = Files.createDirectory(workRoot.resolve("Pad"));
        TLA2TexTestSupport.writeFile(workDir.resolve("Pad.tla"), String.join("\n",
            "---- MODULE Pad ----",
            "\\* @tlatex: macro Sqrt/1 \\sqrt{#1}",
            "A == Sqrt(2)      + Foo(2)      + 1",
            "===="));
        TLA2TexTestSupport.CliResult result = TLA2TexTestSupport.runCli(workDir,
            "-latexCommand", "pdflatex", "-nops", "Pad.tla");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, result.output.contains("TLATeX pdf output written on Pad.pdf."));
        String tex = TLA2TexTestSupport.readFile(workDir.resolve("Pad.tex"));
        // The spaces after Sqrt(2) padded the source text, which the macro replaces;
        // the same spaces after Foo(2) still widen the output as they always have.
        assertTrue(tex, tex.contains("\\sqrt{{2}} \\.{+} Foo ( 2 )\\@s{"));
    }

    @Test
    public void latexErrorGivesANonZeroExitAfterTheOutputIsWritten() throws IOException, InterruptedException {
        Path workDir = Files.createDirectory(workRoot.resolve("Bad"));
        TLA2TexTestSupport.writeFile(workDir.resolve("Bad.tla"), String.join("\n",
            "---- MODULE Bad ----",
            "(* `^\\undefinedcommand^' *)",
            "A == 1",
            "===="));
        TLA2TexTestSupport.CliResult result = TLA2TexTestSupport.runCli(workDir,
            "-latexCommand", "pdflatex", "-nops", "Bad.tla");
        assertTrue(result.output, result.exitCode != 0);
        assertTrue(result.output, result.output.contains("LaTeX reported an error typesetting Bad.tex"));
        assertTrue(Files.exists(workDir.resolve("Bad.tex")));
        assertTrue(Files.exists(workDir.resolve("Bad.pdf")));
    }

    @Test
    public void inputAndOutputAreUtf8WhateverTheDefaultEncoding() throws IOException, InterruptedException {
        Path workDir = Files.createDirectory(workRoot.resolve("Utf8"));
        TLA2TexTestSupport.writeFile(workDir.resolve("Utf8.tla"), String.join("\n",
            "---- MODULE Utf8 ----",
            "\\* Caf\u00e9 cr\u00e8me",
            "A == 1",
            "===="));
        TLA2TexTestSupport.CliResult result = TLA2TexTestSupport.runCliWithJvmArgs(workDir,
            List.of("-Dfile.encoding=US-ASCII"), "-latexCommand", "pdflatex", "-nops", "Utf8.tla");
        assertEquals(result.output, 0, result.exitCode);
        String tex = TLA2TexTestSupport.readFile(workDir.resolve("Utf8.tex"));
        assertTrue(tex, tex.contains("Caf\u00e9 cr\u00e8me"));
    }

    @Test
    public void outputFileNamesMayContainSpaces() throws IOException, InterruptedException {
        Path workDir = Files.createDirectory(workRoot.resolve("Spaces"));
        TLA2TexTestSupport.writeFile(workDir.resolve("Spaces.tla"), "---- MODULE Spaces ----\nA == 1\n====\n");
        TLA2TexTestSupport.CliResult result = TLA2TexTestSupport.runCli(workDir,
            "-latexCommand", "pdflatex", "-nops", "-out", "my spec", "Spaces.tla");
        assertEquals(result.output, 0, result.exitCode);
        assertTrue(result.output, Files.exists(workDir.resolve("my spec.pdf")));
    }
}
