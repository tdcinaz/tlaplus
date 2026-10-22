package tla2tex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import util.ToolIO;

/**
 * TLATeX directives: comment lines that tell TLATeX how to typeset
 * identifiers and operator applications. A directive is a comment line
 * (or prolog/epilog line) whose text starts with {@code @tlatex:}:
 *
 * <pre>
 * \* @tlatex: symbol alpha \alpha
 * \* @tlatex: macro Integral/3 \int_{#2}^{#3} #1
 * \* @tlatex: preset greek
 * </pre>
 *
 * Directives apply to the whole module wherever they appear. Their lines are
 * removed from the typeset output and from the {@code -tlaOut} file. The same
 * lines, with or without the leading {@code \*}, can also be given in a file
 * with the {@code -directives} option. Lines of other tools (for example
 * {@code @type:}) are left alone.
 *
 * <p>The table in {@link #active} is the one the output stage uses.
 */
final class Directives
{
    static final String PREFIX = "@tlatex:";

    /** The directives of the spec being typeset. Empty unless {@link #prepare} found some. */
    static Directives active = new Directives();

    /** Restores the empty table; called between specs by the tests. */
    static void reset()
    {
        active = new Directives();
    }

    /** An operator-to-macro template, for example {@code \int_{#2}^{#3} #1}. */
    static final class Macro
    {
        final String name;
        final int arity;
        final String template;

        Macro(String name, int arity, String template)
        {
            this.name = name;
            this.arity = arity;
            this.template = template;
        }

        /** The template with {@code #k} replaced by {@code {args[k-1]}} and {@code ##} by {@code #}. */
        String instantiate(String[] args)
        {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < template.length())
            {
                char ch = template.charAt(i);
                if (ch == '#' && i + 1 < template.length())
                {
                    char next = template.charAt(i + 1);
                    if (next == '#')
                    {
                        out.append('#');
                        i = i + 2;
                        continue;
                    }
                    if (next >= '1' && next <= '9')
                    {
                        out.append('{').append(args[next - '1'].trim()).append('}');
                        i = i + 2;
                        continue;
                    }
                }
                out.append(ch);
                i = i + 1;
            }
            return out.toString();
        }

