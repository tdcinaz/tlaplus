package tla2tex;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import tla2sany.output.LogLevel;
import tla2sany.output.SanyOutput;
import tla2sany.parser.SyntaxTreeNode;
import tla2sany.parser.TLAplusParser;
import tla2sany.st.Location;
import tla2sany.st.SyntaxTreeConstants;
import util.ToolIO;

/**
 * Finds the operator applications that {@code macro} directives turn into
 * TeX macros.
 *
 * <p>SANY's syntax-only parse locates applications and their arguments; the
 * locations are then mapped onto TLATeX's tokens, which still do all the
 * printing. A SANY location {@code line:col} is the token of
 * {@code spec[line-1]} that starts in column {@code col-1} of the source line:
 * both lexers expand tabs to 8-column stops (see {@link SourceColumns}). An application is typeset as a macro only if it and its
 * arguments lie on one line, every argument boundary falls on a token
 * boundary, it contains no comment, and (checked by {@link #finish} after
 * alignment) none of its tokens after the first takes part in an alignment.
 * Otherwise it is printed as usual; {@code -debug} says why.
 *
 * <p>If SANY cannot parse the spec, no macros are applied and one warning
 * names SANY's first error. TLATeX itself still does not check syntax.
 */
final class MacroSpans
{
    /** An application typeset as a macro, attached to its first token. */
    static final class Application
    {
        final Directives.Macro macro;
        /** The tokens of each argument, in order. */
        final Token[][] args;
        /** The application's last token, its closing parenthesis. */
        final Token last;
        /** The source line (0-based), for messages. */
        final int line;

        Application(Directives.Macro macro, Token[][] args, Token last, int line)
        {
            this.macro = macro;
            this.args = args;
            this.last = last;
            this.line = line;
        }
    }

    private MacroSpans()
    {
    }

    /**
     * Parses {@code source} with SANY and attaches an {@link Application} to
     * the first token of each application in {@code spec} that a macro of
     * {@code directives} matches. Does nothing, and does not run SANY, when
     * there are no macro directives. {@code spec} must still have one row per
     * source line.
     */
    static void find(byte[] source, Token[][] spec, Directives directives)
    {
        if (!directives.hasMacros())
        {
            return;
        }
        FirstError errors = new FirstError();
        SyntaxTreeNode root = null;
        String failure = null;
        try
        {
            TLAplusParser parser = new TLAplusParser(errors, source);
            if (parser.parse())
            {
                root = (SyntaxTreeNode) parser.rootNode();
            } else
            {
                failure = errors.first != null ? errors.first : "unknown parse error";
            }
        } catch (VirtualMachineError e)
        {
            throw e;
        } catch (Throwable e)
        {
            failure = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        if (root == null)
        {
            directives.warn("macro directives not applied because SANY could not parse the spec: "
                            + firstLine(failure));
            return;
        }

        SourceColumns columns = new SourceColumns(source, spec);
        Deque<SyntaxTreeNode> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty())
        {
            SyntaxTreeNode node = todo.pop();
            if (node.isKind(SyntaxTreeConstants.N_OpApplication) && node.getHeirs().length == 2
                && node.getHeirs()[1].isKind(SyntaxTreeConstants.N_OpArgs))
            {
                consider(node, applicationName(node), applicationArgs(node), spec, columns, directives);
            } else if (node.isKind(SyntaxTreeConstants.N_IdentLHS) && node.getHeirs().length > 1)
            {
                consider(node, node.getHeirs()[0].getImage(), lhsArgs(node), spec, columns, directives);
            }
            SyntaxTreeNode[] heirs = node.getHeirs();
            for (int i = heirs.length - 1; i >= 0; i--)
            {
                todo.push(heirs[i]);
            }
        }
    }

    /** The last name component of an N_OpApplication's head: Op in M!Op(...). */
    private static String applicationName(SyntaxTreeNode app)
    {
        SyntaxTreeNode[] head = app.getHeirs()[0].getHeirs();
        return head.length == 0 ? "" : head[head.length - 1].getImage();
    }

