import java_cup.runtime.Symbol;
import java.util.Locale;

%%
%class Lexer
%public
%unicode
%cup
%char
%state COMMENT
%{
    private int nextParameter;
    private Symbol token(int kind) {
        return new Symbol(kind, yychar, yychar + yylength());
    }
    private Symbol token(int kind, Object value) {
        return new Symbol(kind, yychar, yychar + yylength(), value);
    }
    private Symbol identifier(String text) {
        // Keeping keywords here makes quoted identifiers independent of keyword rules.
        switch (text.toUpperCase(Locale.ROOT)) {
            case "SELECT": return token(sym.SELECT);
            case "WITH": return token(sym.WITH);
            case "CASE": return token(sym.CASE);
            case "CONFLICT": return token(sym.CONFLICT);
            case "CASCADE": return token(sym.CASCADE);
            case "SAVEPOINT": return token(sym.SAVEPOINT);
            case "RELEASE": return token(sym.RELEASE);
            case "RESTRICT": return token(sym.RESTRICT);
            case "NO": return token(sym.NO);
            case "ACTION": return token(sym.ACTION);
            case "DEFERRABLE": return token(sym.DEFERRABLE);
            case "INITIALLY": return token(sym.INITIALLY);
            case "DEFERRED": return token(sym.DEFERRED);
            case "IMMEDIATE": return token(sym.IMMEDIATE);
            case "DO": return token(sym.DO);
            case "NOTHING": return token(sym.NOTHING);
            case "RETURNING": return token(sym.RETURNING);
            case "WHEN": return token(sym.WHEN);
            case "THEN": return token(sym.THEN);
            case "ELSE": return token(sym.ELSE);
            case "CAST": return token(sym.CAST);
            case "CURRENT_DATE": return token(sym.CURRENT_DATE);
            case "CURRENT_TIME": return token(sym.CURRENT_TIME);
            case "CURRENT_TIMESTAMP": return token(sym.CURRENT_TIMESTAMP);
            case "UNION": return token(sym.UNION);
            case "INTERSECT": return token(sym.INTERSECT);
            case "EXCEPT": return token(sym.EXCEPT);
            case "FROM": return token(sym.FROM);
            case "WHERE": return token(sym.WHERE);
            case "DISTINCT": return token(sym.DISTINCT);
            case "ALL": return token(sym.ALL);
            case "AS": return token(sym.AS);
            case "LIMIT": return token(sym.LIMIT);
            case "OFFSET": return token(sym.OFFSET);
            case "CREATE": return token(sym.CREATE);
            case "ALTER": return token(sym.ALTER);
            case "INDEX": return token(sym.INDEX);
            case "EXPLAIN": return token(sym.EXPLAIN);
            case "QUERY": return token(sym.QUERY);
            case "PLAN": return token(sym.PLAN);
            case "ADD": return token(sym.ADD);
            case "COLUMN": return token(sym.COLUMN);
            case "RENAME": return token(sym.RENAME);
            case "TO": return token(sym.TO);
            case "DROP": return token(sym.DROP);
            case "TABLE": return token(sym.TABLE);
            case "IF": return token(sym.IF);
            case "EXISTS": return token(sym.EXISTS);
            case "PRIMARY": return token(sym.PRIMARY);
            case "CONSTRAINT": return token(sym.CONSTRAINT);
            case "DEFAULT": return token(sym.DEFAULT);
            case "UNIQUE": return token(sym.UNIQUE);
            case "CHECK": return token(sym.CHECK);
            case "REFERENCES": return token(sym.REFERENCES);
            case "FOREIGN": return token(sym.FOREIGN);
            case "KEY": return token(sym.KEY);
            case "NOT": return token(sym.NOT);
            case "NULL": return token(sym.NULL);
            case "INTEGER": case "INT": return token(sym.INTEGER, text.toUpperCase(Locale.ROOT));
            case "REAL": case "DOUBLE": case "FLOAT": return token(sym.REAL);
            case "CHAR": return token(sym.CHAR);
            case "VARCHAR": return token(sym.VARCHAR);
            case "TEXT": return token(sym.TEXT);
            case "INSERT": return token(sym.INSERT);
            case "INTO": return token(sym.INTO);
            case "VALUES": return token(sym.VALUES);
            case "UPDATE": return token(sym.UPDATE);
            case "SET": return token(sym.SET);
            case "DELETE": return token(sym.DELETE);
            case "BEGIN": return token(sym.BEGIN);
            case "WORK": return token(sym.WORK);
            case "TRANSACTION": return token(sym.TRANSACTION);
            case "COMMIT": return token(sym.COMMIT);
            case "ROLLBACK": return token(sym.ROLLBACK);
            case "END": return token(sym.END);
            case "AND": return token(sym.AND);
            case "OR": return token(sym.OR);
            case "IS": return token(sym.IS);
            case "LIKE": return token(sym.LIKE);
            case "CLIKE": return token(sym.CLIKE);
            case "IN": return token(sym.IN);
            case "BETWEEN": return token(sym.BETWEEN);
            case "JOIN": return token(sym.JOIN);
            case "INNER": return token(sym.INNER);
            case "LEFT": return token(sym.LEFT);
            case "OUTER": return token(sym.OUTER);
            case "CROSS": return token(sym.CROSS);
            case "ON": return token(sym.ON);
            case "GROUP": return token(sym.GROUP);
            case "BY": return token(sym.BY);
            case "HAVING": return token(sym.HAVING);
            case "ORDER": return token(sym.ORDER);
            case "ASC": return token(sym.ASC);
            case "DESC": return token(sym.DESC);
            default: return token(sym.ID, text);
        }
    }
%}