        @Override
        public String toString()
        {
            return name + "/" + arity + " " + template;
        }
    }

    private static final String[] GREEK = {
        "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta",
        "theta", "vartheta", "iota", "kappa", "lambda", "mu", "nu", "xi", "pi",
        "varpi", "rho", "varrho", "sigma", "varsigma", "tau", "upsilon", "phi",
        "varphi", "chi", "psi", "omega",
        "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon",
        "Phi", "Psi", "Omega" };

    private final Map<String, String> symbols = new LinkedHashMap<>();
    private final Map<String, String> symbolOrigins = new LinkedHashMap<>();
    private final Map<String, String> presetSymbols = new LinkedHashMap<>();
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final Map<String, String> macroOrigins = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /** The TeX that identifier {@code ident} typesets as, or null. */
    String symbolFor(String ident)
    {
        String tex = symbols.get(ident);
        return tex != null ? tex : presetSymbols.get(ident);
    }

    /** The macro for an application of {@code name} to {@code arity} arguments, or null. */
    Macro macroFor(String name, int arity)
    {
        return macros.get(name + "/" + arity);
    }

    /** True if some macro is named {@code name}, whatever its arity. */
    boolean hasMacroNamed(String name)
    {
        for (Macro m : macros.values())
        {
            if (m.name.equals(name))
            {
                return true;
            }
        }
        return false;
    }

    boolean hasMacros()
    {
        return !macros.isEmpty();
    }

    boolean isEmpty()
    {
        return symbols.isEmpty() && presetSymbols.isEmpty() && macros.isEmpty();
    }

    /** The warnings issued so far, without the "TLATeX warning: " prefix. */
    List<String> warnings()
    {
        return warnings;
    }

    void warn(String msg)
    {
        warnings.add(msg);
        ToolIO.out.println("TLATeX warning: " + msg);
    }

    // ------------------------------------------------------------- parsing

    /**
     * If {@code text} (the contents of a comment line, without its comment
     * delimiters) is a directive, returns what follows {@code @tlatex:};
     * otherwise null. With {@code boxed} true, a leading run of {@code *} and
     * a trailing run preceded by a space are dropped first, as in a line of a
     * boxed {@code (* ... *)} comment.
     */
    static String directiveBody(String text, boolean boxed)
    {
        if (text == null)
        {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("\\*"))
        {
            s = s.substring(2).trim();
        }
        if (boxed)
        {
            int i = 0;
            while (i < s.length() && s.charAt(i) == '*')
            {
                i++;
            }
            s = s.substring(i).trim();
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '*')
            {
                end--;
            }
            if (end < s.length() && end > 0 && Character.isWhitespace(s.charAt(end - 1)))
            {
                s = s.substring(0, end).trim();
            }
        }
        if (!s.startsWith(PREFIX))
        {
            return null;
        }
        return s.substring(PREFIX.length()).trim();
    }

    /** Parses the body of one directive; {@code where} locates it in warnings, e.g. "line 3". */
    void parse(String body, String where)
    {
        String[] parts = body.trim().split("\\s+", 2);
        String command = parts[0];
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (command)
        {
        case "symbol":
            parseSymbol(rest, where);
            break;
        case "macro":
            parseMacro(rest, where);
            break;
        case "preset":
            parsePreset(rest, where);
            break;
        case "":
            warn(where + ": empty @tlatex: directive ignored");
            break;
        default:
            warn(where + ": unknown @tlatex: directive `" + command + "' ignored");
        }
    }

    private void parseSymbol(String rest, String where)
    {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty())
        {
            warn(where + ": `symbol' needs an identifier and its TeX, as in `symbol alpha \\alpha'");
            return;
        }
        String ident = parts[0];
        String tex = parts[1].trim();
        if (!isIdentifier(ident))
        {
            warn(where + ": `" + ident + "' is not a TLA+ identifier; symbol ignored");
            return;
        }
        if (BuiltInSymbols.IsBuiltInSymbol(ident))
        {
            warn(where + ": `" + ident + "' is a TLA+ keyword, not an identifier; symbol ignored");
            return;
        }
        String old = symbols.get(ident);
        if (old != null && !old.equals(tex))
        {
            warn(where + ": symbol `" + ident + "' redefined (was `" + old + "' at "
                 + symbolOrigins.get(ident) + ")");
        }
        symbols.put(ident, tex);
        symbolOrigins.put(ident, where);
    }

    private void parseMacro(String rest, String where)
    {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty())
        {
            warn(where + ": `macro' needs Name/arity and a template, as in `macro Sqrt/1 \\sqrt{#1}'");
            return;
        }
        String head = parts[0];
        String template = parts[1].trim();
        int slash = head.lastIndexOf('/');
        if (slash <= 0 || !isIdentifier(head.substring(0, slash)))
        {
            warn(where + ": `" + head + "' is not of the form Name/arity; macro ignored");
            return;
        }
        String name = head.substring(0, slash);
        int arity;
        try
        {
            arity = Integer.parseInt(head.substring(slash + 1));
        } catch (NumberFormatException e)
        {
            arity = -1;
        }
        if (arity < 1 || arity > 9)
        {
            warn(where + ": arity of `" + head + "' must be a number from 1 to 9; macro ignored");
            return;
        }
        int i = 0;
        while (i < template.length())
        {
            if (template.charAt(i) == '#')
            {
                char next = i + 1 < template.length() ? template.charAt(i + 1) : ' ';
                if (next == '#')
                {
                    i = i + 2;
                    continue;
                }
                if (next < '1' || next > '0' + arity)
                {
                    warn(where + ": template of `" + head + "' uses `#" + (next == ' ' ? "" : String.valueOf(next))
                         + "', but the arity is " + arity + " (write ## for a literal #); macro ignored");
                    return;
                }
            }
            i = i + 1;
        }
        String key = name + "/" + arity;
        Macro old = macros.get(key);
        if (old != null && !old.template.equals(template))
        {
            warn(where + ": macro `" + key + "' redefined (was `" + old.template + "' at "
                 + macroOrigins.get(key) + ")");
        }
        macros.put(key, new Macro(name, arity, template));
        macroOrigins.put(key, where);
    }

    private void parsePreset(String rest, String where)
    {
        if (rest.equals("greek"))
        {
            for (String g : GREEK)
            {
                presetSymbols.put(g, "\\" + g);
            }
        } else
        {
            warn(where + ": unknown preset `" + rest + "' ignored (known presets: greek)");
        }
    }

    static boolean isIdentifier(String s)
    {
        boolean letter = false;
        if (s.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < s.length(); i++)
        {
            char ch = s.charAt(i);
            if (Misc.IsLetter(ch) && ch != '_')
            {
                letter = true;
            } else if (!(Misc.IsDigit(ch) || ch == '_'))
            {
                return false;
            }
        }
        return letter;
    }

    /** Reads directives from a file of directive lines; other non-blank lines are reported. */
    void readFile(String fileName)
    {
        List<String> lines;
        try
        {
            lines = Files.readAllLines(Paths.get(fileName), StandardCharsets.UTF_8);
        } catch (IOException e)
        {
            Debug.ReportError("Could not read -directives file " + fileName + ": " + e.getMessage());
            return;
        }
        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            String where = fileName + ":" + (i + 1);
            String body = directiveBody(line, false);
            if (body != null)
            {
                parse(body, where);
            } else if (!line.trim().isEmpty())
            {
                warn(where + ": not a " + PREFIX + " directive; line ignored");
            }
        }
    }

    // ------------------------------------------------------ spec processing

    /**
     * If {@code tok} is a directive, returns its body; otherwise null.
     * Recognised tokens are prolog and epilog lines and comment lines.
     */
    private static String directiveBodyOf(Token tok)
    {
        switch (tok.type)
        {
        case Token.PROLOG:
        case Token.EPILOG:
            return directiveBody(tok.string, false);
        case Token.COMMENT:
            int raw = ((CommentToken) tok).rsubtype;
            boolean boxed = raw == CommentToken.BEGIN_OVERRUN || raw == CommentToken.END_OVERRUN
                            || raw == CommentToken.OVERRUN;
            return directiveBody(tok.string, boxed);
        default:
            return null;
        }
    }

    /** Parses every directive in {@code spec}, without changing it. */
    void collect(Token[][] spec)
    {
        for (int line = 0; line < spec.length; line++)
        {
            for (Token tok : spec[line])
            {
                String body = directiveBodyOf(tok);
                if (body != null)
                {
                    parse(body, "line " + (line + 1));
                }
            }
        }
    }

    /**
     * Returns {@code spec} without its directives. A line that contains only
     * a directive is dropped entirely, so it leaves no blank line behind. A
     * directive that shares its line with other tokens is dropped from the
     * line, except that the first or last line of a multi-line comment keeps
     * an empty comment so the comment stays well formed. The PlusCal
     * positions recorded by the tokenizer are adjusted for dropped lines.
     */
    static Token[][] removeDirectives(Token[][] spec)
    {
        List<Token[]> kept = new ArrayList<>(spec.length);
        int[] removedBefore = new int[spec.length + 1];
        int removed = 0;
        for (int line = 0; line < spec.length; line++)
        {
            removedBefore[line] = removed;
            Token[] toks = spec[line];
            List<Token> out = new ArrayList<>(toks.length);
            boolean hadDirective = false;
            for (Token tok : toks)
            {
                if (directiveBodyOf(tok) == null)
                {
                    out.add(tok);
                    continue;
                }
                hadDirective = true;
                if (toks.length > 1 && tok.type == Token.COMMENT)
                {
                    int raw = ((CommentToken) tok).rsubtype;
                    if (raw == CommentToken.BEGIN_OVERRUN || raw == CommentToken.END_OVERRUN)
                    {
                        tok.string = "";
                        out.add(tok);
                    }
                }
            }
            if (!hadDirective)
            {
                kept.add(toks);
            } else if (!out.isEmpty())
            {
                kept.add(out.toArray(new Token[0]));
            } else
            {
                removed++;
            }
        }
        removedBefore[spec.length] = removed;
        if (removed == 0 && kept.size() == spec.length)
        {
            boolean same = true;
            for (int i = 0; i < spec.length && same; i++)
            {
                same = kept.get(i) == spec[i];
            }
            if (same)
            {
                return spec;
            }
        }
        if (TokenizeSpec.hasPcal)
        {
            TokenizeSpec.pcalStart = shift(TokenizeSpec.pcalStart, removedBefore);
            TokenizeSpec.pcalEnd = shift(TokenizeSpec.pcalEnd, removedBefore);
        }
        return kept.toArray(new Token[0][]);
    }

    private static Position shift(Position pos, int[] removedBefore)
    {
        if (pos == null || pos.line < 0)
        {
            return pos;
        }
        int line = Math.min(pos.line, removedBefore.length - 1);
        return new Position(pos.line - removedBefore[line], pos.item);
    }

    /**
     * Reads the {@code -directives} file (if any) and the spec's directives
     * into {@link #active}, and returns the spec without its directive lines.
     */
    static Token[][] prepare(Token[][] spec)
    {
        Directives d = new Directives();
        if (!Parameters.DirectivesFile.equals(""))
        {
            d.readFile(Parameters.DirectivesFile);
        }
        d.collect(spec);
        active = d;
        if (Parameters.Debug && !d.isEmpty())
        {
            ToolIO.out.println("TLATeX directives: symbols " + d.symbols + ", preset symbols "
                               + d.presetSymbols.size() + ", macros " + d.macros.values());
        }
        return removeDirectives(spec);
    }
}
