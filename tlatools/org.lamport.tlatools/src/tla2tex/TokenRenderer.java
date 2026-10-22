package tla2tex;

/**
 * The TeX for the specification tokens that are typeset in math mode on a
 * spec line: built-in symbols, numbers, identifiers, PlusCal labels, strings
 * and proof-step numbers. Comments, dashes, the end of the module and the
 * epilog are laid out by the writers in {@link LaTeXOutput} themselves.
 *
 * <p>Both the alignment writer and the final writer use this class, so the
 * alignment run measures exactly what the output prints. That includes the
 * TeX of an operator application typeset as a macro (see {@link MacroSpans}),
 * which is printed for its first token. An instance tracks
 * whether a sub/superscript opened by a {@code ]_}, {@code WF_} or {@code ^}
 * token is still open; use one instance per line (or per argument).
 */
final class TokenRenderer
{
    private boolean inSub = false;

    /** True for the token types this class renders. */
    static boolean handles(Token tok)
    {
        switch (tok.type)
        {
        case Token.BUILTIN:
        case Token.NUMBER:
        case Token.IDENT:
        case Token.PCAL_LABEL:
        case Token.STRING:
        case Token.PF_STEP:
            return true;
        default:
            return false;
        }
    }

    boolean inSub()
    {
        return inSub;
    }

    /** Returns "}" and leaves the open sub/superscript if {@code tok} is not part of it. */
    String closeSubBefore(Token tok)
    {
        if (inSub && !tok.subscript)
        {
            inSub = false;
            return "}";
        }
        return "";
    }

    /** Returns "}" and leaves the open sub/superscript, if any. */
    String closeSub()
    {
        if (inSub)
        {
            inSub = false;
            return "}";
        }
        return "";
    }

    /**
     * The TeX for {@code line[item]}, which must satisfy {@link #handles}. The
     * next token of {@code line} decides whether a subscripted symbol starts a
     * sub/superscript.
     */
    String render(Token[] line, int item)
    {
        Token tok = line[item];
        if (tok.macro != null)
        {
            return " " + renderMacro(tok.macro);
        }
        switch (tok.type)
        {
        case Token.BUILTIN:
            Symbol sym = BuiltInSymbols.GetBuiltInSymbol(tok.string, true);
            /***************************************************************
            * A subscripted/superscript symbol (like "]_" or "^") begins a  *
            * sub/superscript iff it is followed by a subscript token on    *
            * the same line.                                                *
            ***************************************************************/
            if (!inSub
                && (sym.symbolType == Symbol.SUBSCRIPTED || tok.string.equals("^"))
                && item + 1 < line.length
                && line[item + 1].subscript)
            {
                inSub = true;
                if (sym.symbolType == Symbol.SUBSCRIPTED)
                {
                    return " " + sym.TeXString + "_{";
                }
                return " " + "^{";
            }
            /***************************************************************
            * It's always safe to put a space in a specification line,      *
            * since it's typeset in math mode.                              *
            ***************************************************************/
            return " " + sym.TeXString;

        case Token.NUMBER:
            /***************************************************************
            * TeXify typesets the "\" of numbers like "\O777".              *
            ***************************************************************/
            return " " + Misc.TeXify(tok.string);

        case Token.IDENT:
            String symbol = Directives.active.symbolFor(tok.string);
            if (symbol != null)
            {
                return " {" + symbol + "}";
            }
            return " " + Misc.TeXifyIdent(tok.string);

        case Token.PCAL_LABEL:
            return " " + Misc.TeXifyPcalLabel(tok.string);

        case Token.STRING:
            return Parameters.LaTeXStringCommand + "{" + LaTeXOutput.FixString(tok.string) + "}";

        case Token.PF_STEP:
            String step = LaTeXOutput.PfStepString(tok.string);
            if (((Token.PfStepToken) tok).needsSpace)
            {
                step = step + "\\ ";
            }
            return step;

        default:
            Debug.ReportBug("TokenRenderer.render called on a token of type " + tok.type);
            return "";
        }
    }

    /** The macro's template with each {@code #k} replaced by the TeX of argument k. */
    private static String renderMacro(MacroSpans.Application app)
    {
        String[] args = new String[app.args.length];
        for (int k = 0; k < args.length; k++)
        {
            args[k] = renderTokens(app.args[k]);
        }
        return app.macro.instantiate(args);
    }

    /** The TeX for a sequence of tokens from one line, such as a macro argument. */
    private static String renderTokens(Token[] toks)
    {
        TokenRenderer renderer = new TokenRenderer();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < toks.length)
        {
            Token tok = toks[i];
            out.append(renderer.closeSubBefore(tok));
            out.append(renderer.render(toks, i));
            int next = i + 1;
            if (tok.macro != null)
            {
                /***********************************************************
                * A nested macro printed its own arguments; skip to the    *
                * token after its closing parenthesis.                     *
                ***********************************************************/
                for (int j = i + 1; j < toks.length; j++)
                {
                    if (toks[j] == tok.macro.last)
                    {
                        next = j + 1;
                        break;
                    }
                }
            }
            i = next;
        }
        out.append(renderer.closeSub());
        return out.toString();
    }
}
