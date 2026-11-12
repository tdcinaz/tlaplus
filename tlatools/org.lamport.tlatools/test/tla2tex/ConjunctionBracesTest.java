package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The {@code brace conjunctions} directive: a definition whose body is a
 * conjunction list is typeset with a tall left brace instead of bullets. Each
 * test parses a small module from a string; the output is checked without
 * running LaTeX (see {@link TypesetWithLaTeXTest} for the LaTeX run).
 */
public class ConjunctionBracesTest {

    private static final String DIRECTIVE = "\\* @tlatex: brace conjunctions\n";

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-braces-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    private static String module(String... lines) {
        return "---- MODULE M ----\n" + DIRECTIVE + "EXTENDS Naturals\nVARIABLES x, y\n"
               + String.join("\n", lines) + "\n====\n";
    }

    /** The left-hand sides (the token before the ==) of the definitions typeset with a brace. */
    private static List<String> braced(String spec) {
        Token[][] toks = TLA2TexTestSupport.analyze(spec);
        List<String> result = new ArrayList<>();
        for (Token[] row : toks) {
            for (int i = 0; i < row.length; i++) {
                if (row[i].braceRegion != null) {
                    result.add(i == 0 ? "" : row[i - 1].string);
                }
            }
        }
        return result;
    }

    private String tex(String spec) throws IOException {
        TLA2TexTestSupport.resetStatics();
        return TLA2TexTestSupport.latexWithoutAlignment(spec, workDir);
    }

    private String body(String spec) throws IOException {
        return TLA2TexTestSupport.documentBody(tex(spec));
    }

    private static void assertContains(String body, String expected) {
        assertTrue("Expected `" + expected + "' in:\n" + body, body.contains(expected));
    }

    // ------------------------------------------------------------- braced

    @Test
    public void listAfterTheDefineIsBoxedWithInvisibleBullets() throws IOException {
        String spec = module("Inv == /\\ x \\in Nat", "       /\\ y \\in Nat", "B == 1");
        assertEquals(List.of("Inv"), braced(spec));
        assertContains(body(spec), String.join("\n",
            "\\@bx{ Inv \\.{\\defeq}}{%",
            "\\@x{\\phantom{ \\.{\\land}} x \\.{\\in} Nat}%",
            "\\@x{\\phantom{ \\.{\\land}} y \\.{\\in} Nat}%",
            "}%",
            "\\@x{ B \\.{\\defeq} 1}%"));
    }

    @Test
    public void listOnTheNextLinePassesItsColumn() throws IOException {
        String spec = module("Inv ==", "  /\\ x \\in Nat", "  /\\ y \\in Nat");
        assertEquals(List.of("Inv"), braced(spec));
        assertContains(body(spec), String.join("\n",
            "\\@bx[0]{ Inv \\.{\\defeq}}{%",
            "\\@x{\\phantom{ \\.{\\land}} x \\.{\\in} Nat}%",
            "\\@x{\\phantom{ \\.{\\land}} y \\.{\\in} Nat}%",
            "}%"));
    }

    @Test
    public void nestedListsGetNestedBoxesAndOnlyTheirOwnBulletsHide() throws IOException {
        String spec = module(
            "Inv == /\\ x = 1",
            "       /\\ \\/ y = 1",
            "          \\/ y = 2",
            "       /\\ LET P == /\\ x = 2",
            "                   /\\ y = 3",
            "          IN P");
        assertEquals(List.of("Inv", "P"), braced(spec));
        String b = body(spec);
        assertContains(b, "\\@x{\\phantom{ \\.{\\land}} \\.{\\lor} y \\.{=} 1}%");
        assertContains(b, "\\@bx{\\phantom{ \\.{\\land}} \\.{\\LET} P \\.{\\defeq}}{%");
        assertContains(b, "\\@x{\\phantom{ \\.{\\land}} y \\.{=} 3}%\n}%\n\\@x{ \\.{\\IN} P}%\n}%");
    }

    @Test
    public void innerListFollowedByInOnItsLastLineIsTypesetAsUsual() {
        String spec = module(
            "Inv == /\\ x = 1",
            "       /\\ LET P == /\\ x = 2",
            "                   /\\ y = 3 IN P",
            "B == 1");
        assertEquals(List.of("Inv"), braced(spec));
        String sameLineIn = module(
            "Q == LET R == /\\ x = 1",
            "              /\\ y = 2",
            "     IN R");
        assertEquals(List.of("R"), braced(sameLineIn));
    }

    @Test
    public void endOfLineCommentsStayInTheBox() throws IOException {
        String spec = module("Inv == /\\ x \\in Nat  \\* first", "       /\\ y \\in Nat  \\* second");
        assertEquals(List.of("Inv"), braced(spec));
        String b = body(spec);
        int open = b.indexOf("\\@bx{");
        int second = b.indexOf("second");
        int close = b.indexOf("}%\n", b.indexOf("\\@xx{", second));
        assertTrue(b, open >= 0 && open < b.indexOf("first") && second < close);
    }

