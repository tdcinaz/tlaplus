package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Operator application to TeX macro: SANY locates applications and arguments,
 * TLATeX's tokens print them. Each test parses a small module from a string.
 */
public class MacroSpansTest {

    private static final String DIRECTIVES = String.join("\n",
        "\\* @tlatex: macro Pair/2 \\langle #1 \\mid #2 \\rangle",
        "\\* @tlatex: macro Sqrt/1 \\sqrt{#1}",
        "\\* @tlatex: macro Integral/3 \\int_{#2}^{#3} #1",
        "\\* @tlatex: macro Abs/1 \\vert #1 \\vert",
        "");

    private Path workDir;

    @Before
    public void reset() throws IOException {
        TLA2TexTestSupport.resetStatics();
        workDir = TLA2TexTestSupport.createTempDir("tla2tex-macros-");
    }

    @After
    public void cleanUp() throws IOException {
        TLA2TexTestSupport.deleteRecursively(workDir);
    }

    private static String moduleExtending(String extendsLine, String... lines) {
        return "---- MODULE M ----\n" + extendsLine + "\n" + DIRECTIVES + String.join("\n", lines) + "\n====\n";
    }

    private static String module(String... lines) {
        return moduleExtending("EXTENDS Naturals", lines);
    }

    /** The arguments, as space-separated token strings, of every macro application in the module, in order. */
    private static List<List<String>> applications(String spec) {
        Token[][] toks = TLA2TexTestSupport.analyze(spec);
        List<List<String>> result = new ArrayList<>();
        for (Token[] row : toks) {
            for (Token tok : row) {
                if (tok.macro != null) {
                    List<String> args = new ArrayList<>();
                    for (Token[] arg : tok.macro.args) {
                        StringBuilder sb = new StringBuilder();
                        for (Token t : arg) {
                            sb.append(sb.length() == 0 ? "" : " ").append(t.string);
                        }
                        args.add(sb.toString());
                    }
                    result.add(args);
                }
            }
        }
        return result;
    }

    private static List<String> onlyApplication(String spec) {
        List<List<String>> apps = applications(spec);
        assertEquals(apps.toString(), 1, apps.size());
        return apps.get(0);
    }

    private String body(String spec) throws IOException {
        TLA2TexTestSupport.resetStatics();
        return TLA2TexTestSupport.documentBody(TLA2TexTestSupport.latexWithoutAlignment(spec, workDir));
    }

    private static void assertContains(String body, String expected) {
        assertTrue("Expected `" + expected + "' in:\n" + body, body.contains(expected));
    }

    // ------------------------------------------------------------ arguments

    @Test
    public void nestedParenthesesSetsTuplesAndFunctionApplication() {
        assertEquals(List.of("( 1 + 2 ) * 3", "{ 1 , 2 }"), onlyApplication(module("A == Pair((1 + 2) * 3, {1, 2})")));
        assertEquals(List.of("<< 1 , 2 >>", "f [ 2 ]"),
                     onlyApplication(module("CONSTANT f", "A == Pair(<<1, 2>>, f[2])")));
    }

    @Test
    public void quantifierAndLambdaCommasAreNotArgumentSeparators() {
        assertEquals(List.of("\\A x , y : x = y", "TRUE"),
                     onlyApplication(module("A == Pair(\\A x, y : x = y, TRUE)")));
        assertEquals(List.of("LAMBDA u , v : u + v", "1"),
                     onlyApplication(module("A(F(_, _)) == 1", "B == A(LAMBDA u, v : u + v) + Pair(LAMBDA u, v : u + v, 1)"))
                         .subList(0, 2));
    }

    @Test
    public void subscriptedActionsCloseTheirArgument() {
        assertEquals(List.of("[] [ A ]_ v", "<< A >>_ v"),
                     onlyApplication(module("VARIABLE v", "A == v' = v", "B == Pair([][A]_v, <<A>>_v)")));
    }

    @Test
    public void instanceQualifiedApplicationsMatchOnTheLastName() {
        List<String> args = onlyApplication(moduleExtending("EXTENDS Naturals, DoesNotExist", "A == M!Sqrt(2)"));
        assertEquals(List.of("2"), args);
    }

    @Test
    public void nestedMacroHeadsAreFoundInsideArguments() throws IOException {
        String spec = module("A == Pair(Abs(-5), Sqrt(7))");
        assertEquals(3, applications(spec).size());
        assertContains(body(spec), "A \\.{\\defeq} \\langle {\\vert {\\.{-} 5} \\vert} \\mid {\\sqrt{{7}}} \\rangle");
    }

    @Test
    public void definitionsAreRewrittenToo() throws IOException {
        String spec = module("Integral(f, lo, hi) == hi - lo", "B == Integral(1, 0, 10)");
        String b = body(spec);
        assertContains(b, "\\int_{{lo}}^{{hi}} {f} \\.{\\defeq} hi \\.{-} lo");
        assertContains(b, "B \\.{\\defeq} \\int_{{0}}^{{10}} {1}");
    }