    /** The argument nodes of an N_OpApplication: N_OpArgs is ( a1 , a2 , ... ). */
    private static SyntaxTreeNode[] applicationArgs(SyntaxTreeNode app)
    {
        SyntaxTreeNode[] opArgs = app.getHeirs()[1].getHeirs();
        SyntaxTreeNode[] args = new SyntaxTreeNode[(opArgs.length - 1) / 2];
        for (int k = 0; k < args.length; k++)
        {
            args[k] = opArgs[2 * k + 1];
        }
        return args;
    }

    /** The parameter nodes of an N_IdentLHS: Op ( p1 , p2 , ... ). */
    private static SyntaxTreeNode[] lhsArgs(SyntaxTreeNode lhs)
    {
        SyntaxTreeNode[] heirs = lhs.getHeirs();
        SyntaxTreeNode[] args = new SyntaxTreeNode[(heirs.length - 2) / 2];
        for (int k = 0; k < args.length; k++)
        {
            args[k] = heirs[2 * k + 2];
        }
        return args;
    }

    private static void consider(SyntaxTreeNode node, String name, SyntaxTreeNode[] argNodes,
                                 Token[][] spec, SourceColumns columns, Directives directives)
    {
        if (!directives.hasMacroNamed(name))
        {
            return;
        }
        Location loc = node.getLocation();
        int line = loc.beginLine() - 1;
        String what = name + " at line " + loc.beginLine();
        Directives.Macro macro = directives.macroFor(name, argNodes.length);
        if (macro == null)
        {
            decline(what, "no macro for " + name + "/" + argNodes.length);
            return;
        }
        if (loc.endLine() != loc.beginLine())
        {
            decline(what, "the application spans lines " + loc.beginLine() + "-" + loc.endLine());
            return;
        }
        if (line < 0 || line >= spec.length)
        {
            decline(what, "SANY's line is outside the spec");
            return;
        }
        Token[] row = spec[line];
        int[] cols = columns.of(line);
        int first = startingAt(row, cols, loc.beginColumn() - 1);
        int last = endingAt(row, cols, loc.endColumn() - 1);
        if (first < 0 || last < 0 || !row[last].string.equals(")"))
        {
            decline(what, "its tokens do not match SANY's location");
            return;
        }
        for (int i = first; i <= last; i++)
        {
            if (!TokenRenderer.handles(row[i]))
            {
                decline(what, "it contains a comment or other non-expression token");
                return;
            }
        }
        if (row[first].macro != null)
        {
            decline(what, "another macro already starts at the same token");
            return;
        }
        Token[][] args = new Token[argNodes.length][];
        for (int k = 0; k < argNodes.length; k++)
        {
            Location a = argNodes[k].getLocation();
            int from = startingAt(row, cols, a.beginColumn() - 1);
            int to = endingAt(row, cols, a.endColumn() - 1);
            if (a.beginLine() != loc.beginLine() || a.endLine() != loc.beginLine()
                || from <= first || to >= last || to < from)
            {
                decline(what, "argument " + (k + 1) + " does not match TLATeX's tokens");
                return;
            }
            args[k] = new Token[to - from + 1];
            System.arraycopy(row, from, args[k], 0, args[k].length);
        }
        row[first].macro = new Application(macro, args, row[last], line);
    }

    /** Index of the token of {@code row} that starts at {@code column}, or -1. */
    private static int startingAt(Token[] row, int[] cols, int column)
    {
        for (int i = 0; i < row.length; i++)
        {
            if (cols[i] == column)
            {
                return i;
            }
        }
        return -1;
    }