Digit = [0-9]
Exponent = [eE][+-]?{Digit}+
Real = (({Digit}+"."{Digit}*|"."{Digit}+){Exponent}?|{Digit}+{Exponent})
Identifier = [a-zA-Z_][a-zA-Z_0-9]*
Whitespace = [ \t\r\n\f]+
String = \'([^\'\r\n]|\'\')*\'
QuotedIdentifier = \"([^\"\r\n]|\"\")*\"

%%
<YYINITIAL> {
    {Whitespace} { }
    "--"[^\r\n]* { }
    "/*" { yybegin(COMMENT); }
    {String} {
        return token(sym.STRING, yytext().substring(1, yylength()-1).replace("''", "'"));
    }
    {QuotedIdentifier} {
        return token(sym.ID, yytext().substring(1, yylength()-1).replace("\"\"", "\""));
    }
    {Identifier} { return identifier(yytext()); }
    {Real} {
        double number = Double.parseDouble(yytext());
        if (!Double.isFinite(number)) { throw new InvalidSyntaxException("Real literal is out of range"); }
        return token(sym.REAL_VAL, number);
    }
    {Digit}+ {
        return token(sym.INT_VAL, new java.math.BigInteger(yytext()));
    }
    "(" { return token(sym.LPAREN); }
    ")" { return token(sym.RPAREN); }
    "," { return token(sym.COMMA); }
    "." { return token(sym.DOT); }
    "*" { return token(sym.STAR); }
    ";" { return token(sym.SEMICOLON); }
    "="|"==" { return token(sym.EQ); }
    "!="|"<>" { return token(sym.NOTEQ); }
    "<=" { return token(sym.LTEQ); }
    ">=" { return token(sym.GTEQ); }
    "<" { return token(sym.LT); }
    ">" { return token(sym.GT); }
    "+" { return token(sym.PLUS); }
    "-" { return token(sym.MINUS); }
    "/" { return token(sym.DIVIDE); }
    "%" { return token(sym.REMAINDER); }
    "||" { return token(sym.CONCAT); }
    "?" { return token(sym.PARAMETER, nextParameter++); }
    . { throw new InvalidSyntaxException("Illegal character at " + yychar + ": " + yytext()); }
}
<COMMENT> {
    "*/" { yybegin(YYINITIAL); }
    [^*]+ { }
    "*" { }
}
<<EOF>> {
    if (yystate() == COMMENT) { throw new InvalidSyntaxException("Unterminated SQL comment"); }
    return token(sym.EOF);
}
