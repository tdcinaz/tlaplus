package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The {@code @tlatex:} directive notation: parsing, warnings, and removal of directive lines. */
public class DirectivesTest {

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        BuiltInSymbols.Initialize();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-directives-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    private static Directives parse(String... bodies) {
        Directives d = new Directives();
        for (int i = 0; i < bodies.length; i++) {
            d.parse(bodies[i], "line " + (i + 1));
        }
        return d;
    }

    private String body(String spec) throws IOException {
        TLA2TexTestSupport.resetStatics();
        return TLA2TexTestSupport.documentBody(TLA2TexTestSupport.latexWithoutAlignment(spec, workDir));
    }

    // ----------------------------------------------------------- recognition

    @Test
    public void directiveBodyRecognisesCommentAndPrologForms() {
        assertEquals("symbol alpha \\alpha", Directives.directiveBody(" @tlatex: symbol alpha \\alpha", false));
        assertEquals("preset greek", Directives.directiveBody("\\* @tlatex:   preset greek  ", false));
        assertEquals("preset greek", Directives.directiveBody("@tlatex:preset greek", false));
        assertEquals("preset greek", Directives.directiveBody(" * @tlatex: preset greek        *", true));
        assertEquals("macro Star/1 #1^*", Directives.directiveBody(" @tlatex: macro Star/1 #1^*", false));
        assertNull(Directives.directiveBody(" @type: Int => Int;", false));
        assertNull(Directives.directiveBody(" see @tlatex: in the docs", false));
        assertNull(Directives.directiveBody(" @tlatex symbol a b", false));
    }

    // ---------------------------------------------------------------- parsing

    @Test
    public void symbolMacroAndPresetAreParsed() {
        Directives d = parse("symbol alpha \\alpha", "macro Integral/3 \\int_{#2}^{#3} #1", "preset greek",
                             "symbol 1x \\one");
        assertEquals("\\one", d.symbolFor("1x"));
        assertEquals("\\alpha", d.symbolFor("alpha"));
        assertEquals("\\Omega", d.symbolFor("Omega"));
        assertNull(d.symbolFor("alpha1"));
        assertNull(d.symbolFor("omicron"));
        assertEquals("\\int_{#2}^{#3} #1", d.macroFor("Integral", 3).template);
        assertNull(d.macroFor("Integral", 2));
        assertTrue(d.hasMacroNamed("Integral"));
        assertTrue(d.warnings().isEmpty());
    }

    @Test
    public void explicitSymbolBeatsPresetWhateverTheOrder() {
        Directives d = parse("symbol pi \\varpi", "preset greek");
        assertEquals("\\varpi", d.symbolFor("pi"));
        assertTrue(d.warnings().isEmpty());
    }

    @Test
    public void templateSubstitutesBracedArgumentsAndDoubleHash() {
        Directives.Macro m = parse("macro Pow/2 #1^{#2} ## done").macroFor("Pow", 2);
        assertEquals("{x}^{{2}} # done", m.instantiate(new String[] { " x", "2 " }));
    }

    @Test
    public void malformedDirectivesAreReportedWithTheirLineAndIgnored() {
        Directives d = parse(
            "frobnicate x",              // 1 unknown command
            "symbol x-y \\one",          // 2 not an identifier
            "symbol ENABLED \\E",        // 3 keyword
            "symbol beta",               // 4 no TeX
            "macro Sqrt \\sqrt{#1}",     // 5 no arity
            "macro Sqrt/0 x",            // 6 arity out of range
            "macro Frac/2 \\frac{#1}{#3}", // 7 #3 > arity
            "macro Odd/1 50#",           // 8 dangling #
            "preset klingon",            // 9 unknown preset
            "");                         // 10 empty
        List<String> w = d.warnings();
        assertEquals(w.toString(), 10, w.size());
        for (int i = 0; i < w.size(); i++) {
            assertTrue(w.get(i), w.get(i).startsWith("line " + (i + 1) + ":"));
        }
        assertTrue(w.get(6), w.get(6).contains("#3") && w.get(6).contains("arity is 2"));
        assertNull(d.symbolFor("beta"));
        assertNull(d.macroFor("Frac", 2));
        assertFalse(d.hasMacros());
    }

    @Test
    public void redefinitionWarnsAndTheLastDefinitionWins() {
        Directives d = parse("symbol a \\alpha", "symbol a \\alpha", "symbol a \\aleph",
                             "macro F/1 f(#1)", "macro F/1 g(#1)", "macro F/2 h(#1,#2)");
        assertEquals(2, d.warnings().size());
        assertTrue(d.warnings().get(0), d.warnings().get(0).contains("was `\\alpha' at line 2"));
        assertEquals("\\aleph", d.symbolFor("a"));
        assertEquals("g(#1)", d.macroFor("F", 1).template);
        assertEquals("h(#1,#2)", d.macroFor("F", 2).template);
    }