    /** Index of the token of {@code row} whose last character is at {@code column}, or -1. */
    private static int endingAt(Token[] row, int[] cols, int column)
    {
        for (int i = 0; i < row.length; i++)
        {
            if (cols[i] + row[i].getWidth() - 1 == column)
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * The source column of each token, for matching SANY's locations.
     *
     * <p>Token.column is usually that column, but the tokenizer gives the
     * token after a built-in that it had to shorten (such as the "(" of
     * "(-5" or "(\E x", which begin like "(-)" and "(\X)") a column one or
     * more places too far right. Correcting Token.column itself would move
     * alignments in existing output, so the correction is made only here: a
     * token whose text is not at its column in the tab-expanded source line is
     * moved left to the nearest column, after the previous token, where it is.
     */
    private static final class SourceColumns
    {
        private final String[] lines;
        private final Token[][] spec;
        private final int[][] cache;

        SourceColumns(byte[] source, Token[][] spec)
        {
            this.lines = new String(source, StandardCharsets.UTF_8).split("\r\n|\r|\n", -1);
            this.spec = spec;
            this.cache = new int[spec.length][];
        }

        int[] of(int line)
        {
            if (cache[line] == null)
            {
                cache[line] = compute(spec[line], line < lines.length ? expandTabs(lines[line]) : "");
            }
            return cache[line];
        }

        private static int[] compute(Token[] row, String text)
        {
            int[] cols = new int[row.length];
            int minStart = 0;
            for (int i = 0; i < row.length; i++)
            {
                Token tok = row[i];
                int col = tok.column;
                if (verbatim(tok) && !text.startsWith(tok.string, col))
                {
                    for (int c = col - 1; c >= minStart; c--)
                    {
                        if (text.startsWith(tok.string, c))
                        {
                            col = c;
                            break;
                        }
                    }
                }
                cols[i] = col;
                minStart = col + tok.getWidth();
            }
            return cols;
        }

        /** True for tokens whose string is exactly their source text. */
        private static boolean verbatim(Token tok)
        {
            return tok.type == Token.BUILTIN || tok.type == Token.IDENT || tok.type == Token.NUMBER
                   || tok.type == Token.PF_STEP;
        }

        private static String expandTabs(String line)
        {
            if (line.indexOf('\t') < 0)
            {
                return line;
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < line.length(); i++)
            {
                char ch = line.charAt(i);
                if (ch == '\t')
                {
                    do
                    {
                        out.append(' ');
                    } while (out.length() % 8 != 0);
                } else
                {
                    out.append(ch);
                }
            }
            return out.toString();
        }
    }

    /**
     * Run after FindAlignments: declines every application with a token after
     * its first one that takes part in an alignment, then marks the tokens
     * after the first of each remaining application as hidden.
     */
    static void finish(Token[][] spec)
    {
        for (Token[] row : spec)
        {
            for (int item = 0; item < row.length; item++)
            {
                Application app = row[item].macro;
                if (app == null)
                {
                    continue;
                }
                int last = indexOf(row, app.last, item);
                String what = app.macro.name + " at line " + (app.line + 1);
                if (last < 0)
                {
                    decline(what, "its tokens were split across lines");
                    row[item].macro = null;
                    continue;
                }
                for (int i = item + 1; i <= last; i++)
                {
                    Token tok = row[i];
                    if (tok.aboveAlign.line != -1 || tok.belowAlign.line != -1 || tok.isAlignmentPoint)
                    {
                        decline(what, "its token `" + tok.string + "' is aligned with another line");
                        row[item].macro = null;
                        break;
                    }
                }
            }
        }
        for (Token[] row : spec)
        {
            for (int item = 0; item < row.length; item++)
            {
                Application app = row[item].macro;
                if (app != null)
                {
                    int last = indexOf(row, app.last, item);
                    for (int i = item + 1; i <= last; i++)
                    {
                        row[i].hiddenByMacro = true;
                    }
                }
            }
        }
    }

    private static int indexOf(Token[] row, Token tok, int from)
    {
        for (int i = from; i < row.length; i++)
        {
            if (row[i] == tok)
            {
                return i;
            }
        }
        return -1;
    }

    private static void decline(String what, String why)
    {
        if (Parameters.Debug)
        {
            ToolIO.out.println("TLATeX macro " + what + " typeset without its macro: " + why);
        }
    }

    private static String firstLine(String msg)
    {
        String s = msg.trim();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).trim();
    }

    /** Remembers SANY's first error message and discards the rest of its output. */
    private static final class FirstError implements SanyOutput
    {
        private final PrintStream sink = new PrintStream(OutputStream.nullOutputStream());
        String first = null;

        @Override
        public void log(LogLevel level, String format, Object... args)
        {
            if (level == LogLevel.ERROR && first == null)
            {
                first = args.length == 0 ? format : String.format(format, args);
            }
        }

        @Override
        public PrintStream getStream(LogLevel level)
        {
            return sink;
        }
    }
}
