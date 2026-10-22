package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Command-line handling and how TLATeX runs external programs. */
public class CommandLineTest {

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-cli-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    @Test
    public void helpAndInfoReturnInsteadOfExiting() {
        // System.exit here would end the test JVM and fail the run.
        TLA.runTranslation(new String[] { "-help" });
        TLA.runTranslation(new String[] { "-info" });
    }

    @Test
    public void helpAndInfoExitWithZeroFromTheCommandLine() throws IOException, InterruptedException {
        TLA2TexTestSupport.CliResult help = TLA2TexTestSupport.runCli(workDir, "-help");
        assertEquals(help.output, 0, help.exitCode);
        assertTrue(help.output, help.output.contains("-directives file"));
        TLA2TexTestSupport.CliResult info = TLA2TexTestSupport.runCli(workDir, "-info");
        assertEquals(info.output, 0, info.exitCode);
        assertTrue(info.output, info.output.contains("TYPESETTING IDENTIFIERS AS SYMBOLS AND OPERATORS AS MACROS"));
    }

    @Test
    public void outputExtensionFollowsTheLatexCommand() {
        assertFalse(TLA.LaTeXProducesPdf("latex"));
        assertTrue(TLA.LaTeXProducesPdf("pdflatex"));
        assertTrue(TLA.LaTeXProducesPdf("pdflatex -interaction=batchmode"));
        assertTrue(TLA.LaTeXProducesPdf("/usr/local/texlive/bin/x86_64-linux/lualatex"));
        assertTrue(TLA.LaTeXProducesPdf("\"C:\\Program Files\\MiKTeX\\bin\\x64\\xelatex.exe\""));
        assertFalse(TLA.LaTeXProducesPdf("platex"));
    }

    @Test
    public void commandsAreSplitIntoWordsAndTheFileStaysOneArgument() throws IOException {
        assertEquals(List.of("pdflatex", "my spec.tex"), ExecuteCommand.commandLine("pdflatex", "my spec.tex"));
        assertEquals(List.of("pdflatex", "-interaction=batchmode", "a.tex"),
                     ExecuteCommand.commandLine("  pdflatex   -interaction=batchmode ", "a.tex"));
        assertEquals(List.of("C:\\Program Files\\TeX\\pdflatex.exe", "-x", "a.tex"),
                     ExecuteCommand.commandLine("\"C:\\Program Files\\TeX\\pdflatex.exe\" -x", "a.tex"));
        Path program = workDir.resolve("my latex");
        TLA2TexTestSupport.writeFile(program, "");
        assertEquals(List.of(program.toString(), "a.tex"), ExecuteCommand.commandLine(program.toString(), "a.tex"));
    }
}