    @Test
    public void directivesAreCollectedFromEveryKindOfCommentLine() {
        String spec = String.join("\n",
            "@tlatex: symbol p1 \\pi_1",
            "---- MODULE M ----",
            "\\* @tlatex: symbol a \\alpha",
            "(* @tlatex: symbol b \\beta *)",
            "(**************************)",
            "(* @tlatex: symbol c \\gamma *)",
            "(**************************)",
            "(* first line",
            "   @tlatex: symbol d \\delta",
            "   last line *)",
            "x == 1 \\* @tlatex: symbol e \\epsilon",
            "====",
            "\\* @tlatex: symbol z \\zeta",
            "");
        Directives.prepare(TLA2TexTestSupport.tokenize(spec), () -> spec.getBytes());
        Directives d = Directives.active;
        assertEquals("\\pi_1", d.symbolFor("p1"));
        for (String[] pair : new String[][] { { "a", "\\alpha" }, { "b", "\\beta" }, { "c", "\\gamma" },
                                              { "d", "\\delta" }, { "e", "\\epsilon" }, { "z", "\\zeta" } }) {
            assertEquals(pair[0], pair[1], d.symbolFor(pair[0]));
        }
        assertTrue(d.warnings().toString(), d.warnings().isEmpty());
    }

    // ---------------------------------------------------------------- removal

    @Test
    public void specWithOnlyDirectivesHasTheSameBodyAsWithoutThem() throws IOException {
        String plain = String.join("\n",
            "Prolog text.",
            "---- MODULE M ----",
            "\\* A comment",
            "\\* that continues.",
            "(*************************)",
            "(* Boxed comment         *)",
            "(*************************)",
            "(* Paragraph comment",
            "   second line *)",
            "x == 1 \\* end comment",
            "y == 2",
            "====",
            "Epilog text.",
            "");
        String withDirectives = String.join("\n",
            "Prolog text.",
            "\\* @tlatex: preset greek",
            "---- MODULE M ----",
            "\\* @tlatex: symbol alpha \\alpha",
            "\\* A comment",
            "\\* @tlatex: macro Sqrt/1 \\sqrt{#1}",
            "\\* that continues.",
            "(*************************)",
            "(* Boxed comment         *)",
            "(* @tlatex: symbol b \\beta *)",
            "(*************************)",
            "(* @tlatex: symbol c \\gamma *)",
            "(* Paragraph comment",
            "   @tlatex: symbol d \\delta",
            "   second line *)",
            "(* @tlatex: symbol e \\epsilon",
            "   @tlatex: symbol f \\phi *)",
            "x == 1 \\* end comment",
            "y == 2 \\* @tlatex: symbol g \\gamma",
            "====",
            "Epilog text.",
            "@tlatex: symbol h \\eta",
            "");
        assertEquals(body(plain), body(withDirectives));
    }

    @Test
    public void otherToolsAnnotationsAreLeftAlone() throws IOException {
        String spec = String.join("\n",
            "---- MODULE M ----",
            "\\* @type: Int => Int;",
            "x == 1",
            "====",
            "");
        assertTrue(body(spec).contains("@type"));
        assertTrue(Directives.active.warnings().isEmpty());
    }

    @Test
    public void tlaOutFileDropsDirectiveLines() throws IOException {
        String spec = String.join("\n",
            "---- MODULE M ----",
            "\\* @tlatex: symbol alpha \\alpha",
            "\\* kept",
            "x == 1",
            "====",
            "");
        Token[][] toks = TLA2TexTestSupport.analyze(spec);
        Path out = workDir.resolve("out.tla");
        WriteTLAFile.Write(toks, out.toString());
        String text = TLA2TexTestSupport.readFile(out);
        assertFalse(text, text.contains("@tlatex"));
        assertTrue(text, text.contains("\\* kept"));
    }

    @Test
    public void directivesFileIsReadAndBadLinesAreReported() throws IOException {
        Path file = workDir.resolve("shared.tlatex");
        TLA2TexTestSupport.writeFile(file, String.join("\n",
            "@tlatex: symbol alpha \\alpha",
            "",
            "\\* @tlatex: macro Sqrt/1 \\sqrt{#1}",
            "not a directive",
            ""));
        Parameters.DirectivesFile = file.toString();
        String spec = "---- MODULE M ----\nx == 1\n====\n";
        Directives.prepare(TLA2TexTestSupport.tokenize(spec), () -> spec.getBytes());
        Directives d = Directives.active;
        assertEquals("\\alpha", d.symbolFor("alpha"));
        assertEquals("\\sqrt{#1}", d.macroFor("Sqrt", 1).template);
        assertEquals(1, d.warnings().size());
        assertTrue(d.warnings().get(0), d.warnings().get(0).startsWith(file + ":4:"));
    }

    @Test
    public void plusCalPositionsFollowRemovedLines() {
        String spec = String.join("\n",
            "---- MODULE M ----",
            "\\* @tlatex: symbol alpha \\alpha",
            "\\* @tlatex: symbol beta \\beta",
            "(* --algorithm A",
            "   begin skip;",
            "   end algorithm *)",
            "====",
            "");
        Token[][] toks = TLA2TexTestSupport.tokenize(spec);
        int start = TokenizeSpec.pcalStart.line;
        int end = TokenizeSpec.pcalEnd.line;
        Directives.prepare(toks, () -> spec.getBytes());
        assertEquals(start - 2, TokenizeSpec.pcalStart.line);
        assertEquals(end - 2, TokenizeSpec.pcalEnd.line);
    }
}
