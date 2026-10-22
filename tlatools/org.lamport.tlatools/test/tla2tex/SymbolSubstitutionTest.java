package tla2tex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Identifier-to-symbol substitution driven by {@code symbol} and {@code preset} directives. */
public class SymbolSubstitutionTest {

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-symbols-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    private String body(String... moduleLines) throws IOException {
        String spec = "---- MODULE M ----\n"
                      + "\\* @tlatex: preset greek\n"
                      + "\\* @tlatex: symbol vmax v_{\\mathrm{max}}\n"
                      + "CONSTANTS alpha, nu, chi, alpha1, vmax\n"
                      + String.join("\n", moduleLines) + "\n====\n";
        return TLA2TexTestSupport.documentBody(TLA2TexTestSupport.latexWithoutAlignment(spec, workDir));
    }

    private static void assertContains(String body, String expected) {
        assertTrue("Expected `" + expected + "' in:\n" + body, body.contains(expected));
    }

    @Test
    public void identifiersBecomeBracedSymbols() throws IOException {
        String b = body("A == alpha + vmax");
        assertContains(b, "A \\.{\\defeq} {\\alpha} \\.{+} {v_{\\mathrm{max}}}");
        assertContains(b, "{\\CONSTANTS} {\\alpha} ,\\, {\\nu} ,\\, {\\chi}");
    }

    @Test
    public void onlyWholeIdentifiersAreReplaced() throws IOException {
        String b = body("A == alpha1 + alpha");
        assertContains(b, "A \\.{\\defeq} alpha1 \\.{+} {\\alpha}");
    }

    @Test
    public void primesSubscriptsAndApplicationsStillWork() throws IOException {
        String b = body("A == alpha' + WF_alpha(nu) + [][nu]_alpha", "B == alpha(1)");
        assertContains(b, "{\\alpha} \\.{'}");
        assertContains(b, "{\\WF}_{ {\\alpha}} ( {\\nu} )");
        assertContains(b, "[ {\\nu} ]_{ {\\alpha}}");
        assertContains(b, "B \\.{\\defeq} {\\alpha} ( 1 )");
    }

    @Test
    public void commentTokensClassifiedAsTlaGetTheSymbol() throws IOException {
        String b = body("A == alpha'", "\\* alpha and chi are constants; alpha' is primed.");
        // "alpha" is an English word, so prose stays text; chi and alpha' are TLA tokens.
        assertContains(b, " alpha and \\ensuremath{{\\chi}} are constants;");
        assertContains(b, "\\ensuremath{{\\alpha}\\.{'}} is");
    }

    @Test
    public void tlaCommentOptionTurnsAmbiguousWordsIntoSymbols() throws IOException {
        Parameters.TLACommentOption = true;
        String b = body("\\* The alpha particle.");
        assertContains(b, "The \\ensuremath{{\\alpha}} particle.");
    }

    @Test
    public void withoutDirectivesIdentifiersStayAsTheyAre() throws IOException {
        String spec = "---- MODULE M ----\nCONSTANT alpha\nA == alpha\n\\* chi here\n====\n";
        String b = TLA2TexTestSupport.documentBody(TLA2TexTestSupport.latexWithoutAlignment(spec, workDir));
        assertContains(b, "A \\.{\\defeq} alpha");
        assertFalse(b, b.contains("\\alpha"));
        assertFalse(b, b.contains("\\chi"));
    }
}
