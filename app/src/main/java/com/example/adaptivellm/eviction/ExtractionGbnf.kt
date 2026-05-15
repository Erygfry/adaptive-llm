package com.example.adaptivellm.eviction

/**
 * GBNF grammar for Phase 4 eviction extraction output (Stage 6.1).
 *
 * Each rule on its own single line — llama.cpp GBNF parser строг к
 * multi-line rule definitions. Underscore_case для rule names.
 *
 * Constraint'ит LLM output на JSON-схему: один summary object с 4 строковыми
 * секциями + facts array со строго типизированными элементами. category
 * enum'ифицирована. event_date либо null либо ISO YYYY-MM-DD. importance 1..10.
 */
object ExtractionGbnf {

    val GRAMMAR: String = """
root ::= "{" ws "\"summary\":" ws summary ws "," ws "\"facts\":" ws facts ws "}"
summary ::= "{" ws "\"user_profile\":" ws string ws "," ws "\"ongoing_topics\":" ws string ws "," ws "\"key_decisions\":" ws string ws "," ws "\"pending_items\":" ws string ws "}"
facts ::= "[" ws "]" | "[" ws fact (ws "," ws fact)* ws "]"
fact ::= "{" ws "\"content\":" ws string ws "," ws "\"keywords\":" ws strarr ws "," ws "\"context\":" ws string ws "," ws "\"category\":" ws cat ws "," ws "\"importance\":" ws imp ws "," ws "\"event_date\":" ws edate ws "}"
strarr ::= "[" ws string (ws "," ws string)* ws "]"
cat ::= "\"personal_info\"" | "\"preference\"" | "\"goal\"" | "\"instruction\"" | "\"event\"" | "\"relationship\""
imp ::= [1-9] | "10"
edate ::= "null" | "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] "\""
string ::= "\"" ( [^"\\] | "\\" ["\\/bfnrt] )* "\""
ws ::= [ \t\n]*
""".trimIndent()
}