    @Test
    public void higherOrderParametersAreNotApplications() throws IOException {
        // Sqrt(_) on the left is a declaration of an operator parameter, not an application.
        String spec = module("F(Sqrt(_)) == Sqrt(1)");
        assertEquals(List.of(List.of("1")), applications(spec));
        assertContains(body(spec), "F ( Sqrt ( \\_ ) ) \\.{\\defeq} \\sqrt{{1}}");
    }

    @Test
    public void tokensAfterAShortenedBuiltinStillMatch() {
        // "(-" and "(\\" begin the built-ins "(-)" and "(\\X)"; the tokenizer then
        // gives the next token a column too far right, which the mapping corrects.
        assertEquals(List.of(List.of("- 5")), applications(module("A == Sqrt(-5)")));
        assertEquals(List.of(List.of("\\E x \\in { 1 } : x = 1")),
                     applications(module("A == Sqrt(\\E x \\in {1} : x = 1)")));
    }

    // ------------------------------------------------------------- declined

    @Test
    public void arityMismatchIsPrintedAsUsual() throws IOException {
        String spec = module("A == Sqrt(1, 2)");
        assertTrue(applications(spec).isEmpty());
        assertContains(body(spec), "A \\.{\\defeq} Sqrt ( 1 ,\\, 2 )");
    }

    @Test
    public void applicationsSpanningLinesArePrintedAsUsual() throws IOException {
        String spec = module("A == Pair(1,", "          2)", "B == Sqrt(3)");
        List<List<String>> apps = applications(spec);
        assertEquals(List.of(List.of("3")), apps);
        assertContains(body(spec), "A \\.{\\defeq} Pair ( 1 ,");
    }

    @Test
    public void alignedTokensInsideAnApplicationDeclineIt() throws IOException {
        // The two "+" symbols are aligned, so Pair(...) on the first line keeps its tokens.
        String spec = module("A == Pair(1 + 2, 3)", "B ==      1 + Sqrt(2)");
        Token[][] toks = TLA2TexTestSupport.analyze(spec);
        boolean pairKept = false;
        boolean sqrtKept = false;
        for (Token[] row : toks) {
            for (Token tok : row) {
                pairKept |= tok.string.equals("Pair") && tok.macro != null;
                sqrtKept |= tok.string.equals("Sqrt") && tok.macro != null;
            }
        }
        assertFalse("Pair has an aligned + inside and must be declined", pairKept);
        assertTrue(sqrtKept);
    }

    @Test
    public void commentsInsideAnApplicationDeclineIt() {
        assertTrue(applications(module("A == Pair(1 (* one *), 2)")).isEmpty());
    }

    @Test
    public void tabsBeforeTheApplicationAreExpandedConsistently() throws IOException {
        String spec = module("A ==\tPair(1,\t2) + Sqrt(3)");
        assertEquals(List.of(List.of("1", "2"), List.of("3")), applications(spec));
    }

    @Test
    public void unresolvableExtendsDoesNotMatter() {
        assertEquals(List.of("2"), onlyApplication(moduleExtending("EXTENDS DoesNotExist", "A == Sqrt(2)")));
    }

    @Test
    public void specSanyRejectsGetsNoMacrosOneWarningAndTheUsualOutput() throws IOException {
        String bad = module("A == Sqrt(2) +", "B == Pair(1, 2)");
        String b = body(bad);
        List<String> warnings = Directives.active.warnings();
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).startsWith("macro directives not applied because SANY could not parse"));
        String withoutDirectives = body(bad.replace(DIRECTIVES, ""));
        assertEquals(withoutDirectives, b);
    }

    @Test
    public void macrosInPlusCalSourceAreLeftAlone() {
        String spec = module(
            "(* --algorithm Alg",
            "   variable x = Sqrt(1);",
            "   begin skip;",
            "   end algorithm *)",
            "A == Sqrt(2)");
        assertEquals(List.of(List.of("2")), applications(spec));
    }

    @Test
    public void subscriptsInsideArgumentsAreClosedInsideTheArgument() throws IOException {
        String spec = module("VARIABLE v", "A == Sqrt(v^2) + 1");
        assertContains(body(spec), "A \\.{\\defeq} \\sqrt{{v ^{ 2}}} \\.{+} 1");
    }

    @Test
    public void symbolsApplyInsideMacroArguments() throws IOException {
        String spec = module("CONSTANT alpha", "\\* @tlatex: symbol alpha \\alpha", "A == Sqrt(alpha)");
        assertContains(body(spec), "\\sqrt{{{\\alpha}}}");
    }

    @Test
    public void withoutMacroDirectivesSanyIsNotRun() throws IOException {
        // An unparseable spec without macro directives gives no warning.
        body("---- MODULE M ----\n\\* @tlatex: symbol a \\alpha\nA == +\n====\n");
        assertTrue(Directives.active.warnings().isEmpty());
        assertNull(Directives.active.macroFor("Sqrt", 1));
    }
}