    @Test
    public void functionDefinitionsAndMacrosWorkTogether() throws IOException {
        String spec = "---- MODULE M ----\n" + DIRECTIVE
                      + "\\* @tlatex: macro Abs/1 \\vert #1 \\vert\n"
                      + "EXTENDS Naturals\n"
                      + "f[n \\in Nat] == /\\ Abs(n) > 0\n"
                      + "                /\\ n < 5\n"
                      + "====\n";
        assertEquals(List.of("]"), braced(spec));
        assertContains(body(spec), "\\@x{\\phantom{ \\.{\\land}} \\vert {n} \\vert \\.{>} 0}%");
    }

    @Test
    public void bracesStyleIsInTheOutputOnlyWithTheDirective() throws IOException {
        String with = module("Inv == /\\ x = 1", "       /\\ y = 2");
        assertContains(tex(with), "\\newcommand{\\@bx}");
        String without = with.replace(DIRECTIVE, "");
        assertFalse(tex(without).contains("\\@bx"));
        assertFalse(tex(without).contains("\\phantom"));
    }

    // ------------------------------------------------------------ declined

    @Test
    public void oneConjunctIsTypesetAsUsual() throws IOException {
        String spec = module("Inv == /\\ x = 1");
        assertTrue(braced(spec).isEmpty());
        assertContains(body(spec), "\\@x{ Inv \\.{\\defeq} \\.{\\land} x \\.{=} 1}%");
    }

    @Test
    public void bodiesThatAreNotJustAListAreTypesetAsUsual() {
        assertTrue(braced(module("Inv == \\A z \\in Nat : /\\ z = x", "                       /\\ z = y")).isEmpty());
        assertTrue(braced(module("Inv == \\/ x = 1", "       \\/ y = 1")).isEmpty());
    }

    @Test
    public void commentBetweenTheDefineAndTheListDeclinesIt() {
        assertTrue(braced(module("Inv == \\* the invariant", "  /\\ x = 1", "  /\\ y = 1")).isEmpty());
        assertTrue(braced(module("Inv ==", "  \\* the invariant", "  /\\ x = 1", "  /\\ y = 1")).isEmpty());
    }

    @Test
    public void commentLeftOfTheBulletsDeclinesIt() {
        assertTrue(braced(module("Inv == /\\ x = 1", "\\* about y", "       /\\ y = 1")).isEmpty());
    }

    @Test
    public void multiLineCommentInsideDeclinesIt() {
        assertTrue(braced(module("Inv == /\\ x = 1   (* about x,",
                                 "                     continued *)",
                                 "       /\\ y = 1")).isEmpty());
    }

    @Test
    public void listsLongerThanTheLimitAreTypesetAsUsual() {
        List<String> lines = new ArrayList<>();
        lines.add("Inv == /\\ x = 0");
        for (int i = 1; i < ConjunctionBraces.MAX_LINES; i++) {
            lines.add("       /\\ x = " + i);
        }
        assertEquals(List.of("Inv"), braced(module(lines.toArray(new String[0]))));
        lines.add("       /\\ y = 0");
        assertTrue(braced(module(lines.toArray(new String[0]))).isEmpty());
    }

    @Test
    public void lineNumbersDeclineAllLists() {
        Parameters.PrintLineNumbers = true;
        List<String> got = braced(module("Inv == /\\ x = 1", "       /\\ y = 1"));
        Parameters.PrintLineNumbers = false;
        assertTrue(got.isEmpty());
    }

    @Test
    public void listsInPlusCalCodeAreLeftAlone() {
        String spec = module(
            "(* --algorithm Alg",
            "   variable z = 0;",
            "   define Inv == /\\ z = 0",
            "                 /\\ z < 1",
            "   end define;",
            "   begin skip;",
            "   end algorithm *)",
            "Inv2 == /\\ x = 1",
            "        /\\ y = 1");
        assertEquals(List.of("Inv2"), braced(spec));
    }

    @Test
    public void specSanyRejectsGetsOneWarningAndTheUsualOutput() throws IOException {
        String bad = module("Inv == /\\ x = 1", "       /\\ y = ", "B == 1");
        String b = body(bad);
        List<String> warnings = Directives.active.warnings();
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).startsWith("brace directive not applied because SANY could not parse"));
        assertFalse(b.contains("\\@bx"));
    }

    @Test
    public void unknownBraceTargetIsAWarning() throws IOException {
        String spec = module("Inv == /\\ x = 1", "       /\\ y = 1").replace("brace conjunctions", "brace disjunctions");
        assertTrue(braced(spec).isEmpty());
        assertEquals(List.of("line 2: unknown brace target `disjunctions' ignored (known targets: conjunctions)"),
                     Directives.active.warnings());
    }
}
