package tla2tex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the tokenizer's observable behaviour on small modules. They
 * pin down the token shapes that later phases (symbol substitution, operator
 * macros) depend on.
 */
public class TokenizeSpecTest {

    private static final String HEADER = "---- MODULE M ----\n";
    private static final String FOOTER = "====\n";

    @Before
    public void reset() {
        TLA2TexTestSupport.resetStatics();
    }

    private static Token[][] module(String... bodyLines) {
        return TLA2TexTestSupport.tokenize(HEADER + String.join("\n", bodyLines) + "\n" + FOOTER);
    }

    private static void assertToken(Token token, int type, String string) {
        assertEquals("token type of `" + token.string + "'", type, token.type);
        assertEquals("token string", string, token.string);
    }

    private static void assertLine(Token[] line, int type0, String... strings) {
        // Convenience for lines whose tokens are all of one type.
        assertEquals("tokens on line", strings.length, line.length);
        for (int i = 0; i < strings.length; i++) {
            assertToken(line[i], type0, strings[i]);
        }
    }

    @Test
    public void moduleHeaderAndFooterAreRecognised() {
        Token[][] spec = module("VARIABLE x");
        // Header, body line, footer; the trailing newline yields one empty line.
        assertEquals(4, spec.length);
        assertEquals(0, spec[3].length);
        assertToken(spec[0][0], Token.DASHES, "----");
        assertToken(spec[0][1], Token.BUILTIN, "MODULE");
        assertToken(spec[0][2], Token.IDENT, "M");
        assertToken(spec[0][3], Token.DASHES, "----");
        assertToken(spec[2][0], Token.END_MODULE, "====");
    }

    @Test
    public void keywordsAreBuiltinAndNamesAreIdentifiers() {
        Token[][] spec = module("VARIABLE x", "Init == x = 0");
        assertToken(spec[1][0], Token.BUILTIN, "VARIABLE");
        assertToken(spec[1][1], Token.IDENT, "x");
        assertEquals("column of x", 9, spec[1][1].column);

        assertToken(spec[2][0], Token.IDENT, "Init");
        assertToken(spec[2][1], Token.BUILTIN, "==");
        assertToken(spec[2][2], Token.IDENT, "x");
        assertToken(spec[2][3], Token.BUILTIN, "=");
        assertToken(spec[2][4], Token.NUMBER, "0");
    }

    @Test
    public void operatorApplicationIsIdentThenParenthesisTokens() {
        Token[][] spec = module("A == Integral(a + b, c, d)");
        Token[] line = spec[1];
        assertToken(line[2], Token.IDENT, "Integral");
        assertToken(line[3], Token.BUILTIN, "(");
        assertToken(line[4], Token.IDENT, "a");
        assertToken(line[5], Token.BUILTIN, "+");
        assertToken(line[6], Token.IDENT, "b");
        assertToken(line[7], Token.BUILTIN, ",");
        assertToken(line[8], Token.IDENT, "c");
        assertToken(line[9], Token.BUILTIN, ",");
        assertToken(line[10], Token.IDENT, "d");
        assertToken(line[11], Token.BUILTIN, ")");
        assertEquals(Symbol.LEFT_PAREN, BuiltInSymbols.GetBuiltInSymbol("(").symbolType);
        assertEquals(Symbol.RIGHT_PAREN, BuiltInSymbols.GetBuiltInSymbol(")").symbolType);
        assertEquals(Symbol.PUNCTUATION, BuiltInSymbols.GetBuiltInSymbol(",").symbolType);
    }

    @Test
    public void weakFairnessPrefixIsSplitFromItsSubscript() {
        Token[][] spec = module("Fair == WF_x(Next)");
        Token[] line = spec[1];
        assertToken(line[2], Token.BUILTIN, "WF_");
        assertToken(line[3], Token.IDENT, "x");
        assertToken(line[4], Token.BUILTIN, "(");
        assertToken(line[5], Token.IDENT, "Next");
        assertToken(line[6], Token.BUILTIN, ")");
        assertEquals(Symbol.SUBSCRIPTED, BuiltInSymbols.GetBuiltInSymbol("WF_").symbolType);
    }

    @Test
    public void primeIsASeparatePostfixToken() {
        Token[][] spec = module("Next == x' = x + 1");
        Token[] line = spec[1];
        assertToken(line[2], Token.IDENT, "x");
        assertToken(line[3], Token.BUILTIN, "'");
        assertToken(line[4], Token.BUILTIN, "=");
        assertEquals(Symbol.POSTFIX, BuiltInSymbols.GetBuiltInSymbol("'").symbolType);
    }

    @Test
    public void backslashOperatorsAreSingleBuiltins() {
        Token[][] spec = module("S == {r \\in Nat : r \\leq n} /\\ TRUE");
        Token[] line = spec[1];
        assertToken(line[2], Token.BUILTIN, "{");
        assertToken(line[3], Token.IDENT, "r");
        assertToken(line[4], Token.BUILTIN, "\\in");
        assertToken(line[5], Token.IDENT, "Nat");
        assertToken(line[6], Token.BUILTIN, ":");
        assertToken(line[8], Token.BUILTIN, "\\leq");
        assertToken(line[10], Token.BUILTIN, "}");
        assertToken(line[11], Token.BUILTIN, "/\\");
        assertToken(line[12], Token.BUILTIN, "TRUE");
    }

    @Test
    public void stringsAndLineCommentsAreSingleTokens() {
        Token[][] spec = module("S == \"a b\" \\* trailing words");
        Token[] line = spec[1];
        assertEquals(Token.STRING, line[2].type);
        assertEquals("a b", line[2].string);
        assertEquals(Token.COMMENT, line[3].type);
        assertTrue(line[3] instanceof CommentToken);
    }

    @Test
    public void prologAndEpilogLinesAreSingleTokens() {
        Token[][] spec = TLA2TexTestSupport.tokenize(
            "Text before the module.\n" + HEADER + "x == 1\n" + FOOTER + "Text after.\n");
        assertLine(spec[0], Token.PROLOG, "Text before the module.");
        assertToken(spec[1][1], Token.BUILTIN, "MODULE");
        assertLine(spec[4], Token.EPILOG, "Text after.");
    }

    @Test
    public void identifierTablesAreClearedByReset() {
        module("Init == TRUE");
        assertTrue(TokenizeSpec.isIdent("Init"));
        assertTrue(TokenizeSpec.isUsedBuiltin("TRUE"));
        TLA2TexTestSupport.resetStatics();
        assertFalse(TokenizeSpec.isIdent("Init"));
        assertFalse(TokenizeSpec.isUsedBuiltin("TRUE"));
    }

    @Test
    public void parametersAreRestoredByReset() {
        Parameters.CommentShading = true;
        Parameters.LaTeXptSize = 12;
        Parameters.LaTeXOutputFile = "somewhere";
        TLA2TexTestSupport.resetStatics();
        assertFalse(Parameters.CommentShading);
        assertEquals(10, Parameters.LaTeXptSize);
        assertEquals("", Parameters.LaTeXOutputFile);
    }
}
